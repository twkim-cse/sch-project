"""Bounded extract-only recovery. No clients, keys, persistence or raw errors."""
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import math
import random
from threading import Lock
import time

MAX_ATTEMPTS = 3
ATTEMPT_SECONDS = 90
CALL_SECONDS = 300
RETRY_WAIT_SECONDS = 15
COOLDOWN_SECONDS = 60


def retry_after(exc):
    headers = getattr(getattr(exc, 'response', None), 'headers', None)
    value = headers.get('Retry-After') if headers is not None else None
    if isinstance(value, str):
        try:
            seconds = float(value)
            if math.isfinite(seconds) and seconds >= 0:
                return seconds
        except ValueError:
            try:
                date = parsedate_to_datetime(value)
                if date.tzinfo is not None:
                    return max(0, (date - datetime.now(timezone.utc)).total_seconds())
            except (ValueError, TypeError, OverflowError):
                pass
    details = getattr(exc, 'details', None)
    error = details.get('error', details) if isinstance(details, dict) else {}
    entries = error.get('details', []) if isinstance(error, dict) else []
    for entry in entries if isinstance(entries, list) else []:
        if isinstance(entry, dict) and entry.get('@type') == 'type.googleapis.com/google.rpc.RetryInfo':
            value = entry.get('retryDelay')
            if isinstance(value, str) and value.endswith('s'):
                try:
                    seconds = float(value[:-1])
                    if math.isfinite(seconds) and seconds >= 0:
                        return seconds
                except ValueError:
                    pass
    return None


def delay(exc, retry_index):
    backoff = 2 * 2 ** retry_index + random.uniform(0, 1)
    instructed = retry_after(exc)
    return max(backoff, instructed) if instructed is not None else backoff


class CooldownError(RuntimeError):
    code = 503
    status = 'UNAVAILABLE'


class Cooldown:
    """Shared per AI process. It cannot enforce account-wide quotas."""
    def __init__(self):
        self._until = 0.0
        self._lock = Lock()

    def defer(self, seconds):
        with self._lock:
            self._until = max(self._until, time.monotonic() + seconds)

    def remaining(self):
        with self._lock:
            return max(0, self._until - time.monotonic())

    def header_seconds(self):
        return min(2_147_483_647, math.ceil(self.remaining()))

    def check(self):
        if self.remaining() > 0:
            raise CooldownError('extract_service_cooldown')
