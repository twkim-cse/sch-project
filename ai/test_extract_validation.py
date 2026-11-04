"""Offline extract whitespace comparison and safe count diagnostics."""
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import test_explain as fixtures
import test_real_policy


class ExtractValidationTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.payload = {'analysis_id': 'audit', 'check_items': [6], 'clauses': [
            {'clause_id': 'known', 'page': 1, 'text': 'PRIVATE  보험료\n3영업일 이내 지급합니다.'}]}

    def run_rows(self, rows):
        self.h.responses({'extractions': rows})
        with patch.object(self.h.ns['logger'], 'info') as log:
            response = self.h.api.post('/ai/extract', json=self.payload)
        lines = [call.args[0] % call.args[1:] for call in log.call_args_list]
        counts = json.loads(next(line.split('counts=', 1)[1] for line in lines if 'validation counts=' in line))
        self.assertNotIn('PRIVATE', '\n'.join(lines))
        return response, counts

    def row(self, **changes):
        return dict({'check_item': 6, 'clause_id': 'known', 'quote': self.payload['clauses'][0]['text']}, **changes)

    def test_exact_linebreak_and_repeated_space_pass_unchanged(self):
        for quote in (self.row()['quote'], 'PRIVATE  보험료 3영업일 이내 지급합니다.', 'PRIVATE 보험료 3영업일 이내 지급합니다.'):
            with self.subTest(quote=quote):
                response, counts = self.run_rows([self.row(quote=quote)])
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()['extractions'][0]['quote'], quote)
                self.assertEqual(counts['accepted_count'], 1)
                self.assertTrue(test_real_policy.validate_response('extract', self.payload, response.json())['valid'])

    def test_changed_characters_numbers_and_missing_id_rejected(self):
        rows = [self.row(quote='PRIVATE 보험금 3영업일 이내 지급합니다.'),
                self.row(quote='PRIVATE 보험료 5영업일 이내 지급합니다.'),
                self.row(clause_id='PRIVATE_UNKNOWN'), self.row(check_item=5)]
        response, counts = self.run_rows(rows)
        self.assertEqual(response.json(), {'extractions': []})
        self.assertEqual(counts['excluded_reasons'], {'quote_mismatch': 2, 'unknown_clause_id': 1, 'unrequested_check_item': 1})
        self.assertEqual(counts['excluded_count'], 4)
        self.assertEqual(counts['returned_by_check_item']['6'], 3)

    def test_normalized_duplicate_and_invalid_row_counts(self):
        rows = [self.row(), self.row(quote='PRIVATE 보험료 3영업일 이내 지급합니다.'), self.row(check_item='6')]
        response, counts = self.run_rows(rows)
        self.assertEqual(len(response.json()['extractions']), 1)
        self.assertEqual(counts['excluded_reasons'], {'duplicate_extraction': 1, 'invalid_extraction_structure': 1})
        self.assertEqual(counts['returned_by_check_item']['6'], 2)
        self.assertEqual(counts['accepted_by_check_item']['6'], 1)

    def test_invalid_json_and_response_structure_diagnostics(self):
        for text, reason in [('PRIVATE_INVALID_JSON', 'invalid_json'), ('[]', 'invalid_response_structure')]:
            self.h.mock.models.generate_content.side_effect = [SimpleNamespace(text=text)]
            with patch.object(self.h.ns['logger'], 'info') as info, patch.object(self.h.ns['logger'], 'error') as error:
                response = self.h.api.post('/ai/extract', json=self.payload)
                self.assertEqual(response.status_code, 502)
                self.assertIn(reason, str(info.call_args_list))
                self.assertNotIn('PRIVATE', str(info.call_args_list) + str(error.call_args_list))
                self.assertNotIn('PRIVATE', response.text)

    def test_same_rule_does_not_remove_internal_whitespace(self):
        # Whitespace collapse is not whitespace removal or fuzzy matching.
        response, counts = self.run_rows([self.row(quote='PRIVATE 보험료3영업일 이내 지급합니다.')])
        self.assertEqual(response.json(), {'extractions': []})
        self.assertEqual(counts['excluded_reasons'], {'quote_mismatch': 1})


if __name__ == '__main__':
    unittest.main()
