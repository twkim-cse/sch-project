"""Deterministic negative-filter tests using synthetic wording only."""
import json
import unittest
from unittest.mock import patch
from refund_validation import is_clear_loan_only
import test_explain as fixtures


class RefundPostvalidationTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.loan = '계약자는 보험계약대출을 받을 수 있습니다.'

    def validate(self, text, quote=None, item=6, copies=1):
        quote = text if quote is None else quote
        req = self.h.ns['ExtractRequest'].model_validate({'analysis_id': 'synthetic', 'check_items': [item],
            'clauses': [{'clause_id': 'synthetic', 'page': 1, 'text': text}]})
        row = {'check_item': item, 'clause_id': 'synthetic', 'quote': quote}
        with patch.object(self.h.ns['logger'], 'info') as log:
            result = self.h.ns['validate_extractions']({'extractions': [row] * copies}, req)
        return result.model_dump(), json.loads(log.call_args.args[1]), log

    def test_permission_only_excluded(self):
        out, counts, _ = self.validate(self.loan)
        self.assertEqual(out, {'extractions': []})
        self.assertEqual(counts['excluded_reasons'], {'refund_loan_only': 1})

    def test_refund_scoped_permission_excluded(self):
        text = '계약자는 해약환급금 범위 내에서 회사가 정한 방법에 따라 보험계약대출을 받을 수 있습니다.'
        self.assertEqual(self.validate(text)[0], {'extractions': []})

    def test_loan_limit_only_excluded(self):
        self.assertEqual(self.validate('보험계약대출 한도는 해약환급금의 80% 이내입니다.')[0], {'extractions': []})

    def test_availability_exception_only_excluded(self):
        text = self.loan + ' 다만, 보험상품의 종류에 따라 보험계약대출이 제한될 수도 있습니다.'
        self.assertEqual(self.validate(text)[0], {'extractions': []})

    def test_actual_deduction_retained(self):
        text = '보험계약대출 원금과 이자는 해약환급금에서 공제하여 지급합니다.'
        self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_refund_payment_retained(self):
        text = '해약환급금은 청구일부터 3영업일 이내 지급합니다.'
        self.assertFalse(is_clear_loan_only(text, text))
        self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_calculation_retained(self):
        text = '해약환급금은 보험료 및 책임준비금 산출방법서에 따라 계산합니다.'
        self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_claim_limitation_retained(self):
        text = '해약환급금 청구권은 3년간 행사하지 않으면 소멸시효가 완성됩니다.'
        self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_mixed_clause_retained_even_when_quote_only_permission(self):
        text = self.loan + ' 계약 해지 시 대출 원리금은 해약환급금에서 차감합니다.'
        self.assertEqual(len(self.validate(text, self.loan)[0]['extractions']), 1)

    def test_unclear_effect_retained(self):
        text = '계약자는 보험계약대출을 받을 수 있으며 환급금에 대한 별도 조건은 해당 규정을 따릅니다.'
        self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_negated_or_unknown_wording_retained(self):
        for text in ('계약자는 보험계약대출을 받을 수 없습니다.',
                     '계약자는 환급금 변동을 확인한 후 보험계약대출을 받을 수 있습니다.'):
            self.assertEqual(len(self.validate(text)[0]['extractions']), 1)

    def test_other_five_items_untouched(self):
        for item in range(1, 6):
            self.assertEqual(len(self.validate(self.loan, item=item)[0]['extractions']), 1)

    def test_whitespace_and_duplicate_handling_retained(self):
        text = '해약환급금은\n 3영업일 이내 지급합니다.'
        out, counts, _ = self.validate(text, '해약환급금은 3영업일 이내 지급합니다.', copies=2)
        self.assertEqual(len(out['extractions']), 1)
        self.assertEqual(counts['excluded_reasons'], {'duplicate_extraction': 1})

    def test_quote_mismatch_precedes_semantic_filter(self):
        _, counts, _ = self.validate('해약환급금을 지급합니다.', self.loan)
        self.assertEqual(counts['excluded_reasons'], {'quote_mismatch': 1})

    def test_safe_aggregate_logging(self):
        out, counts, log = self.validate(self.loan)
        rendered = '\n'.join(c.args[0] % c.args[1:] for c in log.call_args_list)
        self.assertNotIn(self.loan, rendered)
        self.assertNotIn('clause_id', rendered)
        self.assertEqual(counts['returned_by_check_item']['6'], 1)
        self.assertEqual(counts['accepted_by_check_item']['6'], 0)

    def test_unknown_source_prefix_retained(self):
        text = '계약의 특별한 조건을 확인해야 합니다. ' + self.loan
        self.assertEqual(len(self.validate(text, self.loan)[0]['extractions']), 1)

    def test_api_contract_and_empty_result(self):
        self.h.responses({'extractions': [{'check_item': 6, 'clause_id': 'synthetic', 'quote': self.loan}]})
        with patch.object(self.h.ns['logger'], 'info'):
            res = self.h.api.post('/ai/extract', json={'analysis_id': 'postvalidation', 'check_items': [6],
                'clauses': [{'clause_id': 'synthetic', 'page': 1, 'text': self.loan}]})
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json(), {'extractions': []})
        self.assertEqual(self.h.mock.models.generate_content.call_count, 1)


if __name__ == '__main__': unittest.main()
