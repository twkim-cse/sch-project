"""Lossless, stateless planning/merge and caller-owned resumable orchestration.

No Gemini calls, files, or environment reads. Original coordinates are Python
Unicode character offsets, NOT PDF coordinates or Java UTF-16 offsets.
"""
from collections import defaultdict
from dataclasses import dataclass, field
import hashlib
import json
import logging
import re
import time
from itertools import groupby

from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, StrictStr, create_model
from extract_planning import make_batch, MAX_INPUT_BYTES, MAX_BATCH_CLAUSES

MAX_SOURCE_BYTES = 16_000_000
MAX_SOURCE_CLAUSES = 10_000
MAX_PARTS = 500
PRIMARY_BUDGET = 14_000
MAX_JOB_SECONDS = 14_400
MAX_HTTP_ATTEMPTS = 600
MAX_ATTEMPTS_PER_PART = 2
PART_TIMEOUT_SECONDS = 270
logger = logging.getLogger('uvicorn.error')
MESSAGES = {
    'BULK_INPUT_BUDGET_EXCEEDED': '전체 입력 또는 분할 요청 수가 처리 예산을 초과했습니다.',
    'BULK_UNSPLITTABLE_CLAUSE': '완전한 문장·문단 경계로 나눌 수 없는 과대 조항입니다. 원문 구조를 확인해 주세요.',
    'BULK_PLAN_MISMATCH': '입력 또는 분할 계획이 변경되어 기존 결과를 사용할 수 없습니다.',
    'BULK_INCOMPLETE': '모든 분할 요청이 완료되지 않았습니다.',
    'BULK_RESULT_INVALID': '분할 결과의 조항 또는 원문 인용을 검증하지 못했습니다.',
    'BULK_JOB_BUDGET_EXCEEDED': '작업 시간 또는 호출 예산을 초과했습니다.',
}


class BulkError(Exception):
    def __init__(self, code, status=422):
        self.code, self.status = code, status
        super().__init__(code)


