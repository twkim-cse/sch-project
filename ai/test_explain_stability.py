"""Offline-only explain deadlines, shared retry budget, and safe diagnostics."""
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import httpx
from google.genai import errors
import test_explain as fixtures


class ExplainStabilityTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.g = self.h.ns['explain_document'].__globals__
        self.mock = self.h.mock.models.generate_content

    def error(self, code):
        return (errors.ServerError if code >= 500 else errors.ClientError)(code, {'error': {'message': 'SECRET', 'status': 'DEADLINE_EXCEEDED' if code == 504 else 'UNAVAILABLE'}})

    def response(self, obj):
        return SimpleNamespace(text=json.dumps(obj))

    def post(self):
        with patch.object(self.h.ns['logger'], 'info') as info, patch.object(self.h.ns['logger'], 'warning') as warning, patch.object(self.h.ns['logger'], 'error') as error:
            result = self.h.post()
        self.logs = '\n'.join(c.args[0] % c.args[1:] for log in (info, warning, error) for c in log.call_args_list)
        self.assertNotIn('SECRET', self.logs + result.text)
        return result

    def test_stage_timeouts_and_sdk_single_attempt(self):
        self.h.responses(self.h.draft(), self.h.review())
        self.assertEqual(self.post().status_code, 200)
        calls = self.mock.call_args_list
        self.assertEqual([c.kwargs['config'].http_options.timeout for c in calls], [60000, 45000])
        self.assertTrue(all(c.kwargs['config'].http_options.retry_options.attempts == 1 for c in calls))
        lookup = self.h.mock.models.list.call_args.kwargs['config'].http_options
        self.assertEqual(lookup.timeout, 15000)
        self.assertEqual(lookup.retry_options.attempts, 1)

    def test_503_two_backoffs_then_success(self):
        self.mock.side_effect = [self.error(503), self.error(503), self.response(self.h.draft()), self.response(self.h.review())]
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 4)
        self.assertEqual([c.args[0] for c in self.h.sleep.call_args_list], [2, 4])

    def test_504_two_backoffs_then_final_timeout(self):
        self.mock.side_effect = self.error(504)
        res = self.post()
        self.assertEqual(res.status_code, 502)
        self.assertEqual(res.json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 3)
        self.assertIn('timeout_origin=server', self.logs)

    def test_review_504_retries_without_regenerating(self):
        self.mock.side_effect = [self.response(self.h.draft()), self.error(504), self.error(504), self.response(self.h.review())]
        self.assertEqual(self.post().status_code, 200)
        calls = self.mock.call_args_list
        self.assertEqual(sum('candidate' not in json.loads(c.kwargs['contents']) for c in calls), 1)
        self.assertEqual(calls[1].kwargs['contents'], calls[2].kwargs['contents'])
        self.assertEqual(calls[2].kwargs['contents'], calls[3].kwargs['contents'])

    def test_client_timeout_is_not_server_504_and_not_retried(self):
        self.mock.side_effect = httpx.ReadTimeout('SECRET')
        self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 1)
        self.assertIn('timeout_origin=client', self.logs)
        self.assertNotIn('timeout_origin=server', self.logs)

    def test_deadline_before_lookup_blocks_calls(self):
        with patch.dict(self.g, {'EXPLAIN_TOTAL_SECONDS': 0}):
            self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.h.mock.models.list.assert_not_called()
        self.mock.assert_not_called()

    def test_deadline_in_limiter_blocks_review(self):
        self.h.ns['explain_call_limiter'].limit = 1
        self.h.responses(self.h.draft())
        with patch.dict(self.g, {'EXPLAIN_TOTAL_SECONDS': 50}):
            self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 1)
        self.h.sleep.assert_not_called()

    def test_slow_lookup_exhausts_budget(self):
        def listed(**kwargs):
            self.h.clock += 241
            return [SimpleNamespace(name='models/test-model', supported_actions=['generateContent'])]
        self.h.mock.models.list.side_effect = listed
        self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.mock.assert_not_called()

    def test_remaining_time_clips_each_rpc(self):
        def listed(**kwargs):
            self.h.clock = 210
            return [SimpleNamespace(name='models/test-model', supported_actions=['generateContent'])]
        self.h.mock.models.list.side_effect = listed
        def generated(**kwargs):
            if self.mock.call_count == 1:
                self.h.clock = 220
                return self.response(self.h.draft())
            return self.response(self.h.review())
        self.mock.side_effect = generated
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual([c.kwargs['config'].http_options.timeout for c in self.mock.call_args_list], [30000, 20000])

    def test_late_success_is_not_returned_as_verified(self):
        def generated(**kwargs):
            self.h.clock += 241
            return self.response(self.h.draft())
        self.mock.side_effect = generated
        self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 1)
        self.assertIn('timeout_origin=request_budget', self.logs)

    def test_backoff_never_exceeds_remaining_budget(self):
        self.mock.side_effect = self.error(503)
        with patch.dict(self.g, {'EXPLAIN_TOTAL_SECONDS': 5}):
            self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 2)
        self.h.sleep.assert_called_once_with(2)

    def test_generation_and_review_share_retry_budget(self):
        self.mock.side_effect = [self.error(503), self.response(self.h.draft()), self.error(504), self.response(self.h.review())]
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual([c.args[0] for c in self.h.sleep.call_args_list], [2, 4])

    def test_exhausted_generation_retry_budget_stops_review_retry(self):
        self.mock.side_effect = [self.error(503), self.error(503), self.response(self.h.draft()), self.error(503)]
        res = self.post()
        self.assertEqual(res.status_code, 502)
        self.assertEqual(set(res.json()), {'code', 'message'})
        self.assertEqual(self.mock.call_count, 4)
        self.assertIn('reason=request_retry_budget', self.logs)

    def test_request_call_cap_discards_partial_cards(self):
        self.h.body['items'].append(dict(self.h.body['items'][0], item_id='item-two'))
        self.h.responses(self.h.draft(), self.h.review())
        with patch.dict(self.g, {'EXPLAIN_MAX_ATTEMPTS': 2}):
            res = self.post()
        self.assertEqual(res.status_code, 502)
        self.assertNotIn('cards', res.json())
        self.assertEqual(self.mock.call_count, 2)

    def test_format_recovery_uses_same_request_call_budget(self):
        self.h.responses([], self.h.draft())
        with patch.dict(self.g, {'EXPLAIN_MAX_ATTEMPTS': 2}):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.call_count, 2)

    def test_semantic_fail_regeneration_and_final_fail_preserved(self):
        self.h.responses(self.h.draft(), self.h.review('fail'), self.h.draft(), self.h.review('fail'))
        res = self.post()
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()['cards'][0]['review'], 'fail')
        self.assertEqual(self.mock.call_count, 4)

    def test_budgets_are_isolated_between_requests(self):
        self.mock.side_effect = self.error(503)
        self.assertEqual(self.post().status_code, 502)
        self.mock.reset_mock()
        self.h.responses(self.h.draft(), self.h.review())
        self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.call_count, 2)
        self.assertIsNone(self.h.ns['_explain_budget'].get())

    def test_long_retry_after_is_not_slept_past_deadline(self):
        exc = errors.ClientError(429, {'error': {}}, httpx.Response(429, headers={'Retry-After': '300'}))
        self.mock.side_effect = exc
        self.assertEqual(self.post().json()['code'], 'GEMINI_TIMEOUT')
        self.assertEqual(self.mock.call_count, 1)
        self.h.sleep.assert_not_called()

    def test_permanent_errors_and_connect_error_not_retried(self):
        for exc in [self.error(400), self.error(401), self.error(403), httpx.ConnectError('SECRET')]:
            self.mock.reset_mock()
            self.mock.side_effect = exc
            self.assertEqual(self.post().status_code, 502)
            self.assertEqual(self.mock.call_count, 1)

    def test_attempt_duration_excludes_backoff_and_safe_stage_logs(self):
        def generated(**kwargs):
            if self.mock.call_count == 1:
                self.h.clock += 3
                raise self.error(503)
            return self.response(self.h.draft() if self.mock.call_count == 2 else self.h.review())
        self.mock.side_effect = generated
        self.assertEqual(self.post().status_code, 200)
        self.assertIn('elapsed_ms=3000', self.logs)
        self.assertIn('stage=meaning_review', self.logs)
        self.assertIn('item_index=1', self.logs)

    def test_extract_and_classify_keep_existing_http_options(self):
        self.h.responses({'doc_type': 'TERMS', 'product_name': None, 'version_label': None}, {'extractions': []})
        self.assertEqual(self.h.api.post('/ai/classify', json={'document_id': 'd', 'pages': []}).status_code, 200)
        self.assertEqual(self.h.api.post('/ai/extract', json={'analysis_id': 'a', 'check_items': [1], 'clauses': [{'clause_id': 'c', 'page': 1, 'text': 'synthetic'}]}).status_code, 200)
        self.assertTrue(all(c.kwargs['config'].http_options is None for c in self.mock.call_args_list))
        self.assertEqual(self.h.mock.models.list.call_args.kwargs, {})

    def test_real_sdk_forwards_per_call_http_timeout_without_network(self):
        from google import genai
        from google.genai import types
        observed = []
        def transport(request):
            observed.append(dict(request.extensions['timeout']))
            return httpx.Response(200, json={'candidates': [{'content': {'role': 'model', 'parts': [{'text': '{}'}]}, 'finishReason': 'STOP'}]})
        client = genai.Client(api_key='offline-test-placeholder', vertexai=False, http_options=types.HttpOptions(
            timeout=120000, client_args={'transport': httpx.MockTransport(transport), 'trust_env': False}))
        try:
            for milliseconds in (60000, 45000):
                client.models.generate_content(model='offline-model', contents='synthetic', config=types.GenerateContentConfig(
                    response_mime_type='application/json', http_options=types.HttpOptions(timeout=milliseconds, retry_options=types.HttpRetryOptions(attempts=1))))
        finally:
            client.close()
        self.assertEqual([t['read'] for t in observed], [60.0, 45.0])

    def test_real_sdk_single_attempt_does_not_hide_503_retries(self):
        from google import genai
        from google.genai import types
        observed = []
        def transport(request):
            observed.append(True)
            return httpx.Response(503, json={'error': {'code': 503, 'status': 'UNAVAILABLE', 'message': 'synthetic'}})
        client = genai.Client(api_key='offline-test-placeholder', vertexai=False, http_options=types.HttpOptions(
            client_args={'transport': httpx.MockTransport(transport), 'trust_env': False}))
        try:
            with self.assertRaises(errors.ServerError):
                client.models.generate_content(model='offline-model', contents='synthetic', config=types.GenerateContentConfig(
                    http_options=types.HttpOptions(timeout=60000, retry_options=types.HttpRetryOptions(attempts=1))))
        finally:
            client.close()
        self.assertEqual(len(observed), 1)


if __name__ == '__main__': unittest.main()
