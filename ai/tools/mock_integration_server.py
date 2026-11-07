"""Loopback-only FastAPI integration harness. NEVER invokes Gemini or reads .env.

All generated outputs are fabricated plumbing fixtures, not accuracy labels.
Run explicitly in a separate process; never deploy this as the AI service.
"""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys
from types import SimpleNamespace
from unittest.mock import Mock, patch

import uvicorn


def build_app():
    ai = Path(__file__).resolve().parents[1]
    sys.path.insert(0, str(ai))
    with patch('dotenv.load_dotenv'), patch.dict(os.environ, {'GEMINI_API_KEY':'', 'GEMINI_MODEL':'gemini-3.6-flash'}):
        ns = runpy.run_path(str(ai/'app.py'))
    metrics = {'extract_attempts':0,'generation_attempts':0,'review_attempts':0,'real_gemini_calls':0,
               'plan_requests':0,'extract_requests':0,'merge_requests':0}

    def generate(**kwargs):
        payload=json.loads(kwargs['contents'])
        if 'clauses' in payload:
            metrics['extract_attempts']+=1
            # A deliberately fabricated single row per part to exercise downstream
            # ID/quote mapping. Do not interpret check_item=1 as a real label.
            clauses=payload['clauses']
            rows=[] if not clauses else [dict(check_item=1,clause_id=clauses[0]['clause_id'],quote=clauses[0]['text'][:80])]
            value={'extractions':rows}
        elif 'candidate' in payload:
            metrics['review_attempts']+=1
            value={'item_id':payload['source']['item_id'],'review':'pass','review_reason':None}
        else:
            metrics['generation_attempts']+=1
            value={'item_id':payload['source']['item_id'],'title':'Mock 연동 검증입니다.',
                   'explanation':payload['source']['quote']}
        return SimpleNamespace(text=json.dumps(value,ensure_ascii=False))

    fake=Mock()
    fake.models.list.return_value=[SimpleNamespace(name='models/gemini-3.6-flash',supported_actions=['generateContent'])]
    fake.models.generate_content.side_effect=generate
    ns['extract_document'].__globals__['client']=fake
    # No real waits and no event-loop/global clock patches in this HTTP server.
    ns['explain_call_limiter'].acquire=Mock()
    app=ns['app']

    @app.middleware('http')
    async def count_calls(request, call_next):
        names={'/ai/extract/plan':'plan_requests','/ai/extract':'extract_requests','/ai/extract/merge':'merge_requests'}
        if request.url.path in names:metrics[names[request.url.path]]+=1
        return await call_next(request)

    @app.get('/mock/metrics')
    def safe_metrics():return dict(metrics,mode='MOCK_ONLY',b_recheck_enabled=ns['extract_recheck'].ENABLED)
    return app


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18081)
    uvicorn.run(build_app(),host='127.0.0.1',port=parser.parse_args().port,access_log=False,log_level='warning')
