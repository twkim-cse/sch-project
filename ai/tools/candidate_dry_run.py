"""Offline candidate/full-plan comparison. No environment or Gemini access."""
import argparse,json,os,runpy,sys
from pathlib import Path
from unittest.mock import patch
p=argparse.ArgumentParser()
p.add_argument('--data',required=True)
p.add_argument('--mode',choices=['recall','broad','full'],default='recall')
p.add_argument('--focus',type=int,nargs='+')
args=p.parse_args()
ai=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ai))
with patch('dotenv.load_dotenv'),patch.dict(os.environ,{'GEMINI_API_KEY':'','GEMINI_MODEL':'offline'}):
    ns=runpy.run_path(str(ai/'app.py'))
import candidate_filter as cf
import bulk_extract as b
try:
    source=ns['ExtractRequest'].model_validate(json.loads(Path(args.data).read_text(encoding='utf-8-sig')))
    selection=cf.select_candidates(source,args.mode,args.focus)
    bundle,sub=cf.candidate_plan(source,ns['ExtractRequest'],ns['CHECK_ITEM_DEFINITIONS'],ns['extract_instruction_for'],args.mode,args.focus)
    original=b.plan_bulk(source,{str(i):ns['CHECK_ITEM_DEFINITIONS'][i] for i in source.check_items},ns['extract_instruction_for'](source.check_items))
    out=dict(selection['coverage'],mode=args.mode,unfiltered_requests=len(original['parts']),
             filtered_requests=len(bundle['candidate_plan']['parts']),actual_gemini_calls=0,
             selected_text_preservation=b.validate_preservation(sub,bundle['candidate_plan']))
    print(json.dumps(out,ensure_ascii=False,indent=2))
except Exception as exc:
    print(json.dumps({'failed':True,'error_type':type(exc).__name__,'code':getattr(exc,'code','INPUT_INVALID')}))
    sys.exit(1)
