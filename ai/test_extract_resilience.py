"""No network/key access: bounded 503 recovery, headers, cooldown and real SDK transport."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import httpx
from google import genai
from google.genai import errors, types
import extract_resilience as resilience
import test_explain as fixtures


class ExtractResilienceTests(unittest.TestCase):
    def setUp(self):
        self.h=fixtures.ExplainTests();self.h.setUp();self.addCleanup(self.h.doCleanups)
        self.ns=self.h.ns;self.generate=self.h.mock.models.generate_content
        self.body={'analysis_id':'synthetic','check_items':[1],
                   'clauses':[{'clause_id':'c1','page':1,'text':'SECRET 보험금을 지급하지 않습니다.'}]}
    def error(self,header=None):
        response=httpx.Response(503,headers={} if header is None else {'Retry-After':header})
        return errors.ServerError(503,{'error':{'status':'UNAVAILABLE','message':'SECRET'}},response=response)
    def success(self):return SimpleNamespace(text='{"extractions":[]}')
    def post(self):
        with patch.object(self.ns['logger'],'error'),patch.object(self.ns['logger'],'warning'):
            return self.h.api.post('/ai/extract',json=self.body)

    def test_third_attempt_can_recover_without_more_calls(self):
        self.generate.side_effect=[self.error(),self.error(),self.success()]
        self.assertEqual(self.post().status_code,200)
        self.assertEqual(self.generate.call_count,3)
        self.assertEqual([c.args[0] for c in self.h.sleep.call_args_list],[2,4])
        for call in self.generate.call_args_list:
            options=call.kwargs['config'].http_options
            self.assertEqual(options.retry_options.attempts,1)
            self.assertLessEqual(options.timeout,90_000)

    def test_persistent_503_sets_cooldown_and_no_metadata_or_generation_during_it(self):
        self.generate.side_effect=self.error()
        first=self.post();self.assertEqual(first.status_code,502)
        self.assertEqual(first.headers['Retry-After'],'60')
        self.assertEqual(self.generate.call_count,3)
        lookups=self.h.mock.models.list.call_count
        second=self.post();self.assertEqual(second.status_code,502)
        self.assertEqual(self.generate.call_count,3)
        self.assertEqual(self.h.mock.models.list.call_count,lookups)
        self.h.clock+=61;self.generate.side_effect=lambda **kw:self.success()
        self.assertEqual(self.post().status_code,200)

    def test_long_retry_after_defers_instead_of_retrying_early(self):
        self.generate.side_effect=self.error('120')
        response=self.post()
        self.assertEqual(response.status_code,502)
        self.assertEqual(response.headers['Retry-After'],'120')
        self.assertEqual(self.generate.call_count,1);self.h.sleep.assert_not_called()

    def test_short_retry_after_is_respected(self):
        self.generate.side_effect=[self.error('10'),self.success()]
        self.assertEqual(self.post().status_code,200)
        self.h.sleep.assert_called_once_with(10)

    def test_deadline_too_short_for_503_wait_defers_without_retrying_early(self):
        self.generate.side_effect=self.error()
        with patch.object(resilience,'CALL_SECONDS',1):response=self.post()
        self.assertEqual(response.status_code,502)
        self.assertEqual(response.headers['Retry-After'],'60')
        self.assertEqual(self.generate.call_count,1);self.h.sleep.assert_not_called()

    def test_jitter_is_bounded_and_exponential(self):
        with patch.object(resilience.random,'uniform',return_value=.75):
            self.assertEqual(resilience.delay(self.error(),0),2.75)
            self.assertEqual(resilience.delay(self.error(),1),4.75)

    def test_invalid_retry_headers_are_ignored_without_printing(self):
        for header in ['SECRET','nan','inf','-1']:
            self.assertIsNone(resilience.retry_after(self.error(header)))

    def test_retry_info_is_respected(self):
        exc=errors.ServerError(503,{'error':{'details':[{'@type':'type.googleapis.com/google.rpc.RetryInfo','retryDelay':'30s'}]}})
        self.assertEqual(resilience.retry_after(exc),30)

    def test_unrelated_client_error_is_not_retried_or_cooled(self):
        self.generate.side_effect=errors.ClientError(400,{'error':{'message':'SECRET'}})
        self.assertEqual(self.post().status_code,502)
        self.assertEqual(self.generate.call_count,1);self.h.sleep.assert_not_called()
        self.assertEqual(self.ns['extract_service_cooldown'].remaining(),0)

    def test_logs_and_error_response_never_contain_source_or_exception_body(self):
        self.generate.side_effect=self.error()
        with patch.object(self.ns['logger'],'warning') as warning,patch.object(self.ns['logger'],'error') as error:
            response=self.h.api.post('/ai/extract',json=self.body)
        self.assertNotIn('SECRET',str(warning.call_args_list)+str(error.call_args_list)+response.text)
        self.assertEqual(set(response.json()),{'code','message'})

    def test_sdk_internal_retries_disabled_actual_http_count_is_three(self):
        requests=[]
        def transport(request):
            requests.append(1)
            return httpx.Response(503,json={'error':{'status':'UNAVAILABLE','message':'SECRET'}})
        sdk=genai.Client(api_key='offline-placeholder',vertexai=False,
            http_options=types.HttpOptions(client_args={'transport':httpx.MockTransport(transport),'trust_env':False}))
        self.addCleanup(sdk.close)
        globals_=self.ns['extract_document'].__globals__
        with patch.dict(globals_,{'client':sdk,'verify_model':lambda **kw:None}):
            self.assertEqual(self.post().status_code,502)
        self.assertEqual(len(requests),3)

    def test_call_time_budget_also_bounds_limiter_wait(self):
        def acquire(**kw):self.h.clock+=301
        with patch.object(self.ns['explain_call_limiter'],'acquire',side_effect=acquire):
            self.assertEqual(self.post().status_code,502)
        self.generate.assert_not_called()

    def test_input_413_is_not_hidden_by_cooldown(self):
        self.ns['extract_service_cooldown'].defer(60)
        self.body['clauses'][0]['text']='SECRET 보험금을 지급하지 않습니다. '*2000
        self.assertEqual(self.post().status_code,413)
        self.generate.assert_not_called()

    def test_bulk_http_and_admission_respect_cooldown_even_when_wrapped_in_502(self):
        from bulk_http import HttpPartSender
        from bulk_parallel import Admission
        from bulk_extract import BulkHttpError
        for status in (502,503,429):
            with self.subTest(status=status):
                def transport(request):return httpx.Response(status,json={'code':'GEMINI_API_ERROR'},headers={'Retry-After':'120'})
                with HttpPartSender('http://offline',lambda:httpx.MockTransport(transport)) as sender:
                    with self.assertRaises(BulkHttpError) as captured:sender({},1)
                self.assertEqual(captured.exception.retry_after_seconds,120)
                admission=Admission(clock=lambda:0)
                admission.outcome(False,status,'GEMINI_API_ERROR',retry_after=captured.exception.retry_after_seconds)
                self.assertEqual(admission.cooldown,120)

    def test_bulk_permanent_413_is_not_made_retryable_by_header(self):
        from bulk_http import HttpPartSender
        from bulk_extract import BulkHttpError
        def transport(request):return httpx.Response(413,json={'code':'EXTRACT_INPUT_BUDGET_EXCEEDED'},headers={'Retry-After':'120'})
        with HttpPartSender('http://offline',lambda:httpx.MockTransport(transport)) as sender:
            with self.assertRaises(BulkHttpError) as captured:sender({},1)
        self.assertFalse(hasattr(captured.exception,'retry_after_seconds'))


if __name__=='__main__':unittest.main()
