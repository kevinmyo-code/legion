"""The engine's model-call helper (architecture review 2026-11-10, item 15).

Every engine Gemini call goes through one seam already: a `Transport`, `(method, url,
headers, body) -> Response` (`ingest/drive.py`). This module adds three things around it
without replacing any caller's own error handling or wording:

- **`retrying(transport)`**: retry with exponential backoff and jitter on HTTP 429 and
  5xx and on a refused connection, honouring `Retry-After` (capped). Never on a timeout:
  a call that already used its whole time budget is not retried inside the same request.
  Opt-in per call site, with that site's own attempt count, because the voice-turn calls
  (`web_search` 10 s, `decide` 3 s) have no room for a second attempt and stay at one.
- **`usage_of(payload)`**: the token counts Google reported and an ESTIMATED cost.
- **`record(...)`**: one `llm_usage` row; never raises (a failed write is logged and the
  caller's answer still goes out).

The member daily cap (Q9) is in `llm/cap.py`.
"""

from __future__ import annotations

import logging
import random
import time
import urllib.error
from collections.abc import Callable

from django.db import DatabaseError

logger = logging.getLogger(__name__)

RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
BASE_DELAY_SECONDS = 1.0
MAX_DELAY_SECONDS = 8.0

# Gemini 3.5 Flash-Lite list prices, as `brief/news.py` quotes them (pricing page, updated
# 2026-10-22): $0.30 / 1M input tokens, $2.50 / 1M output tokens including thinking, $14 per
# 1,000 grounded searches. In hundredths of a micro-dollar per token. An ESTIMATE.
INPUT_MICRO_USD_PER_TOKEN_X100 = 30
OUTPUT_MICRO_USD_PER_TOKEN_X100 = 250
SEARCH_MICRO_USD = 14_000

_sleep: Callable[[float], None] = time.sleep


def _retry_after(response) -> float | None:
    headers = getattr(response, "headers", None) or {}
    raw = None
    for key, value in dict(headers).items():
        if str(key).lower() == "retry-after":
            raw = value
    try:
        return min(float(raw), MAX_DELAY_SECONDS) if raw is not None else None
    except (TypeError, ValueError):
        return None


def _delay(attempt: int) -> float:
    ceiling = min(BASE_DELAY_SECONDS * (2**attempt), MAX_DELAY_SECONDS)
    return random.uniform(ceiling / 2, ceiling)  # noqa: S311 - jitter, not security


def retrying(transport, *, attempts: int = 3):
    """`transport`, retried up to `attempts` times in all on 429/5xx or a refused
    connection. The last response (or exception) is what the caller sees, so its own
    "Gemini answered HTTP 503" wording is unchanged."""
    if attempts < 1:
        raise ValueError("attempts must be at least 1")

    def send(method, url, headers, body):
        for attempt in range(attempts):
            last = attempt == attempts - 1
            try:
                response = transport(method, url, headers, body)
            except TimeoutError:
                raise
            except (urllib.error.URLError, ConnectionError) as exc:
                if last or isinstance(getattr(exc, "reason", None), TimeoutError):
                    raise
                logger.warning(
                    "llm: %s, retrying (%d/%d)", type(exc).__name__, attempt + 1, attempts
                )
                _sleep(_delay(attempt))
                continue
            if response.status not in RETRY_STATUSES or last:
                return response
            wait = _retry_after(response)
            logger.warning(
                "llm: HTTP %s, retrying (%d/%d)", response.status, attempt + 1, attempts
            )
            _sleep(wait if wait is not None else _delay(attempt))
        raise AssertionError("unreachable")

    return send


def usage_of(payload: dict) -> dict:
    """Google's `usageMetadata` plus grounded-search count, and an estimated cost."""
    payload = payload if isinstance(payload, dict) else {}
    usage = payload.get("usageMetadata") or {}
    candidates = payload.get("candidates") or [None]
    candidate = candidates[0] if isinstance(candidates, list) and candidates else None
    candidate = candidate if isinstance(candidate, dict) else {}
    queries = (candidate.get("groundingMetadata") or {}).get("webSearchQueries") or []
    prompt = usage.get("promptTokenCount")
    output = usage.get("candidatesTokenCount")
    thinking = usage.get("thoughtsTokenCount")
    tool_prompt = usage.get("toolUsePromptTokenCount")
    cost = None
    if usage:
        input_tokens = (prompt or 0) + (tool_prompt or 0)
        out_tokens = (output or 0) + (thinking or 0)
        cost = (
            -(-input_tokens * INPUT_MICRO_USD_PER_TOKEN_X100 // 100)
            + -(-out_tokens * OUTPUT_MICRO_USD_PER_TOKEN_X100 // 100)
            + len(queries) * SEARCH_MICRO_USD
        )
    return {
        "prompt_tokens": prompt,
        "output_tokens": (output or 0) + (thinking or 0) if usage else None,
        "total_tokens": usage.get("totalTokenCount"),
        "search_queries": len(queries),
        "cost_estimate_micro_usd": cost,
    }


def record(
    *,
    household,
    feature: str,
    model: str,
    usage: dict | None = None,
    user=None,
    cost_estimate_micro_usd: int | None = None,
) -> object | None:
    """One `llm_usage` row, returned. Never raises: accounting must not cost the caller its
    answer (None when nothing was written)."""
    from llm.models import LlmUsage

    if household is None:
        return None
    usage = usage or {}
    member = user if getattr(user, "pk", None) else None
    try:
        return LlmUsage.objects.create(
            household=household,
            user=member,
            feature=feature,
            model=model,
            prompt_tokens=usage.get("prompt_tokens"),
            output_tokens=usage.get("output_tokens"),
            total_tokens=usage.get("total_tokens"),
            search_queries=usage.get("search_queries") or 0,
            cost_estimate_micro_usd=(
                cost_estimate_micro_usd
                if cost_estimate_micro_usd is not None
                else usage.get("cost_estimate_micro_usd")
            ),
        )
    except DatabaseError:
        logger.warning("llm: usage row for %s not written", feature, exc_info=True)
        return None
