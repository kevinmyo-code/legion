"""`web_search`: one Gemini call with Google Search grounding, on the household key.

Kevin, 2026-11-03: "it needs a websearch tool". A visitor asked what the H-1B1
visa is and got "Kevin hasn't shared that here"; a member asking the news
got "no web search here". This is the engine's answer to both.

**An engine tool, not Live's built-in `googleSearch`.** It reaches every
client through the registry (`/mcp`, the members' web assistant, a
recruiter's visit), its call lands in the conversation record like any other
tool, and it sidesteps a reported Live issue where `google_search` came back
to the client as a function call.

**The call.** `POST` Gemini's `generateContent` on `ingest.statements.
GEMINI_URL` (the household's own `LEGION_GEMINI_KEY`, the flash-lite model the
engine already uses for statements and the brief's News), with
`tools: [{"google_search": {}}]`, the shape `brief/news.py` sends and tests.
The answer is the response's text parts; the sources are the
`groundingMetadata.groundingChunks[].web` titles and URIs Gemini returned
(Google's grounding redirect links; none is constructed here), read with
`brief.news._web_link`. `TIMEOUT_SECONDS` bounds it: a voice turn cannot wait
for a slow search.

**Read-through.** Nothing is stored: the query and answer exist in this call
and in the tool result, which the conversation record trims to its tool cap
(`devicelogs.web_conversation.CAP_TOOL`). The prompt carries the question
only, never household data.

**Honest failure.** Every failure is a `SearchFailed` whose message says
nothing was found or searched, and why, in words. An answer with no web
source is returned, but marked as unsourced.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field

from brief.news import _web_link
from ingest import statements
from ingest.drive import _OPENER, Response, Transport

TIMEOUT_SECONDS = 10
MAX_SOURCES = 5
MAX_ANSWER_CHARS = 1200


@dataclass(frozen=True)
class WebAnswer:
    query: str
    answer: str
    sources: list[dict] = field(default_factory=list)
    search_queries: list[str] = field(default_factory=list)
    model: str = statements.GEMINI_MODEL
    # Google's token counts and an estimated cost (`llm.client.usage_of`), for `llm_usage`.
    usage: dict = field(default_factory=dict)


class SearchFailed(Exception):
    """Nothing usable came back. `message` is for the model to repeat;
    `refused` is True when Google answered with an error (so nothing was
    billed for a search, and a recruiter's reservation may be released)."""

    def __init__(self, message: str, *, refused: bool = False):
        super().__init__(message)
        self.message = message
        self.refused = refused


def prompt(query: str) -> str:
    return (
        "Search the web and answer this question in at most three short, plain, factual "
        "sentences. No preamble, no markdown, no URLs. If the search results do not answer it, "
        f"say so in one sentence.\n\nQuestion: {query}"
    )


def request_body(query: str) -> bytes:
    return json.dumps(
        {
            "contents": [{"role": "user", "parts": [{"text": prompt(query)}]}],
            "tools": [{"google_search": {}}],
            "generationConfig": {"temperature": 0.2},
        }
    ).encode()


def _transport(method: str, url: str, headers: dict[str, str], body: bytes | None) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


def parse(query: str, payload: dict) -> WebAnswer:
    """Pure over the response JSON. Raises SearchFailed when there is no text."""
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    candidate = candidates[0] if isinstance(candidates, list) and candidates else {}
    candidate = candidate if isinstance(candidate, dict) else {}
    content = candidate.get("content") if isinstance(candidate.get("content"), dict) else {}
    parts = content.get("parts") if isinstance(content.get("parts"), list) else []
    text = "".join(
        p["text"]
        for p in parts
        if isinstance(p, dict) and isinstance(p.get("text"), str) and p.get("thought") is not True
    )
    answer = " ".join(text.split())
    if not answer:
        raise SearchFailed(
            "Nothing was found. Gemini answered the web search with no text "
            f"(finish reason {candidate.get('finishReason') or 'not given'}), so nothing is "
            "known from it."
        )
    if len(answer) > MAX_ANSWER_CHARS:
        answer = answer[:MAX_ANSWER_CHARS].rsplit(" ", 1)[0] + " [...]"
    metadata = candidate.get("groundingMetadata")
    metadata = metadata if isinstance(metadata, dict) else {}
    chunks = metadata.get("groundingChunks")
    sources: list[dict] = []
    seen: set[str] = set()
    for chunk in chunks if isinstance(chunks, list) else []:
        link = _web_link(chunk)
        if link is not None and link["url"] not in seen:
            seen.add(link["url"])
            sources.append(link)
        if len(sources) == MAX_SOURCES:
            break
    queries = metadata.get("webSearchQueries")
    queries = [q for q in queries if isinstance(q, str)] if isinstance(queries, list) else []
    return WebAnswer(query=query, answer=answer, sources=sources, search_queries=queries)


def search(query: str, *, transport: Transport | None = None, key: str | None = None) -> WebAnswer:
    key = key if key is not None else statements.gemini_key()
    if not key:
        raise SearchFailed(
            f"Nothing was searched. {statements.GEMINI_KEY_ENV} is not set on this server, so "
            "there is no web search here; say you cannot look it up.",
            refused=True,
        )
    send = transport or _transport
    try:
        response = send(
            "POST",
            statements.GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            request_body(query),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError, OSError) as exc:
        raise SearchFailed(
            f"Nothing was found. The web search did not answer in time or could not be reached "
            f"({type(exc).__name__}), so nothing is known from it; say you could not look it "
            "up just now."
        ) from None
    status = response.status
    if status in (401, 403):
        raise SearchFailed(
            f"Nothing was searched. Google refused this server's Gemini key (HTTP {status}).",
            refused=True,
        )
    if status != 200:
        raise SearchFailed(
            f"Nothing was searched. Google answered the web search with HTTP {status}; say you "
            "could not look it up just now.",
            refused=True,
        )
    try:
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("not an object")
    except (ValueError, UnicodeDecodeError):
        raise SearchFailed(
            "Nothing was found. The web search's answer could not be read, so nothing is known "
            "from it."
        ) from None
    found = parse(query, payload)
    from llm.client import usage_of

    found.usage = usage_of(payload)
    return found
