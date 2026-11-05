"""Compare four generation requests. Never print credentials or response bodies."""

import argparse
import json
import logging
import os
from pathlib import Path
import time
import warnings

import httpx
from dotenv import load_dotenv
from google import genai
from google.genai import types


MODELS = ("gemini-2.5-flash", "gemini-3.8-flash")
VERSIONS = ("v1beta", "v1")


def classify_error(exc):
    code = getattr(exc, "code", None)
    code = code if type(code) is int and 100 <= code <= 599 else None
    if isinstance(exc, (TimeoutError, httpx.TimeoutException)):
        category = "client_timeout"
    elif code in (408, 504):
        category = "server_timeout"
    elif code in (401, 403):
        category = "authentication_or_permission_error"
    elif code == 404:
        category = "model_or_endpoint_not_found"
    elif code == 503:
        category = "service_unavailable"
    elif code == 429:
        category = "quota_or_rate_limit"
    elif code == 400:
        category = "bad_request"
    elif code is not None:
        category = "server_error" if code >= 500 else "http_error"
    elif isinstance(exc, httpx.TransportError):
        category = "network_error"
    else:
        category = "sdk_or_configuration_error"
    return code, category


def probe(api_key, model, version):
    started = time.monotonic()
    client = None
    code = None
    category = "missing_api_key"
    observed_status = []

    def record_status(response):
        observed_status.append(response.status_code)

    try:
        if api_key:
            client = genai.Client(
                api_key=api_key,
                vertexai=False,
                http_options=types.HttpOptions(
                    base_url="https://generativelanguage.googleapis.com",
                    api_version=version,
                    timeout=20_000,
                    retry_options=types.HttpRetryOptions(attempts=1),
                    client_args={"event_hooks": {"response": [record_status]}},
                ),
            )
            # Both model IDs were previously confirmed by the user's models.list().
            # Keep identical, minimal generation settings across the four calls.
            response = client.models.generate_content(
                model=model,
                contents="안녕하세요",
                config=types.GenerateContentConfig(max_output_tokens=16),
            )
            category = "success"
    except Exception as exc:
        code, category = classify_error(exc)
    if observed_status:
        code = observed_status[-1]
    elapsed_ms = round((time.monotonic() - started) * 1000)
    if client is not None:
        try:
            client.close()
        except Exception:
            pass
    print(json.dumps({
        "model": model,
        "api_version": version,
        "http_status": code,
        "elapsed_ms": elapsed_ms,
        "error_category": category,
    }), flush=True)
    return category == "success"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefixed", action="store_true",
                        help="Pass models/<ID> instead of bare model IDs")
    args = parser.parse_args()
    logging.disable(logging.CRITICAL)
    warnings.filterwarnings("ignore")
    # Only the script reads the credential for authentication; never display it.
    try:
        load_dotenv(Path(__file__).resolve().with_name(".env"), override=False)
        api_key = os.getenv("GEMINI_API_KEY")
    except Exception:
        api_key = None
    all_succeeded = True
    for model in MODELS:
        for version in VERSIONS:
            name = "models/" + model if args.prefixed else model
            if not probe(api_key, name, version):
                all_succeeded = False
    return 0 if all_succeeded else 1


if __name__ == "__main__":
    raise SystemExit(main())
