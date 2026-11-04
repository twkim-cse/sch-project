"""Explicit opt-in HTTP tests. Never import app.py or load .env."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import time

import httpx


ROOT = Path(__file__).resolve().parent
KST = timezone(timedelta(hours=9))
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?\s*(?:%|퍼센트|일|개월|개년|년|월|회|번|만원|원|배)?")


def numeric_tokens(text):
    # Literal Arabic-number comparison only; intentionally advisory.
    return {re.sub(r"[\s,]", "", match.group()) for match in NUMBER.finditer(text)}


def number_advisory(source, explanation):
    unmatched = numeric_tokens(explanation) - numeric_tokens(source)
    return {"status": "warning" if unmatched else "no_literal_mismatch",
            "unmatched_count": len(unmatched), "advisory_only": True}


def nonblank(value):
    return isinstance(value, str) and bool(value.strip())


def normalize_quote_whitespace(text):
    return re.sub(r"\s+", " ", text)


def validate_response(api, payload, data):
    failures = []
    metrics = {}
    if not isinstance(data, dict):
        return {"valid": False, "failures": ["response_not_object"], "metrics": {}}
    if api == "classify":
        valid = (set(data) == {"doc_type", "product_name", "version_label"}
                 and data["doc_type"] in ("TERMS", "POLICY", "PRODUCT_GUIDE", "OTHER")
                 and all(data[k] is None or isinstance(data[k], str) for k in ("product_name", "version_label")))
        if not valid:
            failures.append("invalid_classify_structure")
    elif api == "extract":
        rows = data.get("extractions")
        if set(data) != {"extractions"} or not isinstance(rows, list):
            return {"valid": False, "failures": ["invalid_extractions_structure"], "metrics": {}}
        sources = {c["clause_id"]: c["text"] for c in payload["clauses"]}
        checks, quotes = [], []
        for index, row in enumerate(rows):
            valid = (isinstance(row, dict) and set(row) == {"check_item", "clause_id", "quote"}
                     and type(row.get("check_item")) is int and 1 <= row["check_item"] <= 6
                     and row["check_item"] in payload["check_items"]
                     and isinstance(row.get("clause_id"), str) and nonblank(row.get("quote")))
            original = (valid and row["clause_id"] in sources
                        and normalize_quote_whitespace(row["quote"]) in normalize_quote_whitespace(sources[row["clause_id"]]))
            quotes.append({"result_index": index, "valid": bool(original)})
            if not original:
                failures.append("invalid_extraction_" + str(index))
            if valid:
                checks.append(row["check_item"])
        metrics = {"extracted_check_items": sorted(set(checks)), "quote_validation": quotes}
    else:
        cards = data.get("cards")
        if set(data) != {"cards"} or not isinstance(cards, list):
            return {"valid": False, "failures": ["invalid_cards_structure"], "metrics": {}}
        sources = {item["item_id"]: item["quote"] for item in payload["items"]}
        seen, reviews = set(), []
        for index, card in enumerate(cards):
            valid = (isinstance(card, dict) and set(card) == {"item_id", "title", "explanation", "review", "review_reason"}
                     and nonblank(card.get("item_id")) and nonblank(card.get("title"))
                     and nonblank(card.get("explanation")) and card.get("review") in ("pass", "fail"))
            if valid:
                valid = (card["item_id"] in sources and card["item_id"] not in seen
                         and ((card["review"] == "pass" and card["review_reason"] is None)
                              or (card["review"] == "fail" and nonblank(card["review_reason"]))))
            if not valid:
                failures.append("invalid_card_" + str(index))
                continue
            seen.add(card["item_id"])
            reviews.append({"result_index": index, "review": card["review"],
                            "review_reason_present": card["review_reason"] is not None,
                            "numbers": number_advisory(sources[card["item_id"]], card["title"] + " " + card["explanation"])})
            if card["review"] == "fail":
                failures.append("semantic_review_failed_" + str(index))
        if seen != set(sources):
            failures.append("missing_or_duplicate_cards")
        metrics = {"meaning_reviews": reviews}
    return {"valid": not failures, "failures": failures, "metrics": metrics}


def load_data(path):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    # Reuse request definitions without importing the server or reading credentials.
    # Data validation is deliberately independent of the server runtime.
    if not isinstance(data, dict) or data.get("source_kind") not in ("synthetic", "real_policy"):
        raise ValueError("invalid_dataset")
    requests = data.get("requests")
    if not isinstance(requests, dict) or not requests or set(requests) - {"classify", "extract", "explain"}:
        raise ValueError("invalid_requests")
    for api, payload in requests.items():
        if not isinstance(payload, dict):
            raise ValueError("invalid_payload")
        if api == "classify":
            assert isinstance(payload["document_id"], str)
            assert isinstance(payload["pages"], list)
            for page in payload["pages"]:
                assert type(page["page"]) is int and isinstance(page["text"], str)
        else:
            assert isinstance(payload["analysis_id"], str)
            if api == "extract":
                assert isinstance(payload["check_items"], list)
                assert all(type(i) is int and 1 <= i <= 6 for i in payload["check_items"])
                assert isinstance(payload["clauses"], list)
                ids = []
                for clause in payload["clauses"]:
                    assert nonblank(clause["clause_id"]) and isinstance(clause["text"], str)
                    assert type(clause["page"]) is int and clause["page"] >= 1
                    ids.append(clause["clause_id"])
            else:
                assert isinstance(payload["items"], list)
                ids = []
                for item in payload["items"]:
                    assert nonblank(item["item_id"]) and nonblank(item["quote"])
                    assert type(item["check_item"]) is int and 1 <= item["check_item"] <= 6
                    ids.append(item["item_id"])
            assert len(ids) == len(set(ids))
    return data


def print_review_details(dataset, payload, data):
    """Terminal-only diagnostics. Never attach these fields to the report."""
    if dataset.get("source_kind") != "synthetic" or not isinstance(data, dict):
        return
    cards = data.get("cards")
    if not isinstance(cards, list):
        return
    sources = {item["item_id"]: item for item in payload["items"]}
    for index, card in enumerate(cards):
        if not isinstance(card, dict) or card.get("review") != "fail":
            continue
        item_id = card.get("item_id")
        source = sources.get(item_id) if isinstance(item_id, str) else None
        if source is None:
            continue
        detail = {"result_index": index, "check_item": source["check_item"],
                  "title": card.get("title"), "explanation": card.get("explanation"),
                  "review_reason": card.get("review_reason"), "quote": source["quote"]}
        # JSON escaping keeps embedded newlines/control characters in a safe display.
        print("가상 데이터 의미 검토 실패 상세 (터미널 전용):")
        print(json.dumps(detail, ensure_ascii=False, indent=2))


def run_tests(client, dataset, apis, show_review_details=False):
    if show_review_details and dataset.get("source_kind") != "synthetic":
        raise ValueError("review_details_require_synthetic_data")
    report = {"executed_at": datetime.now(KST).isoformat(),
              "source_kind": dataset["source_kind"],
              "real_policy_test": "executed" if dataset["source_kind"] == "real_policy" else "not_performed",
              "model": None, "model_status": "unavailable", "results": []}
    # Model is the only server-provided text persisted, with a restricted format.
    try:
        health = client.get("/health")
        model = health.json().get("model") if health.status_code == 200 else None
        if isinstance(model, str) and re.fullmatch(r"(?:models/)?gemini-[A-Za-z0-9._-]{1,100}", model):
            report["model"], report["model_status"] = model, "reported_by_health"
    except Exception:
        pass
    for api in apis:
        record = {"api": api, "http_status": None, "elapsed_ms": None}
        started = time.monotonic()
        try:
            response = client.post("/ai/" + api, json=dataset["requests"][api])
            record["http_status"] = response.status_code
            record["elapsed_ms"] = round((time.monotonic() - started) * 1000)
            if response.status_code != 200:
                record.update(valid=False, failures=["http_error_" + str(response.status_code)], metrics={})
            else:
                try:
                    data = response.json()
                    record.update(validate_response(api, dataset["requests"][api], data))
                    if show_review_details and api == "explain":
                        print_review_details(dataset, dataset["requests"][api], data)
                except Exception:
                    record.update(valid=False, failures=["invalid_response_json_or_structure"], metrics={})
        except Exception as exc:
            category = "timeout" if isinstance(exc, httpx.TimeoutException) else "request_failed"
            record.update(valid=False, failures=[category], metrics={})
        record["elapsed_ms"] = record["elapsed_ms"] or round((time.monotonic() - started) * 1000)
        report["results"].append(record)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "test_data" / "synthetic_policy.json")
    parser.add_argument("--server", default="http://127.0.0.1:8000")
    parser.add_argument("--api", choices=["all", "classify", "extract", "explain"], default="all")
    parser.add_argument("--run", action="store_true", help="Explicitly permit server/Gemini calls")
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--show-review-details", action="store_true",
                        help="Print failed explain cards to terminal; synthetic data only")
    args = parser.parse_args()
    try:
        dataset = load_data(args.data)
        apis = list(dataset["requests"]) if args.api == "all" else [args.api]
        if any(api not in dataset["requests"] for api in apis) or args.timeout <= 0:
            raise ValueError("invalid_options")
    except Exception:
        print("입력 JSON 또는 실행 옵션이 올바르지 않습니다. 입력 내용은 출력하지 않습니다.")
        return 2
    if args.show_review_details and dataset["source_kind"] != "synthetic":
        print("--show-review-details는 source_kind=synthetic 데이터에서만 허용됩니다. API 미호출.")
        return 2
    if not args.run:
        print("입력 JSON 검사 통과. API 미호출. 실제 실행은 --run 옵션이 필요합니다.")
        print("실제 약관 테스트: 미실시")
        return 0
    with httpx.Client(base_url=args.server, timeout=args.timeout, follow_redirects=False) as client:
        report = run_tests(client, dataset, apis, show_review_details=args.show_review_details)
    folder = ROOT / "test_results"
    folder.mkdir(exist_ok=True)
    output = folder / ("policy_test_" + datetime.now(KST).strftime("%Y%m%d_%H%M%S_%f") + ".json")
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("실제 약관 테스트: " + ("미실시 (가상 데이터)" if dataset["source_kind"] == "synthetic" else "실행됨"))
    print("모델: " + (report["model"] or "확인 불가"))
    for result in report["results"]:
        print(f"{result['api']}: HTTP {result['http_status']} / {result['elapsed_ms']}ms / "
              + ("통과" if result["valid"] else "실패: " + ", ".join(result["failures"])))
        print(json.dumps(result["metrics"], ensure_ascii=False))
    print("숫자 검사는 참고용이며 최종 숫자 검증을 대체하지 않습니다.")
    print("결과 파일: " + str(output))
    return 0 if all(r["valid"] for r in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
