"""Offline regression tests for Spring's success/error contracts."""
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from google.genai import errors
import test_explain as fixtures


class ContractAuditTests(unittest.TestCase):
    def setUp(self):
        self.h = fixtures.ExplainTests()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)

    def test_classify_invalid_results_return_502(self):
        for value in ({'doc_type': 'INVALID', 'product_name': None, 'version_label': None}, [], {'unexpected': True},
                      {'doc_type': 'TERMS'}, {'doc_type': 'TERMS', 'product_name': 123, 'version_label': None},
                      {'doc_type': 'TERMS', 'product_name': None, 'version_label': []}):
            self.h.responses(value)
            with patch.object(self.h.ns['logger'], 'error'):
                response = self.h.api.post('/ai/classify', json={'document_id': 'audit', 'pages': []})
            self.assertEqual(response.status_code, 502)
            self.assertEqual(response.json()['code'], 'GEMINI_RESPONSE_VALIDATION_ERROR')
            self.assertEqual(set(response.json()), {'code', 'message'})

    def test_http_errors_use_standard_envelope(self):
        self.h.ns['explain_document'].__globals__['client'] = None
        response = self.h.api.post('/ai/explain', json=self.h.body)
        self.assertEqual(response.status_code, 500)
        self.assertEqual(set(response.json()), {'code', 'message'})
        self.assertEqual(response.json()['code'], 'SERVER_CONFIGURATION_ERROR')

    def test_request_validation_does_not_echo_input(self):
        self.h.body['items'][0]['check_item'] = 'AUDIT_PRIVATE_INPUT'
        response = self.h.post()
        self.assertEqual(response.status_code, 422)
        self.assertEqual(set(response.json()), {'code', 'message'})
        self.assertEqual(response.json()['code'], 'REQUEST_VALIDATION_ERROR')
        self.assertNotIn('AUDIT_PRIVATE_INPUT', response.text)

    def test_optional_metadata_and_classify_openapi(self):
        schemas = self.h.api.get('/openapi.json').json()
        clause = schemas['components']['schemas']['Clause']
        self.assertNotIn('rider_id', clause['required'])
        self.assertNotIn('from_table', clause['required'])
        response = schemas['paths']['/ai/classify']['post']['responses']['200']['content']['application/json']['schema']
        self.assertEqual(response, {'$ref': '#/components/schemas/ClassifyResponse'})
        output = schemas['components']['schemas']['ClassifyResponse']
        self.assertEqual(set(output['required']), {'doc_type', 'product_name', 'version_label'})
        self.assertEqual(set(output['properties']['doc_type']['enum']), {'TERMS', 'POLICY', 'PRODUCT_GUIDE', 'OTHER'})
        for status in ('422', '500', '502'):
            self.assertEqual(schemas['paths']['/ai/classify']['post']['responses'][status]['content']['application/json']['schema']['$ref'], '#/components/schemas/ErrorResponse')

    def test_missing_review_reason_is_rejected_even_for_pass(self):
        self.h.responses(self.h.draft(), {'item_id': 'c-017', 'review': 'pass'})
        from unittest.mock import patch
        with patch.object(self.h.ns['logger'], 'error'):
            self.assertEqual(self.h.post().status_code, 502)

    def test_classify_valid_strings_and_nulls(self):
        for name, version in [('상품명', '버전'), (None, None)]:
            for doc_type in ('TERMS', 'POLICY', 'PRODUCT_GUIDE', 'OTHER'):
                value = {'doc_type': doc_type, 'product_name': name, 'version_label': version}
                self.h.responses(value)
                response = self.h.api.post('/ai/classify', json={'document_id': 'audit', 'pages': []})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json(), value)

    def test_gemini_errors_and_sensitive_data_redacted(self):
        self.h.body['items'][0]['quote'] = 'PRIVATE_POLICY_TEXT'
        for code, expected in [(403, 'GEMINI_AUTHENTICATION_ERROR'), (504, 'GEMINI_TIMEOUT'), (400, 'GEMINI_API_ERROR'), (429, 'GEMINI_API_ERROR'), (503, 'GEMINI_API_ERROR')]:
            self.h.mock.models.generate_content.side_effect = errors.APIError(code, {'error': {'message': 'PRIVATE_CREDENTIAL PRIVATE_POLICY_TEXT'}})
            with patch.object(self.h.ns['logger'], 'error') as log, patch.object(self.h.ns['logger'], 'warning') as warning:
                response = self.h.post()
                self.assertEqual(response.status_code, 502)
                self.assertEqual(response.json()['code'], expected)
                self.assertEqual(set(response.json()), {'code', 'message'})
                for secret in ('PRIVATE_CREDENTIAL', 'PRIVATE_POLICY_TEXT'):
                    self.assertNotIn(secret, response.text)
                    self.assertNotIn(secret, str(log.call_args_list) + str(warning.call_args_list))

    def test_internal_and_framework_errors_standardized(self):
        app = self.h.ns['app']
        @app.get('/audit-private-error')
        def private_error():
            raise RuntimeError('PRIVATE_INTERNAL_KEY')
        api = TestClient(app, raise_server_exceptions=False)
        with patch.object(self.h.ns['logger'], 'error') as log:
            response = api.get('/audit-private-error')
            self.assertEqual(response.status_code, 500)
            self.assertEqual(response.json()['code'], 'INTERNAL_ERROR')
            self.assertNotIn('PRIVATE_INTERNAL_KEY', response.text + str(log.call_args_list))
        for response, code in [(api.get('/not-present'), 'NOT_FOUND'), (api.get('/ai/classify'), 'METHOD_NOT_ALLOWED')]:
            self.assertEqual(set(response.json()), {'code', 'message'})
            self.assertEqual(response.json()['code'], code)

    def test_malformed_request_json_standardized(self):
        response = self.h.api.post('/ai/classify', content='{"PRIVATE_INPUT":', headers={'Content-Type': 'application/json'})
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()['code'], 'REQUEST_VALIDATION_ERROR')
        self.assertNotIn('PRIVATE_INPUT', response.text)


if __name__ == '__main__':
    unittest.main()
