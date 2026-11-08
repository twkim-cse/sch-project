"""AI-only wire contracts; no Spring classes, credentials, network or policy fixtures."""
import copy
import unittest
import test_explain as fixtures


class AiScopeContractTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests();self.h.setUp();self.addCleanup(self.h.doCleanups)
        self.api=self.h.api
        self.source={'analysis_id':'synthetic-scope','check_items':[1], 'clauses':[
            {'clause_id':f'synthetic-{i}','rider_id':'r','section':'합성 특약',
             'article':f'제{i+1}조','article_title':'보험금을 지급하지 않는 사유',
             'paragraph':'제1항','page':i+1,'from_table':False,
             'text':'회사는 고의로 발생한 손해에 대해서는 보험금을 지급하지 않습니다.'}
            for i in range(26)]}

    def bundle(self):
        body={'source_request':self.source,'mode':'recall','focus_items':None}
        response=self.api.post('/ai/extract/candidates/plan',json=body)
        self.assertEqual(response.status_code,200)
        plan=response.json()
        return dict(body,selection_id=plan['selection']['selection_id'],
                    plan_id=plan['candidate_plan']['plan_id'],part_results=self.results(plan['candidate_plan']))

    def results(self,plan):
        return [{'part_id':p['part_id'],'response':{'extractions':[
            {'check_item':1,'clause_id':c['clause_id'],'quote':c['text']} for c in p['request']['clauses']]}}
            for p in plan['parts']]

    def test_candidate_wrapper_parts_and_metadata_preserved_without_generation(self):
        before=copy.deepcopy(self.source)
        response=self.api.post('/ai/extract/candidates/plan',json={'source_request':self.source})
        self.assertEqual(response.status_code,200)
        value=response.json();self.assertEqual(set(value),{'selection','candidate_plan'})
        rows=[c for p in value['candidate_plan']['parts'] for c in p['request']['clauses']]
        self.assertEqual(rows,self.source['clauses'])
        self.assertEqual(len(value['candidate_plan']['parts']),2)
        self.assertEqual(before,self.source)
        self.h.mock.models.generate_content.assert_not_called()

    def test_candidate_parts_execute_on_existing_extract_then_candidate_merge(self):
        body=self.bundle()
        planned=self.api.post('/ai/extract/candidates/plan',json={'source_request':self.source}).json()['candidate_plan']
        results=[]
        for part in planned['parts']:
            c=part['request']['clauses'][0]
            self.h.responses({'extractions':[{'check_item':1,'clause_id':c['clause_id'],'quote':c['text']}]})
            response=self.api.post('/ai/extract',json=part['request'])
            self.assertEqual(response.status_code,200)
            self.assertEqual(set(response.json()),{'extractions'})
            results.append({'part_id':part['part_id'],'response':response.json()})
        response=self.api.post('/ai/extract/candidates/merge',json=dict(body,part_results=results))
        self.assertEqual(response.status_code,200)
        self.assertEqual(set(response.json()),{'extractions','coverage','selection_id','plan_id'})
        self.assertTrue(response.json()['coverage']['all_candidates_completed'])
        self.assertFalse(response.json()['coverage']['risk_absence_determined'])
        self.assertEqual(self.h.mock.models.generate_content.call_count,2)

    def test_full_plan_merge_remain_available_without_generation(self):
        p=self.api.post('/ai/extract/plan',json=self.source)
        self.assertEqual(p.status_code,200)
        plan=p.json()
        r=self.api.post('/ai/extract/merge',json={'source_request':self.source,'plan_id':plan['plan_id'],'part_results':self.results(plan)})
        self.assertEqual(r.status_code,200);self.assertEqual(set(r.json()),{'extractions'})
        self.h.mock.models.generate_content.assert_not_called()

    def test_full_and_candidate_plans_must_not_be_mixed(self):
        candidate=self.bundle()
        response=self.api.post('/ai/extract/merge',json={k:v for k,v in candidate.items() if k in ('source_request','plan_id','part_results')})
        self.assertEqual(response.status_code,422)
        self.assertEqual(response.json()['code'],'BULK_PLAN_MISMATCH')
        full=self.api.post('/ai/extract/plan',json=self.source).json()
        response=self.api.post('/ai/extract/candidates/merge',json=dict(candidate,plan_id=full['plan_id'],part_results=self.results(full)))
        self.assertEqual(response.status_code,422)

    def test_missing_parts_is_409_never_partial_200(self):
        body=self.bundle();body['part_results']=body['part_results'][:-1]
        response=self.api.post('/ai/extract/candidates/merge',json=body)
        self.assertEqual(response.status_code,409);self.assertEqual(response.json()['code'],'BULK_INCOMPLETE')

    def test_source_or_mode_change_invalidates_checkpoint_ids(self):
        for field in ('source','mode'):
            body=self.bundle()
            if field=='source':body['source_request']=copy.deepcopy(self.source);body['source_request']['clauses'][0]['text']+=' 별도 조건입니다.'
            else:body['mode']='full'
            response=self.api.post('/ai/extract/candidates/merge',json=body)
            self.assertEqual(response.status_code,422)

    def test_non_source_quote_rejected_on_merge(self):
        body=self.bundle();body['part_results'][0]['response']['extractions'][0]['quote']='입력에 없는 합성 문장'
        response=self.api.post('/ai/extract/candidates/merge',json=body)
        self.assertEqual(response.status_code,502);self.assertEqual(response.json()['code'],'BULK_RESULT_INVALID')

    def test_progress_is_advisory_not_verified_completion(self):
        body=self.bundle();parts=body.pop('part_results')
        response=self.api.post('/ai/extract/candidates/progress',json=dict(body,
            completed_part_ids=[p['part_id'] for p in parts],failed_part_ids=[]))
        self.assertEqual(response.status_code,200)
        self.assertTrue(response.json()['advisory_checkpoint_snapshot'])
        self.assertFalse(response.json()['results_validated_by_this_endpoint'])
        self.assertFalse(response.json()['full_document_success'])


if __name__=='__main__':unittest.main()
