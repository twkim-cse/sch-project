"""Bounded, caller-side HTTP orchestration. No SDK calls or environment reads.

Spring must implement the same scheduling contract; importing this module does
not make FastAPI's Gemini calls parallel or alter any public request schema.
"""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass, field
from threading import Condition, Event, Lock, BoundedSemaphore
import copy
import math
import re
import time

import bulk_extract as b


@dataclass
class ParallelCheckpoint(b.Checkpoint):
    states: dict = field(default_factory=dict)
    peak_inflight: int = 0


def checkpoint_payload(cp):
    """Only validated result bodies and safe state, never locks/exceptions.

    Contains private quotes: persist through backend authorization/F-21 only.
    """
    with _registry_lock:
        lock=_state_locks.get(cp.plan_id)
        running=_running_times.get(cp.plan_id)
    def snapshot():
        out=copy.deepcopy({k:getattr(cp,k) for k in
            ('plan_id','completed','attempts','elapsed_seconds','failure','states','peak_inflight')})
        if running:
            clock,start,initial=running
            out['elapsed_seconds']=max(out['elapsed_seconds'],initial+max(0,clock()-start))
        return out
    if lock:
        with lock:return snapshot()
    return snapshot()


def restore_checkpoint(payload):
    allowed={'plan_id','completed','attempts','elapsed_seconds','failure','states','peak_inflight'}
    if set(payload)-allowed:raise b.BulkError('BULK_PLAN_MISMATCH')
    data=copy.deepcopy(payload)
    if data.get('failure') is not None:data['failure']=tuple(data['failure'])
    data.setdefault('states',{})
    for pid in data.get('completed',{}):data['states'].setdefault(pid,{'state':'succeeded'})
    for pid,count in data.get('attempts',{}).items():
        if count and pid not in data.get('completed',{}) and pid not in data['states']:
            # A legacy checkpoint with no final failure cannot establish that
            # its interrupted request did not execute remotely.
            data['states'][pid]={'state':'failed' if data.get('failure') else 'in_flight'}
    if any(v.get('state')=='in_flight' for v in data.get('states',{}).values()):
        data['failure']=(502,'BULK_CLIENT_ERROR')
        for v in data['states'].values():
            if v.get('state')=='in_flight':v['state']='interrupted_unknown'
    return ParallelCheckpoint(**data)


def upgrade_checkpoint(legacy):
    """Explicit copy migration; retain the returned object for future saves."""
    return restore_checkpoint({k:copy.deepcopy(getattr(legacy,k)) for k in
        ('plan_id','completed','attempts','elapsed_seconds','failure')})


class Admission:
    """Process-local shared start/concurrency/rate guard, not an account meter.

    Default HTTP pacing assumes up to TWO SDK generate attempts per extract.
    TPM is an optional conservative *estimate*, not actual model token usage.
    Tests can inject an explicitly quota-free Mock-only instance and fake clock.
    """
    def __init__(self,max_inflight=4,starts_per_minute=5,daily_starts=200,
                 estimated_tpm=None,output_reserve=8192,clock=time.monotonic,
                 sleeper=None):
        if not 1<=max_inflight<=4 or (starts_per_minute is not None and starts_per_minute<1):
            raise ValueError('invalid_admission_config')
        self.maximum=max_inflight;self.effective=max_inflight
        self.rpm=starts_per_minute;self.daily=daily_starts;self.tpm=estimated_tpm
        self.reserve=output_reserve;self.clock=clock;self.sleeper=sleeper
        self.cv=Condition();self.starts=deque();self.days=deque();self.tokens=deque()
        self.active=0;self.peak=0;self.cooldown=0;self.errors=0;self.successes=0

    def acquire(self,estimate,deadline,stop):
        while True:
            if stop.is_set():raise b.BulkHttpError(409,'BULK_CANCELLED')
            with self.cv:
                now=self.clock()
                if now>=deadline:raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
                while self.starts and self.starts[0]<=now-60:self.starts.popleft()
                while self.days and self.days[0]<=now-86400:self.days.popleft()
                while self.tokens and self.tokens[0][0]<=now-60:self.tokens.popleft()
                if self.daily is not None and len(self.days)>=self.daily:
                    raise b.BulkHttpError(429,'BULK_CLIENT_QUOTA')
                if self.tpm is not None and estimate>self.tpm:
                    raise b.BulkHttpError(413,'BULK_TOKEN_ESTIMATE_BUDGET')
                delay=max(0,self.cooldown-now)
                if self.rpm and self.starts:
                    delay=max(delay,self.starts[-1]+60/self.rpm-now)
                    if len(self.starts)>=self.rpm:delay=max(delay,self.starts[0]+60-now)
                if self.tpm and sum(v for _,v in self.tokens)+estimate>self.tpm:
                    delay=max(delay,self.tokens[0][0]+60-now)
                if self.active<self.effective and delay<=0:
                    self.active+=1;self.peak=max(self.peak,self.active)
                    self.starts.append(now);self.days.append(now);self.tokens.append((now,estimate))
                    return
                pause=min(0.05 if self.active>=self.effective else max(delay,0.001),0.1)
                if now+max(delay,0)>=deadline:
                    raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
            if self.sleeper:self.sleeper(pause)
            else:stop.wait(pause)

    def release(self):
        with self.cv:self.active-=1;self.cv.notify_all()

    def outcome(self,success,status=None,code=None,retry_after=None):
        with self.cv:
            if not success and (status in (429,503,504) or status==502 and code in ('GEMINI_API_ERROR','GEMINI_TIMEOUT')):
                self.errors+=1;self.successes=0
                self.effective=1
                delay=min(60,2**min(self.errors,6))
                if isinstance(retry_after,(int,float)) and math.isfinite(retry_after) and retry_after>=0:
                    delay=max(delay,retry_after)
                self.cooldown=max(self.cooldown,self.clock()+delay)
            elif success:
                self.successes+=1
                if self.successes>=2 and self.clock()>=self.cooldown:
                    self.effective=min(self.maximum,self.effective+1);self.successes=0
                    if self.effective==self.maximum:self.errors=0


