"""Opt-in JSON audit with fabricated Gemini output. No .env or network access."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from collections import Counter
from fastapi.testclient import TestClient
from mock_integration_server import build_app


def audit(path):
    app=build_app()
    ns=next(r.endpoint.__globals__ for r in app.routes if getattr(r,'path',None)=='/ai/extract')
    import bulk_extract as bulk
    import extract_planning as ep
    raw=path.read_bytes(); before=hashlib.sha256(raw).hexdigest()
    source=ns['ExtractRequest'].model_validate_json(raw)
    client=TestClient(app)
    rejected=client.post('/ai/extract',json=source.model_dump())
    p=client.post('/ai/extract/plan',json=source.model_dump())
    assert p.status_code==200
    plan=p.json();assert bulk.validate_preservation(source,plan)
    defs={str(i):ns['CHECK_ITEM_DEFINITIONS'][i] for i in source.check_items}
    inst=ns['extract_instruction_for'](source.check_items)
    results=[]
    for part in plan['parts']:
        req=ns['ExtractRequest'].model_validate(part['request'])
        batches,_=ep.plan_extraction(req.clauses,req.check_items,defs,inst)
        assert len(batches)==1 and len(req.clauses)<=25 and batches[0].input_bytes<=24000
        assert batches[0].input_bytes==part['input_bytes']
        r=client.post('/ai/extract',json=part['request']);assert r.status_code==200
        results.append(dict(part_id=part['part_id'],response=r.json()))
    body=dict(source_request=source.model_dump(),plan_id=plan['plan_id'],part_results=results)
    merged=client.post('/ai/extract/merge',json=body);assert merged.status_code==200
    missing=client.post('/ai/extract/merge',json=dict(body,part_results=results[:-1]));assert missing.status_code==409
    assert before==hashlib.sha256(path.read_bytes()).hexdigest()
    normalized=[''.join(c.text.split()) for c in source.clauses]
    duplicates=Counter(normalized)
    return dict(source_clauses=len(source.clauses),fragments=plan['fragment_count'],parts=len(plan['parts']),
                max_part_clauses=max(len(p['request']['clauses']) for p in plan['parts']),
                max_input_bytes=max(p['input_bytes'] for p in plan['parts']),
                min_input_bytes=min(p['input_bytes'] for p in plan['parts']),
                legacy_http=rejected.status_code,legacy_code=rejected.json().get('code'),
                all_part_http=200,merge_http=merged.status_code,missing_merge_http=missing.status_code,
                source_sha_preserved=True,primary_text_and_fields_preserved=True,
                duplicate_id_count=len(source.clauses)-len({c.clause_id for c in source.clauses}),
                same_text_duplicate_groups=sum(n>1 for n in duplicates.values()),
                page_outside_1_to_256=sum(not 1<=c.page<=256 for c in source.clauses),
                missing_article_title=sum(c.article_title is None for c in source.clauses),
                context_fragment_copies=plan['context_copy_count'],neighbor_copies=plan['neighbor_context_copy_count'],
                fabricated_mock_extractions=len(merged.json()['extractions']),actual_gemini_calls=0,
                pdf_verified=False,accuracy_verified=False)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('input',type=Path);p.add_argument('--report',type=Path)
    args=p.parse_args();result=audit(args.input)
    if args.report:args.report.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result))
