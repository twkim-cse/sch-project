"""Recall-oriented candidate scheduling; never a finding of absent risks.

This module has no clients, environment reads, persistence, or Gemini calls.
Search normalization never modifies text sent to the lossless planner.
"""
from collections import Counter
import logging
import re

from fastapi.responses import JSONResponse
from pydantic import ConfigDict, Field, StrictStr, create_model
from typing import Literal
from bulk_extract import (BulkError, PartResult, MESSAGES, MAX_SOURCE_BYTES,
                         MAX_SOURCE_CLAUSES, canonical, digest, plan_bulk, merge_bulk)

VERSION = 'recall-rules-2'
logger = logging.getLogger('uvicorn.error')

DIRECT = {
  1: r'면책|부담보|지급하지|보상하지|보장하지|보장제외|지급제한|보상제외|지급에서제외|보장대상에서제외',
  2: r'면책기간|감액|대기기간|보장개시|보상개시|책임개시|보장한도|지급한도|지급률|보상한도|부담보기간|일당한도',
  3: r'갱신보험료|보험료재산정|보험료인상|보험료인하|보험료변동|보험료변경|보험료조정|보험요율',
  4: r'알릴의무|고지의무|통지의무|의무위반|고지사항|계약전고지|계약후고지',
  5: r'진단확정|한국표준질병|질병분류|상병분류|수술분류|입원의정의|수술의정의|질병의정의|치료의정의|병리학|조직검사|관혈|내시경|치아우식|치주질환|치수염|의료법제3조',
  6: r'해약환급|해지환급|환급금|환급액|해약반환금|미경과보험료|보험계약대출|자동대출납입',
}
PAIRS = {
  1: (r'보험금|급여금|보장|보상|지급', r'제외|제한|금지|없다|없습니다|고의|자살|범죄|감액'),
  2: (r'보험금|급여금|보장|보상|지급|치료', r'이내|이후|이전|경과|지난|최초|일한도|횟수|퍼센트|%|분의'),
  3: (r'갱신|재가입|renewal', r'보험료|요율|보험기간|조건|나이|연령|인상|인하|기초율|위험률|premium'),
  4: (r'고지|통지|알려|알릴|알려야|사실대로|직업|직무|건강상태', r'의무|질문|청약|계약|변경|보험|답하여|답해야'),
  5: (r'질병|상해|뇌심|암|치아|입원|통원|수술|치료|의료행위|의료기관|요양|절단|절제|절개|진단', r'정의|이란|이라함|이라하|말합|말한|인정|범위|해당|분류|기준|직접목적'),
  6: (r'해지|해약|환급|대출|책임준비금|계약자적립액|적립금', r'보험|계약|지급|반환|공제|차감|한도|금액|소멸시효|청구|원리금'),
}
BROAD = {
  1: r'면책|부담보|제외|제한|지급하지|보상하지|고의|자살',
  2: r'기간|감액|개시|한도|지급률|%|퍼센트|경과|이후|이내',
  3: r'갱신|재가입|보험료|요율|위험률|renewal',
  4: r'고지|통지|알릴|청약|질문|의무|변경',
  5: r'정의|질병|상해|입원|통원|수술|치료|진단|의료|치아|뇌심|diagnosis|surgery',
  6: r'환급|해지|해약|대출|공제|반환|소멸시효',
}
INSURANCE = r'보험금|보험료|보험계약|보장|급여금|환급|고지|알릴의무|진단|입원|수술|치료|치아'
EXCEPTION = r'^(?:[①-⑳]\s*)?(?:다만|그러나|예외|전항|제\d+항|위의규정에도|앞의규정에도)'


def search(text):
    return re.sub(r'\s+', '', text or '').casefold()


