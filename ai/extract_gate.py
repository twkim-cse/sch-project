"""Process-local extract in-flight guard; SDK retry/settings stay untouched."""
from contextlib import contextmanager
from functools import wraps
from threading import Condition
import logging
import os
import time


class ExtractGate:
    def __init__(self,limit=1,queue_seconds=30):
        if type(limit) is not int or not 1<=limit<=4:raise ValueError('invalid_extract_concurrency')
        self.limit=limit;self.queue_seconds=queue_seconds;self.cv=Condition();self.active=0;self.peak=0

    @contextmanager
    def hold(self,deadline=None):
        end=time.monotonic()+self.queue_seconds
        if deadline is not None:end=min(end,deadline)
        with self.cv:
            while self.active>=self.limit:
                remaining=end-time.monotonic()
                if remaining<=0:
                    logging.getLogger('uvicorn.error').warning('Gemini extract gate outcome=queue_timeout')
                    raise TimeoutError('extract_queue_timeout')
                self.cv.wait(min(remaining,.1))
            if time.monotonic()>=end:
                logging.getLogger('uvicorn.error').warning('Gemini extract gate outcome=queue_timeout')
                raise TimeoutError('extract_queue_timeout')
            self.active+=1;self.peak=max(self.peak,self.active)
        try:yield
        finally:
            with self.cv:self.active-=1;self.cv.notify_all()

    def limit_call(self,fn):
        @wraps(fn)
        def wrapped(*args,**kwargs):
            recovery=kwargs.get('recovery',args[1] if len(args)>1 else None)
            with self.hold(recovery['deadline'] if recovery else None):return fn(*args,**kwargs)
        return wrapped


try:
    configured=int(os.getenv('AI_EXTRACT_CONCURRENCY','1'))
    if not 1<=configured<=4:raise ValueError()
except ValueError:
    configured=1
    logging.getLogger('uvicorn.error').warning('Gemini extract gate invalid_config using_default=1')
EXTRACT_GATE=ExtractGate(configured)
logging.getLogger('uvicorn.error').info('Gemini extract gate concurrency_limit=%d queue_seconds=30',configured)
