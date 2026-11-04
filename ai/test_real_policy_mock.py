import json
from pathlib import Path
import unittest
import contextlib
import io
import tempfile
from unittest.mock import patch

import httpx
import test_real_policy as tool


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.data = tool.load_data(tool.ROOT / 'test_data' / 'synthetic_policy.json')

    def test_dataset(self):
        self.assertEqual(set(self.data['requests']['extract']['check_items']), set(range(1, 7)))
        self.assertEqual(len(self.data['requests']['explain']['items']), 6)

    def test_all_apis_mocked(self):
        clause = self.data['requests']['extract']['clauses'][0]
        def handler(request):
            if request.url.path == '/health':
                return httpx.Response(200, json={'model': 'gemini-test-model'})
            if request.url.path == '/ai/classify':
                return httpx.Response(200, json={'doc_type': 'TERMS', 'product_name': None, 'version_label': None})
            if request.url.path == '/ai/extract':
                return httpx.Response(200, json={'extractions': [{'check_item': 1, 'clause_id': clause['clause_id'], 'quote': clause['text']}]})
            return httpx.Response(200, json={'cards': [{'item_id': i['item_id'], 'title': '테스트 제목', 'explanation': '테스트 해설입니다.', 'review': 'pass', 'review_reason': None} for i in self.data['requests']['explain']['items']]})
        with httpx.Client(base_url='http://test', transport=httpx.MockTransport(handler)) as client:
            report = tool.run_tests(client, self.data, list(self.data['requests']))
        self.assertTrue(all(r['valid'] for r in report['results']))
        self.assertEqual(report['real_policy_test'], 'not_performed')
        self.assertEqual(report['model'], 'gemini-test-model')
        saved = json.dumps(report, ensure_ascii=False)
        self.assertNotIn(clause['text'], saved)
        self.assertNotIn(clause['clause_id'], saved)

    def test_quote_and_check_item(self):
        payload = self.data['requests']['extract']
        for row in [{'check_item': 7, 'clause_id': 'x', 'quote': 'x'}, {'check_item': 1, 'clause_id': payload['clauses'][0]['clause_id'], 'quote': 'invented'}, {'check_item': True, 'clause_id': 'x', 'quote': 'x'}]:
            self.assertFalse(tool.validate_response('extract', payload, {'extractions': [row]})['valid'])
        subset = dict(payload, check_items=[2])
        row = {'check_item': 1, 'clause_id': payload['clauses'][0]['clause_id'], 'quote': payload['clauses'][0]['text']}
        self.assertFalse(tool.validate_response('extract', subset, {'extractions': [row]})['valid'])

    def test_review_and_numbers(self):
        payload = self.data['requests']['explain']
        item = payload['items'][0]
        payload = dict(payload, items=[item])
        card = {'item_id': item['item_id'], 'title': '60일', 'explanation': '테스트', 'review': 'fail', 'review_reason': 'SECRET'}
        result = tool.validate_response('explain', payload, {'cards': [card]})
        self.assertFalse(result['valid'])
        self.assertEqual(result['metrics']['meaning_reviews'][0]['numbers']['status'], 'warning')
        self.assertNotIn('SECRET', json.dumps(result))
        card.update(review='pass', review_reason='invalid')
        self.assertFalse(tool.validate_response('explain', payload, {'cards': [card]})['valid'])
        card.update(review_reason=None)
        self.assertTrue(tool.validate_response('explain', payload, {'cards': [card]})['valid'])
        self.assertFalse(tool.validate_response('explain', payload, {'cards': [card, card]})['valid'])
        self.assertFalse(tool.validate_response('explain', payload, {'cards': []})['valid'])

    def test_errors(self):
        for kind in ['http', 'json', 'timeout']:
            def handler(request):
                if kind == 'timeout':
                    raise httpx.ReadTimeout('SECRET')
                return httpx.Response(502 if kind == 'http' else 200, text='SECRET')
            with httpx.Client(base_url='http://test', transport=httpx.MockTransport(handler)) as client:
                report = tool.run_tests(client, self.data, ['extract'])
            self.assertFalse(report['results'][0]['valid'])
            self.assertNotIn('SECRET', json.dumps(report))

    def detail_handler(self, request):
        if request.url.path == '/health':
            return httpx.Response(200, json={'model': 'gemini-test-model'})
        items = self.data['requests']['explain']['items']
        # Reverse response order to check that quote/check_item use IDs, not position.
        return httpx.Response(200, json={'cards': [
            {'item_id': item['item_id'], 'title': 'DETAIL_TITLE', 'explanation': 'DETAIL_EXPLANATION',
             'review': 'fail' if index == 0 else 'pass',
             'review_reason': 'DETAIL_REASON' if index == 0 else None}
            for index, item in enumerate(reversed(items))]})

    def test_details_opt_in_and_no_report_leak(self):
        for enabled in (False, True):
            output = io.StringIO()
            with httpx.Client(base_url='http://test', transport=httpx.MockTransport(self.detail_handler)) as client, contextlib.redirect_stdout(output):
                report = tool.run_tests(client, self.data, ['explain'], show_review_details=enabled)
            text = output.getvalue()
            self.assertEqual('DETAIL_REASON' in text, enabled)
            if enabled:
                detail = json.loads(text[text.index('{'):])
                self.assertEqual(detail['result_index'], 0)
                self.assertEqual(detail['check_item'], 6)
                self.assertEqual(detail['quote'], self.data['requests']['explain']['items'][-1]['quote'])
                self.assertEqual(set(detail), {'result_index', 'check_item', 'title', 'explanation', 'review_reason', 'quote'})
            self.assertNotIn('DETAIL_', json.dumps(report))
            self.assertNotIn(self.data['requests']['explain']['items'][-1]['quote'], json.dumps(report, ensure_ascii=False))

    def test_real_data_details_blocked_before_http(self):
        self.data['source_kind'] = 'real_policy'
        with httpx.Client(base_url='http://test', transport=httpx.MockTransport(lambda request: self.fail('HTTP must not run'))) as client:
            with self.assertRaises(ValueError):
                tool.run_tests(client, self.data, ['explain'], show_review_details=True)
        with patch.object(tool, 'load_data', return_value=self.data), patch('sys.argv', ['test_real_policy.py', '--show-review-details', '--run']), patch.object(tool.httpx, 'Client') as client, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(tool.main(), 2)
            client.assert_not_called()

    def test_details_never_saved_by_cli(self):
        with tempfile.TemporaryDirectory() as folder:
            client = httpx.Client(base_url='http://test', transport=httpx.MockTransport(self.detail_handler))
            output = io.StringIO()
            with patch.object(tool, 'ROOT', Path(folder)), patch.object(tool, 'load_data', return_value=self.data), patch('sys.argv', ['test_real_policy.py', '--api', 'explain', '--run', '--show-review-details']), patch.object(tool.httpx, 'Client', return_value=client), contextlib.redirect_stdout(output):
                self.assertEqual(tool.main(), 1)  # Semantic fail remains a test failure.
            files = list((Path(folder) / 'test_results').glob('*.json'))
            self.assertEqual(len(files), 1)
            saved = files[0].read_text(encoding='utf-8')
            self.assertNotIn('DETAIL_', saved)
            self.assertNotIn(self.data['requests']['explain']['items'][-1]['quote'], saved)
            self.assertIn('DETAIL_REASON', output.getvalue())


if __name__ == '__main__':
    unittest.main()
