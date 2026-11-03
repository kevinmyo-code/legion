"""A `decide` step's one model call (procedures ticket 12, ruling 02, ADR 0070).

**What the model may do: pick ONE of the branch names the definition lists, nothing else.** It
never states a figure, a date, a name or a sentence the user hears. The request asks for JSON
constrained to `{"branch": <one of the names>}` (`responseSchema` with an enum, the shape
`ingest/statements.py` already sends); anything else is a `DecideFailed`, and the runner takes
the step's `on_fail` branch, chosen by code, and says so.

**The call.** `POST` Gemini's `generateContent` on `ingest.statements.GEMINI_URL`: the household's
own key (`LEGION_GEMINI_KEY`) and the small model the engine already uses
(`statements.GEMINI_MODEL`). No key, a refusal, a timeout or an unreadable answer each fail in
words. Token counts and the ESTIMATED cost come from `brief.news.usage_of`, whose rate constants
are named and labelled an estimate there; the runner stores them on the step row.

**The labels** (ruling 02) are the runner's: `chosen_by = model` with the model's name on the
trace, and "(a model chose ...)" in the result.

The module-level `_transport` is replaced in every test (`tests/conftest.py`), so no test reaches
Google; a test hands `choose` its own transport.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from brief.news import usage_of
from ingest import statements
from ingest.drive import _OPENER, Response, Transport

# Ruling 04's budget is 4 s for a whole run with a decide step; the call gets most of it.
TIMEOUT_SECONDS = 3
MAX_CONTEXT_CHARS = 4000


@dataclass(frozen=True)
class Choice:
    branch: str
    model: str
    prompt_tokens: int | None = None
    output_tokens: int | None = None
    cost_estimate_micro_usd: int | None = None


class DecideFailed(Exception):
    """No branch was chosen by the model. `message` says why, in words. `refused` is True when
    Google answered with an error (nothing billed). `usage` is set when a call was made."""

    def __init__(self, message: str, *, refused: bool = False, usage: dict | None = None):
        super().__init__(message)
        self.message = message
        self.refused = refused
        self.usage = usage or {}


def prompt(question: str, context, branches: list[str]) -> str:
    try:
        facts = json.dumps(context, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        facts = json.dumps(str(context))
    if len(facts) > MAX_CONTEXT_CHARS:
        facts = facts[:MAX_CONTEXT_CHARS] + "..."
    return (
        'You choose one branch of a fixed procedure. Answer with JSON {"branch": <name>} and '
        "nothing else. The name must be exactly one of: "
        + ", ".join(branches)
        + f".\n\nQuestion: {question}\n\nFacts: {facts}"
    )


def request_body(question: str, context, branches: list[str]) -> bytes:
    return json.dumps(
        {
            "contents": [
                {"role": "user", "parts": [{"text": prompt(question, context, branches)}]}
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": {
                    "type": "OBJECT",
                    "properties": {"branch": {"type": "STRING", "enum": list(branches)}},
                    "required": ["branch"],
                },
                "temperature": 0,
            },
        }
    ).encode()


def _transport(method: str, url: str, headers: dict[str, str], body: bytes | None) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


def parse(payload: dict, branches: list[str]) -> str:
    """The chosen branch, or DecideFailed. Pure over the response JSON."""
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    candidate = candidates[0] if isinstance(candidates, list) and candidates else {}
    candidate = candidate if isinstance(candidate, dict) else {}
    content = candidate.get("content") if isinstance(candidate.get("content"), dict) else {}
    parts = content.get("parts") if isinstance(content.get("parts"), list) else []
    text = "".join(
        p["text"]
        for p in parts
        if isinstance(p, dict) and isinstance(p.get("text"), str) and p.get("thought") is not True
    ).strip()
    try:
        answer = json.loads(text)
    except ValueError:
        answer = None
    branch = answer.get("branch") if isinstance(answer, dict) else None
    if not isinstance(branch, str) or branch not in branches:
        raise DecideFailed(
            "The model did not pick one of the named branches, so no model choice was used."
        )
    return branch


def choose(
    question: str,
    context,
    branches: list[str],
    *,
    transport: Transport | None = None,
    key: str | None = None,
) -> Choice:
    key = key if key is not None else statements.gemini_key()
    if not key:
        raise DecideFailed(
            f"No model was asked: {statements.GEMINI_KEY_ENV} is not set on this server.",
            refused=True,
        )
    send = transport or _transport
    try:
        response = send(
            "POST",
            statements.GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            request_body(question, context, branches),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError, OSError) as exc:
        raise DecideFailed(
            f"The model did not answer in time or could not be reached ({type(exc).__name__})."
        ) from None
    if response.status != 200:
        raise DecideFailed(
            f"Google answered the model call with HTTP {response.status}; no model choice was "
            "made.",
            refused=True,
        )
    try:
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("not an object")
    except (ValueError, UnicodeDecodeError):
        raise DecideFailed("The model's answer could not be read.") from None
    usage = usage_of(payload)
    try:
        branch = parse(payload, branches)
    except DecideFailed as exc:
        raise DecideFailed(exc.message, usage=usage) from None
    return Choice(
        branch=branch,
        model=statements.GEMINI_MODEL,
        prompt_tokens=usage.get("prompt_tokens"),
        output_tokens=usage.get("output_tokens"),
        cost_estimate_micro_usd=usage.get("cost_estimate_micro_usd"),
    )
