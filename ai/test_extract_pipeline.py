"""Offline full-path tests; all clauses and Gemini responses are synthetic."""
import json
import unittest
from unittest.mock import patch
from google.genai import errors
import extract_recheck as rc
import test_explain as fixtures


class ExtractPipelineTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        texts = ['고의 사고에는 보험금을 지급하지 않습니다.',
                 '계약 후 90일 동안 보장을 제한합니다.',
                 '갱신 시 보험료가 인상되거나 인하될 수 있습니다.',
                 '청약 시 질문한 건강상태를 사실대로 알릴 의무가 있습니다.',
                 '수술이란 치료를 목적으로 절단 또는 절제하는 것을 말합니다.',
                 '해약환급금은 청구 후 3영업일 이내 지급합니다.',
                 '계약자는 해약환급금 범위 내에서 보험계약대출을 받을 수 있습니다.']
        self.clauses = [{'clause_id': 'synthetic-' + str(i), 'page': 1, 'article': str(i),
                         'text': texts[i] if i < len(texts) else '고객센터 운영시간 안내입니다.'}
                        for i in range(40)]
        self.payload = {'analysis_id': 'pipeline', 'check_items': [1, 2, 3, 4, 5, 6], 'clauses': self.clauses}
        self.primary = [self.row(1, 0), self.row(2, 1), self.row(3, 2), self.row(5, 4),
                        self.row(6, 5), self.row(6, 6), self.row(6, 5)]
        self.extra = [self.row(4, 3), self.row(4, 3), dict(self.row(4, 3), quote='원문에 없는 숫자 999일')]

    def row(self, item, index):
        c = self.clauses[index]
        return {'check_item': item, 'clause_id': c['clause_id'], 'quote': c['text']}

    def post(self, enabled):
        with patch.object(rc, 'ENABLED', enabled), patch.object(self.h.ns['logger'], 'info') as log, patch.object(self.h.ns['logger'], 'error'):
            response = self.h.api.post('/ai/extract', json=self.payload)
        counts = [json.loads(c.args[1]) for c in log.call_args_list if c.args[0] == 'Gemini extract validation counts=%s']
        return response, counts

    def test_complete_order_and_merge(self):
        self.h.responses({'extractions': self.primary}, {'extractions': []}, {'extractions': self.extra})
        response, counts = self.post(True)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()['extractions']), 6)
        self.assertEqual({r['check_item'] for r in response.json()['extractions']}, set(range(1, 7)))
        self.assertEqual(counts[0]['excluded_reasons'], {'refund_loan_only': 1, 'duplicate_extraction': 1})
        self.assertEqual(counts[1]['excluded_reasons'], {'duplicate_extraction': 1, 'quote_mismatch': 1})
        self.assertEqual(counts[2]['accepted_count'], 6)
        calls = self.h.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 3)
        for call in calls[:2]:
            self.assertEqual(call.kwargs['config'].system_instruction, self.h.ns['EXTRACT_INSTRUCTION'])
        self.assertEqual(set(json.loads(calls[2].kwargs['contents'])['check_items']), {'4'})
        self.assertEqual(calls[2].kwargs['config'].system_instruction, self.h.ns['extract_instruction_for']([4]))

    def test_off_preserves_two_calls_with_postfilter(self):
        self.h.responses({'extractions': self.primary}, {'extractions': []})
        response, counts = self.post(False)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()['extractions']), 5)
        self.assertEqual(self.h.mock.models.generate_content.call_count, 2)
        self.assertEqual(counts[0]['excluded_reasons']['refund_loan_only'], 1)

    def test_recovery_error_never_returns_primary_success(self):
        from types import SimpleNamespace
        self.h.mock.models.generate_content.side_effect = [
            SimpleNamespace(text=json.dumps({'extractions': self.primary})),
            SimpleNamespace(text='{"extractions":[]}'), errors.ClientError(400, {'error': {}})]
        response, _ = self.post(True)
        self.assertEqual(response.status_code, 502)
        self.assertEqual(set(response.json()), {'code', 'message'})
        self.assertEqual(self.h.mock.models.generate_content.call_count, 3)


if __name__ == '__main__': unittest.main()
