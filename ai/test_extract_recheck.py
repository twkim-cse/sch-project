"""Offline recovery tests; no credentials, network, or real sleeps."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from google.genai import errors
import extract_recheck as recovery
import test_explain as fixtures


class RecheckTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        enabled = patch.object(recovery, 'ENABLED', True)
        enabled.start()
        self.addCleanup(enabled.stop)
        self.mock = self.h.mock.models.generate_content
        self.body = {'analysis_id': 'offline', 'check_items': [3], 'clauses': [
            {'clause_id': 'c1', 'article': '제1조', 'page': 1, 'text': '갱신 시 보험료가 변경됩니다.'}]}
        self.row = {'check_item': 3, 'clause_id': 'c1', 'quote': self.body['clauses'][0]['text']}

    def response(self, rows=()):
        return SimpleNamespace(text=json.dumps({'extractions': list(rows)}))

    def post(self):
        with patch.object(self.h.ns['logger'], 'info'), patch.object(self.h.ns['logger'], 'error'), patch.object(self.h.ns['logger'], 'warning'):
            return self.h.api.post('/ai/extract', json=self.body)

    def test_default_off(self):
        with patch.object(recovery, 'ENABLED', False):
            self.mock.return_value = self.response()
            self.assertEqual(self.post().status_code, 200)
            self.assertEqual(self.mock.call_count, 1)

    def test_missing_recovered_one_item_only(self):
        self.h.responses({'extractions': []}, {'extractions': [self.row]})
        self.assertEqual(self.post().json(), {'extractions': [self.row]})
        call = self.mock.call_args
        self.assertEqual(set(json.loads(call.kwargs['contents'])['check_items']), {'3'})
        config = call.kwargs['config']
        self.assertIsNone(config.response_schema)
        self.assertEqual(config.http_options.retry_options.attempts, 1)
        self.assertLessEqual(config.http_options.timeout, 120000)

    def test_found_item_not_rechecked(self):
        self.mock.return_value = self.response([self.row])
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 1)

    def test_keyword_score_without_concrete_cue_skipped(self):
        self.body['clauses'][0]['text'] = '갱신 안내입니다.'
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 1)

    def test_empty_result_allowed(self):
        self.mock.return_value = self.response()
        self.assertEqual(self.post().json(), {'extractions': []})
        self.assertEqual(self.mock.call_count, 2)

    def test_invalid_quotes_and_duplicates_filtered(self):
        self.h.responses({'extractions': []}, {'extractions': [self.row, self.row, dict(self.row, quote='다른 내용'), dict(self.row, check_item=4)]})
        self.assertEqual(self.post().json(), {'extractions': [self.row]})

    def test_final_failure_discards_first_pass_success(self):
        self.body['check_items'] = [1, 3]
        error = errors.ServerError(503, {'error': {'message': 'PRIVATE'}})
        self.mock.side_effect = [self.response([dict(self.row, check_item=1)]), error, error]
        self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.call_count, 3)
        self.h.sleep.assert_called_once_with(2)

    def test_503_retries_only_recheck(self):
        self.mock.side_effect = [self.response(), errors.ServerError(503, {'error': {}}), self.response([self.row])]
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 3)

    def test_429_retry_and_shared_limiter(self):
        self.mock.side_effect = [self.response(), errors.ClientError(429, {'error': {}}), self.response()]
        self.assertEqual(self.post().status_code, 200)
        self.h.sleep.assert_called_once_with(60)

    def test_deadline_blocks_retry_wait(self):
        self.mock.side_effect = [self.response(), errors.ClientError(429, {'error': {}})]
        with patch.object(recovery, 'MAX_SECONDS', 10):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.call_count, 2)
        self.h.sleep.assert_not_called()

    def test_attempt_cap(self):
        self.mock.side_effect = [self.response(), errors.ServerError(503, {'error': {}})]
        with patch.object(recovery, 'MAX_ATTEMPTS', 1):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.call_count, 2)

    def test_time_spent_in_generation_rejected(self):
        def generate(**kw):
            if self.mock.call_count > 1:
                self.h.clock += 121
            return self.response()
        self.mock.side_effect = generate
        self.assertEqual(self.post().status_code, 502)

    def test_three_item_cap_and_single_item_payloads(self):
        self.body['check_items'] = [1, 2, 3, 4, 5, 6]
        self.body['clauses'][0]['text'] = '보험금을 지급하지 않습니다. 면책기간은 90일입니다. 갱신 보험료가 변경됩니다. 알릴 의무가 있습니다. 수술이란 절제를 말합니다. 해약환급금을 지급합니다.'
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 4)
        for call in self.mock.call_args_list[1:]:
            self.assertEqual(len(json.loads(call.kwargs['contents'])['check_items']), 1)

    def test_clause_count_and_byte_budget(self):
        self.body['clauses'] = [dict(self.body['clauses'][0], clause_id=str(i), article='제%d조' % i) for i in range(20)]
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        call = self.mock.call_args
        self.assertEqual(len(json.loads(call.kwargs['contents'])['clauses']), 12)
        from extract_planning import WIRE_RESERVE_BYTES
        self.assertLessEqual(len(call.kwargs['contents'].encode()) + len(call.kwargs['config'].system_instruction.encode()) + WIRE_RESERVE_BYTES, 16000)

    def test_whole_article_over_budget_not_clipped(self):
        self.body['clauses'] = [dict(self.body['clauses'][0], clause_id=str(i)) for i in range(13)]
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 1)

    def test_bad_json_fails_entire_request(self):
        self.mock.side_effect = [self.response(), SimpleNamespace(text='[PRIVATE')]
        response = self.post()
        self.assertEqual(response.status_code, 502)
        self.assertNotIn('PRIVATE', response.text)

    def test_byte_budget_skips_oversized_group(self):
        self.body['clauses'][0]['text'] += '가' * 4000
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 1)

    def test_same_article_exception_preserved(self):
        self.body['clauses'].append({'clause_id': 'exception', 'article': '제1조', 'page': 1,
                                     'text': '다만, 별도로 정한 경우에는 적용하지 않습니다.'})
        self.mock.return_value = self.response()
        self.assertEqual(self.post().status_code, 200)
        sent = json.loads(self.mock.call_args.kwargs['contents'])['clauses']
        self.assertEqual([c['clause_id'] for c in sent], ['c1', 'exception'])

    def test_deadline_in_shared_limiter_prevents_extra_call(self):
        limiter = self.h.ns['explain_call_limiter']
        limiter.limit = 1
        self.mock.return_value = self.response()
        with patch.object(recovery, 'MAX_SECONDS', 10):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.call_count, 1)
        self.h.sleep.assert_not_called()


if __name__ == '__main__':
    unittest.main()
