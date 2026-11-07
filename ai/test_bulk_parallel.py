import json,time,threading,unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
import httpx
import test_explain as fixtures
import bulk_extract as b
import bulk_parallel as p
import candidate_filter as cf
from bulk_http import HttpPartSender


class ParallelTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests();self.h.setUp();self.addCleanup(self.h.doCleanups)
        self.ns=self.h.ns

    def request(self,n=100):
        return self.ns['ExtractRequest'].model_validate(dict(analysis_id='parallel',check_items=[1],
         clauses=[dict(clause_id=f'c-{i}',page=1,text='보험금을 지급하지 않습니다.') for i in range(n)]))

    def plan(self,src):
        return b.plan_bulk(src,{'1':self.ns['CHECK_ITEM_DEFINITIONS'][1]},self.ns['extract_instruction_for']([1]))

    def admission(self,maximum=4):return p.Admission(max_inflight=maximum,starts_per_minute=None,daily_starts=None)

    def test_concurrency_limits_and_deterministic_merge_order(self):
        for c in [1,2,3,4]:
            src=self.request(150);plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id'])
            active=[0,0];lock=threading.Lock()
            def send(body,timeout):
                with lock:active[0]+=1;active[1]=max(active)
                threading.Event().wait(.01 if body['clauses'][0]['clause_id']=='c-0' else .002)
                with lock:active[0]-=1
                row=body['clauses'][0]
                return {'extractions':[dict(check_item=1,clause_id=row['clause_id'],quote=row['text'])]}
            result,_=p.run_bounded_bulk(src,plan,send,cp,concurrency=c,admission=self.admission())
            self.assertLessEqual(active[1],c);self.assertLessEqual(cp.peak_inflight,c)
            self.assertEqual([r['clause_id'] for r in result['extractions']],
                             [x['request']['clauses'][0]['clause_id'] for x in plan['parts']])

    def test_failure_drains_success_without_starting_later_and_resume_skips_success(self):
        src=self.request(150);plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);gate=threading.Barrier(2);calls=[]
        def send(body,timeout):
            cid=body['clauses'][0]['clause_id'];calls.append(cid);gate.wait(timeout=3)
            if cid=='c-0':raise b.BulkHttpError(503,'GEMINI_API_ERROR')
            threading.Event().wait(.01);return {'extractions':[]}
        with self.assertRaises(b.BulkHttpError):p.run_bounded_bulk(src,plan,send,cp,concurrency=2,admission=self.admission())
        self.assertEqual(len(calls),2);self.assertEqual(len(cp.completed),1)
        done=set(cp.completed);seen=[]
        def success(body,timeout):seen.append(body['clauses'][0]['clause_id']);return {'extractions':[]}
        p.run_bounded_bulk(src,plan,success,cp,concurrency=2,admission=self.admission())
        self.assertEqual(len(seen),len(plan['parts'])-1)
        self.assertTrue(done.issubset(cp.completed))

    def test_permanent_413_422_and_bad_quote_not_resumed(self):
        for code in [413,422,0]:
            src=self.request();plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);calls=[]
            def fail(body,timeout):
                calls.append(1)
                if code:raise b.BulkHttpError(code,'REQUEST_VALIDATION_ERROR')
                return {'extractions':[dict(check_item=1,clause_id=body['clauses'][0]['clause_id'],quote='SECRET wrong quote')]}
            with self.assertRaises(b.BulkHttpError):p.run_bounded_bulk(src,plan,fail,cp,concurrency=1,admission=self.admission())
            count=len(calls)
            with self.assertRaises(b.BulkHttpError):p.run_bounded_bulk(src,plan,fail,cp,admission=self.admission())
            self.assertEqual(len(calls),count)

    def test_cancel_drains_and_can_explicitly_resume(self):
        src=self.request();plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);cancel=threading.Event()
        def send(body,timeout):cancel.set();threading.Event().wait(.01);return {'extractions':[]}
        with self.assertRaises(b.BulkHttpError):p.run_bounded_bulk(src,plan,send,cp,concurrency=1,admission=self.admission(),cancel=cancel)
        self.assertEqual(len(cp.completed),1)
        restored=p.restore_checkpoint(json.loads(json.dumps(p.checkpoint_payload(cp))))
        p.run_bounded_bulk(src,plan,lambda *_:{'extractions':[]},restored,admission=self.admission())
        self.assertEqual(len(restored.completed),len(plan['parts']))

    def test_shared_global_inflight_limit_across_documents(self):
        admission=self.admission(2);lock=threading.Lock();active=[0,0]
        def job(i):
            src=self.request(150);src.analysis_id=str(i);plan=self.plan(src)
            def send(body,timeout):
                with lock:active[0]+=1;active[1]=max(active)
                threading.Event().wait(.005)
                with lock:active[0]-=1
                return {'extractions':[]}
            p.run_bounded_bulk(src,plan,send,concurrency=4,admission=admission)
        with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(job,[1,2]))
        self.assertEqual(active[1],2);self.assertEqual(admission.peak,2)

    def test_pacing_daily_cap_and_estimated_tpm_without_real_sleep(self):
        now=[0.];stop=threading.Event()
        admission=p.Admission(starts_per_minute=5,daily_starts=2,clock=lambda:now[0],sleeper=lambda dt:now.__setitem__(0,now[0]+dt))
        admission.acquire(10,100,stop);admission.release();admission.acquire(10,100,stop);admission.release()
        self.assertGreaterEqual(now[0],12)
        with self.assertRaises(b.BulkHttpError):admission.acquire(10,100,stop)
        limited=p.Admission(estimated_tpm=5,clock=lambda:0)
        with self.assertRaises(b.BulkHttpError):limited.acquire(6,100,stop)

    def test_429_503_reduce_admission_and_use_bounded_backoff(self):
        now=[0.];a=p.Admission(clock=lambda:now[0])
        a.outcome(False,503,'GEMINI_API_ERROR');self.assertEqual(a.effective,1);self.assertEqual(a.cooldown,2)
        a.outcome(False,429,'GEMINI_API_ERROR');self.assertEqual(a.cooldown,4)
        now[0]=5;a.outcome(True);a.outcome(True);self.assertEqual(a.effective,2)

    def test_retry_budget_remains_bounded_across_resumes(self):
        src=self.request(1);plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);calls=[]
        def fail(*_):calls.append(1);raise b.BulkHttpError(503,'GEMINI_API_ERROR')
        for _ in range(3):
            with self.assertRaises(b.BulkHttpError):p.run_bounded_bulk(src,plan,fail,cp,admission=self.admission())
        self.assertEqual(len(calls),2)

    def test_candidate_plan_and_lossless_oversized_source(self):
        src=self.request(1);src.clauses[0].text='보험금을 지급하지 않습니다. '*2000
        bundle,sub=cf.candidate_plan(src,self.ns['ExtractRequest'],self.ns['CHECK_ITEM_DEFINITIONS'],self.ns['extract_instruction_for'])
        plan=bundle['candidate_plan'];self.assertTrue(b.validate_preservation(sub,plan))
        result,_=p.run_bounded_bulk(sub,plan,lambda *_:{'extractions':[]},concurrency=2,admission=self.admission())
        self.assertEqual(result,{'extractions':[]})

    def test_checkpoint_plan_mismatch_and_tampered_result_rejected(self):
        src=self.request();plan=self.plan(src);cp=p.ParallelCheckpoint('wrong')
        with self.assertRaises(b.BulkError):p.run_bounded_bulk(src,plan,lambda *_:self.fail(),cp)
        cp=p.ParallelCheckpoint(plan['plan_id'],completed={plan['parts'][0]['part_id']:{'unexpected':[]}})
        with self.assertRaises(b.BulkError):p.run_bounded_bulk(src,plan,lambda *_:self.fail(),cp)

    def test_crash_snapshot_does_not_blindly_replay_unknown_inflight(self):
        cp=p.ParallelCheckpoint('test',states={'p':{'state':'in_flight'}})
        restored=p.restore_checkpoint(json.loads(json.dumps(p.checkpoint_payload(cp))))
        self.assertEqual(restored.failure,(502,'BULK_CLIENT_ERROR'))
        self.assertEqual(restored.states['p']['state'],'interrupted_unknown')

    def test_budget_and_invalid_concurrency(self):
        src=self.request();plan=self.plan(src)
        for value in [0,5,True]:
            with self.assertRaises(ValueError):p.run_bounded_bulk(src,plan,lambda *_:self.fail(),concurrency=value)
        cp=p.ParallelCheckpoint(plan['plan_id'],elapsed_seconds=b.MAX_JOB_SECONDS+1)
        with self.assertRaises(b.BulkError):p.run_bounded_bulk(src,plan,lambda *_:self.fail(),cp,admission=self.admission())

    def test_http_adapter_is_thread_local_has_no_hidden_retry_and_safe_errors(self):
        count=[];lock=threading.Lock()
        def handler(req):
            with lock:count.append(threading.get_ident())
            threading.Event().wait(.002)
            return httpx.Response(503,json={'code':'GEMINI_API_ERROR','message':'SECRET'})
        with HttpPartSender('http://mock',lambda:httpx.MockTransport(handler)) as sender:
            def call(_):
                try:sender({},2)
                except b.BulkHttpError as exc:self.assertNotIn('SECRET',str(exc))
            with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(call,[1,2]))
            self.assertEqual(len(sender.clients),2)
        self.assertEqual(len(count),2)

    def test_permanent_failure_wins_over_concurrent_transient(self):
        src=self.request();plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);barrier=threading.Barrier(2)
        def send(body,timeout):
            barrier.wait(timeout=3)
            raise b.BulkHttpError(503 if body['clauses'][0]['clause_id']=='c-0' else 422,'REQUEST_VALIDATION_ERROR')
        with self.assertRaises(b.BulkHttpError) as e:p.run_bounded_bulk(src,plan,send,cp,concurrency=2,admission=self.admission())
        self.assertEqual(e.exception.status,422);self.assertEqual(len(cp.attempts),2)

    def test_same_plan_cannot_execute_twice_concurrently(self):
        src=self.request(1);plan=self.plan(src);entered=threading.Event();release=threading.Event()
        def send(*_):entered.set();release.wait(3);return {'extractions':[]}
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(p.run_bounded_bulk,src,plan,send,None,1,self.admission())
            self.assertTrue(entered.wait(3))
            try:
                with self.assertRaises(b.BulkHttpError) as e:p.run_bounded_bulk(src,plan,send,admission=self.admission())
                self.assertEqual(e.exception.code,'BULK_JOB_ALREADY_RUNNING')
            finally:release.set()
            future.result(timeout=3)

    def test_late_success_is_saved_but_job_is_not_reported_successful(self):
        src=self.request(1);plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);now=[0.]
        def send(*_):now[0]=b.MAX_JOB_SECONDS+1;return {'extractions':[]}
        a=p.Admission(starts_per_minute=None,daily_starts=None,clock=lambda:now[0])
        with self.assertRaises(b.BulkError):p.run_bounded_bulk(src,plan,send,cp,admission=a,clock=lambda:now[0])
        self.assertEqual(len(cp.completed),1)

    def test_server_extract_gate_default_and_concurrent_limit(self):
        from extract_gate import ExtractGate
        gate=ExtractGate(2);barrier=threading.Barrier(2);started=[0];lock=threading.Lock()
        @gate.limit_call
        def fake(contents,recovery=None):
            with lock:started[0]+=1;index=started[0]
            if index<=2:barrier.wait(3)
            threading.Event().wait(.002)
        with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(fake,range(6)))
        self.assertEqual(gate.peak,2);self.assertEqual(gate.active,0)
        with self.assertRaises(TimeoutError):
            with ExtractGate(queue_seconds=0).hold():pass

    def test_shared_sync_sdk_mock_transport_isolates_request_options(self):
        from google import genai
        from google.genai import types
        barrier=threading.Barrier(2);observed=[];lock=threading.Lock()
        def transport(req):
            barrier.wait(3)
            with lock:observed.append(req.extensions['timeout']['read'])
            return httpx.Response(200,json={'candidates':[{'content':{'parts':[{'text':'{}'}],'role':'model'},'finishReason':'STOP'}]})
        sdk=genai.Client(api_key='offline-test-placeholder',vertexai=False,http_options=types.HttpOptions(client_args={'transport':httpx.MockTransport(transport),'trust_env':False}))
        try:
            def call(ms):return sdk.models.generate_content(model='offline-model',contents='synthetic',config=types.GenerateContentConfig(http_options=types.HttpOptions(timeout=ms,retry_options=types.HttpRetryOptions(attempts=1))))
            with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(call,[60000,45000]))
        finally:sdk.close()
        self.assertEqual(sorted(observed),[45.,60.])

    def test_retry_after_429_is_respected_without_actual_wait(self):
        def transport(req):return httpx.Response(429,json={'code':'GEMINI_API_ERROR'},headers={'Retry-After':'120'})
        with HttpPartSender('http://mock',lambda:httpx.MockTransport(transport)) as sender:
            with self.assertRaises(b.BulkHttpError) as e:sender({},1)
        self.assertEqual(e.exception.retry_after_seconds,120)
        admission=p.Admission(clock=lambda:0)
        admission.outcome(False,429,'GEMINI_API_ERROR',retry_after=e.exception.retry_after_seconds)
        self.assertEqual(admission.cooldown,120)
        with self.assertRaises(b.BulkError):admission.acquire(1,100,threading.Event())

    def test_keyboard_interrupt_drains_before_returning_cancelled(self):
        src=self.request();plan=self.plan(src);cp=p.ParallelCheckpoint(plan['plan_id']);entered=threading.Event()
        real_wait=p.wait;first=[True]
        def interrupt(*args,**kwargs):
            if first[0]:
                self.assertTrue(entered.wait(3));first[0]=False;raise KeyboardInterrupt()
            return real_wait(*args,**kwargs)
        def send(*_):entered.set();threading.Event().wait(.01);return {'extractions':[]}
        with patch.object(p,'wait',side_effect=interrupt):
            with self.assertRaises(b.BulkHttpError) as e:p.run_bounded_bulk(src,plan,send,cp,admission=self.admission())
        self.assertEqual(e.exception.code,'BULK_CANCELLED');self.assertEqual(len(cp.completed),1)

    def test_legacy_checkpoint_migration_preserves_success_and_known_failure(self):
        src=self.request(60);plan=self.plan(src);first,second=[x['part_id'] for x in plan['parts'][:2]]
        old=b.Checkpoint(plan['plan_id'],completed={first:{'extractions':[]}},attempts={first:1,second:1},failure=(502,'GEMINI_API_ERROR'))
        cp=p.upgrade_checkpoint(old);calls=[]
        def send(body,timeout):calls.append(body['clauses'][0]['clause_id']);return {'extractions':[]}
        p.run_bounded_bulk(src,plan,send,cp,concurrency=2,admission=self.admission())
        self.assertEqual(len(calls),len(plan['parts'])-1)
        self.assertEqual(len(old.completed),1);self.assertEqual(len(cp.completed),len(plan['parts']))

    def test_ambiguous_legacy_inflight_is_not_blindly_migrated_to_retry(self):
        cp=p.upgrade_checkpoint(b.Checkpoint('p',attempts={'part':1}))
        self.assertEqual(cp.failure,(502,'BULK_CLIENT_ERROR'))


if __name__=='__main__':unittest.main()
