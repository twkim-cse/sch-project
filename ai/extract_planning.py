"""Lossless extraction scheduling. Scores order work; they never discard clauses."""
from collections import OrderedDict, Counter
from dataclasses import dataclass, field
import json
import re


MAX_BATCH_CLAUSES = 25
MAX_INPUT_BYTES = 24_000
WIRE_RESERVE_BYTES = 2_048
MAX_BATCHES = 40

KEYWORDS = {
    1: ("면책", "보상하지", "지급하지", "보장하지", "보장제외", "제외사항", "고의", "부담보"),
    2: ("면책기간", "감액", "대기기간", "보장개시", "보상개시", "부담보기간", "부담보", "일이지난", "일이내"),
    3: ("갱신", "갱신보험료", "보험료인상", "보험료변경", "보험요율", "위험률", "기초율"),
    4: ("알릴의무", "고지", "통지", "청약서", "질문한사항", "직업변경", "직무변경"),
    5: ("정의", "용어", "질병", "수술", "의료기관", "입원", "진단확정", "절단", "절제"),
    6: ("해약환급금", "해지환급금", "환급금", "중도해지", "계약해지", "미경과보험료", "적립액"),
}


class ExtractPlanningError(ValueError):
    pass


@dataclass
class ExtractBatch:
    clauses: list
    contents: str
    input_bytes: int
    context_reasons: dict = field(default_factory=dict)
    context_omitted_budget: bool = False


def validate_batch_coverage(clauses, batches):
    originals = {c.clause_id: c.model_dump() for c in clauses}
    if len(originals) != len(clauses):
        raise RuntimeError("duplicate_input_clause_id")
    primary = Counter()
    for batch in batches:
        ids = [c.clause_id for c in batch.clauses]
        if len(ids) != len(set(ids)) or not set(batch.context_reasons).issubset(ids):
            raise RuntimeError("invalid_context_annotation")
        for clause in batch.clauses:
            if clause.clause_id not in originals or clause.model_dump() != originals[clause.clause_id]:
                raise RuntimeError("unexpected_or_modified_clause")
            if clause.clause_id in batch.context_reasons:
                if (batch.context_reasons[clause.clause_id] != "same_article_boundary_exception_context"
                        or primary[clause.clause_id] != 1):
                    raise RuntimeError("undocumented_context_duplicate")
            else:
                primary[clause.clause_id] += 1
    if primary != Counter({cid: 1 for cid in originals}):
        raise RuntimeError("internal_coverage_error")


def search_text(text):
    return re.sub(r"\s+", "", text or "").casefold()


def score_clause(clause, check_items):
    fields = ((search_text(clause.article_title), 8),
              (search_text(clause.section), 3), (search_text(clause.text), 1))
    return {item: sum(weight for text, weight in fields for word in KEYWORDS[item] if word in text)
            for item in dict.fromkeys(check_items)}


def make_batch(clauses, definitions, instruction):
    contents = json.dumps({"check_items": definitions,
                           "clauses": [clause.model_dump() for clause in clauses]}, ensure_ascii=False)
    size = len(contents.encode("utf-8")) + len(instruction.encode("utf-8")) + WIRE_RESERVE_BYTES
    return ExtractBatch(list(clauses), contents, size)


def plan_extraction(clauses, check_items, definitions, instruction):
    scores = [score_clause(clause, check_items) for clause in clauses]
    summary = {}
    for item in dict.fromkeys(check_items):
        values = [row[item] for row in scores]
        summary[str(item)] = {"zero": sum(v == 0 for v in values),
                              "low_1_to_9": sum(0 < v < 10 for v in values),
                              "high_10_plus": sum(v >= 10 for v in values),
                              "max": max(values, default=0)}
    if not clauses:
        return [], summary

    def fits(batch):
        return len(batch.clauses) <= MAX_BATCH_CLAUSES and batch.input_bytes <= MAX_INPUT_BYTES

    # Preflight every clause before any model call, even low-priority clauses.
    for clause in clauses:
        if not fits(make_batch([clause], definitions, instruction)):
            raise ExtractPlanningError("single_clause_exceeds_input_budget")
    whole = make_batch(clauses, definitions, instruction)
    if fits(whole):
        validate_batch_coverage(clauses, [whole])
        return [whole], summary  # Preserve small-request order and single-call behavior.

    # Same article in the same policy scope stays together when it fits. Missing
    # article metadata must not combine the entire document into one group.
    groups = OrderedDict()
    for index, clause in enumerate(clauses):
        key = (clause.rider_id, clause.section, clause.article) if clause.article else ("index", index)
        groups.setdefault(key, []).append(index)
    ranked_groups = sorted(groups.values(),
                           key=lambda indices: (-max(max(scores[i].values(), default=0) for i in indices), indices[0]))
    batches, current = [], []

    def flush():
        nonlocal current
        if current:
            batches.append(make_batch(current, definitions, instruction))
            current = []

    for indices in ranked_groups:
        group = [clauses[i] for i in indices]  # Original paragraph order preserved.
        if fits(make_batch(group, definitions, instruction)):
            if not fits(make_batch(current + group, definitions, instruction)):
                flush()
            current.extend(group)
        else:
            # An oversized article is split only between complete clauses. No text
            # truncation and no overlap; neighboring clauses remain consecutive.
            flush()
            for clause in group:
                if not fits(make_batch(current + [clause], definitions, instruction)):
                    flush()
                current.append(clause)
            flush()
    flush()
    if len(batches) > MAX_BATCHES:
        raise ExtractPlanningError("request_exceeds_batch_limit")
    validate_batch_coverage(clauses, batches)
    # Context copies are allowed only for a boundary exception/reference in the
    # same article and policy scope, within the existing input/count budgets.
    for index in range(1, len(batches)):
        previous = batches[index-1].clauses[-1]
        first = batches[index].clauses[0]
        same_scope = (first.article and (previous.rider_id, previous.section, previous.article)
                      == (first.rider_id, first.section, first.article))
        if same_scope and re.match(r"\s*(?:[①-⑳]\s*)?(?:다만|그러나|예외|전항|위\s*항|제\s*\d+\s*항)", first.text):
            candidate = make_batch([previous] + batches[index].clauses, definitions, instruction)
            if fits(candidate):
                candidate.context_reasons[previous.clause_id] = "same_article_boundary_exception_context"
                batches[index] = candidate
            else:
                batches[index].context_omitted_budget = True
    validate_batch_coverage(clauses, batches)
    return batches, summary