DEFAULT_ADMISSION=Admission()
_job_slots=BoundedSemaphore(8)  # at most 8 jobs x 4 bounded workers per process
_registry_lock=Lock()
_active_plans=set()
_invocations=set()
_state_locks={}
_running_times={}


def transient(status,code):
    return status in (429,503,504) or status==502 and code in ('GEMINI_TIMEOUT','GEMINI_API_ERROR')


def validate_part_response(source,part,response):
    # Whole-plan preservation is verified before dispatch and again at final
    # merge. Validate just this part here; don't rehash an entire policy per RPC.
    if not isinstance(response,dict) or set(response)!={'extractions'} or not isinstance(response['extractions'],list):
        raise b.BulkError('BULK_RESULT_INVALID',502)
    texts={c['clause_id']:re.sub(r'\s+',' ',c['text']) for c in part['request']['clauses']}
    for row in response['extractions']:
        if not isinstance(row,dict) or set(row)!={'check_item','clause_id','quote'}:
            raise b.BulkError('BULK_RESULT_INVALID',502)
        cid,item,quote=row['clause_id'],row['check_item'],row['quote']
        if not isinstance(cid,str) or cid not in texts or type(item) is not int or item not in source.check_items or not isinstance(quote,str) or not quote.strip() or re.sub(r'\s+',' ',quote) not in texts[cid]:
            raise b.BulkError('BULK_RESULT_INVALID',502)


