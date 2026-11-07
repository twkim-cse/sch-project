"""Offline, synthetic-only lossless request planning and orchestration tests."""
import copy
import unittest
from unittest.mock import patch
import test_explain as fixtures
import bulk_extract as b
from extract_planning import make_batch, plan_extraction


class BulkExtractTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests(); self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.ns = self.h.ns
        self.defs = {str(i): self.ns['CHECK_ITEM_DEFINITIONS'][i] for i in range(1, 7)}
        self.instruction = self.ns['extract_instruction_for']([1,2,3,4,5,6])

    def request(self, count=10, text='이 조항은 가상의 보험약관 테스트 문장입니다. '):
        return self.ns['ExtractRequest'].model_validate(dict(analysis_id='bulk-test',check_items=[1,2,3,4,5,6],
          clauses=[dict(clause_id=f'c-{i}',rider_id='r1',section='가상 특약',article=f'제{i+1}조',
                        article_title='시험',paragraph='제1항',page=i+1,from_table=bool(i%2),text=text) for i in range(count)]))

    def plan(self, req):
        return b.plan_bulk(req,self.defs,self.instruction)

    def empty_results(self, plan):
        return {p['part_id']:{'extractions':[]} for p in plan['parts']}

    def test_small_preserves_every_field_and_single_request(self):
        req=self.request();plan=self.plan(req)
        self.assertEqual(plan['parts'][0]['request'],req.model_dump())
        self.assertEqual(len(plan['parts']),1)
        self.assertTrue(b.validate_preservation(req,plan))

    def test_100_300_500_are_bounded_and_have_no_missing_ids(self):
        for count in [100,300,500]:
            req=self.request(count);plan=self.plan(req)
            ids=[c['clause_id'] for p in plan['parts'] for c in p['request']['clauses']]
            self.assertEqual(len(ids),count);self.assertEqual(len(set(ids)),count)
            for p in plan['parts']:
                self.assertLessEqual(len(p['request']['clauses']),25)
                self.assertLessEqual(p['input_bytes'],24000)
                chunk=self.ns['ExtractRequest'].model_validate(p['request'])
                batches,_=plan_extraction(chunk.clauses,chunk.check_items,self.defs,self.instruction)
                self.assertEqual(len(batches),1)

    def test_oversized_clause_reassembles_exactly(self):
        req=self.request(1,('조건과 예외를 그대로 보존하는 가상 문장입니다.\n\n'*1000)+'끝입니다.')
        plan=self.plan(req);self.assertGreater(plan['fragment_count'],1)
        self.assertGreater(plan['context_copy_count'],0)
        self.assertTrue(b.validate_preservation(req,plan))
        self.assertTrue(all(m['context_reasons'] for m in plan['fragments'].values()))

    def test_unsplittable_never_truncates(self):
        req=self.request(1,'가'*20000)
        with self.assertRaises(b.BulkError) as e:self.plan(req)
        self.assertEqual(e.exception.code,'BULK_UNSPLITTABLE_CLAUSE')
        self.assertEqual(len(req.clauses[0].text),20000)

    def test_structured_numbered_list_is_not_cut_inside_item(self):
        req=self.request(1,'목록의 공통 조건 '+('1. '+('가'*120)+' 2. '+('나'*120)+' 3. '+('다'*120)+' ')*100)
        plan=self.plan(req)
        self.assertTrue(b.validate_preservation(req,plan))
        self.assertGreater(plan['fragment_count'],1)
        self.assertTrue(all(m.get('boundary_context_review_required') for m in plan['fragments'].values()))

    def test_damaged_primary_coverage_rejected(self):
        req=self.request();plan=self.plan(req)
        plan['fragments']['c-0']['primary_start']=1
        with self.assertRaises(b.BulkError):b.validate_preservation(req,plan)

    def test_changed_metadata_rejected(self):
        req=self.request();plan=self.plan(req)
        plan['parts'][0]['request']['clauses'][0]['rider_id']='other'
        with self.assertRaises(b.BulkError):b.validate_preservation(req,plan)

    def test_merge_restores_original_id_and_keeps_positions(self):
        req=self.request(1,'고유한 문장입니다. '+('예외를 보존하는 시험 문장입니다. '*1500))
        plan=self.plan(req);results=self.empty_results(plan)
        for part in plan['parts']:
            c=part['request']['clauses'][0]
            results[part['part_id']]['extractions']=[dict(check_item=1,clause_id=c['clause_id'],quote=c['text'])]
        merged,meta=b.merge_bulk(req,plan,results)
        self.assertTrue(all(x['clause_id']=='c-0' for x in merged['extractions']))
        self.assertTrue(all(x['positions'] for x in meta['locations']))

    def test_whitespace_quote_allowed_changed_number_rejected(self):
        req=self.request(1,'대기 기간은 90일입니다.\n  적용 조건입니다.');plan=self.plan(req)
        results=self.empty_results(plan);pid=plan['parts'][0]['part_id']
        results[pid]['extractions']=[dict(check_item=2,clause_id='c-0',quote='90일입니다. 적용')]
        self.assertEqual(len(b.merge_bulk(req,plan,results)[0]['extractions']),1)
        results[pid]['extractions'][0]['quote']='60일입니다.'
        with self.assertRaises(b.BulkError):b.merge_bulk(req,plan,results)

    def test_duplicate_results_preserve_all_possible_locations(self):
        req=self.request(1,'같은 문장입니다. 같은 문장입니다.');plan=self.plan(req)
        row=dict(check_item=1,clause_id='c-0',quote='같은 문장입니다.')
        results={plan['parts'][0]['part_id']:{'extractions':[row,row]}}
        merged,meta=b.merge_bulk(req,plan,results)
        self.assertEqual(len(merged['extractions']),1);self.assertEqual(meta['duplicates_removed'],1)
        self.assertEqual(len(meta['locations'][0]['positions']),2)
        self.assertTrue(meta['locations'][0]['ambiguous_location'])

    def test_incomplete_never_returns_partial_success(self):
        req=self.request(50);plan=self.plan(req)
        with self.assertRaises(b.BulkError) as e:b.merge_bulk(req,plan,{})
        self.assertEqual(e.exception.status,409)

    def test_second_request_failure_stops_then_only_failed_and_later_resume(self):
        req=self.request(70);plan=self.plan(req);cp=b.Checkpoint(plan['plan_id']);calls=[]
        def fail_second(body,timeout):
            calls.append(body['clauses'][0]['clause_id'])
            if len(calls)==2:raise b.BulkHttpError(502,'GEMINI_API_ERROR')
            return {'extractions':[]}
        with self.assertRaises(b.BulkHttpError):b.run_bulk(req,plan,fail_second,cp)
        self.assertEqual(len(calls),2);self.assertEqual(len(cp.completed),1)
        resumed=[]
        def succeed(body,timeout):resumed.append(body['clauses'][0]['clause_id']);return {'extractions':[]}
        self.assertEqual(b.run_bulk(req,plan,succeed,cp)[0],{'extractions':[]})
        self.assertNotIn(calls[0],resumed);self.assertEqual(resumed[0],calls[1])

    def test_permanent_413_422_are_not_resumed(self):
        for status in [413,422]:
            req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id']);calls=[]
            def fail(body,timeout):calls.append(1);raise b.BulkHttpError(status,'REQUEST_VALIDATION_ERROR')
            for _ in range(2):
                with self.assertRaises(b.BulkHttpError):b.run_bulk(req,plan,fail,cp)
            self.assertEqual(len(calls),1)

    def test_retry_attempts_are_bounded_across_resumes(self):
        req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id']);calls=[]
        def fail(body,timeout):calls.append(1);raise b.BulkHttpError(503,'GEMINI_API_ERROR')
        for _ in range(2):
            with self.assertRaises(b.BulkHttpError):b.run_bulk(req,plan,fail,cp)
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,fail,cp)
        self.assertEqual(len(calls),2)

    def test_total_budget_includes_completed_and_failed_calls(self):
        req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id']);now=[0]
        def slow(body,timeout):
            self.assertLessEqual(timeout,270);now[0]+=b.MAX_JOB_SECONDS+1;return {'extractions':[]}
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,slow,cp,clock=lambda:now[0])
        self.assertEqual(cp.completed,{})
        self.assertGreater(cp.elapsed_seconds,b.MAX_JOB_SECONDS)

    def test_changed_plan_cannot_resume_old_results(self):
        req=self.request();plan=self.plan(req);cp=b.Checkpoint('old')
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,lambda *_:{'extractions':[]},cp)

    def test_foreign_ids_and_nonrequested_items_rejected(self):
        req=self.request();plan=self.plan(req);pid=plan['parts'][0]['part_id']
        for row in [dict(check_item=1,clause_id='foreign',quote='文'),dict(check_item=7,clause_id='c-0',quote='文')]:
            with self.assertRaises(b.BulkError):b.merge_bulk(req,plan,{pid:{'extractions':[row]}})

    def test_plan_and_merge_http_without_gemini(self):
        req=self.request(80);res=self.h.api.post('/ai/extract/plan',json=req.model_dump())
        self.assertEqual(res.status_code,200);plan=res.json()
        merged=self.h.api.post('/ai/extract/merge',json=dict(source_request=req.model_dump(),plan_id=plan['plan_id'],
          part_results=[dict(part_id=p['part_id'],response={'extractions':[]}) for p in plan['parts']]))
        self.assertEqual(merged.status_code,200);self.assertEqual(merged.json(),{'extractions':[]})
        self.h.mock.models.generate_content.assert_not_called();self.h.mock.models.list.assert_not_called()

    def test_merge_http_missing_part_and_plan_mismatch(self):
        req=self.request();plan=self.plan(req)
        for plan_id,expected in [(plan['plan_id'],409),('wrong',422)]:
            res=self.h.api.post('/ai/extract/merge',json=dict(source_request=req.model_dump(),plan_id=plan_id,part_results=[]))
            self.assertEqual(res.status_code,expected);self.assertEqual(set(res.json()),{'code','message'})

    def test_job_and_input_limits_and_b_default_off(self):
        self.assertFalse(self.ns['extract_recheck'].ENABLED)
        req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id'],elapsed_seconds=b.MAX_JOB_SECONDS)
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,lambda *_:self.fail('must not call'),cp)

    def test_changed_outer_request_and_duplicate_part_ids_rejected(self):
        req=self.request(50);plan=self.plan(req)
        changed=req.model_copy(update={'analysis_id':'other'})
        with self.assertRaises(b.BulkError):b.validate_preservation(changed,plan)
        plan['parts'][1]['part_id']=plan['parts'][0]['part_id']
        with self.assertRaises(b.BulkError):b.validate_preservation(req,plan)

    def test_changed_requested_items_in_part_rejected(self):
        req=self.request();plan=self.plan(req);plan['parts'][0]['request']['check_items']=[4]
        with self.assertRaises(b.BulkError):b.validate_preservation(req,plan)

    def test_invalid_response_is_not_checkpointed_or_resumed(self):
        req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id']);calls=[]
        def invalid(body,timeout):calls.append(1);return {'extractions':[{'check_item':1,'clause_id':'wrong','quote':'unknown'}]}
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,invalid,cp)
        with self.assertRaises(b.BulkHttpError):b.run_bulk(req,plan,invalid,cp)
        self.assertEqual(cp.completed,{});self.assertEqual(len(calls),1)

    def test_completed_checkpoint_still_respects_job_deadline(self):
        req=self.request();plan=self.plan(req);cp=b.Checkpoint(plan['plan_id'],completed=self.empty_results(plan),elapsed_seconds=b.MAX_JOB_SECONDS+1)
        with self.assertRaises(b.BulkError):b.run_bulk(req,plan,lambda *_:self.fail('must not call'),cp)

    def test_same_article_boundary_exception_has_documented_context_copy(self):
        req=self.request(26)
        for c in req.clauses:c.article='제1조'
        req.clauses[-1].text='다만 다음 예외 조건이 적용됩니다.'
        plan=self.plan(req)
        self.assertEqual(plan['neighbor_context_copy_count'],1)
        self.assertEqual(len(plan['parts']),2)
        self.assertTrue(b.validate_preservation(req,plan))
        self.assertEqual(plan['parts'][1]['context_reasons'],{'c-24':'same_article_boundary_exception_context'})

    def test_part_count_and_source_size_limits_fail_before_sender(self):
        for name,value in [('MAX_PARTS',1),('MAX_SOURCE_BYTES',100)]:
            with patch.object(b,name,value):
                with self.assertRaises(b.BulkError) as e:self.plan(self.request(60))
            self.assertEqual(e.exception.code,'BULK_INPUT_BUDGET_EXCEEDED')
            self.assertEqual(e.exception.status,413)

    def test_http_diagnostics_do_not_log_original_text(self):
        req=self.request(1,'SECRET private source. 보험약관 시험입니다.')
        with patch.object(b.logger,'info') as info:
            res=self.h.api.post('/ai/extract/plan',json=req.model_dump())
        self.assertEqual(res.status_code,200)
        self.assertIn('original_clauses',str(info.call_args_list))
        self.assertNotIn('SECRET',str(info.call_args_list))
        bad=self.request(1,'SECRET'*20000)
        with patch.object(b.logger,'warning') as warning:
            res=self.h.api.post('/ai/extract/plan',json=bad.model_dump())
        self.assertEqual(res.status_code,413)
        self.assertNotIn('SECRET',str(warning.call_args_list)+res.text)


if __name__=='__main__':unittest.main()
