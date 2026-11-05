"""Offline contract/prompt tests. Mock labels do not establish AI accuracy."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import test_explain as fixtures


class ExtractCategoryTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        data=json.loads(Path(__file__).with_name('test_data').joinpath('extract_category_cases.json').read_text(encoding='utf-8'))
        self.assertEqual(data['source_kind'],'synthetic')
        self.cases={c['case_id']:c for c in data['cases']}

    def check_case(self,name):
        case=self.cases[name]
        cid='synthetic-'+name
        payload={'analysis_id':'categories','check_items':[1,2],
                 'clauses':[{'clause_id':cid,'page':1,'text':case['text']}]}
        rows=[{'check_item':i,'clause_id':cid,'quote':case['text']} for i in case['expected_items']]
        self.h.responses({'extractions':rows})
        response=self.h.api.post('/ai/extract',json=payload)
        self.assertEqual(response.status_code,200)
        self.assertEqual(response.json(),{'extractions':rows})
        instruction=self.h.mock.models.generate_content.call_args.kwargs['config'].system_instruction
        for phrase in ('1번은 보험금을 지급하지 않는 사유', '2번은 계약 후 일정 기간',
                       '특정 질병·신체부위', '기간 조건이 없는', '각각 관련 원문 인용으로 복수 추출',
                       '6개 항목을 채우기 위해'):
            self.assertIn(phrase,instruction)
        sent=json.loads(self.h.mock.models.generate_content.call_args.kwargs['contents'])
        self.assertEqual(sent['clauses'][0]['text'],case['text'])
        self.assertEqual(set(sent['check_items']),{'1','2'})
        return payload,rows

    def test_general_exclusion(self): self.check_case('general_exclusion')
    def test_waiting_90_days(self): self.check_case('waiting_90_days')
    def test_temporary_reduction(self): self.check_case('temporary_reduction')
    def test_specific_condition_period(self): self.check_case('specific_condition_period')
    def test_permanent_exclusion(self): self.check_case('permanent_exclusion')
    def test_both_types(self): self.check_case('both_types')
    def test_irrelevant(self): self.check_case('irrelevant')

    def test_multi_item_validation_keeps_both_and_deduplicates_per_item(self):
        payload,rows=self.check_case('both_types')
        request=self.h.ns['ExtractRequest'].model_validate(payload)
        with patch.object(self.h.ns['logger'],'info') as log:
            result=self.h.ns['validate_extractions']({'extractions':rows+rows},request)
        self.assertEqual([r.check_item for r in result.extractions],[1,2])
        counts=json.loads(log.call_args.args[1])
        self.assertEqual(counts['excluded_reasons'],{'duplicate_extraction':2})
        self.assertEqual(counts['accepted_by_check_item']['1'],1)
        self.assertEqual(counts['accepted_by_check_item']['2'],1)


if __name__=='__main__': unittest.main()
