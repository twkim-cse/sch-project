"""Offline prompt delivery and expected-result handling, not live classification."""
import json
from pathlib import Path
import unittest
import test_explain as fixtures


class ExtractRefundTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        data=json.loads(Path(__file__).with_name('test_data').joinpath('extract_refund_cases.json').read_text(encoding='utf-8'))
        self.assertEqual(data['source_kind'],'synthetic')
        self.cases={c['id']:c for c in data['cases']}

    def check_case(self,name):
        case=self.cases[name]
        clauses=[dict(c,page=1,article='제1조',paragraph='제'+str(i+1)+'항') for i,c in enumerate(case['clauses'])]
        expected=[{'check_item':6,'clause_id':c['clause_id'],'quote':c['text']} for c in clauses if c['clause_id'] in case['selected_ids']]
        self.h.responses({'extractions':expected})
        response=self.h.api.post('/ai/extract',json={'analysis_id':'refund-fixture','check_items':[6],'clauses':clauses})
        self.assertEqual(response.status_code,200)
        self.assertEqual(response.json(),{'extractions':expected})
        kwargs=self.h.mock.models.generate_content.call_args.kwargs
        instruction=kwargs['config'].system_instruction
        for phrase in ('6번은 해약환급금의 계산·산출 기준','청구권 행사기간·소멸시효',
                       '환급금 효과를 직접 설명하는 항을 중심으로', '대출 원리금의 환급금 차감 규정',
                       '6번 결과를 일정 개수 이상 채우려고'):
            self.assertIn(phrase,instruction)
        sent=json.loads(kwargs['contents'])
        self.assertEqual(set(sent['check_items']),{'6'})
        self.assertEqual([c['text'] for c in sent['clauses']],[c['text'] for c in clauses])
        self.assertEqual(self.h.mock.models.generate_content.call_count,1)

    def test_calculation(self): self.check_case('calculation')
    def test_deadline(self): self.check_case('deadline')
    def test_deduction(self): self.check_case('deduction')
    def test_limitation(self): self.check_case('limitation')
    def test_termination_only(self): self.check_case('termination_only')
    def test_loan_only(self): self.check_case('loan_only')
    def test_linked_payment(self): self.check_case('linked_payment')
    def test_unrelated(self): self.check_case('unrelated')
    def test_lapse_only(self): self.check_case('lapse_only')
    def test_other_right_limitation(self): self.check_case('other_right_limitation')

    def test_existing_1_to_5_contract_and_prompt_preserved(self):
        cases=[(1,'고의로 발생시킨 사고는 보험금을 지급하지 않습니다.'),
               (2,'계약일부터 90일 동안 해당 보장을 적용하지 않습니다.'),
               (3,'갱신 시 보험료가 인상될 수 있습니다.'),
               (4,'청약서에서 질문한 사항은 사실대로 알려야 합니다.'),
               (5,'입원은 의료기관에서 계속하여 치료를 받는 것을 말합니다.')]
        clauses=[{'clause_id':'existing-'+str(i),'page':1,'text':text} for i,text in cases]
        expected=[{'check_item':i,'clause_id':'existing-'+str(i),'quote':text} for i,text in cases]
        self.h.responses({'extractions':expected})
        response=self.h.api.post('/ai/extract',json={'analysis_id':'other-fixture','check_items':[1,2,3,4,5],'clauses':clauses})
        self.assertEqual(response.json(),{'extractions':expected})
        sent=json.loads(self.h.mock.models.generate_content.call_args.kwargs['contents'])
        self.assertNotIn('6',sent['check_items'])
        instruction=self.h.mock.models.generate_content.call_args.kwargs['config'].system_instruction
        self.assertIn('1번은 보험금을 지급하지 않는 사유',instruction)
        self.assertIn('2번은 계약 후 일정 기간',instruction)


if __name__=='__main__': unittest.main()
