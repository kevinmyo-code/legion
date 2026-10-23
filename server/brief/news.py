"""The brief's News section: ONE Gemini `generateContent` call with Google
Search grounding, on the household key (ai-upgrade ticket 02, ruling 3).

**Only grounded items are kept.** Every bullet must be tied, by the response's
own `groundingMetadata.groundingSupports`, to at least one `groundingChunks`
web source; a bullet no source supports is left out rather than shown as news
with nothing behind it. The links are the URIs Gemini returns, which are
Google's grounding redirect links, titled with the source's domain.

**A failure here never fails the brief.** Every outcome is a `NewsResult`
whose section says in words what happened; nothing raises to the caller.

**Read-through (CLAUDE.md section 7):** the prompt carries no household data
and the answer is web news only. The phone's Gmail newsletter module is never
called from here (ruling 5).

Structured output is not requested: the grounding tool's support for
`responseSchema` was not confirmed on the current API (2026-10-22), so the
answer is plain lines and the parser is strict about which survive.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import urllib.error
import uuid
from dataclasses import dataclass, field
from urllib.parse import urlparse

from brief.compose import line, section
from ingest import statements
from ingest.drive import Response, Transport

TOPICS = (
    "AI news",
    "AI companies and new models",
    "software engineering and tech news",
    "cybersecurity",
    "Android and developer tools",
)
MIN_ITEMS = 5
MAX_ITEMS = 8
TIMEOUT_SECONDS = 60

# Gemini API pricing page (ai.google.dev/gemini-api/docs/pricing, last updated
# 2026-10-22) for Gemini 3.5 Flash-Lite, standard paid tier: $0.30 per 1M
# input tokens, $2.50 per 1M output tokens including thinking, and Google
# Search grounding at $14 per 1,000 search requests after 5,000 free a month
# shared across Gemini 3 and newer. In micro-dollars: 0.30 per input token,
# 2.50 per output token, 14,000 per search. An ESTIMATE: every search is
# counted at the paid rate, so inside the free allowance it overstates.
INPUT_MICRO_USD_PER_TOKEN_X100 = 30
OUTPUT_MICRO_USD_PER_TOKEN_X100 = 250
SEARCH_MICRO_USD = 14_000

NO_KEY = (
    f"News was not built: {statements.GEMINI_KEY_ENV} is not set on the server, so nothing "
    f"was sent to Gemini."
)


@dataclass
class NewsResult:
    section: dict
    ok: bool
    call_id: uuid.UUID | None = None
    model: str | None = None
    usage: dict = field(default_factory=dict)

    def usage_fields(self) -> dict:
        """The `briefs` columns this call fills."""
        return {
            "news_ok": self.ok,
            "news_call_id": self.call_id,
            "news_model": self.model,
            "prompt_tokens": self.usage.get("prompt_tokens"),
            "output_tokens": self.usage.get("output_tokens"),
            "thinking_tokens": self.usage.get("thinking_tokens"),
            "tool_prompt_tokens": self.usage.get("tool_prompt_tokens"),
            "total_tokens": self.usage.get("total_tokens"),
            "search_queries": self.usage.get("search_queries"),
            "cost_estimate_micro_usd": self.usage.get("cost_estimate_micro_usd"),
        }


def failed(sentence: str, **kwargs) -> NewsResult:
    return NewsResult(section("news", "News", "failed", [], sentence), ok=False, **kwargs)


def prompt(today: dt.date) -> str:
    topics = "; ".join(TOPICS)
    return (
        f"Today is {today.isoformat()}. Search the web for the most important news from the last "
        f"two days on these topics: {topics}. Write {MIN_ITEMS} to {MAX_ITEMS} items. Each item "
        f"is ONE line starting with '- ', one plain factual sentence about one story, naming who "
        f"did what. No headings, no preamble, no closing remarks, no markdown links, no URLs. "
        f"Only include stories your search results support."
    )


def request_body(today: dt.date) -> bytes:
    return json.dumps(
        {
            "contents": [{"role": "user", "parts": [{"text": prompt(today)}]}],
            "tools": [{"google_search": {}}],
            "generationConfig": {"temperature": 0.2},
        }
    ).encode()


def _transport(method: str, url: str, headers: dict[str, str], body: bytes | None) -> Response:
    import urllib.request

    from ingest.drive import _OPENER

    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


_BULLET = re.compile(r"^\s*(?:[-*•]|\d+[.)])\s+(?P<text>.+?)\s*$")


def _domain(uri: str, title: str | None) -> str:
    """Grounding chunk titles are usually the source's domain already; the
    URI is a Google redirect, so it is never used as the label."""
    if title and title.strip():
        return title.strip()
    return urlparse(uri).netloc or "source"


def parse(payload: dict) -> tuple[list[dict], int]:
    """(grounded bullets as section lines, how many bullets were dropped for
    having no source). Pure over the response JSON."""
    candidate = (payload.get("candidates") or [None])[0] or {}
    parts = (candidate.get("content") or {}).get("parts") or []
    text = "".join(part.get("text", "") for part in parts if isinstance(part, dict))
    metadata = candidate.get("groundingMetadata") or {}
    chunks = metadata.get("groundingChunks") or []
    supports = metadata.get("groundingSupports") or []

    # Each bullet with its byte range in the answer: Segment indices are UTF-8
    # byte offsets into the Part (API reference, `Segment`).
    bullets: list[tuple[str, int, int]] = []
    offset = 0
    for raw in text.splitlines(keepends=True):
        size = len(raw.encode("utf-8"))
        match = _BULLET.match(raw.rstrip("\r\n"))
        if match:
            bullets.append((match.group("text"), offset, offset + size))
        offset += size

    lines: list[dict] = []
    dropped = 0
    for bullet, start, end in bullets:
        indices: list[int] = []
        for support in supports:
            segment = support.get("segment") or {}
            seg_text = (segment.get("text") or "").strip()
            seg_start = segment.get("startIndex", 0) or 0
            seg_end = segment.get("endIndex", 0) or 0
            inside = seg_end > start and seg_start < end if seg_end else False
            if inside or (seg_text and seg_text in bullet):
                indices.extend(support.get("groundingChunkIndices") or [])
        links: list[dict] = []
        seen: set[str] = set()
        for index in indices:
            if not isinstance(index, int) or not 0 <= index < len(chunks):
                continue
            web = (chunks[index] or {}).get("web") or {}
            uri = web.get("uri")
            if not uri or uri in seen:
                continue
            seen.add(uri)
            links.append({"title": _domain(uri, web.get("title")), "url": uri})
        if not links:
            dropped += 1
            continue
        lines.append(line(bullet, links=links))
        if len(lines) == MAX_ITEMS:
            break
    return lines, dropped


def usage_of(payload: dict) -> dict:
    usage = payload.get("usageMetadata") or {}
    candidate = (payload.get("candidates") or [None])[0] or {}
    queries = (candidate.get("groundingMetadata") or {}).get("webSearchQueries") or []
    prompt_tokens = usage.get("promptTokenCount")
    output_tokens = usage.get("candidatesTokenCount")
    thinking = usage.get("thoughtsTokenCount")
    tool_prompt = usage.get("toolUsePromptTokenCount")
    total = usage.get("totalTokenCount")
    input_tokens = (prompt_tokens or 0) + (tool_prompt or 0)
    out_tokens = (output_tokens or 0) + (thinking or 0)
    cost = None
    if usage:
        # Rounded up to the next whole micro-dollar.
        cost = (
            -(-input_tokens * INPUT_MICRO_USD_PER_TOKEN_X100 // 100)
            + -(-out_tokens * OUTPUT_MICRO_USD_PER_TOKEN_X100 // 100)
            + len(queries) * SEARCH_MICRO_USD
        )
    return {
        "prompt_tokens": prompt_tokens,
        "output_tokens": output_tokens,
        "thinking_tokens": thinking,
        "tool_prompt_tokens": tool_prompt,
        "total_tokens": total,
        "search_queries": len(queries),
        "cost_estimate_micro_usd": cost,
    }


def build_news(
    today: dt.date, *, transport: Transport | None = None, key: str | None = None
) -> NewsResult:
    """The News section for one household's run. Never raises."""
    key = key if key is not None else statements.gemini_key()
    if not key:
        return failed(NO_KEY)
    send = transport or _transport
    call_id = uuid.uuid4()
    model = statements.GEMINI_MODEL
    try:
        response = send(
            "POST",
            statements.GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            request_body(today),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError, OSError) as exc:
        return failed(
            f"News was not built: Gemini did not answer ({type(exc).__name__}). The other "
            f"sections are unaffected."
        )
    status = response.status
    if status in (401, 403):
        return failed(
            f"News was not built: Gemini refused {statements.GEMINI_KEY_ENV} (HTTP {status})."
        )
    if status != 200:
        return failed(f"News was not built: Gemini answered HTTP {status}.")
    try:
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("not an object")
    except (ValueError, UnicodeDecodeError):
        return failed("News was not built: Gemini's answer could not be read.")
    usage = usage_of(payload)
    try:
        lines, dropped = parse(payload)
    except (TypeError, AttributeError, ValueError):
        return failed(
            "News was not built: Gemini's answer was not in the expected shape.",
            call_id=call_id,
            model=model,
            usage=usage,
        )
    if not lines:
        return failed(
            "News was not built: Gemini answered, but no item in it was tied to a web source, "
            "so none is shown.",
            call_id=call_id,
            model=model,
            usage=usage,
        )
    note = None
    if dropped:
        note = (
            f"{dropped} item{'s were' if dropped != 1 else ' was'} left out because no source "
            f"was tied to {'them' if dropped != 1 else 'it'}."
        )
    if len(lines) < MIN_ITEMS:
        short = f"Only {len(lines)} sourced item{'s' if len(lines) != 1 else ''} today."
        note = f"{short} {note}" if note else short
    return NewsResult(
        section("news", "News", "ok", lines, note),
        ok=True,
        call_id=call_id,
        model=model,
        usage=usage,
    )
