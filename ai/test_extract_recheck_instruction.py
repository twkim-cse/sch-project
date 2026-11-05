"""Offline routing and budgeting for single-item recovery instructions."""
import json
import unittest
from unittest.mock import patch
from google.genai import errors
import extract_recheck as rc
import test_explain as fixtures
from extract_planning import WIRE_RESERVE_BYTES


class RecheckInstructionTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.payload = {'analysis_id': 'routing', 'check_items': [1, 2, 3, 4, 5, 6],
                        'clauses': [{'clause_id': str(i), 'page': 1, 'article': str(i),
                                     'text': '갱신 보험료가 변경됩니다. 계약 전 알릴 의무가 있습니다. 수술이란 절제를 말합니다.'}
                                    for i in range(40)]}

    def post(self, enabled=True):
        with patch.object(rc, 'ENABLED', enabled), patch.object(self.h.ns['logger'], 'info'), patch.object(self.h.ns['logger'], 'error'), patch.object(self.h.ns['logger'], 'warning'):
            return self.h.api.post('/ai/extract', json=self.payload)

    def test_mixed_base_common_and_rechecks_3_4_5_routed(self):
        self.h.mock.models.generate_content.return_value.text = '{"extractions":[]}'
        self.assertEqual(self.post().status_code, 200)
        calls = self.h.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 5)
        base = self.h.ns['EXTRACT_INSTRUCTION']
        for call in calls[:2]:
            self.assertEqual(call.kwargs['config'].system_instruction, base)
        for item, call in zip((3, 4, 5), calls[2:]):
            contents = call.kwargs['contents']
            instruction = call.kwargs['config'].system_instruction
            self.assertEqual(set(json.loads(contents)['check_items']), {str(item)})
            self.assertEqual(instruction, self.h.ns['extract_instruction_for']([item]))
            self.assertNotEqual(instruction, base)
            self.assertIn(f'"check_item":{item}', instruction)
            self.assertLessEqual(len(contents.encode()) + len(instruction.encode()) + WIRE_RESERVE_BYTES, rc.MAX_BYTES)

    def test_off_keeps_two_base_calls(self):
        self.h.mock.models.generate_content.return_value.text = '{"extractions":[]}'
        self.assertEqual(self.post(False).status_code, 200)
        self.assertEqual(self.h.mock.models.generate_content.call_count, 2)
        for call in self.h.mock.models.generate_content.call_args_list:
            self.assertEqual(call.kwargs['config'].system_instruction, self.h.ns['EXTRACT_INSTRUCTION'])

    def test_recovery_duty_filters_wrong_quote_and_duplicate(self):
        self.payload['check_items'] = [1, 4]
        self.payload['clauses'] = self.payload['clauses'][:1]
        row = {'check_item': 4, 'clause_id': '0', 'quote': self.payload['clauses'][0]['text']}
        self.h.responses({'extractions': []}, {'extractions': [row, row, dict(row, quote='원문에 없는 내용')]})
        self.assertEqual(self.post().json(), {'extractions': [row]})
        self.assertEqual(self.h.mock.models.generate_content.call_count, 2)

    def test_recovery_error_still_fails_entire_request(self):
        self.payload['check_items'] = [1, 4]
        self.payload['clauses'] = self.payload['clauses'][:1]
        from types import SimpleNamespace
        self.h.mock.models.generate_content.side_effect = [SimpleNamespace(text='{"extractions":[]}'), errors.ClientError(400, {'error': {}})]
        self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.h.mock.models.generate_content.call_count, 2)

    def test_planner_uses_selected_instruction_length_at_budget_boundary(self):
        req = self.h.ns['ExtractRequest'].model_validate(self.payload)
        definitions = {str(i): self.h.ns['CHECK_ITEM_DEFINITIONS'][i] for i in req.check_items}
        result = self.h.ns['ExtractResponse'](extractions=[])
        def long_duty(items):
            return 'x' * 20000 if items == [4] else self.h.ns['EXTRACT_INSTRUCTION']
        plans = rc.plan_rechecks(req, result, definitions, self.h.ns['EXTRACT_INSTRUCTION'], instruction_for=long_duty)
        self.assertEqual([item for item, _ in plans], [3, 5])


if __name__ == '__main__':
    unittest.main()
