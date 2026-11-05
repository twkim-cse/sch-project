"""Synthetic fixtures verify prompt routing/contracts, not Gemini accuracy."""
import json
import unittest
from unittest.mock import patch
import test_explain as fixtures


class DutyInstructionTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)

    def check_case(self, text, expected):
        clause = {'clause_id': 'synthetic-duty', 'page': 1, 'text': text}
        rows = [{'check_item': 4, 'clause_id': clause['clause_id'], 'quote': text}] if expected else []
        self.h.responses({'extractions': rows})
        payload = {'analysis_id': 'duty-test', 'check_items': [4], 'clauses': [clause]}
        with patch.object(self.h.ns['logger'], 'info'):
            response = self.h.api.post('/ai/extract', json=payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'extractions': rows})
        self.assertEqual(self.h.mock.models.generate_content.call_count, 1)
        call = self.h.mock.models.generate_content.call_args.kwargs
        self.assertEqual(json.loads(call['contents'])['clauses'][0]['text'], text)
        self.assertEqual(set(json.loads(call['contents'])['check_items']), {'4'})
        instruction = call['config'].system_instruction
        self.assertEqual(instruction, self.h.ns['extract_instruction_for']([4]))
        self.assertIn('"check_item":4', instruction)
        self.assertNotIn('"check_item":1', instruction)
        self.assertIsNone(call['config'].response_schema)
        return instruction

    def test_precontract_questions(self):
        self.check_case('계약자는 청약 시 질문사항에 사실대로 답해야 합니다.', True)

    def test_postcontract_changes(self):
        self.check_case('계약 후 직업이 변경되면 회사에 알려야 합니다.', True)

    def test_violation_effect(self):
        self.check_case('중요한 고지의무를 위반한 경우 회사는 계약을 해지할 수 있습니다.', True)

    def test_duty_without_adverse_consequence(self):
        instruction = self.check_case('피보험자는 청약서에서 질문한 건강상태를 사실대로 알려야 합니다.', True)
        self.assertIn('반드시 불이익이나 위반 효과가 있어야 추출하는 것은 아닙니다', instruction)

    def test_unrelated_obligation(self):
        self.check_case('계약자는 매월 정해진 날에 보험료를 납입해야 합니다.', False)

    def test_no_relevant_content(self):
        self.check_case('이 계약의 보험증권은 전자문서로 발급합니다.', False)

    def test_required_positive_and_negative_guidance(self):
        instruction = self.h.ns['extract_instruction_for']([4])
        for phrase in ('계약 전 알릴 의무', '질문사항에 사실대로', '건강상태',
                       '변경사항 통지의무', '계약 해지 또는 보장 제한',
                       '일반 계약 의무', '원문에 없는 고지 조건이나 불이익', '빈 배열'):
            self.assertIn(phrase, instruction)

    def test_other_requests_keep_exact_original_instruction_and_example(self):
        base = self.h.ns['EXTRACT_INSTRUCTION']
        for items in ([1], [2], [6], [1, 4], [1, 2, 3, 4, 5, 6]):
            self.assertEqual(self.h.ns['extract_instruction_for'](items), base)
        # [3]/[5] now have their own detail branches; [4] remains intact.
        self.assertEqual(self.h.ns['extract_instruction_for']([4]).count('"check_item":4'), 1)

    def test_budget_uses_actual_instruction(self):
        original = self.h.ns['extract_document'].__globals__['plan_extraction']
        seen = []
        def plan(clauses, items, definitions, instruction):
            seen.append(instruction)
            return original(clauses, items, definitions, instruction)
        with patch.dict(self.h.ns['extract_document'].__globals__, {'plan_extraction': plan}):
            self.check_case('청약할 때 건강상태를 알려야 합니다.', True)
        self.assertEqual(seen, [self.h.ns['extract_instruction_for']([4])])


if __name__ == '__main__':
    unittest.main()
