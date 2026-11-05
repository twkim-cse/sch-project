"""Prompt/contract regression fixtures, not a simulation of model reasoning."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import extract_recheck as rc
import test_explain as fixtures


class AccuracyGuidanceTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        data = json.loads(Path('test_data/extract_accuracy_cases.json').read_text(encoding='utf-8'))
        self.assertEqual(data['source_kind'], 'synthetic')
        self.cases = {c['id']: c for c in data['cases']}

    def check_case(self, name):
        case = self.cases[name]
        clause = {'clause_id': 'synthetic-' + name, 'page': 1, 'text': case['text']}
        row = {'check_item': case['item'], 'clause_id': clause['clause_id'], 'quote': case['text']}
        expected = [row] if case['selected'] else []
        self.h.responses({'extractions': expected})
        with patch.object(self.h.ns['logger'], 'info'):
            res = self.h.api.post('/ai/extract', json={'analysis_id': 'accuracy', 'check_items': [case['item']], 'clauses': [clause]})
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json(), {'extractions': expected})
        call = self.h.mock.models.generate_content.call_args.kwargs
        self.assertEqual(call['config'].system_instruction, self.h.ns['extract_instruction_for']([case['item']]))
        self.assertEqual(json.loads(call['contents'])['clauses'], [self.h.ns['Clause'].model_validate(clause).model_dump()])
        self.assertIsNone(call['config'].response_schema)
        self.assertEqual(self.h.mock.models.generate_content.call_count, 1)

    def test_renewal_change(self): self.check_case('renewal_change')
    def test_renewal_calculation(self): self.check_case('renewal_calculation')
    def test_payment_only(self): self.check_case('payment_only')
    def test_renewal_overdue(self): self.check_case('renewal_overdue')
    def test_surgery_definition(self): self.check_case('surgery_definition')
    def test_disease_definition(self): self.check_case('disease_definition')
    def test_hospital_definition(self): self.check_case('hospital_definition')
    def test_administrative_term(self): self.check_case('administrative_term')
    def test_refund_payment(self): self.check_case('refund_payment')
    def test_refund_deduction(self): self.check_case('refund_deduction')
    def test_refund_limitation(self): self.check_case('refund_limitation')
    def test_loan_limit_only(self): self.check_case('loan_limit_only')
    def test_loan_actual_deduction(self): self.check_case('loan_actual_deduction')

    def test_common_guidance_has_inclusion_exclusion_and_no_forced_results(self):
        base = self.h.ns['EXTRACT_INSTRUCTION']
        for phrase in ('3번은 갱신 시 보험료 계산·재산정', '반드시 인상된다는 문구가 없어도',
                       '미납에 따른 납입최고·해지', '5번은 보험금 지급 또는 보장 범위',
                       '무관한 일반 용어·행정 절차·상품 안내', '환급금 범위 내 대출 가능·한도 안내만으로는',
                       '실제 공제는 구별', '빈 배열', '6개 항목을 채우기 위해 없는 내용을'):
            self.assertIn(phrase, base)

    def test_target_details_and_duty_retained(self):
        for item in (3, 4, 5):
            instruction = self.h.ns['extract_instruction_for']([item])
            self.assertIn(f'"check_item":{item}', instruction)
            self.assertNotIn('"check_item":1', instruction)
        self.assertIn('반드시 불이익이나 위반 효과가 있어야 추출하는 것은 아닙니다', self.h.ns['extract_instruction_for']([4]))
        self.assertIn('반드시 인상된다는 문구가 있어야만', self.h.ns['extract_instruction_for']([3]))
        self.assertIn('모든 용어 정의를 무조건 추출하지', self.h.ns['extract_instruction_for']([5]))
        self.assertEqual(self.h.ns['extract_instruction_for']([6]), self.h.ns['EXTRACT_INSTRUCTION'])

    def test_duplicate_and_wrong_quote_still_filtered(self):
        clause = {'clause_id': 'same', 'page': 1, 'text': '갱신 시 보험료가 인하될 수 있습니다.'}
        row = {'check_item': 3, 'clause_id': 'same', 'quote': clause['text']}
        self.h.responses({'extractions': [row, row, dict(row, quote='보험료가 무조건 인상됩니다.')]})
        with patch.object(self.h.ns['logger'], 'info'):
            res = self.h.api.post('/ai/extract', json={'analysis_id': 'dedupe', 'check_items': [3], 'clauses': [clause]})
        self.assertEqual(res.json(), {'extractions': [row]})
        self.assertFalse(rc.ENABLED)


if __name__ == '__main__': unittest.main()