def rejected_kind(clause):
    """Only high-confidence noise; scope metadata alone never excludes."""
    t = search(clause.text)
    if not t:
        return 'empty_text'
    if clause.from_table:
        return None  # A short number/percentage can be a material table cell.
    # Require multiple complete TOC rows, not a lone title + possible amount.
    # Normative prose or an ambiguous continuation defeats this exclusion.
    lines = [search(line) for line in clause.text.splitlines() if search(line)]
    toc_row = r'제\d+조(?:\([^\d\n]+\)|[^\d\n]+?)(?:[.·…]+)?\d{1,3}'
    normative = r'합니다|습니다|한다|된다|하여야|해야|이란|이라함|경우|다만|[①②③④⑤⑥⑦⑧⑨⑩]'
    if (len(lines) >= 2 and all(re.fullmatch(toc_row, line) for line in lines)
            and not re.search(normative, t)):
        return 'multiple_complete_toc_rows'
    if re.fullmatch(r'[-－—×○◯]+',t):
        return None  # Missing table metadata must not erase a meaningful cell.
    if re.fullmatch(r'[\W_]+', t) and not re.search(r'%|‰', t):
        return 'nonprose_fragment'
    prose = re.search(r'합니다|습니다|한다|된다|하여야|해야|이란|이라함|말한다|말합니다|경우|다만|[①-⑳]', t)
    title = search(clause.article_title)
    compact_heading_page = bool(title and re.fullmatch(re.escape(title)+r'\d{1,3}',t)
                                and not re.search(r'보험금|환급|보험료|한도|지급률|기간',title))
    if len(t) < 220 and not prose and (re.search(r'\.{3,}|…{2,}', t) or compact_heading_page):
        return 'toc_like_fragment'
    scope = search(clause.section) + search(clause.article_title)
    legal_scope = re.search(r'형법|민법|법규|관련법|성폭력|손괴의죄|절도와강도의죄', scope)
    criminal = re.search(r'징역|벌금|강간|살인|처벌|형에처|손괴', t)
    # A stale clinical/refund title cannot turn an unrelated penal provision
    # into a policy rule. Actual body evidence still vetoes exclusion.
    body_evidence = any(re.search(DIRECT[i], t) or
                        (re.search(PAIRS[i][0], t) and re.search(PAIRS[i][1], t))
                        for i in DIRECT)
    if legal_scope and criminal and not re.search(INSURANCE, t) and not body_evidence:
        return 'clearly_unrelated_criminal_statute'
    return None


def evidence(clause, item, broad=False):
    t, title, section = search(clause.text), search(clause.article_title), search(clause.section)
    reasons, score = [], 0
    if re.search(DIRECT[item], t):
        reasons.append('body_direct'); score += 6
    a, b = PAIRS[item]
    if re.search(a, t) and re.search(b, t):
        reasons.append('body_multiple_signals'); score += 4
    if re.search(DIRECT[item], title) and len(t) >= 8:
        reasons.append('title_direct_ambiguous'); score += 3
    if item == 5 and len(t) >= 8 and re.search(r'질병|감염|진단|수술|입원|통원|의료기관|병원|치아|치료|장애|장해',title):
        reasons.append('clinical_title_ambiguous'); score += 3
    if re.search(a, title + section) and re.search(b, t) and re.search(a, t):
        reasons.append('metadata_body_combination'); score += 2
    if broad and re.search(BROAD[item], t + title):
        reasons.append('broad_fallback_ambiguous'); score += 1
    return score, reasons


