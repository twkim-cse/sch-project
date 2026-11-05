"""Offline-only candidate ordering, coverage, budgets, and failure semantics."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from google.genai import errors
import extract_planning as planning
import test_explain as fixtures


class ExtractBatchingTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.mock = self.h.mock
        self.ns = self.h.ns

    def clauses(self, count):
        return [{'clause_id': 'fixture-' + str(i), 'rider_id': None, 'section': '일반',
                 'article': '제' + str(i) + '조', 'article_title': '안내',
                 'paragraph': '제1항', 'page': i + 1, 'from_table': False,
                 'text': '가상 비교 문장입니다. 이 자료는 테스트 전용입니다.'} for i in range(count)]

    def payload(self, clauses, items=None):
        return dict(analysis_id='batch-fixture', check_items=items or [1,2,3,4,5,6], clauses=clauses)

    def empty_response(self, **kwargs):
        return SimpleNamespace(text='{"extractions":[]}')

    def request(self, clauses, items=None):
        with patch.object(self.ns['logger'], 'info'), patch.object(self.ns['logger'], 'warning'), patch.object(self.ns['logger'], 'error'):
            return self.h.api.post('/ai/extract', json=self.payload(clauses, items))

    def sent_batches(self):
        return [json.loads(call.kwargs['contents']) for call in self.mock.models.generate_content.call_args_list]

    def check_coverage(self, clauses):
        sent = [c for b in self.sent_batches() for c in b['clauses']]
        self.assertEqual(len(sent), len(clauses))
        self.assertEqual({c['clause_id'] for c in sent}, {c['clause_id'] for c in clauses})
        self.assertEqual(len({c['clause_id'] for c in sent}), len(sent))
        self.assertEqual({c['clause_id']: c for c in sent}, {c['clause_id']: c for c in clauses})
        for call in self.mock.models.generate_content.call_args_list:
            config = call.kwargs['config']
            self.assertEqual(config.response_mime_type, 'application/json')
            self.assertIsNone(config.response_schema)
            size = len(call.kwargs['contents'].encode('utf-8')) + len(config.system_instruction.encode('utf-8')) + planning.WIRE_RESERVE_BYTES
            self.assertLessEqual(size, planning.MAX_INPUT_BYTES)

    def test_6_and_10_clauses_keep_single_call_and_original_order(self):
        for count in (6,10):
            self.mock.models.generate_content.reset_mock()
            self.mock.models.generate_content.side_effect = self.empty_response
            clauses = self.clauses(count)
            clauses[-1]['article_title'] = '해약환급금'
            self.assertEqual(self.request(clauses).status_code, 200)
            self.assertEqual(self.mock.models.generate_content.call_count, 1)
            self.assertEqual(self.sent_batches()[0]['clauses'], clauses)

    def test_100_300_500_clauses_full_coverage(self):
        for count, expected in ((100,4),(300,12),(500,20)):
            with self.subTest(count=count):
                self.mock.models.generate_content.reset_mock()
                self.mock.models.generate_content.side_effect = self.empty_response
                clauses = self.clauses(count)
                self.assertEqual(self.request(clauses).status_code, 200)
                self.assertEqual(self.mock.models.generate_content.call_count, expected)
                self.check_coverage(clauses)

    def test_title_only_keyword_prioritized_and_no_keyword_tail_retained(self):
        clauses = self.clauses(100)
        clauses[-1]['article_title'] = '해약환급금'
        self.mock.models.generate_content.side_effect = self.empty_response
        self.assertEqual(self.request(clauses,[6]).status_code, 200)
        self.assertEqual(self.sent_batches()[0]['clauses'][0]['clause_id'], clauses[-1]['clause_id'])
        self.check_coverage(clauses)

    def test_body_only_and_section_keywords(self):
        clauses = self.clauses(60)
        clauses[-1]['text'] = '갱신 시 보험료 인상 가능성이 있습니다.'
        clauses[-2]['section'] = '갱신보험료'
        self.mock.models.generate_content.side_effect = self.empty_response
        self.assertEqual(self.request(clauses,[3]).status_code, 200)
        first_ids = [c['clause_id'] for c in self.sent_batches()[0]['clauses']]
        self.assertIn(clauses[-1]['clause_id'],first_ids)
        self.assertIn(clauses[-2]['clause_id'],first_ids)
        self.check_coverage(clauses)

    def test_title_weight_exceeds_body_and_scoring_does_not_mutate_text(self):
        base = self.ns['Clause'].model_validate(self.clauses(1)[0])
        title = base.model_copy(update={'article_title':'해약환급금'})
        body = base.model_copy(update={'text':'해 약 환 급 금'})
        self.assertGreater(planning.score_clause(title,[6])[6],planning.score_clause(body,[6])[6])
        self.assertEqual(body.text,'해 약 환 급 금')

    def test_same_article_exception_paragraphs_kept_together(self):
        clauses = self.clauses(50)
        for i in (47,48,49):
            clauses[i].update(article='제100조', paragraph='제'+str(i-46)+'항')
        clauses[47]['article_title']='면책 사유'
        clauses[48]['text']='다만, 위 제한을 적용하지 않는 예외가 있습니다.'
        self.mock.models.generate_content.side_effect=self.empty_response
        self.assertEqual(self.request(clauses,[1]).status_code,200)
        self.assertEqual(self.sent_batches()[0]['clauses'][:3],clauses[47:])
        self.check_coverage(clauses)

    def test_oversized_article_splits_between_whole_clauses(self):
        clauses=self.clauses(60)
        for clause in clauses:
            clause['article']='제1조'
        self.mock.models.generate_content.side_effect=self.empty_response
        self.assertEqual(self.request(clauses).status_code,200)
        self.assertEqual([c['clause_id'] for b in self.sent_batches() for c in b['clauses']], [c['clause_id'] for c in clauses])
        self.check_coverage(clauses)

    def test_same_article_in_different_rider_not_grouped(self):
        clauses=self.clauses(30)
        clauses[0].update(article='제1조',rider_id='one')
        clauses[-1].update(article='제1조',rider_id='two',article_title='해약환급금')
        self.mock.models.generate_content.side_effect=self.empty_response
        self.assertEqual(self.request(clauses,[6]).status_code,200)
        self.assertEqual(self.sent_batches()[0]['clauses'][0]['rider_id'],'two')

    def test_duplicate_extractions_merged_and_whitespace_validation_preserved(self):
        clauses=self.clauses(30)
        clauses[0]['text']='환급금은\n3영업일 이내 지급합니다.'
        row={'check_item':6,'clause_id':clauses[0]['clause_id'],'quote':'환급금은 3영업일 이내 지급합니다.'}
        self.mock.models.generate_content.side_effect=lambda **kw: SimpleNamespace(text=json.dumps({'extractions':[row,dict(row,quote='환급금은 5영업일 이내 지급합니다.')]}))
        response=self.request(clauses,[6])
        self.assertEqual(response.status_code,200)
        self.assertEqual(response.json(),{'extractions':[row]})

    def test_later_batch_api_error_does_not_return_partial_success(self):
        self.mock.models.generate_content.side_effect=[self.empty_response(), errors.ClientError(400,{'error':{'message':'PRIVATE'}})]
        response=self.request(self.clauses(50))
        self.assertEqual(response.status_code,502)
        self.assertEqual(set(response.json()),{'code','message'})
        self.assertNotIn('PRIVATE',response.text)
        self.assertEqual(self.mock.models.generate_content.call_count,2)

    def test_later_invalid_json_or_structure_is_whole_request_failure(self):
        for text in ('PRIVATE_INVALID_JSON','[]','{"extractions":{}}'):
            self.mock.models.generate_content.reset_mock()
            self.mock.models.generate_content.side_effect=[self.empty_response(),SimpleNamespace(text=text)]
            self.assertEqual(self.request(self.clauses(50)).status_code,502)
            self.assertEqual(self.mock.models.generate_content.call_count,2)

    def test_single_clause_over_budget_rejected_before_any_api_call(self):
        clauses=self.clauses(1)
        clauses[0]['text']='가'*planning.MAX_INPUT_BYTES
        response=self.request(clauses)
        self.assertEqual(response.status_code,413)
        self.assertEqual(response.json()['code'],'EXTRACT_INPUT_BUDGET_EXCEEDED')
        self.mock.models.generate_content.assert_not_called()
        self.mock.models.list.assert_not_called()

    def test_length_and_instruction_metadata_count_towards_budget(self):
        clauses=self.clauses(6)
        for clause in clauses:
            clause['section']='메타데이터'*150
            clause['text']='가'*2500
        self.mock.models.generate_content.side_effect=self.empty_response
        self.assertEqual(self.request(clauses).status_code,200)
        self.assertGreater(self.mock.models.generate_content.call_count,1)
        self.check_coverage(clauses)

    def test_empty_list_makes_no_calls(self):
        self.assertEqual(self.request([]).json(),{'extractions':[]})
        self.mock.models.generate_content.assert_not_called()

    def test_only_requested_check_item_is_sent(self):
        self.mock.models.generate_content.side_effect=self.empty_response
        self.assertEqual(self.request(self.clauses(30),[4]).status_code,200)
        for batch in self.sent_batches():
            self.assertEqual(set(batch['check_items']),{'4'})

    def test_batch_count_limit_rejected_before_calls(self):
        with patch.object(planning,'MAX_BATCHES',1):
            self.assertEqual(self.request(self.clauses(50)).status_code,413)
        self.mock.models.generate_content.assert_not_called()

    def test_transient_retry_is_bounded_and_same_batch_only(self):
        self.mock.models.generate_content.side_effect=[errors.ServerError(503,{'error':{'message':'PRIVATE'}}),self.empty_response(),self.empty_response()]
        self.assertEqual(self.request(self.clauses(30)).status_code,200)
        calls=self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls),3)
        self.assertEqual(calls[0].kwargs,calls[1].kwargs)
        self.h.sleep.assert_called_once_with(2)

    def test_persistent_429_no_unbounded_retry(self):
        self.mock.models.generate_content.side_effect=errors.ClientError(429,{'error':{'message':'PRIVATE'}})
        self.assertEqual(self.request(self.clauses(50)).status_code,502)
        self.assertEqual(self.mock.models.generate_content.call_count,2)
        self.h.sleep.assert_called_once_with(60)

    def test_safe_planning_and_validation_metrics(self):
        clauses=self.clauses(30)
        clauses[-1]['article_title']='해약환급금 PRIVATE_TITLE'
        clauses[-1]['text']='PRIVATE_ORIGINAL'
        self.mock.models.generate_content.side_effect=self.empty_response
        with patch.object(self.ns['logger'],'info') as log:
            self.assertEqual(self.h.api.post('/ai/extract',json=self.payload(clauses)).status_code,200)
        text='\n'.join(c.args[0] % c.args[1:] for c in log.call_args_list)
        self.assertNotIn('PRIVATE',text)
        for marker in ('input_clauses=30','batches=2','score_distribution=', 'clause_count=', 'input_budget_bytes=', 'analyzed_clauses=30', 'excluded_reasons'):
            self.assertIn(marker,text)

    def test_first_batch_persistent_503_aborts_all_following(self):
        self.mock.models.generate_content.side_effect=errors.ServerError(503,{'error':{'message':'PRIVATE'}})
        self.assertEqual(self.request(self.clauses(75)).status_code,502)
        self.assertEqual(self.mock.models.generate_content.call_count,2)
        self.assertEqual(self.sent_batches()[0],self.sent_batches()[1])
        self.h.sleep.assert_called_once_with(2)

    def test_middle_batch_persistent_503_never_calls_last_batch(self):
        error=errors.ServerError(503,{'error':{'message':'PRIVATE'}})
        self.mock.models.generate_content.side_effect=[self.empty_response(),error,error]
        self.assertEqual(self.request(self.clauses(75)).status_code,502)
        self.assertEqual(self.mock.models.generate_content.call_count,3)
        batches=self.sent_batches()
        self.assertEqual(batches[1],batches[2])
        self.assertNotEqual(batches[0],batches[1])

    def test_boundary_exception_copy_has_reason_and_final_deduplication(self):
        clauses=self.clauses(30)
        for clause in clauses:
            clause['article']='제1조'
        clauses[25]['text']='다만, 앞 항의 예외는 별도로 적용합니다.'
        request=self.ns['ExtractRequest'].model_validate(self.payload(clauses,[1]))
        definitions={'1':self.ns['CHECK_ITEM_DEFINITIONS'][1]}
        batches,_=planning.plan_extraction(request.clauses,[1],definitions,self.ns['EXTRACT_INSTRUCTION'])
        planning.validate_batch_coverage(request.clauses,batches)
        self.assertEqual(len(batches),2)
        self.assertEqual(batches[1].context_reasons,{'fixture-24':'same_article_boundary_exception_context'})
        self.assertEqual(sum(len(b.clauses) for b in batches),31)
        row={'check_item':1,'clause_id':'fixture-24','quote':clauses[24]['text']}
        self.mock.models.generate_content.side_effect=lambda **kw: SimpleNamespace(text=json.dumps({'extractions':[row]}))
        self.assertEqual(self.request(clauses,[1]).json(),{'extractions':[row]})

    def test_unexplained_duplicate_or_missing_clause_is_rejected(self):
        request=self.ns['ExtractRequest'].model_validate(self.payload(self.clauses(2)))
        one=planning.make_batch(request.clauses,{},'')
        duplicate=planning.make_batch([request.clauses[0]],{},'')
        with self.assertRaises(RuntimeError):
            planning.validate_batch_coverage(request.clauses,[one,duplicate])
        with self.assertRaises(RuntimeError):
            planning.validate_batch_coverage(request.clauses,[duplicate])

    def test_context_copy_not_added_when_count_budget_full(self):
        clauses=self.clauses(50)
        for clause in clauses:
            clause['article']='제1조'
        clauses[25]['text']='다만, 예외입니다.'
        request=self.ns['ExtractRequest'].model_validate(self.payload(clauses))
        batches,_=planning.plan_extraction(request.clauses,[1],{'1':'면책'},self.ns['EXTRACT_INSTRUCTION'])
        self.assertTrue(batches[1].context_omitted_budget)
        self.assertFalse(batches[1].context_reasons)
        planning.validate_batch_coverage(request.clauses,batches)


if __name__=='__main__':
    unittest.main()