def _run_bounded_owned(source,plan,send,checkpoint=None,concurrency=1,
                     admission=None,cancel=None,clock=time.monotonic):
    """send(body,timeout) must honor timeout and be safe for concurrent calls.

    No outer automatic retry. Drain in-flight requests; checkpoint valid success
    even when another part fails. Never return a partial merge as success.
    """
    if type(concurrency) is not int or not 1<=concurrency<=4:
        raise ValueError('concurrency_must_be_1_to_4')
    cp=checkpoint if checkpoint is not None else ParallelCheckpoint(plan['plan_id'])
    if not isinstance(cp,ParallelCheckpoint):
        raise b.BulkError('BULK_PLAN_MISMATCH')
    b.validate_preservation(source,plan)
    ids={p['part_id'] for p in plan['parts']}
    if cp.plan_id!=plan['plan_id'] or not set(cp.completed).issubset(ids) or not set(cp.attempts).issubset(ids):
        raise b.BulkError('BULK_PLAN_MISMATCH')
    if not set(cp.states).issubset(ids) or any(type(v) is not int or v<0 for v in cp.attempts.values()) or not isinstance(cp.elapsed_seconds,(int,float)) or not math.isfinite(cp.elapsed_seconds) or cp.elapsed_seconds<0:
        raise b.BulkError('BULK_PLAN_MISMATCH')
    if cp.failure and cp.failure!=(409,'BULK_CANCELLED') and not transient(*cp.failure):
        raise b.BulkHttpError(*cp.failure)
    # Validate restored checkpoints, not just their IDs.
    provisional={pid:cp.completed.get(pid,{'extractions':[]}) for pid in ids}
    b.merge_bulk(source,plan,provisional)
    if not _job_slots.acquire(blocking=False):raise b.BulkHttpError(503,'BULK_BUSY')
    lock=Lock()
    with _registry_lock:
        if plan['plan_id'] in _active_plans:
            _job_slots.release();raise b.BulkHttpError(409,'BULK_JOB_ALREADY_RUNNING')
        _active_plans.add(plan['plan_id'])
        _state_locks[plan['plan_id']]=lock
    start=clock();deadline=start+b.MAX_JOB_SECONDS-cp.elapsed_seconds
    admission=admission if admission is not None else DEFAULT_ADMISSION
    cancel=cancel if cancel is not None else Event()
    with _registry_lock:_running_times[plan['plan_id']]=(clock,start,cp.elapsed_seconds)
    stop=Event();errors=[];inflight=0
    pending=iter(p for p in plan['parts'] if p['part_id'] not in cp.completed)
    for p in plan['parts']:cp.states.setdefault(p['part_id'],{'state':'pending'})

    def work(part):
        nonlocal inflight
        pid=part['part_id'];acquired=False;started=False
        try:
            if cancel.is_set() or stop.is_set():raise b.BulkHttpError(409,'BULK_CANCELLED')
            estimate=part['input_bytes']+admission.reserve  # byte upper estimate + unknown output reserve
            admission.acquire(estimate,deadline,stop);acquired=True
            with lock:
                if cancel.is_set() or stop.is_set():raise b.BulkHttpError(409,'BULK_CANCELLED')
                if sum(cp.attempts.values())>=b.MAX_HTTP_ATTEMPTS or cp.attempts.get(pid,0)>=b.MAX_ATTEMPTS_PER_PART:
                    raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
                cp.attempts[pid]=cp.attempts.get(pid,0)+1
                cp.states[pid]={'state':'in_flight'};started=True
                inflight+=1;cp.peak_inflight=max(cp.peak_inflight,inflight)
            remaining=deadline-clock()
            if remaining<=0:raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
            response=send(copy.deepcopy(part['request']),min(b.PART_TIMEOUT_SECONDS,remaining))
            validate_part_response(source,part,response)
            admission.outcome(True)
            return response,None
        except b.BulkError as exc:
            error=(exc.status,exc.code);admission.outcome(False,*error);stop.set()
            return None,error
        except b.BulkHttpError as exc:
            error=(exc.status,exc.code)
            if error!=(409,'BULK_CANCELLED'):admission.outcome(False,*error,retry_after=getattr(exc,'retry_after_seconds',None));stop.set()
            return None,error
        except Exception:
            stop.set();return None,(502,'BULK_CLIENT_ERROR')
        except (KeyboardInterrupt,SystemExit):
            stop.set();return None,(502,'BULK_CLIENT_ERROR')
        finally:
            if started:
                with lock:inflight-=1
            if acquired:admission.release()

    futures={}
    def fill(pool):
        while len(futures)<concurrency and not stop.is_set() and not cancel.is_set():
            part=next(pending,None)
            if part is None:break
            futures[pool.submit(work,part)]=part

    try:
        if deadline<=start:raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
        with ThreadPoolExecutor(max_workers=concurrency,thread_name_prefix='bulk-http') as pool:
            fill(pool)
            while futures:
                try:
                    if cancel.is_set():stop.set()
                    ready,_=wait(list(futures),timeout=0.05,return_when=FIRST_COMPLETED)
                    for future in ready:
                        part=futures.pop(future);pid=part['part_id']
                        response,error=future.result()
                        if error:
                            with lock:cp.states[pid]={'state':'cancelled' if error==(409,'BULK_CANCELLED') else 'failed','http_status':error[0],'code':error[1]}
                            if error!=(409,'BULK_CANCELLED'):errors.append(error)
                        else:
                            with lock:
                                cp.completed[pid]=copy.deepcopy(response)
                                cp.states[pid]={'state':'succeeded'}
                    fill(pool)
                except KeyboardInterrupt:
                    # Stop admissions, but don't abandon already issued HTTP
                    # calls or discard their successful validated responses.
                    cancel.set();stop.set()
        if errors:
            # Never let a transient response hide a concurrent permanent failure.
            chosen=next((e for e in errors if not transient(*e)),errors[0])
            cp.failure=chosen;raise b.BulkHttpError(*chosen)
        if cancel.is_set():
            cp.failure=(409,'BULK_CANCELLED');raise b.BulkHttpError(*cp.failure)
        if clock()>deadline:
            cp.failure=(502,'BULK_JOB_BUDGET_EXCEEDED');raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
        if set(cp.completed)!=ids:raise b.BulkError('BULK_INCOMPLETE',409)
        result=b.merge_bulk(source,plan,cp.completed)
        if clock()>deadline:raise b.BulkError('BULK_JOB_BUDGET_EXCEEDED',502)
        cp.failure=None;return result
    except b.BulkError as exc:
        cp.failure=(exc.status,exc.code);raise
    finally:
        with lock:cp.elapsed_seconds+=max(0,clock()-start)
        with _registry_lock:
            _active_plans.remove(plan['plan_id'])
            _state_locks.pop(plan['plan_id'],None);_running_times.pop(plan['plan_id'],None)
        _job_slots.release()


def run_bounded_bulk(source,plan,send,checkpoint=None,concurrency=1,
                     admission=None,cancel=None,clock=time.monotonic):
    """Opt-in bounded runner. Default one worker; existing run_bulk is untouched."""
    key=plan['plan_id']
    with _registry_lock:
        if key in _invocations:raise b.BulkHttpError(409,'BULK_JOB_ALREADY_RUNNING')
        if len(_invocations)>=8:raise b.BulkHttpError(503,'BULK_BUSY')
        _invocations.add(key)
    try:
        return _run_bounded_owned(source,plan,send,checkpoint,concurrency,admission,cancel,clock)
    finally:
        with _registry_lock:_invocations.remove(key)