def canonical(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(obj):
    return hashlib.sha256(canonical(obj).encode()).hexdigest()


def fits(clause, definitions, instruction, limit=MAX_INPUT_BYTES):
    return make_batch([clause], definitions, instruction).input_bytes <= limit


def split_clause(clause, definitions, instruction):
    """Never split a sentence to fit. Keep every whitespace character as well."""
    if fits(clause, definitions, instruction):
        return [(clause, dict(original_clause_id=clause.clause_id, start=0,
                             end=len(clause.text), primary_start=0,
                             primary_end=len(clause.text), context_reasons=[]))]
    text = clause.text
    # Blank paragraphs and complete Korean declarative sentences only. No
    # fixed character slicing, fuzzy rewriting, or splitting inline references.
    boundaries = {m.end() for m in re.finditer(r'(?<=다\.)\s+|\r?\n[ \t]*\r?\n', text)}
    # Accept list-item boundaries only when a run of at least three numbered
    # items establishes structure (not one incidental "1." or a decimal).
    markers = list(re.finditer(r'(?<!\S)(\d{1,2})[.)]\s+', text))
    list_boundaries = set()
    for i in range(len(markers)-2):
        nums = [int(m.group(1)) for m in markers[i:i+3]]
        if nums[0] >= 1 and nums == list(range(nums[0], nums[0]+3)):
            list_boundaries.update(m.start() for m in markers[i:i+3])
    ends = sorted({0, len(text)} | boundaries | list_boundaries)
    spans = list(zip(ends, ends[1:]))
    prefix = '__bulk_' + digest(clause.model_dump())[:24] + '_'

    def piece(start, end, index):
        return clause.model_copy(update={'clause_id': prefix + str(index), 'text': text[start:end]})

    cores, start, end = [], 0, 0
    for a, b in spans:
        trial = piece(start, b, len(cores))
        if fits(trial, definitions, instruction, PRIMARY_BUDGET):
            end = b
            continue
        if end > start:
            cores.append((start, end)); start = end
        if not fits(piece(a, b, len(cores)), definitions, instruction):
            raise BulkError('BULK_UNSPLITTABLE_CLAUSE', 413)
        start, end = a, b
    if end > start:
        cores.append((start, end))
    output = []
    for index, (a, b) in enumerate(cores):
        start, end, reasons = a, b, []
        left = next(((x, y) for x, y in spans if y == a), None)
        right = next(((x, y) for x, y in spans if x == b), None)
        for candidate, label in [(left, 'previous_complete_unit'), (right, 'next_complete_unit')]:
            if candidate:
                lo, hi = min(start, candidate[0]), max(end, candidate[1])
                if fits(piece(lo, hi, index), definitions, instruction):
                    start, end = lo, hi; reasons.append(label)
                else:
                    reasons.append(label + '_omitted_budget')
        output.append((piece(start, end, index), dict(original_clause_id=clause.clause_id,
                      start=start, end=end, primary_start=a, primary_end=b,
                      context_reasons=reasons,
                      split_methods=['complete_sentence_or_paragraph'] + (['sequential_numbered_list'] if list_boundaries else []),
                      boundary_context_review_required=bool(list_boundaries))))
    return output


def validate_preservation(source, plan):
    if plan.get('source_digest') != digest(source.model_dump()):
        raise BulkError('BULK_PLAN_MISMATCH')
    part_ids = [p['part_id'] for p in plan['parts']]
    if len(part_ids) != len(set(part_ids)):
        raise BulkError('BULK_PLAN_MISMATCH')
    originals = {c.clause_id: c for c in source.clauses}
    fragments, primary, sent = plan['fragments'], defaultdict(list), []
    for part in plan['parts']:
        if set(part['request']) != {'analysis_id', 'check_items', 'clauses'} or any(
                part['request'][key] != getattr(source, key) for key in ('analysis_id', 'check_items')):
            raise BulkError('BULK_PLAN_MISMATCH')
        context = part.get('context_reasons', {})
        part_ids = [c['clause_id'] for c in part['request']['clauses']]
        if len(part_ids) != len(set(part_ids)) or not set(context).issubset(part_ids):
            raise BulkError('BULK_PLAN_MISMATCH')
        for raw in part['request']['clauses']:
            cid = raw['clause_id']
            if cid not in fragments:
                raise BulkError('BULK_PLAN_MISMATCH')
            m = fragments[cid]; original = originals[m['original_clause_id']]
            if not (0 <= m['start'] <= m['primary_start'] <= m['primary_end'] <= m['end'] <= len(original.text)):
                raise BulkError('BULK_PLAN_MISMATCH')
            expected = original.model_dump()
            expected.update(clause_id=cid, text=original.text[m['start']:m['end']])
            if raw != expected:
                raise BulkError('BULK_PLAN_MISMATCH')
            if cid in context:
                if cid not in sent or context[cid] != 'same_article_boundary_exception_context':
                    raise BulkError('BULK_PLAN_MISMATCH')
                continue
            sent.append(cid)
            primary[original.clause_id].append((m['primary_start'], m['primary_end']))
    if len(sent) != len(set(sent)) or set(sent) != set(fragments) or set(primary) != set(originals):
        raise BulkError('BULK_PLAN_MISMATCH')
    for cid, spans in primary.items():
        cursor, rebuilt = 0, []
        for start, end in sorted(spans):
            if start != cursor:
                raise BulkError('BULK_PLAN_MISMATCH')
            rebuilt.append(originals[cid].text[start:end]); cursor = end
        if cursor != len(originals[cid].text) or ''.join(rebuilt) != originals[cid].text:
            raise BulkError('BULK_PLAN_MISMATCH')
    return True


def plan_bulk(source, definitions, instruction):
    raw = source.model_dump()
    if len(source.clauses) > MAX_SOURCE_CLAUSES or len(canonical(raw).encode()) > MAX_SOURCE_BYTES:
        raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
    plan_id = digest({'source': raw, 'instruction': instruction, 'definitions': definitions,
                      'version': 1, 'limits': [MAX_INPUT_BYTES, MAX_BATCH_CLAUSES, PRIMARY_BUDGET]})
    fragments, ordered = {}, []
    original_ids = {c.clause_id for c in source.clauses}
    for clause in source.clauses:
        for fragment, meta in split_clause(clause, definitions, instruction):
            if fragment.clause_id in fragments or (fragment.clause_id != clause.clause_id and fragment.clause_id in original_ids):
                raise BulkError('BULK_PLAN_MISMATCH')
            fragments[fragment.clause_id] = meta; ordered.append(fragment)
            if len(ordered) > MAX_PARTS * MAX_BATCH_CLAUSES:
                raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
    parts, current, current_context = [], [], {}
    current_omitted = False

    def flush():
        nonlocal current_omitted
        if current:
            batch = make_batch(current, definitions, instruction)
            part_id = plan_id[:24] + ':' + str(len(parts))
            parts.append({'part_id': part_id, 'input_bytes': batch.input_bytes, 'context_reasons': dict(current_context),
                          'neighbor_context_omitted_budget': current_omitted,
                          'request': source.model_copy(update={'clauses': list(current)}).model_dump()})
            if len(parts) > MAX_PARTS:
                raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
            current.clear()
            current_context.clear(); current_omitted = False

    def scope(c):
        return (c.rider_id, c.section, c.article) if c.article else ('unknown', c.clause_id)

    for _, members in groupby(ordered, key=scope):
        group = list(members)
        whole = make_batch(group, definitions, instruction)
        blocks = [group] if len(group) <= MAX_BATCH_CLAUSES and whole.input_bytes <= MAX_INPUT_BYTES else [[c] for c in group]
        for block in blocks:
            trial = make_batch(current + block, definitions, instruction)
            if current and (len(current) + len(block) > MAX_BATCH_CLAUSES or trial.input_bytes > MAX_INPUT_BYTES):
                flush()
            if not current and parts:
                previous = ordered[0].__class__.model_validate(parts[-1]['request']['clauses'][-1])
                first = block[0]
                same_scope = first.article and scope(first) == scope(previous)
                same_original = fragments[first.clause_id]['original_clause_id'] == fragments[previous.clause_id]['original_clause_id']
                if same_scope and not same_original and re.match(r'\s*(?:[①-⑳]\s*)?(?:다만|그러나|예외|전항|제\s*\d+\s*항)', first.text):
                    pair = make_batch([previous]+block, definitions, instruction)
                    if len(block)+1 <= MAX_BATCH_CLAUSES and pair.input_bytes <= MAX_INPUT_BYTES:
                        current.append(previous)
                        current_context[previous.clause_id] = 'same_article_boundary_exception_context'
                    else:
                        current_omitted = True
            current.extend(block)
    flush()
    if len(parts) > MAX_PARTS:
        raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
    neighbor_copies = sum(len(p['context_reasons']) for p in parts)
    omitted_neighbors = sum(p['neighbor_context_omitted_budget'] for p in parts)
    # Bind checkpoints to the actual split/order/context policy, not just input.
    plan_id = digest({'policy':plan_id, 'parts':[p['request'] for p in parts],
                      'contexts':[p['context_reasons'] for p in parts], 'fragments':fragments})
    for index, part in enumerate(parts):
        part['part_id'] = plan_id[:24] + ':' + str(index)
    plan = {'plan_id': plan_id, 'source_digest': digest(raw), 'parts': parts, 'fragments': fragments,
            'original_clause_count': len(source.clauses), 'fragment_count': len(ordered),
            'context_copy_count': sum(bool(m['start'] != m['primary_start'] or m['end'] != m['primary_end']) for m in fragments.values()),
            'neighbor_context_copy_count': neighbor_copies, 'neighbor_context_omitted_count': omitted_neighbors,
            'boundary_review_required_count': sum(bool(m.get('boundary_context_review_required')) for m in fragments.values()),
            'preservation_verified': True}
    validate_preservation(source, plan)
    return plan


def normalize_with_offsets(text):
    chars, offsets = [], []
    for match in re.finditer(r'\s+|\S', text):
        chars.append(' ' if match.group().isspace() else match.group())
        offsets.append((match.start(), match.end()))
    return ''.join(chars), offsets


def merge_bulk(source, plan, results):
    validate_preservation(source, plan)
    parts = {p['part_id']: p for p in plan['parts']}
    if set(results) != set(parts):
        raise BulkError('BULK_INCOMPLETE', 409)
    if len(canonical(results).encode()) > 32_000_000:
        raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
    originals = {c.clause_id: c for c in source.clauses}
    merged, provenance, duplicates = [], {}, 0
    for part_id, part in parts.items():
        payload = results[part_id]
        if not isinstance(payload, dict) or set(payload) != {'extractions'} or not isinstance(payload['extractions'], list):
            raise BulkError('BULK_RESULT_INVALID', 502)
        local = {c['clause_id']: c for c in part['request']['clauses']}
        for row in payload['extractions']:
            if not isinstance(row, dict) or set(row) != {'check_item', 'clause_id', 'quote'}:
                raise BulkError('BULK_RESULT_INVALID', 502)
            cid, item, quote = row['clause_id'], row['check_item'], row['quote']
            if not isinstance(cid, str) or cid not in local or type(item) is not int or item not in source.check_items or not isinstance(quote, str) or not quote.strip():
                raise BulkError('BULK_RESULT_INVALID', 502)
            normalized = re.sub(r'\s+', ' ', quote)
            local_text, offsets = normalize_with_offsets(local[cid]['text'])
            positions = []
            for m in re.finditer(re.escape(normalized), local_text):
                start, end = offsets[m.start()][0], offsets[m.end()-1][1]
                meta = plan['fragments'][cid]
                positions.append([start + meta['start'], end + meta['start']])
            if not positions:
                raise BulkError('BULK_RESULT_INVALID', 502)
            original_id = plan['fragments'][cid]['original_clause_id']
            key = (item, original_id, normalized)
            if key not in provenance:
                merged.append(dict(check_item=item, clause_id=original_id, quote=quote))
                provenance[key] = {'check_item': item, 'clause_id': original_id, 'positions': set(), 'fragment_ids': set()}
            else:
                duplicates += 1
            provenance[key]['positions'].update(tuple(p) for p in positions)
            provenance[key]['fragment_ids'].add(cid)
    locations = [dict(check_item=v['check_item'], clause_id=v['clause_id'],
                      positions=sorted(v['positions']), fragment_ids=sorted(v['fragment_ids']),
                      ambiguous_location=len(v['positions']) > 1) for v in provenance.values()]
    return {'extractions': merged}, {'duplicates_removed': duplicates, 'locations': locations}


class BulkHttpError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__('bulk_part_failed')


@dataclass
class Checkpoint:
    plan_id: str
    completed: dict = field(default_factory=dict)
    attempts: dict = field(default_factory=dict)
    elapsed_seconds: float = 0
    failure: tuple | None = None


def run_bulk(source, plan, send, checkpoint=None, clock=time.monotonic):
    """Caller owns/persists checkpoint. Stop on first error; never auto-retry.

    send(request, timeout_seconds) must honor the timeout. Explicit resumption is
    allowed only for transient failures and is bounded across all resumes.
    """
    cp = checkpoint if checkpoint is not None else Checkpoint(plan['plan_id'])
    if cp.plan_id != plan['plan_id'] or not set(cp.completed).issubset({p['part_id'] for p in plan['parts']}):
        raise BulkError('BULK_PLAN_MISMATCH')
    if cp.failure and not (cp.failure[0] in (429, 503, 504) or (cp.failure[0] == 502 and cp.failure[1] in ('GEMINI_TIMEOUT', 'GEMINI_API_ERROR'))):
        raise BulkHttpError(*cp.failure)
    start = clock()
    try:
        if cp.elapsed_seconds >= MAX_JOB_SECONDS:
            raise BulkError('BULK_JOB_BUDGET_EXCEEDED', 502)
        for part in plan['parts']:
            pid = part['part_id']
            if pid in cp.completed:
                continue
            remaining = MAX_JOB_SECONDS - cp.elapsed_seconds - (clock()-start)
            if remaining <= 0 or sum(cp.attempts.values()) >= MAX_HTTP_ATTEMPTS or cp.attempts.get(pid, 0) >= MAX_ATTEMPTS_PER_PART:
                raise BulkError('BULK_JOB_BUDGET_EXCEEDED', 502)
            cp.attempts[pid] = cp.attempts.get(pid, 0) + 1
            try:
                response = send(part['request'], min(PART_TIMEOUT_SECONDS, remaining))
            except BulkHttpError as exc:
                cp.failure = (exc.status, exc.code)
                raise
            # Validate before checkpointing. No invalid completed result survives.
            # Whole-plan merge below checks complete preservation; validate a
            # single response against all parts by filling the rest with empties.
            provisional = {p['part_id']: {'extractions': []} for p in plan['parts']}
            provisional[pid] = response
            merge_bulk(source, plan, provisional)
            if clock()-start + cp.elapsed_seconds > MAX_JOB_SECONDS:
                raise BulkError('BULK_JOB_BUDGET_EXCEEDED', 502)
            cp.completed[pid] = response; cp.failure = None
        result = merge_bulk(source, plan, cp.completed)
        if clock()-start + cp.elapsed_seconds > MAX_JOB_SECONDS:
            raise BulkError('BULK_JOB_BUDGET_EXCEEDED', 502)
        return result
    except BulkError as exc:
        cp.failure = (exc.status, exc.code)
        raise
    except BulkHttpError:
        raise
    except Exception:
        cp.failure = (502, 'BULK_CLIENT_ERROR')
        raise BulkHttpError(*cp.failure) from None
    finally:
        cp.elapsed_seconds += max(0, clock()-start)


class PartResult(BaseModel):
    model_config = ConfigDict(extra='forbid')
    part_id: StrictStr
    response: dict


def install_bulk_routes(app, request_model, response_model, instruction_for, definitions, validate_final):
    """Optional stateless endpoints. Spring owns async status and checkpoints."""
    merge_model = create_model('BulkMergeRequest', source_request=(request_model, ...),
                              plan_id=(StrictStr, ...), part_results=(list[PartResult], ...))

    def plan_for(req):
        return plan_bulk(req, {str(i):definitions[i] for i in req.check_items}, instruction_for(req.check_items))

    def fail(exc):
        logger.warning('Gemini bulk rejected code=%s status=%d', exc.code, exc.status)
        return JSONResponse(status_code=exc.status, content={'code':exc.code, 'message':MESSAGES[exc.code]})

    @app.post('/ai/extract/plan')
    def bulk_plan(req: request_model):
        try:
            plan = plan_for(req)
            logger.info('Gemini bulk plan original_clauses=%d fragments=%d requests=%d neighbor_copies=%d boundary_review=%d',
                        len(req.clauses), plan['fragment_count'], len(plan['parts']),
                        plan['neighbor_context_copy_count'], plan['boundary_review_required_count'])
            return plan
        except BulkError as exc:
            return fail(exc)

    @app.post('/ai/extract/merge', response_model=response_model)
    def bulk_merge(req: merge_model):
        try:
            plan = plan_for(req.source_request)
            if plan['plan_id'] != req.plan_id or len({p.part_id for p in req.part_results}) != len(req.part_results):
                raise BulkError('BULK_PLAN_MISMATCH')
            payload, meta = merge_bulk(req.source_request, plan, {p.part_id:p.response for p in req.part_results})
            result = validate_final(payload, req.source_request)
            logger.info('Gemini bulk merged completed_requests=%d deduplicated=%d accepted=%d',
                        len(req.part_results), meta['duplicates_removed'], len(result.extractions))
            return result
        except BulkError as exc:
            return fail(exc)
