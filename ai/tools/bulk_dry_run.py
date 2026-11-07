"""Offline only: validate, plan, reassemble, optionally mock the sequential job."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys
from unittest.mock import patch

parser=argparse.ArgumentParser()
parser.add_argument('--data',required=True)
parser.add_argument('--mock',action='store_true',help='Mock empty responses; never infers absence of risks')
parser.add_argument('--plan-out',help='Optional real-text plan, restricted to cwd/local_policy/')
args=parser.parse_args()
ai=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ai))
with patch('dotenv.load_dotenv'),patch.dict(os.environ,{'GEMINI_API_KEY':'','GEMINI_MODEL':'offline-no-model-call'}):
    ns=runpy.run_path(str(ai/'app.py'))
import bulk_extract as b
try:
    req=ns['ExtractRequest'].model_validate(json.loads(Path(args.data).read_text(encoding='utf-8-sig')))
    plan=b.plan_bulk(req,{str(i):ns['CHECK_ITEM_DEFINITIONS'][i] for i in req.check_items},ns['extract_instruction_for'](req.check_items))
    report={k:plan[k] for k in ['original_clause_count','fragment_count','context_copy_count','neighbor_context_copy_count','neighbor_context_omitted_count','boundary_review_required_count','preservation_verified']}
    report.update(parts=len(plan['parts']),normal_generation_calls_if_live=len(plan['parts']),actual_gemini_calls=0)
    if args.mock:
        cp=b.Checkpoint(plan['plan_id'])
        b.run_bulk(req,plan,lambda *_:{'extractions':[]},cp)
        report.update(mock_completed_parts=len(cp.completed),mock_result_is_not_accuracy_measure=True)
    if args.plan_out:
        target=Path(args.plan_out).resolve();allowed=(Path.cwd()/'local_policy').resolve()
        target.relative_to(allowed)
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
        report['real_text_plan_saved_local_only']=True
    print(json.dumps(report,ensure_ascii=False,indent=2))
except Exception as exc:
    print(json.dumps({'offline_validation_failed':True,'error_type':type(exc).__name__,'code':getattr(exc,'code','INPUT_OR_OUTPUT_INVALID')}))
    sys.exit(1)
