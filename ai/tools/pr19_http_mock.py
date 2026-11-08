"""Loopback-only PR19/20 HTTP fixture: real AI routes, fake Gemini, safe metrics."""
import argparse
import json
from fastapi import Request
from fastapi.responses import JSONResponse, Response
import uvicorn
from mock_integration_server import build_app

app=build_app()
base_metrics=next(r.endpoint for r in app.routes if getattr(r,'path',None)=='/mock/metrics')
state={}
def reset(code=0):
    state.clear();state.update(code=code,plans={},extract_calls=0,failed_calls=0,merge_calls=0,
        explain_calls=0,failed_empty_submitted=0,later_after_failure=0,invalid_part_budget=0,
        metadata_verified=True,quote_verified=True,contract_verified=True,all_ids=set(),last_coverage=None,
        original_count=0,candidate_count=0,parts=0,policy_version=None,plan_bytes=0,
        max_part_input_bytes=0,max_part_clauses=0,explain_responses=[],base_start=base_metrics())
reset()

@app.post('/test/control')
async def control(request:Request):
    reset((await request.json()).get('code',0));return {'mode':'MOCK_ONLY','real_gemini_calls':0}

@app.get('/test/metrics')
def metrics():
    counts=base_metrics()
    diff={key:counts[key]-state['base_start'][key] for key in ('extract_attempts','generation_attempts','review_attempts')}
    return {k:v for k,v in state.items() if k not in ('plans','all_ids','base_start')}|diff|{'real_gemini_calls':0,'mode':'MOCK_ONLY'}

@app.middleware('http')
async def instrument(request,call_next):
    path=request.url.path
    if not path.startswith('/ai/'):return await call_next(request)
    body=await request.json();key=body.get('analysis_id') or body.get('source_request',{}).get('analysis_id')
    if path=='/ai/extract':
        state['extract_calls']+=1
        plan=state['plans'].get(key)
        ids=[c['clause_id'] for c in body['clauses']]
        if plan:
            found=next((p for p in plan['parts'] if [c['clause_id'] for c in p['request']['clauses']]==ids),None)
            state['contract_verified'] &= found is not None and body==found['request']
            if len(ids)>25 or found is None or found['input_bytes']>24_000:state['invalid_part_budget']+=1
            fault=plan['parts'][1] if len(plan['parts'])>1 else plan['parts'][0]
            if state['code'] and ids==[c['clause_id'] for c in fault['request']['clauses']]:
                state['failed_calls']+=1
                return JSONResponse(status_code=state['code'],content={'code':'GEMINI_API_ERROR','message':'Mock upstream failure'})
        if state['failed_calls']:state['later_after_failure']+=1
    if path=='/ai/explain':state['explain_calls']+=1
    if path=='/ai/extract/candidates/merge':
        state['merge_calls']+=1
        plan=state['plans'][key]
        state['contract_verified'] &= body.get('mode')==plan['_mode'] and body.get('focus_items')==plan['_focus']
        state['contract_verified'] &= body['selection_id']==plan['_selection_id'] and body['plan_id']==plan['plan_id']
        state['contract_verified'] &= body['source_request']==plan['_source']
        if state['code']:
            fault=plan['parts'][1] if len(plan['parts'])>1 else plan['parts'][0]
            for row in body['part_results']:
                if row['part_id']==fault['part_id'] and row['response']=={'extractions':[]}:
                    state['failed_empty_submitted']+=1
    response=await call_next(request)
    raw=b''.join([chunk async for chunk in response.body_iterator])
    if path=='/ai/explain':
        value=json.loads(raw)
        state['explain_responses'].append({'http':response.status_code,'code':value.get('code'),
            'items':len(body.get('items',[])),'cards':len(value.get('cards',[])),
            'pass':sum(c['review']=='pass' for c in value.get('cards',[])),
            'fail':sum(c['review']=='fail' for c in value.get('cards',[]))})
    if response.status_code==200:
        value=json.loads(raw)
        if path=='/ai/extract/candidates/plan':
            import bulk_extract
            ns=next(r.endpoint.__globals__ for r in app.routes if getattr(r,'path',None)=='/ai/extract')
            source=ns['ExtractRequest'].model_validate(body['source_request'])
            import candidate_filter
            selection=candidate_filter.select_candidates(source,body.get('mode','recall'),body.get('focus_items'))
            sub=ns['ExtractRequest'].model_validate(selection['candidate_request'])
            plan=value['candidate_plan'];state['metadata_verified'] &= bulk_extract.validate_preservation(sub,plan)
            plan.update(_mode=body.get('mode','recall'),_focus=body.get('focus_items'),
                        _source=body['source_request'],_selection_id=value['selection']['selection_id'])
            state['plans'][key]=plan
            coverage=value['selection']['coverage']
            state.update(original_count=coverage['original_count'],candidate_count=coverage['candidate_count'],
                         parts=len(plan['parts']),policy_version=value['selection']['policy_version'],plan_bytes=len(raw),
                         max_part_input_bytes=max((p['input_bytes'] for p in plan['parts']),default=0),
                         max_part_clauses=max((len(p['request']['clauses']) for p in plan['parts']),default=0))
        if path=='/ai/extract/candidates/merge':state['last_coverage']=value['coverage']
        if path=='/ai/extract':
            text={c['clause_id']:c['text'] for c in body['clauses']}
            state['quote_verified'] &= all(e['clause_id'] in text and e['quote'] in text[e['clause_id']] for e in value['extractions'])
    return Response(content=raw,status_code=response.status_code,headers=dict(response.headers),media_type=response.media_type)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18082)
    uvicorn.run(app,host='127.0.0.1',port=parser.parse_args().port,access_log=False,log_level='warning')
