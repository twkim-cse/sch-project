"""Offline tests: dotenv loading and the Gemini client are mocked."""
import ast
import json
import os
from pathlib import Path
import runpy
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import httpx
from fastapi.testclient import TestClient
from google.genai import errors


class ExplainTests(unittest.TestCase):
    def setUp(self):
        with patch("dotenv.load_dotenv"), patch.dict(os.environ, {"GEMINI_API_KEY": "", "GEMINI_MODEL": "test-model"}):
            self.ns = runpy.run_path(str(Path(__file__).with_name("app.py")))
        # Extract backoff jitter is deterministic in offline fixtures only.
        jitter_patch = patch("extract_resilience.random.uniform", return_value=0)
        jitter_patch.start()
        self.addCleanup(jitter_patch.stop)
        self.mock = MagicMock()
        self.ns["explain_document"].__globals__["client"] = self.mock
        self.mock.models.list.return_value = [SimpleNamespace(name="models/test-model", supported_actions=["generateContent"])]
        self.api = TestClient(self.ns["app"])
        sleep_patch = patch("time.sleep")
        self.sleep = sleep_patch.start()
        self.addCleanup(sleep_patch.stop)
        self.clock = 0.0
        self.sleep.side_effect = lambda seconds: setattr(self, "clock", self.clock + seconds)
        clock_patch = patch("time.monotonic", side_effect=lambda: self.clock)
        clock_patch.start()
        self.addCleanup(clock_patch.stop)
        self.body = {"analysis_id": "test-001", "items": [{
            "item_id": "c-017", "check_item": 2, "section": "암진단 특별약관",
            "article_title": "암보장개시일", "quote": "암 보장은 90일이 지난 다음 날부터 시작합니다.", "retry_hint": None,
        }]}

    def draft(self, item_id="c-017"):
        return {"item_id": item_id, "title": "암 보장에는 대기 기간이 있습니다.",
                "explanation": "암 보장은 90일이 지난 다음 날부터 시작합니다. 그 전에는 이 보장이 적용되지 않을 수 있습니다."}

    def review(self, outcome="pass", item_id="c-017"):
        return {"item_id": item_id, "review": outcome, "review_reason": None if outcome == "pass" else "중요한 조건이 누락되었습니다."}

    def responses(self, *values):
        self.mock.models.generate_content.side_effect = [SimpleNamespace(text=json.dumps(v, ensure_ascii=False)) for v in values]

    def post(self):
        return self.api.post("/ai/explain", json=self.body)

    def test_syntax_and_pass(self):
        ast.parse(Path(__file__).with_name("app.py").read_text(encoding="utf-8-sig"))
        self.responses(self.draft(), self.review())
        response = self.post()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["cards"][0]["review"], "pass")
        self.assertIsNone(response.json()["cards"][0]["review_reason"])
        self.ns["ExplainResponse"].model_validate(response.json())
        calls = self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 2)
        self.sleep.assert_not_called()
        self.assertNotEqual(calls[0].kwargs["config"].system_instruction, calls[1].kwargs["config"].system_instruction)
        for call in calls:
            self.assertEqual(call.kwargs["model"], "test-model")
            self.assertEqual(call.kwargs["config"].response_mime_type, "application/json")
            self.assertIsNone(call.kwargs["config"].response_schema)

    def f13_bad_cases(self):
        # Human-labelled negative examples. Mock reviews do not test AI detection.
        return [
            ("가입 권유", "반드시 가입하세요", "이 상품에 반드시 가입하세요. 지금 가입하는 것이 좋습니다."),
            ("지급 보장", "보험금이 무조건 지급됩니다", "보험금이 무조건 지급됩니다. 누구나 지급받을 수 있습니다."),
            ("상품 추천", "가장 좋은 보험입니다", "가장 좋은 보험입니다. 이 상품을 선택하는 것을 추천합니다."),
            ("원문에 없는 보장 확정", "모든 질병이 보장됩니다", "암 이외의 모든 질병도 보장됩니다. 보험금은 확실히 받을 수 있습니다."),
            ("원문에 없는 인정 조건", "심사를 거쳐야 인정됩니다", "별도 심사를 거쳐야 암으로 인정됩니다. 추가 확인서를 반드시 제출해야 합니다."),
        ]

    def test_f13_mock_rejection_then_neutral_regeneration(self):
        for label, title, explanation in self.f13_bad_cases():
            with self.subTest(label=label):
                self.mock.models.generate_content.reset_mock()
                bad = dict(self.draft(), title=title, explanation=explanation)
                rejection = dict(self.review('fail'), review_reason=label + ': 원문 근거 없는 권유 또는 판단')
                self.responses(bad, rejection, self.draft(), self.review())
                response = self.post()
                self.assertEqual(response.status_code, 200)
                card = response.json()['cards'][0]
                self.assertEqual(card['review'], 'pass')
                self.assertEqual(card['explanation'], self.draft()['explanation'])
                calls = self.mock.models.generate_content.call_args_list
                self.assertEqual(len(calls), 4)
                review_input = json.loads(calls[1].kwargs['contents'])
                self.assertEqual(review_input['candidate'], bad)
                self.assertEqual(review_input['source']['quote'], self.body['items'][0]['quote'])
                correction_input = json.loads(calls[2].kwargs['contents'])
                self.assertEqual(correction_input['review_feedback'], rejection['review_reason'])

    def test_f13_persistent_bad_content_returns_fail_but_keeps_text(self):
        # Documents the current exposure risk: failed text is still in the card.
        for label, title, explanation in self.f13_bad_cases():
            with self.subTest(label=label):
                self.mock.models.generate_content.reset_mock()
                bad = dict(self.draft(), title=title, explanation=explanation)
                rejection = dict(self.review('fail'), review_reason=label)
                self.responses(bad, rejection, bad, rejection)
                response = self.post()
                self.assertEqual(response.status_code, 200)
                card = response.json()['cards'][0]
                self.assertEqual(card['review'], 'fail')
                self.assertEqual(card['review_reason'], label)
                self.assertEqual(card['title'], title)
                self.assertEqual(card['explanation'], explanation)
                self.assertEqual(self.mock.models.generate_content.call_count, 4)

    def test_f13_neutral_and_source_based_restrictions_not_word_blocked(self):
        examples = [
            (self.body['items'][0]['quote'], self.draft()),
            ('계약자는 청약서에서 질문한 사항을 반드시 사실대로 알려야 합니다.',
             dict(self.draft(), title='질문한 사항은 사실대로 알려야 합니다.', explanation='계약자는 청약서에서 질문한 사항을 반드시 사실대로 알려야 합니다. 이는 약관에 명시된 알릴 의무입니다.')),
            ('계약일부터 90일 동안 암 진단 보험금을 지급하지 않습니다.',
             dict(self.draft(), title='계약일부터 90일 동안 암 진단 보험금을 지급하지 않습니다.', explanation='계약일부터 90일 동안은 암 진단 보험금을 지급하지 않습니다. 이 기간에는 해당 암 진단 보험금이 지급되지 않는다는 뜻입니다.')),
        ]
        for quote, draft in examples:
            with self.subTest(quote=quote):
                self.mock.models.generate_content.reset_mock()
                self.body['items'][0]['quote'] = quote
                self.responses(draft, self.review())
                card = self.post().json()['cards'][0]
                self.assertEqual(card['review'], 'pass')
                self.assertEqual(card['explanation'], draft['explanation'])
                self.assertEqual(self.mock.models.generate_content.call_count, 2)

    def test_f13_mock_false_pass_is_blocked_without_extra_calls(self):
        bad = dict(self.draft(), title='반드시 가입하세요', explanation='보험금이 무조건 지급됩니다. 가장 좋은 보험입니다.')
        self.responses(bad, self.review())
        card = self.post().json()['cards'][0]
        self.assertEqual(card['review'], 'fail')
        self.assertTrue(card['review_reason'].startswith('안전 검사:'))
        self.assertEqual(card['explanation'], bad['explanation'])
        self.assertEqual(self.mock.models.generate_content.call_count, 2)

    def test_f13_final_safety_individual_positive_cases(self):
        for text in ('반드시 가입하세요', '보험금이 무조건 지급됩니다', '가장 좋은 보험입니다',
                     '다른 상품보다 유리합니다', '보험금을 확실히 받을 수 있습니다'):
            with self.subTest(text=text):
                self.mock.models.generate_content.reset_mock()
                draft = dict(self.draft(), title=text, explanation='약관 내용을 설명합니다.')
                self.responses(draft, self.review())
                card = self.post().json()['cards'][0]
                self.assertEqual(card['review'], 'fail')
                self.assertEqual(card['title'], text)
                self.assertEqual(card['explanation'], draft['explanation'])
                self.assertEqual(self.mock.models.generate_content.call_count, 2)

    def test_f13_negation_and_source_quotation_allowed(self):
        for text in ('보험금이 무조건 지급되는 것은 아닙니다.', '가장 좋은 보험이 아닙니다.',
                     '반드시 가입하라는 뜻이 아닙니다.'):
            with self.subTest(text=text):
                self.responses(dict(self.draft(), explanation=text), self.review())
                self.assertEqual(self.post().json()['cards'][0]['review'], 'pass')
        self.body['items'][0]['quote'] = '이 안내문은 "반드시 가입하세요"라는 표현을 사용하지 않습니다.'
        draft = dict(self.draft(), explanation='원문은 "반드시 가입하세요"라는 표현을 사용하지 않습니다.')
        self.responses(draft, self.review())
        self.assertEqual(self.post().json()['cards'][0]['review'], 'pass')

    def test_f13_later_negation_does_not_cancel_violation(self):
        self.responses(dict(self.draft(), explanation='반드시 가입하세요. 보험금이 무조건 지급되는 것은 아닙니다.'), self.review())
        self.assertEqual(self.post().json()['cards'][0]['review'], 'fail')

    def test_f13_preserves_existing_fail_reason(self):
        bad = dict(self.draft(), title='반드시 가입하세요')
        rejection = dict(self.review('fail'), review_reason='기존 의미 검토 실패 이유')
        self.responses(bad, rejection, bad, rejection)
        card = self.post().json()['cards'][0]
        self.assertEqual(card['review'], 'fail')
        self.assertEqual(card['review_reason'], rejection['review_reason'])
        self.assertEqual(card['title'], bad['title'])

    def test_fail_regenerate_pass(self):
        self.responses(self.draft(), self.review("fail"), self.draft(), self.review())
        response = self.post()
        self.assertEqual(response.json()["cards"][0]["review"], "pass")
        calls = self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 4)
        payload = json.loads(calls[2].kwargs["contents"])
        self.assertEqual(payload["review_feedback"], self.review("fail")["review_reason"])
        self.assertEqual(payload["previous_draft"], self.draft())

    def test_fail_twice(self):
        self.responses(self.draft(), self.review("fail"), self.draft(), self.review("fail"))
        response = self.post()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["cards"][0]["review"], "fail")
        self.assertTrue(response.json()["cards"][0]["review_reason"])
        self.assertEqual(self.mock.models.generate_content.call_count, 4)

    def test_definition_unsupported_condition_regression(self):
        case = json.loads(Path(__file__).with_name("test_data").joinpath("explain_definition_regression.json").read_text(encoding="utf-8"))
        self.body["items"] = [case["item"]]
        # Review is mocked: this checks rejection propagation and correction feedback,
        # not the real model's ability to detect a semantic error.
        self.responses(case["inappropriate_draft"], case["expected_review"],
                       case["corrected_draft_example"], case["expected_review"])
        response = self.post()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["cards"][0]["review"], "fail")
        self.assertEqual(response.json()["cards"][0]["review_reason"], case["expected_review"]["review_reason"])
        calls = self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 4)
        for index in (0, 2):
            instruction = calls[index].kwargs["config"].system_instruction
            self.assertIn("원문에 없는 지급 여부", instruction)
            self.assertIn("절단·절제", instruction)
            self.assertIn("단순한 용어 정의", instruction)
        review_payload = json.loads(calls[1].kwargs["contents"])
        self.assertEqual(review_payload["candidate"], case["inappropriate_draft"])
        self.assertEqual(review_payload["source"]["quote"], case["item"]["quote"])
        regeneration_payload = json.loads(calls[2].kwargs["contents"])
        self.assertEqual(regeneration_payload["review_feedback"], case["expected_review"]["review_reason"])
        self.assertEqual(regeneration_payload["previous_draft"], case["inappropriate_draft"])

    def test_retry_hint(self):
        self.body["items"][0]["retry_hint"] = {"unmatched_numbers": ["60일"]}
        self.responses(self.draft(), self.review())
        self.assertEqual(self.post().status_code, 200)
        payload = json.loads(self.mock.models.generate_content.call_args_list[0].kwargs["contents"])
        self.assertEqual(payload["source"]["retry_hint"], {"unmatched_numbers": ["60일"]})

    def test_multiple_items_and_empty(self):
        second = dict(self.body["items"][0], item_id="second")
        self.body["items"].append(second)
        self.responses(self.draft(), self.review(), self.draft("second"), self.review(item_id="second"))
        self.assertEqual([x["item_id"] for x in self.post().json()["cards"]], ["c-017", "second"])
        self.body["items"] = []
        self.assertEqual(self.post().json(), {"cards": []})

    def test_invalid_requests(self):
        for value in [0, 7, True, "2"]:
            self.body["items"][0]["check_item"] = value
            self.assertEqual(self.post().status_code, 422)
        self.body["items"][0]["check_item"] = 2
        self.body["items"].append(dict(self.body["items"][0]))
        self.assertEqual(self.post().status_code, 422)
        self.mock.models.generate_content.assert_not_called()

    def test_bad_ai_responses(self):
        for output in [self.draft("wrong"), dict(self.draft(), title=" "), {"cards": []}]:
            self.responses(output)
            with patch.object(self.ns["logger"], "error"):
                self.assertEqual(self.post().status_code, 502)
        for review in [self.review(item_id="wrong"), dict(self.review(), review_reason="unexpected"), dict(self.review("fail"), review_reason=None), dict(self.review(), review="unknown")]:
            self.responses(self.draft(), review)
            with patch.object(self.ns["logger"], "error"):
                self.assertEqual(self.post().status_code, 502)

    def test_safe_validation_diagnostics(self):
        cases = [
            ('{"SECRET":', '<root>', 'json_invalid', True),
            (json.dumps({'item_id': 'c-017', 'explanation': 'SECRET'}), 'title', 'missing', False),
            (json.dumps(dict(self.draft(), title={'SECRET': 'SECRET'})), 'title', 'string_type', False),
            (json.dumps(dict(self.draft(), SECRET='SECRET')), '<redacted>', 'extra_forbidden', False),
            (json.dumps(self.draft('SECRET')), 'item_id', 'exact_match_failed', False),
        ]
        for text, field, kind, parse_error in cases:
            self.mock.models.generate_content.side_effect = [SimpleNamespace(text=text)]
            with patch.object(self.ns['logger'], 'error') as log:
                response = self.post()
                self.assertEqual(response.status_code, 502)
                rendered = [call.args[0] % call.args[1:] for call in log.call_args_list]
                detail = next(line for line in rendered if 'Gemini explain validation ' in line)
                self.assertIn('stage=explanation_generation', detail)
                self.assertIn('field_path=' + field, detail)
                self.assertIn('validation_type=' + kind, detail)
                self.assertIn('json_parse_error=' + str(parse_error), detail)
                self.assertNotIn('SECRET', '\n'.join(rendered))
                self.assertNotIn('SECRET', response.text)

    def test_review_validation_diagnostics(self):
        self.responses(self.draft(), dict(self.review('fail'), review_reason=None))
        with patch.object(self.ns['logger'], 'error') as log:
            self.assertEqual(self.post().status_code, 502)
            rendered = [call.args[0] % call.args[1:] for call in log.call_args_list]
            self.assertTrue(any('stage=meaning_review' in line and 'validation_type=value_error' in line for line in rendered))

    def test_empty_and_non_object_diagnostics(self):
        for text, shape in [(None, 'absent'), ('', 'empty'), ('  ', 'empty'), ('[]', 'array'), ('null', 'scalar')]:
            self.mock.models.generate_content.side_effect = [SimpleNamespace(text=text)]
            with patch.object(self.ns['logger'], 'error') as log:
                self.assertEqual(self.post().status_code, 502)
                lines = [c.args[0] % c.args[1:] for c in log.call_args_list]
                self.assertTrue(any('response_shape=' + shape in line for line in lines))

    def test_non_object_format_recovery(self):
        for value in ([self.draft()], None, 'wrong', 123, True):
            self.mock.models.generate_content.reset_mock()
            self.responses(value, self.draft(), self.review())
            with patch.object(self.ns['logger'], 'error'), patch.object(self.ns['logger'], 'warning'):
                response = self.post()
            self.assertEqual(response.status_code, 200)
            calls = self.mock.models.generate_content.call_args_list
            self.assertEqual(len(calls), 3)
            self.assertIn('최상위 자료형이 잘못', calls[1].kwargs['config'].system_instruction)
            self.assertEqual(calls[0].kwargs['contents'], calls[1].kwargs['contents'])

    def test_repeated_array_stops_at_one_recovery(self):
        self.responses([self.draft()], [self.draft()])
        with patch.object(self.ns['logger'], 'error'), patch.object(self.ns['logger'], 'warning'):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.models.generate_content.call_count, 2)

    def test_format_recovery_and_semantic_regeneration_bounded(self):
        self.responses([], self.draft(), self.review('fail'), [], self.draft(), self.review())
        with patch.object(self.ns['logger'], 'error'), patch.object(self.ns['logger'], 'warning'):
            self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.models.generate_content.call_count, 6)

    def test_no_format_recovery_for_field_errors_or_id(self):
        for draft in ({'item_id': 'c-017'}, self.draft('wrong')):
            self.mock.models.generate_content.reset_mock()
            self.responses(draft)
            with patch.object(self.ns['logger'], 'error'):
                self.assertEqual(self.post().status_code, 502)
            self.assertEqual(self.mock.models.generate_content.call_count, 1)

    def test_prevalidation_value_error_diagnostics(self):
        class BrokenResponse:
            @property
            def text(self):
                raise ValueError('SECRET_RESPONSE')
        for response, expected in [(ValueError('SECRET_SDK'), 'sdk_value_error'), (BrokenResponse(), 'response_text_access')]:
            self.mock.models.generate_content.side_effect = [response]
            with patch.object(self.ns['logger'], 'error') as log:
                result = self.post()
                self.assertEqual(result.status_code, 502)
                lines = [c.args[0] % c.args[1:] for c in log.call_args_list]
                self.assertTrue(any('error_kind=' + expected in line for line in lines))
                self.assertNotIn('SECRET', '\n'.join(lines))
                self.assertNotIn('SECRET', result.text)

    def test_pipeline_value_error_fallback(self):
        self.mock.models.list.side_effect = ValueError('SECRET_LOOKUP')
        with patch.object(self.ns['logger'], 'error') as log:
            self.assertEqual(self.post().status_code, 502)
            lines = [c.args[0] % c.args[1:] for c in log.call_args_list]
            self.assertTrue(any('stage=model_lookup' in line and 'operation=request_pipeline' in line for line in lines))
            self.assertNotIn('SECRET', '\n'.join(lines))

    def test_errors_at_generation_and_review(self):
        for exc in [errors.ClientError(403, {"error": {"message": "SECRET"}}), errors.ServerError(504, {"error": {"message": "SECRET"}}), httpx.ReadTimeout("SECRET"), ValueError("SECRET")]:
            for during_review in (False, True):
                sequence = [exc] * (3 if getattr(exc, 'code', None) == 504 else 1)
                if during_review:
                    sequence.insert(0, SimpleNamespace(text=json.dumps(self.draft())))
                self.mock.models.generate_content.side_effect = sequence
                with patch.object(self.ns["logger"], "error") as log:
                    response = self.post()
                    self.assertEqual(response.status_code, 502)
                    self.assertNotIn("SECRET", response.text)
                    self.assertNotIn("SECRET", str(log.call_args))

    def test_existing_endpoints(self):
        self.assertEqual(self.api.get("/health").status_code, 200)
        self.responses({"doc_type": "OTHER", "product_name": None, "version_label": None})
        self.assertEqual(self.api.post("/ai/classify", json={"document_id": "test", "pages": []}).json()["doc_type"], "OTHER")
        quote = "보험금을 지급하지 않습니다."
        row = {"check_item": 1, "clause_id": "c", "quote": quote}
        self.responses({"extractions": [row, row, dict(row, quote="변경된 인용")]})
        result = self.api.post("/ai/extract", json={"analysis_id": "test", "check_items": [1], "clauses": [{"clause_id": "c", "page": 1, "text": quote}]})
        self.assertEqual(result.json(), {"extractions": [row]})
        self.assertIsNone(self.mock.models.generate_content.call_args.kwargs["config"].response_schema)

    def unavailable(self):
        return errors.ServerError(503, {"error": {"status": "UNAVAILABLE", "message": "SECRET"}})

    def exhausted(self, response=None, details=None):
        return errors.ClientError(429, {"error": {"status": "RESOURCE_EXHAUSTED", "message": "SECRET", "details": details or []}}, response=response)

    def test_rolling_window_limit_and_required_wait(self):
        limiter = self.ns["ExplainCallLimiter"]()
        starts = []
        for _ in range(35):
            limiter.acquire()
            starts.append(self.clock)
        for start in starts:
            self.assertLessEqual(sum(start - 60 < t <= start for t in starts), 10)
        self.assertEqual(starts[:10], [0] * 10)
        self.assertEqual(starts[10], 60)
        self.assertEqual(self.sleep.call_count, 3)
        self.sleep.assert_any_call(60)

    def test_only_remaining_window_wait(self):
        limiter = self.ns["ExplainCallLimiter"]()
        for _ in range(10):
            limiter.acquire()
        self.clock = 17
        limiter.acquire()
        self.sleep.assert_called_once_with(43)

    def test_429_retry_after_header(self):
        response = httpx.Response(429, headers={"Retry-After": "7"})
        self.mock.models.generate_content.side_effect = [self.exhausted(response),
            SimpleNamespace(text=json.dumps(self.draft())), SimpleNamespace(text=json.dumps(self.review()))]
        with patch.object(self.ns["logger"], "warning") as warning:
            self.assertEqual(self.post().status_code, 200)
            self.assertNotIn("SECRET", str(warning.call_args_list))
        self.sleep.assert_called_once_with(7)
        self.assertEqual(len(self.ns["explain_call_limiter"].calls), 3)

    def test_429_retry_info(self):
        exc = self.exhausted(details=[{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "3.5s"}])
        self.assertEqual(self.ns["explain_429_delay"](exc), 3.5)
        response = httpx.Response(429, headers={"Retry-After": "11"})
        self.assertEqual(self.ns["explain_429_delay"](self.exhausted(response, exc.details['error']['details'])), 11)

    def test_persistent_429_default_wait_then_502(self):
        self.mock.models.generate_content.side_effect = [self.exhausted(), self.exhausted()]
        with patch.object(self.ns["logger"], "warning"), patch.object(self.ns["logger"], "error"):
            self.assertEqual(self.post().status_code, 502)
        self.sleep.assert_called_once_with(60)
        self.assertEqual(self.mock.models.generate_content.call_count, 2)

    def test_generation_review_share_limit_across_requests(self):
        self.responses(*[value for _ in range(6) for value in (self.draft(), self.review())])
        for _ in range(6):
            self.assertEqual(self.post().status_code, 200)
        self.assertEqual(self.mock.models.generate_content.call_count, 12)
        self.sleep.assert_called_once_with(60)

    def test_503_attempts_consume_window(self):
        limiter = self.ns["explain_call_limiter"]
        for _ in range(9):
            limiter.acquire()
        self.mock.models.generate_content.side_effect = [self.unavailable(),
            SimpleNamespace(text=json.dumps(self.draft())), SimpleNamespace(text=json.dumps(self.review()))]
        with patch.object(self.ns["logger"], "warning"):
            self.assertEqual(self.post().status_code, 200)
        self.assertEqual([call.args[0] for call in self.sleep.call_args_list], [2, 58])

    def test_mixed_transient_errors_respect_request_retry_budget(self):
        self.mock.models.generate_content.side_effect = [self.exhausted(), self.unavailable(), self.unavailable()]
        with patch.object(self.ns["logger"], "warning"), patch.object(self.ns["logger"], "error"):
            self.assertEqual(self.post().status_code, 502)
        self.assertEqual(self.mock.models.generate_content.call_count, 3)

    def test_generation_503_retry_success(self):
        self.mock.models.generate_content.side_effect = [
            self.unavailable(), SimpleNamespace(text=json.dumps(self.draft())),
            SimpleNamespace(text=json.dumps(self.review())),
        ]
        with patch.object(self.ns["logger"], "warning") as warning, patch.object(self.ns["logger"], "info") as info:
            self.assertEqual(self.post().status_code, 200)
            self.assertNotIn("SECRET", str(warning.call_args))
            self.assertIn("retry_succeeded", str(info.call_args_list))
        self.sleep.assert_called_once_with(2)
        calls = self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0].kwargs, calls[1].kwargs)

    def test_review_503_retry_success(self):
        self.mock.models.generate_content.side_effect = [
            SimpleNamespace(text=json.dumps(self.draft())), self.unavailable(),
            SimpleNamespace(text=json.dumps(self.review())),
        ]
        with patch.object(self.ns["logger"], "warning"):
            self.assertEqual(self.post().status_code, 200)
        self.sleep.assert_called_once_with(2)
        calls = self.mock.models.generate_content.call_args_list
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[1].kwargs, calls[2].kwargs)
        self.assertIs(calls[1].kwargs["config"].system_instruction, self.ns["MEANING_REVIEW_INSTRUCTION"])

    def test_503_three_times_returns_502(self):
        for during_review in (False, True):
            self.mock.models.generate_content.reset_mock()
            self.sleep.reset_mock()
            sequence = [self.unavailable(), self.unavailable(), self.unavailable()]
            if during_review:
                sequence.insert(0, SimpleNamespace(text=json.dumps(self.draft())))
            self.mock.models.generate_content.side_effect = sequence
            with patch.object(self.ns["logger"], "warning"), patch.object(self.ns["logger"], "error") as log:
                response = self.post()
                self.assertEqual(response.status_code, 502)
                self.assertIn("final_failure", str(log.call_args_list))
                self.assertNotIn("SECRET", str(log.call_args_list))
            self.assertEqual(self.mock.models.generate_content.call_count, 4 if during_review else 3)
            self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [2, 4])

    def test_other_http_codes_not_retried(self):
        for code in (400, 401, 403):
            self.mock.models.generate_content.reset_mock()
            self.mock.models.generate_content.side_effect = errors.ClientError(code, {"error": {"message": "SECRET"}})
            with patch.object(self.ns["logger"], "error"):
                self.assertEqual(self.post().status_code, 502)
            self.assertEqual(self.mock.models.generate_content.call_count, 1)
        self.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
