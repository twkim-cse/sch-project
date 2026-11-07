"""Explicit-use HTTP adapter; no requests at import/construction time."""
from threading import local, Lock
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import math
import httpx
from bulk_extract import BulkHttpError, MESSAGES

SAFE_CODES=set(MESSAGES)|{'GEMINI_API_ERROR','GEMINI_TIMEOUT','GEMINI_MODEL_ERROR',
 'GEMINI_RESPONSE_VALIDATION_ERROR','GEMINI_AUTHENTICATION_ERROR','SERVER_CONFIGURATION_ERROR',
 'EXTRACT_INPUT_BUDGET_EXCEEDED','REQUEST_VALIDATION_ERROR','SERVICE_UNAVAILABLE','INTERNAL_ERROR'}


class HttpPartSender:
    """One httpx.Client per worker; no SDK/client objects cross worker threads.

    Close only after the bounded runner has drained every in-flight worker.
    transport_factory is intended for HTTPX MockTransport offline tests.
    """
    def __init__(self,base_url,transport_factory=None):
        self.base_url=base_url;self.factory=transport_factory
        self.local=local();self.lock=Lock();self.clients=[];self.closed=False

    def __enter__(self):return self

    def __exit__(self,*args):self.close()

    def close(self):
        with self.lock:
            self.closed=True
            for client in self.clients:client.close()

    def __call__(self,body,timeout):
        if self.closed:raise BulkHttpError(502,'BULK_CLIENT_ERROR')
        if not hasattr(self.local,'client'):
            transport=self.factory() if self.factory else httpx.HTTPTransport(retries=0)
            client=httpx.Client(base_url=self.base_url,transport=transport,
                limits=httpx.Limits(max_connections=1,max_keepalive_connections=1),trust_env=False)
            with self.lock:
                if self.closed:client.close();raise BulkHttpError(502,'BULK_CLIENT_ERROR')
                self.clients.append(client)
            self.local.client=client
        try:response=self.local.client.post('/ai/extract',json=body,timeout=httpx.Timeout(timeout))
        except httpx.HTTPError:raise BulkHttpError(502,'BULK_CLIENT_ERROR') from None
        try:payload=response.json()
        except ValueError:raise BulkHttpError(502,'BULK_RESULT_INVALID') from None
        if response.status_code!=200:
            code=payload.get('code') if isinstance(payload,dict) else None
            exc=BulkHttpError(response.status_code,code if isinstance(code,str) and code in SAFE_CODES else 'BULK_HTTP_ERROR')
            if response.status_code in (429,503,504) or (response.status_code==502 and exc.code in ('GEMINI_API_ERROR','GEMINI_TIMEOUT')):
                value=response.headers.get('Retry-After')
                if value:
                    try:delay=float(value)
                    except ValueError:
                        try:delay=(parsedate_to_datetime(value)-datetime.now(timezone.utc)).total_seconds()
                        except (ValueError,TypeError,OverflowError):delay=-1
                    if math.isfinite(delay) and delay>=0:exc.retry_after_seconds=delay
            raise exc
        return payload
