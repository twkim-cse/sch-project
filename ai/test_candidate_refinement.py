"""Offline precision refinements: ambiguous policy evidence is never discarded."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import candidate_filter as cf
import extract_planning as ep
import bulk_extract as bulk
import test_candidate_filter as existing


class RefinementTests(unittest.TestCase):
    setUp = existing.CandidateTests.setUp
    source = existing.CandidateTests.source
    plan = existing.CandidateTests.plan

    def test_complete_multiline_toc_with_financial_titles_is_noise(self):
        src=self.source(['제33조(해약환급금)49\n제34조(보험계약대출)50'])
        result=cf.select_candidates(src)
        self.assertEqual(result['decisions'][0]['unselected_reason'],'multiple_complete_toc_rows')

    def test_toc_with_one_ambiguous_continuation_is_kept(self):
        src=self.source(['제33조(해약환급금)49\n환급금은 지급하지 않습니다.'])
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_table_toc_like_rows_remain_ambiguous(self):
        src=self.source(['제33조(해약환급금)49\n제34조(보험계약대출)50'],from_table=True)
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_criminal_statute_not_rescued_by_stale_clinical_title(self):
        src=self.source(['타인의 재물을 손괴한 사람은 징역 또는 벌금에 처합니다.'],
                        article_title='의료기관의 정의',section='형법 부록')
        self.assertEqual(cf.select_candidates(src)['decisions'][0]['unselected_reason'],
                         'clearly_unrelated_criminal_statute')

    def test_body_insurance_exclusion_vetoes_criminal_rejection(self):
        src=self.source(['범죄로 징역을 받은 경우 보험금을 지급하지 않습니다.'],section='형법 부록')
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_body_reserve_payment_vetoes_stale_metadata_rejection(self):
        src=self.source(['책임준비금을 반환하지 않으면 벌금에 처합니다.'],section='형법 부록',article_title='입원')
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_medical_definition_in_statute_stays(self):
        src=self.source(['입원이란 치료를 위하여 병원에 입실하는 것을 말합니다. 위반하면 벌금에 처합니다.'],section='법규 부록')
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_body_without_legal_scope_is_not_confidently_rejected(self):
        src=self.source(['타인의 재물을 손괴한 사람은 징역 또는 벌금에 처합니다.'],article_title='입원의 정의')
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_noise_not_reintroduced_as_article_context(self):
        src=self.source(['보험금을 지급하지 않습니다.','제33조(해약환급금)49\n제34조(보험계약대출)50'],article='제5조')
        result=cf.select_candidates(src)
        self.assertTrue(result['decisions'][0]['selected']);self.assertFalse(result['decisions'][1]['selected'])

    def test_full_mode_recovers_new_exclusions_without_modification(self):
        src=self.source(['제33조(해약환급금)49\n제34조(보험계약대출)50'])
        self.assertEqual(cf.select_candidates(src,'full')['candidate_request'],src.model_dump())

    def test_single_clause_413_diagnostic_is_distinct_and_safe(self):
        src=self.source(['SECRET 보험금을 지급하지 않습니다. '*2000])
        with patch.object(self.ns['logger'],'warning') as log:
            response=self.h.api.post('/ai/extract',json=src.model_dump())
        self.assertEqual(response.status_code,413)
        self.assertEqual(response.json()['code'],'EXTRACT_INPUT_BUDGET_EXCEEDED')
        self.assertIn('single_clause_exceeds_input_budget',str(log.call_args_list))
        self.assertNotIn('SECRET',str(log.call_args_list))
        self.h.mock.models.generate_content.assert_not_called()

    def test_over_40_batches_split_http_and_candidate_merge(self):
        src=self.source(['보험금을 지급하지 않습니다.']*1001)
        with patch.object(self.ns['logger'],'warning') as log:
            rejected=self.h.api.post('/ai/extract',json=src.model_dump())
        self.assertEqual(rejected.status_code,413)
        self.assertIn('request_exceeds_batch_limit',str(log.call_args_list))
        self.h.mock.models.generate_content.assert_not_called()
        body={'source_request':src.model_dump(),'mode':'recall'}
        response=self.h.api.post('/ai/extract/candidates/plan',json=body)
        self.assertEqual(response.status_code,200)
        bundle=response.json();plan=bundle['candidate_plan']
        self.assertGreater(len(plan['parts']),40)
        selected=self.ns['ExtractRequest'].model_validate(cf.select_candidates(src)['candidate_request'])
        self.assertTrue(bulk.validate_preservation(selected,plan))
        self.h.mock.models.generate_content.side_effect=lambda **kw:SimpleNamespace(text=json.dumps({'extractions':[]}))
        results=[]
        for part in plan['parts']:
            result=self.h.api.post('/ai/extract',json=part['request'])
            self.assertEqual(result.status_code,200)
            results.append({'part_id':part['part_id'],'response':result.json()})
        merged=self.h.api.post('/ai/extract/candidates/merge',json=dict(body,
               selection_id=bundle['selection']['selection_id'],plan_id=plan['plan_id'],part_results=results))
        self.assertEqual(merged.status_code,200)
        self.assertFalse(merged.json()['coverage']['risk_absence_determined'])
        self.assertEqual(self.h.mock.models.generate_content.call_count,len(plan['parts']))

    def test_unknown_exception_identifier_is_sanitized(self):
        error=ep.ExtractPlanningError('SECRET')
        self.assertEqual(error.reason,'unknown_planning_error')
        self.assertNotIn('SECRET',str(error))

    def test_legacy_limits_unchanged(self):
        self.assertEqual((ep.MAX_BATCH_CLAUSES,ep.MAX_INPUT_BYTES,ep.WIRE_RESERVE_BYTES,ep.MAX_BATCHES),
                         (25,24000,2048,40))


if __name__=='__main__':unittest.main()
