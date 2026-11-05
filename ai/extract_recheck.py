"""Bounded, opt-in scheduling; cues indicate suspicion, never a classification."""
import re
from extract_planning import score_clause, search_text, make_batch

ENABLED = False
MAX_ITEMS = 3
MAX_CLAUSES = 12
MAX_BYTES = 16_000
MAX_ATTEMPTS = 6
MAX_SECONDS = 120

# Require concrete semantic combinations in addition to the existing score.
CUES = {
    1: r'(지급하지|보상하지|보장하지|보장제외)',
    2: r'(면책기간|감액기간|대기기간|보장개시|부담보기간|감액|부담보.*(기간|년|개월|일))',
    3: r'갱신.*보험료|보험료.*(갱신|인상|변경)',
    4: r'(알릴의무|고지의무|통지의무)',
    5: r'(정의|용어|이란|이라함).*(질병|수술|입원|의료)|(질병|수술|입원|의료).*(정의|이란|이라함)',
    6: r'(해약환급금|해지환급금|환급금).*(계산|지급|공제|차감|반환|소멸|청구|금액)|중도해지.*반환',
}


def plan_rechecks(req, result, definitions, instruction, instruction_for=None):
    found = {row.check_item for row in result.extractions}
    plans = []
    for item in dict.fromkeys(req.check_items):
        if item in found:
            continue
        item_instruction = instruction_for([item]) if instruction_for is not None else instruction
        candidates = []
        for index, clause in enumerate(req.clauses):
            score = score_clause(clause, [item])[item]
            text = search_text(' '.join((clause.article_title or '', clause.section or '', clause.text)))
            if score > 0 and re.search(CUES[item], text):
                candidates.append((score, index))
        candidates.sort(key=lambda row: (-row[0], row[1]))
        selected = []
        ids = set()
        for _, index in candidates:
            clause = req.clauses[index]
            # Keep the entire same-article scope, including exceptions. If it
            # cannot fit, skip that group rather than silently clipping context.
            group = [c for c in req.clauses if clause.article and
                     (c.rider_id, c.section, c.article) ==
                     (clause.rider_id, clause.section, clause.article)] if clause.article else [clause]
            proposed = selected + [c for c in group if c.clause_id not in ids]
            batch = make_batch(proposed, {str(item): definitions[str(item)]}, item_instruction)
            if len(proposed) <= MAX_CLAUSES and batch.input_bytes <= MAX_BYTES:
                selected = proposed
                ids = {c.clause_id for c in selected}
        if selected:
            plans.append((item, make_batch(selected, {str(item): definitions[str(item)]}, item_instruction)))
        if len(plans) >= MAX_ITEMS:
            break
    return plans