def select_candidates(source, mode='recall', focus_items=None):
    if mode not in ('recall', 'broad', 'full'):
        raise BulkError('BULK_PLAN_MISMATCH')
    items = list(dict.fromkeys(source.check_items if focus_items is None else focus_items))
    if not items and source.check_items or any(i not in source.check_items for i in items):
        raise BulkError('BULK_PLAN_MISMATCH')
    if len(source.clauses) > MAX_SOURCE_CLAUSES or len(canonical(source.model_dump()).encode()) > MAX_SOURCE_BYTES:
        raise BulkError('BULK_INPUT_BUDGET_EXCEEDED', 413)
    selected, excluded = {}, {}
    decisions = {}

    def add(index, item, score, reasons):
        entry = selected.setdefault(index, {})
        old = entry.setdefault(item, {'score': 0, 'reasons': []})
        old['score'] = max(old['score'], score)
        old['reasons'] = sorted(set(old['reasons'] + reasons))

    for index, clause in enumerate(source.clauses):
        kind = rejected_kind(clause)
        if kind and mode != 'full':
            excluded[index] = kind
            continue
        for item in items:
            score, reasons = evidence(clause, item, broad=mode == 'broad')
            if mode == 'full':
                reasons.append('explicit_full_fallback'); score = max(score, 1)
            if score:
                add(index, item, score, reasons)
        if clause.from_table and not selected.get(index):
            for item in items:
                add(index, item, 1, ['unresolved_table_include'])
        if re.fullmatch(r'[-－—×○◯]+',search(clause.text)):
            for item in items:add(index,item,1,['unresolved_symbol_cell_include'])
        if re.fullmatch(r'\d+(?:[.,]\d+)?',search(clause.text)):
            for item in items:
                if item in (2,6):add(index,item,1,['ambiguous_numeric_fragment_include'])
        t=search(clause.text)
        if 2 in items and len(t)<120 and re.search(r'\d',t) and re.search(r'보험금|급여금|보상액|지급액|한도',t):
            add(index,2,1,['unresolved_amount_or_rate_include'])
        if re.search(EXCEPTION, search(clause.text)) and not selected.get(index):
            for item in items:
                add(index, item, 1, ['orphan_exception_include'])
        if not selected.get(index) and len(t)<240 and re.search(r'제\d+조|별표\d',t) and re.search(r'준용|따른|적용|정한|규정',t):
            for item in items:add(index,item,1,['ambiguous_reference_include'])

    def has_evidence(values,item):
        return item in values and any(r.startswith(('body_','title_','clinical_','metadata_','broad_')) for r in values[item]['reasons'])
    primary_counts = {str(i):sum(has_evidence(values,i) for values in selected.values()) for i in items}
    fallback_items = [i for i in items if primary_counts[str(i)] == 0]
    if mode == 'recall':
        for item in fallback_items:
            for index, clause in enumerate(source.clauses):
                if index not in excluded:
                    score, reasons = evidence(clause, item, broad=True)
                    if score:
                        add(index, item, score, reasons)
    evidence_counts = {str(i):sum(has_evidence(values,i) for values in selected.values()) for i in items}

    # One-hop context only; no recursive flood across bad metadata. A compact
    # contiguous article is kept whole. Large/misparsed articles use neighbors.
    def scope(c):
        return c.rider_id, c.section, c.article

    original_seeds = list(selected)
    for index in original_seeds:
        clause = source.clauses[index]
        neighbors = [j for j in (index-1,index+1) if 0 <= j < len(source.clauses)
                     and scope(source.clauses[j]) == scope(clause)]
        group = [index]
        if clause.article:
            left, right = index-1, index+1
            while left >= 0 and scope(source.clauses[left]) == scope(clause) and len(group) <= 12:
                group.append(left); left -= 1
            while right < len(source.clauses) and scope(source.clauses[right]) == scope(clause) and len(group) <= 12:
                group.append(right); right += 1
        targets = set(neighbors + (group if len(group) <= 12 else []))
        for j in targets:
            if j in excluded:
                continue
            for item in list(selected[index]):
                add(j, item, 1, ['same_scope_article_context'])

    records = []
    for index, clause in enumerate(source.clauses):
        values = selected.get(index, {})
        reason = excluded.get(index, 'no_rule_evidence_unreviewed') if not values else None
        records.append({'clause_id':clause.clause_id, 'source_index':index,
                        'selected':bool(values), 'check_items':sorted(values),
                        'evidence':{str(k):v for k,v in sorted(values.items())},
                        'unselected_reason':reason})
    candidate_request = source.model_copy(update={'check_items':items,
                                                 'clauses':[c for i,c in enumerate(source.clauses) if i in selected]})
    selection_id = digest({'source':source.model_dump(), 'policy':VERSION, 'mode':mode,
                           'items':items, 'records':records})
    return {'selection_id':selection_id,'policy_version':VERSION,'mode':mode,
            'candidate_request':candidate_request.model_dump(),'decisions':records,
            'coverage':{'scope':'candidate_rules','original_count':len(source.clauses),
                        'candidate_count':len(selected),'unselected_count':len(source.clauses)-len(selected),
                        'selected_ratio':len(selected)/len(source.clauses) if source.clauses else 0,
                        'by_check_item':{str(i):sum(i in v for v in selected.values()) for i in items},
                        'requested_check_items':items,'original_requested_check_items':list(source.check_items),
                        'initial_evidence_counts':primary_counts,'expanded_evidence_counts':evidence_counts,
                        'automatic_broad_fallback_items':fallback_items if mode == 'recall' else [],
                        'items_without_evidence':[i for i in items if evidence_counts[str(i)] == 0],
                        'unselected_reasons':dict(Counter(r['unselected_reason'] for r in records if not r['selected'])),
                        'full_document_analyzed':False,'recall_verified':False,
                        'risk_absence_determined':False}}


def candidate_plan(source, request_model, definitions, instruction_for, mode='recall', focus_items=None):
    selection = select_candidates(source, mode, focus_items)
    req = request_model.model_validate(selection['candidate_request'])
    plan = plan_bulk(req, {str(i):definitions[i] for i in req.check_items}, instruction_for(req.check_items))
    plan['plan_id'] = digest({'bulk_plan_id':plan['plan_id'], 'selection_id':selection['selection_id']})
    for index, part in enumerate(plan['parts']):
        part['part_id'] = plan['plan_id'][:24] + ':' + str(index)
    metadata = {k:v for k,v in selection.items() if k != 'candidate_request'}
    return {'selection':metadata, 'candidate_plan':plan}, req


