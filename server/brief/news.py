"""The brief's News section: ONE Gemini `generateContent` call with Google
Search grounding, on the household key (ai-upgrade ticket 02, ruling 3).

**Items carry their own sources when Gemini ties them.** A bullet is tied to
a `groundingChunks` web source by the response's own
`candidates[0].groundingMetadata.groundingSupports`: each support's `segment`
names a Part (`partIndex`) and a UTF-8 BYTE range in it (API reference,
`Segment`), checked against the segment's own `text`. When at least one bullet
is tied, a bullet with no source is left out and counted in the note.

**When none is tied but the answer carried web sources** (no supports, or
none that land on a bullet), every bullet is kept with no links and one last
line lists the sources for the section as a whole; the line and the note say
in words that they are not per item. With no web source at all, nothing is
shown. Links are only ever the URIs Gemini returned (Google's grounding
redirect links), titled with the source's domain; none is constructed.

**A failure or a fallback logs one WARNING line** with the grounding
metadata's key names and counts only, so the next live run says which shape
came back.

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
import logging
import re
import urllib.error
import uuid
from dataclasses import dataclass, field
from urllib.parse import urlparse

from brief.compose import line, section
from ingest import statements
from ingest.drive import Response, Transport

logger = logging.getLogger(__name__)

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


PER_ITEM = "per_item"
SECTION_SOURCES = "section_sources"
NOTHING = "nothing"

SECTION_SOURCES_LINE = (
    "Sources for this section as a whole. Gemini did not tie any one item above to a source, so "
    "these are not per item."
)
SECTION_SOURCES_NOTE = (
    "Sources are listed once for the whole section, not per item: Gemini's answer did not tie "
    "each item to its own source."
)


@dataclass
class Parsed:
    """What `parse` read out of one answer.

    `mode` is `per_item` when at least one bullet was tied to a source by a
    `groundingSupports` entry (unsupported bullets are then dropped and
    counted), `section_sources` when the answer carried web sources but tied
    none of them to a bullet (every bullet is kept with no links, and
    `sources` holds the section's sources), and `nothing` otherwise."""

    mode: str
    items: list[dict]
    dropped: int = 0
    sources: list[dict] = field(default_factory=list)
    counts: dict = field(default_factory=dict)


def _int(value) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _squash(text: str) -> str:
    return " ".join(text.split())


def _web_link(chunk) -> dict | None:
    """A link only from a URI Gemini returned; never a constructed one."""
    if not isinstance(chunk, dict):
        return None
    web = chunk.get("web")
    if not isinstance(web, dict):
        return None
    uri = web.get("uri")
    if not isinstance(uri, str) or not uri.strip():
        return None
    title = web.get("title") if isinstance(web.get("title"), str) else None
    return {"title": _domain(uri, title), "url": uri}


def _segment_range(
    segment: dict, bases: dict[int, int], part_bytes: dict[int, bytes], answer: bytes
) -> tuple[int, int] | None:
    """The segment's byte range in the joined answer, or None.

    `Segment.startIndex`/`endIndex` are byte offsets into the Part named by
    `partIndex` (API reference, `Segment`; an omitted field is 0). The
    segment's own `text` is the check: when the bytes at those offsets are
    not that text (the migration guide calls them character indices, which
    the reference contradicts), the text is located in the Part, then in the
    whole answer, instead. When the segment carries text that is found
    nowhere, its offsets are not trusted either: None, so no bullet is tied
    by a range its own text contradicts."""
    part = _int(segment.get("partIndex")) or 0
    base = bases.get(part)
    data = part_bytes.get(part, b"")
    start = _int(segment.get("startIndex")) or 0
    end = _int(segment.get("endIndex")) or 0
    seg_text = segment.get("text") if isinstance(segment.get("text"), str) else ""
    wanted = seg_text.strip()
    offsets_fit = base is not None and 0 <= start < end <= len(data)
    if offsets_fit:
        at_offsets = data[start:end].decode("utf-8", "replace").strip()
        if not wanted or at_offsets == wanted:
            return base + start, base + end
    if wanted:
        needle = wanted.encode("utf-8")
        found = data.find(needle) if base is not None else -1
        if found >= 0:
            return base + found, base + found + len(needle)
        found = answer.find(needle)
        if found >= 0:
            return found, found + len(needle)
    return None


def grounding_counts(payload: dict) -> dict:
    """Key names and counts of the answer's grounding metadata, for the one
    diagnostic log line. Never content, never a URI."""
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    candidates = candidates if isinstance(candidates, list) else []
    candidate = candidates[0] if candidates and isinstance(candidates[0], dict) else {}
    content = candidate.get("content") if isinstance(candidate.get("content"), dict) else {}
    parts = content.get("parts") if isinstance(content.get("parts"), list) else []
    metadata = candidate.get("groundingMetadata")
    counts: dict = {"candidates": len(candidates), "parts": len(parts)}
    if not isinstance(metadata, dict):
        counts["groundingMetadata"] = "absent"
        metadata = {}
    for key in ("groundingChunks", "groundingSupports", "webSearchQueries"):
        value = metadata.get(key)
        counts[key] = len(value) if isinstance(value, list) else 0
    for key in sorted(k for k in metadata if isinstance(k, str)):
        if key not in counts and key.isidentifier() and len(key) <= 40:
            value = metadata[key]
            counts[key] = len(value) if isinstance(value, list) else "present"
    finish = candidate.get("finishReason")
    if isinstance(finish, str) and finish.isupper():
        counts["finishReason"] = finish
    return counts


def parse(payload: dict) -> Parsed:
    """Pure over the response JSON."""
    counts = grounding_counts(payload)
    candidate = (payload.get("candidates") or [None])[0] or {}
    parts = (candidate.get("content") or {}).get("parts") or []
    metadata = candidate.get("groundingMetadata") or {}
    chunks = metadata.get("groundingChunks") or []
    supports = metadata.get("groundingSupports") or []

    # The answer's text Parts joined, each Part's starting byte remembered so a
    # Segment's per-Part offsets land in the joined text. A thought Part keeps
    # its index (partIndex counts every Part) but adds no text.
    bases: dict[int, int] = {}
    part_bytes: dict[int, bytes] = {}
    pieces: list[str] = []
    offset = 0
    for index, part in enumerate(parts):
        if not isinstance(part, dict):
            continue
        text = part.get("text")
        if not isinstance(text, str) or part.get("thought") is True:
            text = ""
        data = text.encode("utf-8")
        bases[index] = offset
        part_bytes[index] = data
        pieces.append(text)
        offset += len(data)
    answer_text = "".join(pieces)
    answer = answer_text.encode("utf-8")

    # Each bullet with its byte range in the joined answer.
    bullets: list[tuple[str, str, int, int]] = []
    offset = 0
    for raw in answer_text.splitlines(keepends=True):
        size = len(raw.encode("utf-8"))
        match = _BULLET.match(raw.rstrip("\r\n"))
        if match:
            bullets.append((match.group("text"), raw, offset, offset + size))
        offset += size
    counts["bullets"] = len(bullets)

    resolved: list[tuple[tuple[int, int] | None, str, list]] = []
    for support in supports:
        if not isinstance(support, dict):
            continue
        segment = support.get("segment") if isinstance(support.get("segment"), dict) else {}
        indices = support.get("groundingChunkIndices") or []
        seg_text = segment.get("text") if isinstance(segment.get("text"), str) else ""
        span = _segment_range(segment, bases, part_bytes, answer)
        resolved.append((span, _squash(seg_text), indices))

    items: list[dict] = []
    dropped = 0
    for bullet, raw, start, end in bullets:
        if len(items) == MAX_ITEMS:
            break
        indices: list = []
        for span, seg_text, chunk_indices in resolved:
            if span is not None:
                tied = span[1] > start and span[0] < end
            else:
                tied = bool(seg_text) and (seg_text in _squash(raw) or _squash(bullet) in seg_text)
            if tied:
                indices.extend(chunk_indices)
        links: list[dict] = []
        seen: set[str] = set()
        for index in indices:
            if _int(index) is None or not 0 <= index < len(chunks):
                continue
            link = _web_link(chunks[index])
            if link is None or link["url"] in seen:
                continue
            seen.add(link["url"])
            links.append(link)
        if not links:
            dropped += 1
            continue
        items.append(line(bullet, links=links))
    if items:
        return Parsed(PER_ITEM, items, dropped, counts=counts)

    # No bullet was tied to a source. When the answer still carried web
    # sources, keep the bullets and list those sources once, for the section.
    sources: list[dict] = []
    seen = set()
    for chunk in chunks:
        link = _web_link(chunk)
        if link is not None and link["url"] not in seen:
            seen.add(link["url"])
            sources.append(link)
    if bullets and sources:
        kept = [line(bullet) for bullet, _raw, _start, _end in bullets[:MAX_ITEMS]]
        return Parsed(SECTION_SOURCES, kept, sources=sources, counts=counts)
    return Parsed(NOTHING, [], dropped, counts=counts)


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
        parsed = parse(payload)
    except (TypeError, AttributeError, ValueError):
        log_grounding("shape_error", payload)
        return failed(
            "News was not built: Gemini's answer was not in the expected shape.",
            call_id=call_id,
            model=model,
            usage=usage,
        )
    if parsed.mode == NOTHING:
        log_grounding(NOTHING, payload, parsed.counts)
        if not parsed.counts.get("bullets"):
            sentence = (
                "News was not built: Gemini answered, but not as a list of items the brief could "
                "read, so none is shown."
            )
        else:
            sentence = (
                "News was not built: Gemini answered, but no item in it was tied to a web "
                "source, so none is shown."
            )
        return failed(sentence, call_id=call_id, model=model, usage=usage)
    items = parsed.items
    if parsed.mode == SECTION_SOURCES:
        log_grounding(SECTION_SOURCES, payload, parsed.counts)
        note = SECTION_SOURCES_NOTE
        if len(items) < MIN_ITEMS:
            note = f"Only {len(items)} item{'s' if len(items) != 1 else ''} today. {note}"
        lines = [*items, line(SECTION_SOURCES_LINE, links=parsed.sources)]
        return NewsResult(
            section("news", "News", "ok", lines, note),
            ok=True,
            call_id=call_id,
            model=model,
            usage=usage,
        )
    dropped = parsed.dropped
    note = None
    if dropped:
        note = (
            f"{dropped} item{'s were' if dropped != 1 else ' was'} left out because no source "
            f"was tied to {'them' if dropped != 1 else 'it'}."
        )
    if len(items) < MIN_ITEMS:
        short = f"Only {len(items)} sourced item{'s' if len(items) != 1 else ''} today."
        note = f"{short} {note}" if note else short
    return NewsResult(
        section("news", "News", "ok", items, note),
        ok=True,
        call_id=call_id,
        model=model,
        usage=usage,
    )


def log_grounding(outcome: str, payload: dict, counts: dict | None = None) -> None:
    """One WARNING line when News failed or fell back to section-level
    sources: the grounding metadata's key names and counts, nothing else (no
    answer text, no URI, no key). WARNING because the engine sets no logging
    config, so only WARNING and above reach the job's stderr."""
    try:
        counts = counts if counts is not None else grounding_counts(payload)
        detail = " ".join(f"{key}={value}" for key, value in counts.items())
    except Exception:  # a diagnostic must never fail the brief
        detail = "unreadable"
    logger.warning("News grounding outcome=%s %s", outcome, detail)
