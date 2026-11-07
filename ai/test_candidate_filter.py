import copy
import unittest
from unittest.mock import patch
import test_explain as fixtures
import candidate_filter as cf
import bulk_extract as bulk


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests();self.h.setUp();self.addCleanup(self.h.doCleanups)
        self.ns=self.h.ns

    def source(self,texts,items=None,**changes):
        clauses=[dict(clause_id=f'c-{i}',rider_id=None,section='주계약',article=f'제{i+1}조',article_title=None,
                      paragraph=None,page=i+1,from_table=False,text=t,**{}) for i,t in enumerate(texts)]
        for c in clauses:c.update(changes)
        return self.ns['ExtractRequest'].model_validate(dict(analysis_id='synthetic',check_items=items or [1,2,3,4,5,6],clauses=clauses))

    def plan(self,src,mode='recall',focus=None):
        return cf.candidate_plan(src,self.ns['ExtractRequest'],self.ns['CHECK_ITEM_DEFINITIONS'],self.ns['extract_instruction_for'],mode,focus)

    def test_six_body_categories_without_reliable_metadata(self):
        cases=[(1,'회사는 고의로 발생한 손해에 대해서는 보험금을 지급하지 않습니다.'),
         (2,'보장개시일은 계약일부터 90일이 지난 다음 날입니다.'),
         (3,'갱신 시 보험료는 연령과 보험요율에 따라 다시 계산합니다.'),
         (4,'청약서에서 질문한 사항에 대하여 사실대로 답해야 합니다.'),
         (5,'입원이란 질병 치료를 위하여 의료기관에 입실하는 것을 말합니다.'),
         (6,'해약환급금에서 보험계약대출 원리금을 공제하여 지급합니다.')]
        for item,text in cases:
            result=cf.select_candidates(self.source([text],items=[item],article_title='잘못된 제목'))
            self.assertEqual(result['coverage']['candidate_count'],1)
            self.assertIn(item,result['decisions'][0]['check_items'])

    def test_title_only_signal_kept_as_ambiguous(self):
        r=cf.select_candidates(self.source(['해당 조건은 아래 규정에 따라 적용합니다.'],items=[6],article_title='해약환급금'))
        self.assertTrue(r['decisions'][0]['selected'])
        self.assertIn('title_direct_ambiguous',r['decisions'][0]['evidence']['6']['reasons'])

    def test_multiple_categories_and_metadata_are_preserved(self):
        src=self.source(['90일 이내에는 보험금을 지급하지 않습니다.'],rider_id='r1',from_table=True)
        before=src.model_dump();r=cf.select_candidates(src)
        self.assertTrue({1,2}.issubset(r['decisions'][0]['check_items']))
        self.assertEqual(src.model_dump(),before);self.assertEqual(r['candidate_request']['clauses'],before['clauses'])

    def test_table_numbers_are_not_rejected_as_toc(self):
        for flag in [True,False]:
            r=cf.select_candidates(self.source(['50'],from_table=flag))
            self.assertTrue(r['decisions'][0]['selected'])
            self.assertFalse(r['coverage']['risk_absence_determined'])

    def test_confirmed_toc_fragment_excluded_but_real_short_condition_kept(self):
        src=self.source(['해약환급금........49','환급금은 없습니다.'],items=[6])
        r=cf.select_candidates(src)
        self.assertFalse(r['decisions'][0]['selected']);self.assertTrue(r['decisions'][1]['selected'])

    def test_actual_limit_number_without_table_flag_not_dropped(self):
        r=cf.select_candidates(self.source(['보험금 지급한도 100'],items=[2]))
        self.assertTrue(r['decisions'][0]['selected'])

    def test_legal_scope_does_not_exclude_direct_insurance_rule(self):
        r=cf.select_candidates(self.source(['해지환급금 청구권은 3년간 행사하지 않으면 소멸합니다.'],section='형법 부록',items=[6]))
        self.assertTrue(r['decisions'][0]['selected'])

    def test_unrelated_criminal_statute_not_a_policy_definition(self):
        r=cf.select_candidates(self.source(['타인의 재물을 손괴한 사람은 징역 또는 벌금에 처합니다.'],section='제42장 손괴의 죄'))
        self.assertFalse(r['decisions'][0]['selected'])
        self.assertEqual(r['decisions'][0]['unselected_reason'],'clearly_unrelated_criminal_statute')

    def test_important_exception_and_neighbors_in_same_scope(self):
        src=self.source(['별도 표에 따릅니다.','보험금을 지급하지 않습니다.','다만 앞 항의 예외가 적용됩니다.'],items=[1],article='제5조')
        r=cf.select_candidates(src)
        self.assertEqual(r['coverage']['candidate_count'],3)

    def test_context_never_crosses_rider_scope(self):
        src=self.source(['보험금을 지급하지 않습니다.','무관한 안내 문장입니다.'],items=[1],article='제5조')
        src.clauses[1].rider_id='different'
        r=cf.select_candidates(src)
        self.assertFalse(r['decisions'][1]['selected'])

    def test_bad_large_article_metadata_does_not_flood_context(self):
        src=self.source(['보관 안내 문장입니다.']*40,items=[1],article='제44조')
        src.clauses[20].text='보험금을 지급하지 않습니다.'
        r=cf.select_candidates(src)
        self.assertEqual(r['coverage']['candidate_count'],3)

    def test_generic_insurance_word_alone_not_all_categories(self):
        r=cf.select_candidates(self.source(['보험 관련 문서 보관 안내입니다.']))
        self.assertEqual(r['coverage']['candidate_count'],0)
        self.assertFalse(r['coverage']['risk_absence_determined'])

    def test_zero_evidence_triggers_broad_fallback_and_no_absence_claim(self):
        r=cf.select_candidates(self.source(['갱신에 관한 안내입니다.'],items=[3]))
        self.assertIn(3,r['coverage']['automatic_broad_fallback_items'])
        self.assertTrue(r['decisions'][0]['selected'])
        self.assertFalse(r['coverage']['recall_verified'])

    def test_full_fallback_can_recover_all_unselected_and_noise(self):
        src=self.source(['문서 보관 안내입니다.','목차......30'])
        r=cf.select_candidates(src,'full',focus_items=[4])
        self.assertEqual(r['candidate_request']['check_items'],[4])
        self.assertEqual(r['candidate_request']['clauses'],src.model_dump()['clauses'])

    def test_plan_and_fingerprint_bind_even_unselected_source(self):
        src=self.source(['보험금을 지급하지 않습니다.','무관한 보관 안내입니다.'],items=[1])
        bundle,sub=self.plan(src)
        self.assertTrue(bulk.validate_preservation(sub,bundle['candidate_plan']))
        old=bundle['candidate_plan']['plan_id'];src.clauses[1].text+=' 수정'
        self.assertNotEqual(self.plan(src)[0]['candidate_plan']['plan_id'],old)

    def test_oversized_candidate_uses_existing_lossless_split(self):
        src=self.source(['해약환급금을 지급합니다. '*2000],items=[6])
        bundle,sub=self.plan(src)
        self.assertGreater(bundle['candidate_plan']['fragment_count'],1)
        self.assertTrue(bulk.validate_preservation(sub,bundle['candidate_plan']))

    def test_old_full_bulk_plan_still_includes_every_original(self):
        src=self.source(['보험금을 지급하지 않습니다.','무관한 안내입니다.'],items=[1])
        plan=bulk.plan_bulk(src,{'1':self.ns['CHECK_ITEM_DEFINITIONS'][1]},self.ns['extract_instruction_for']([1]))
        self.assertEqual(plan['original_clause_count'],2)
        self.assertEqual(len(plan['parts'][0]['request']['clauses']),2)

    def test_http_merge_candidates_is_explicitly_scoped_and_no_gemini(self):
        src=self.source(['보험금을 지급하지 않습니다.','무관한 안내입니다.'],items=[1])
        body={'source_request':src.model_dump()}
        p=self.h.api.post('/ai/extract/candidates/plan',json=body)
        self.assertEqual(p.status_code,200);bundle=p.json()
        b=dict(body,selection_id=bundle['selection']['selection_id'],plan_id=bundle['candidate_plan']['plan_id'],
               part_results=[{'part_id':x['part_id'],'response':{'extractions':[]}} for x in bundle['candidate_plan']['parts']])
        response=self.h.api.post('/ai/extract/candidates/merge',json=b)
        self.assertEqual(response.status_code,200)
        self.assertFalse(response.json()['coverage']['full_document_analyzed'])
        self.assertFalse(response.json()['coverage']['risk_absence_determined'])
        self.h.mock.models.generate_content.assert_not_called();self.h.mock.models.list.assert_not_called()

    def test_incomplete_filtered_merge_returns_error_not_partial_200(self):
        src=self.source(['보험금을 지급하지 않습니다.'],items=[1]);body={'source_request':src.model_dump()}
        bundle=self.h.api.post('/ai/extract/candidates/plan',json=body).json()
        r=self.h.api.post('/ai/extract/candidates/merge',json=dict(body,selection_id=bundle['selection']['selection_id'],plan_id=bundle['candidate_plan']['plan_id'],part_results=[]))
        self.assertEqual(r.status_code,409);self.assertEqual(set(r.json()),{'code','message'})

    def test_progress_partial_lists_failed_and_pending_original_ranges(self):
        src=self.source(['보험금을 지급하지 않습니다.']*70,items=[1]);bundle,_=self.plan(src);plan=bundle['candidate_plan']
        snap=cf.progress_snapshot(plan,[plan['parts'][0]['part_id']],[plan['parts'][1]['part_id']])
        self.assertEqual(snap['status'],'PARTIAL');self.assertFalse(snap['full_document_success'])
        self.assertTrue(snap['failed_ranges'][0]['original_clause_ids'])
        self.assertFalse(snap['results_validated_by_this_endpoint'])

    def test_invalid_focus_and_changed_fingerprint_rejected(self):
        src=self.source(['보험금을 지급하지 않습니다.'],items=[1])
        r=self.h.api.post('/ai/extract/candidates',json={'source_request':src.model_dump(),'focus_items':[4]})
        self.assertEqual(r.status_code,422)
        bundle,_=self.plan(src)
        r=self.h.api.post('/ai/extract/candidates/progress',json=dict(source_request=src.model_dump(),selection_id='wrong',plan_id=bundle['candidate_plan']['plan_id'],completed_part_ids=[],failed_part_ids=[]))
        self.assertEqual(r.status_code,422)

    def test_safe_logs_do_not_include_source(self):
        with patch.object(cf.logger,'info') as info:
            r=self.h.api.post('/ai/extract/candidates',json={'source_request':self.source(['SECRET 보험금을 지급하지 않습니다.']).model_dump()})
        self.assertEqual(r.status_code,200);self.assertNotIn('SECRET',str(info.call_args_list))
        self.assertFalse(self.ns['extract_recheck'].ENABLED)

    def test_specialist_synonyms_and_nonstandard_spacing(self):
        for item,text in [(2,'회사의 책 임 개 시 일은 다음 규정에 따릅니다.'),
                          (3,'Renewal premium depends on age.'),
                          (5,'이 범위에는 관혈적 조작과 조직검사가 포함됩니다.'),
                          (6,'회사는 계약자적립액을 반환합니다.')]:
            r=cf.select_candidates(self.source([text],items=[item]))
            self.assertTrue(r['decisions'][0]['selected'])

    def test_false_table_metadata_does_not_drop_material_amount(self):
        src=self.source(['보험금 100'],items=[2],from_table=False)
        r=cf.select_candidates(src)
        self.assertEqual(r['candidate_request']['clauses'],src.model_dump()['clauses'])
        self.assertFalse(r['coverage']['risk_absence_determined'])

    def test_existing_checkpoint_abort_resume_still_works_for_filtered_plan(self):
        src=self.source(['보험금을 지급하지 않습니다.']*70,items=[1]);bundle,sub=self.plan(src);plan=bundle['candidate_plan']
        cp=bulk.Checkpoint(plan['plan_id']);calls=[]
        def fail(body,timeout):
            calls.append(body['clauses'][0]['clause_id'])
            if len(calls)==2:raise bulk.BulkHttpError(502,'GEMINI_API_ERROR')
            return {'extractions':[]}
        with self.assertRaises(bulk.BulkHttpError):bulk.run_bulk(sub,plan,fail,cp)
        self.assertEqual(len(calls),2);resumed=[]
        def succeed(body,timeout):resumed.append(body['clauses'][0]['clause_id']);return {'extractions':[]}
        bulk.run_bulk(sub,plan,succeed,cp)
        self.assertNotIn(calls[0],resumed)

    def test_legal_penalty_with_reserve_payment_evidence_is_retained(self):
        src=self.source(['책임준비금을 반환하지 않은 경우 벌금 규정을 적용합니다.'],items=[6],section='법규 부록')
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_title_amount_without_units_is_not_confidently_toc(self):
        src=self.source(['해약환급금 49'],items=[6],article_title='해약환급금',from_table=False)
        self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_clinical_definition_title_with_implicit_body_is_not_lost(self):
        for title in ['의료기관','특정감염병Ⅱ의 정의','장애인 등록']:
            src=self.source(['해당 법률에서 정한 기준을 적용합니다.'],items=[5],article_title=title)
            self.assertTrue(cf.select_candidates(src)['decisions'][0]['selected'])

    def test_reference_only_with_unreliable_metadata_is_ambiguous_candidate(self):
        src=self.source(['제12조에서 정한 규정을 준용합니다.'])
        r=cf.select_candidates(src)
        self.assertTrue(r['decisions'][0]['selected'])
        self.assertFalse(r['coverage']['risk_absence_determined'])

    def test_symbol_cells_with_missing_table_flag_are_ambiguous_not_noise(self):
        for value in ['-','×','○']:
            r=cf.select_candidates(self.source([value],from_table=False))
            self.assertTrue(r['decisions'][0]['selected'])


if __name__=='__main__':unittest.main()