def progress_snapshot(plan, completed, failed):
    parts = {p['part_id']:p for p in plan['parts']}
    done, bad = set(completed), set(failed)
    if len(done) != len(completed) or len(bad) != len(failed) or done & bad or not (done | bad).issubset(parts):
        raise BulkError('BULK_PLAN_MISMATCH')
    pending = set(parts)-done-bad
    status = 'PARTIAL' if done and bad else ('FAILED' if bad else ('CANDIDATES_COMPLETE' if not pending else ('RUNNING' if done else 'PLANNED')))
    def ranges(ids):
        return [{'part_id':pid,'original_clause_ids':sorted({plan['fragments'][c['clause_id']]['original_clause_id'] for c in parts[pid]['request']['clauses']})} for pid in sorted(ids)]
    return {'status':status,'completed_count':len(done),'failed_count':len(bad),
            'pending_count':len(pending),'failed_ranges':ranges(bad),'pending_ranges':ranges(pending),
            'all_candidates_completed':not pending and not bad,'full_document_success':False,
            'advisory_checkpoint_snapshot':True,'results_validated_by_this_endpoint':False}


def install_candidate_routes(app, request_model, check_item_type, definitions, instruction_for, validate_final):
    options = dict(source_request=(request_model,...), mode=(Literal['recall','broad','full'],'recall'),
                   focus_items=(list[check_item_type] | None, Field(default=None,min_length=1)))
    candidate_model = create_model('CandidateRequest',__config__=ConfigDict(extra='forbid'),**options)
    merge_model = create_model('CandidateMergeRequest',__base__=candidate_model,
                              selection_id=(StrictStr,...),plan_id=(StrictStr,...),part_results=(list[PartResult],...))
    progress_model = create_model('CandidateProgressRequest',__base__=candidate_model,
                                 selection_id=(StrictStr,...),plan_id=(StrictStr,...),
                                 completed_part_ids=(list[StrictStr],...), failed_part_ids=(list[StrictStr],...))

    def fail(exc):
        logger.warning('Gemini candidates rejected code=%s status=%d',exc.code,exc.status)
        return JSONResponse(status_code=exc.status,content={'code':exc.code,'message':MESSAGES[exc.code]})

    def planned(req):
        return candidate_plan(req.source_request,request_model,definitions,instruction_for,req.mode,req.focus_items)

    @app.post('/ai/extract/candidates')
    def candidates(req: candidate_model):
        try:
            selected=select_candidates(req.source_request,req.mode,req.focus_items)
            logger.info('Gemini candidates selected original=%d candidates=%d',len(req.source_request.clauses),selected['coverage']['candidate_count'])
            return selected
        except BulkError as exc:return fail(exc)

    @app.post('/ai/extract/candidates/plan')
    def plan(req: candidate_model):
        try:return planned(req)[0]
        except BulkError as exc:return fail(exc)

    def verify(req):
        bundle,sub=planned(req)
        if bundle['selection']['selection_id'] != req.selection_id or bundle['candidate_plan']['plan_id'] != req.plan_id:
            raise BulkError('BULK_PLAN_MISMATCH')
        return bundle,sub

    @app.post('/ai/extract/candidates/merge')
    def merge(req: merge_model):
        try:
            bundle,sub=verify(req)
            if len({r.part_id for r in req.part_results}) != len(req.part_results):raise BulkError('BULK_PLAN_MISMATCH')
            payload,meta=merge_bulk(sub,bundle['candidate_plan'],{r.part_id:r.response for r in req.part_results})
            result=validate_final(payload,req.source_request)
            coverage=dict(bundle['selection']['coverage'],status='CANDIDATES_COMPLETE',all_candidates_completed=True,
                          analyzed_candidate_count=len(sub.clauses),full_document_analyzed=len(sub.clauses)==len(req.source_request.clauses),
                          full_document_success=False,duplicates_removed=meta['duplicates_removed'])
            return dict(result.model_dump(),coverage=coverage,selection_id=req.selection_id,plan_id=req.plan_id)
        except BulkError as exc:return fail(exc)

    @app.post('/ai/extract/candidates/progress')
    def progress(req: progress_model):
        try:
            bundle,_=verify(req)
            return dict(progress_snapshot(bundle['candidate_plan'],req.completed_part_ids,req.failed_part_ids),
                        coverage=bundle['selection']['coverage'])
        except BulkError as exc:return fail(exc)
