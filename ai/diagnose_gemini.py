"""List account-visible Gemini models; optionally send one fixed greeting.

No raw exceptions, credentials, environment values, or response text are printed.
"""

import argparse
import json
import logging
import os
from pathlib import Path
import time

import httpx
from dotenv import load_dotenv
from google import genai
from google.genai import errors, types


def emit(event, **fields):
    print(json.dumps({"event": event, **fields}, ensure_ascii=True), flush=True)


def failure(stage, exc, started):
    code = getattr(exc, "code", None)
    code = code if type(code) is int else None
    status = getattr(exc, "status", None)
    if isinstance(exc, (TimeoutError, httpx.TimeoutException)) or code in (408, 504) or status == "DEADLINE_EXCEEDED":
        category = "timeout"
    elif code in (401, 403) or status in ("UNAUTHENTICATED", "PERMISSION_DENIED"):
        category = "authentication_or_permission_error"
    elif code == 429:
        category = "quota_or_rate_limit"
    elif code == 404:
        category = "api_not_found"
    elif code == 400:
        category = "bad_request_or_invalid_credentials"
    elif isinstance(exc, httpx.TransportError):
        category = "network_error"
    elif isinstance(exc, errors.APIError):
        category = "server_error" if code and code >= 500 else "api_error"
    else:
        category = "unexpected_error"
    emit(stage + "_failed", category=category, http_code=code,
         elapsed_ms=round((time.monotonic() - started) * 1000))


def normalized(name):
    return name.removeprefix("models/")


def diagnose(client, selected, test):
    started = time.monotonic()
    try:
        # Exhaust all pages before claiming that any model is absent.
        models = list(client.models.list())
    except Exception as exc:
        failure("model_list", exc, started)
        return 2
    emit("model_list_succeeded", count=len(models),
         elapsed_ms=round((time.monotonic() - started) * 1000))
    available = {}
    for model in models:
        if not model.name:
            continue
        supported = model.supported_actions
        supports_generation = None if supported is None else "generateContent" in supported
        emit("model", model_id=model.name, generateContent=supports_generation)
        available[normalized(model.name)] = (model.name, supports_generation)

    target = None
    if selected:
        target = available.get(normalized(selected))
        if target is None:
            # Means absent from this account's complete list, not globally nonexistent.
            emit("model_absent_from_account_list")
            return 3
        emit("selected_model", model_id=target[0], generateContent=target[1])
        if target[1] is not True:
            emit("generate_content_support_unconfirmed")
            return 4
    if not test:
        return 0
    if target is None:
        # Select only a listed Gemini model that explicitly supports generation.
        candidates = sorted(value for key, value in available.items()
                            if key.startswith("gemini-") and value[1] is True)
        if not candidates:
            emit("no_confirmed_generation_model")
            return 4
        target = candidates[0]
    emit("greeting_started", model_id=target[0])
    started = time.monotonic()
    try:
        response = client.models.generate_content(
            model=target[0], contents="안녕하세요",
            config=types.GenerateContentConfig(max_output_tokens=128),
        )
        has_text = bool(response.text and response.text.strip())
    except Exception as exc:
        failure("greeting", exc, started)
        return 5
    emit("greeting_succeeded" if has_text else "greeting_empty_response",
         model_id=target[0], has_text=has_text,
         elapsed_ms=round((time.monotonic() - started) * 1000))
    return 0 if has_text else 6


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", help="Exact model ID to check; defaults to GEMINI_MODEL")
    parser.add_argument("--test", action="store_true", help="Send one greeting after model verification")
    parser.add_argument("--timeout-seconds", type=int, default=30, help="Per-request timeout (default: 30)")
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")
    # Suppress SDK/HTTP logs that could contain URLs or response payloads.
    logging.disable(logging.CRITICAL)
    started = time.monotonic()
    client = None
    try:
        load_dotenv(Path(__file__).resolve().with_name(".env"), override=False)
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            emit("configuration_failed", category="missing_api_key")
            return 1
        selected = (args.model if args.model is not None else os.getenv("GEMINI_MODEL", "")).strip()
        client = genai.Client(
            api_key=api_key, vertexai=False,
            http_options=types.HttpOptions(
                timeout=args.timeout_seconds * 1000,
                retry_options=types.HttpRetryOptions(attempts=1),
            ),
        )
        return diagnose(client, selected, args.test)
    except Exception as exc:
        failure("initialization", exc, started)
        return 1
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                emit("client_close_failed")


if __name__ == "__main__":
    raise SystemExit(main())
