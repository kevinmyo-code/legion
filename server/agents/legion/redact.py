"""What a trace may keep of a tool call (plan section 15.1, ADR 0099).

- **Mail never**: a mail tool's arguments, result and any text it returned are dropped before a
  row is written; the trace says only that the tool ran. No mail tool exists until stage 4
  (`agents/legion/mail/`); `is_mail_tool` already catches the names that stage will use, and
  `tests/test_agents_runtime_legion.py` pins that a mail-shaped call stores nothing.
- **Arguments**: kept as the web conversation record keeps them (`devicelogs/web_conversation.py`),
  minus anything shaped like a secret or an ID number (ADR 0076: a vault value never lands in a
  log): long digit runs are masked.
- **Results**: trimmed to `RESULT_CHARS`.
- **History** (the next turn's context, kept on the turn row): a mail tool's response is replaced
  by a one-line stub, so mail text never reaches turn state, checkpoints or the next turn.
- **Outside text, by class** (engine-brain E2, plan 1119 section 5): the callers pass `outside`,
  the names whose results were outside text in this turn (`TurnState.outside`: an engine
  third-party tool, a relayed read-through call, whatever its name). Their results are stubbed,
  their own arguments too (a search query names the topic), and so is everything the model said
  or asked after the first of them (its words and the arguments of its later calls can carry that
  text on). The model's words become `OUTSIDE_ANSWER_NOTE`: answered, not kept, done. Names
  alone never decide it: a relayed `browser` or `look_up` matches no mail pattern.
"""

from __future__ import annotations

import json
import re

from assistant.read_through import THIRD_PARTY_TOOLS

# The phone's `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` (`service/LiveToolbox.kt`, read 2026-11-05): a
# copy, since the engine image holds no Kotlin (`tests/test_procedures_definition.py` and
# `tests/test_member_memory_consolidate.py` fail when it drifts).
PHONE_READ_THROUGH_TOOLS: frozenset[str] = frozenset(
    {
        "search_mail",
        "read_mail",
        "ask_mail",
        "get_sitrep",
        "track_package",
        "flight_status",
        "browser",
        "email",
    }
)
# Every tool whose result is outside text by its name: the phone's and the engine's third-party
# tools. The one list the conversation record, memory consolidation and the procedure count read.
READ_THROUGH_TOOLS: frozenset[str] = PHONE_READ_THROUGH_TOOLS | THIRD_PARTY_TOOLS

RESULT_CHARS = 400
ARG_CHARS = 200
MAIL_STUB = "A mail tool ran. Its content is never stored (ADR 0099)."
OUTSIDE_STUB = "Outside text was read here. It is never stored (ADR 0090)."
OUTSIDE_ARGS = {"note": "[after an outside read: not kept]"}
# What the next turn replays in place of a reply after an outside read: the phone's
# `CascadePrompt.OUTSIDE_ANSWER_NOTE`, word for word (tests/test_agents_outside_answer.py pins it).
# The stored `final_text` and the done event keep `OUTSIDE_REPLY_STUB`: those are records, not
# words the model reads.
OUTSIDE_ANSWER_NOTE = (
    "(Answered from a web search or other outside text. That answer was not kept. "
    "Done unless he asks again.)"
)
# Stage 4's tools (`mail_*`, `gmail_*`, `read_mail`, `send_mail`, `draft_mail`).
_MAIL = re.compile(r"(^|_)(mail|gmail|email|inbox)(_|$)", re.IGNORECASE)
# Nine or more digits, spaces or dashes between them allowed (an SSN, an account number), never a
# YYYY-MM-DD date (a calendar read's window must stay readable in its trace).
_ID_SHAPED = re.compile(r"(?<![\d-])(?!\d{4}-\d{2}-\d{2}(?!\d))\d(?:[ -]?\d){8,}(?![\d-])")
_SECRET_KEYS = re.compile(r"(token|secret|password|key|passport|ssn|account_number)", re.I)


def is_mail_tool(name: str) -> bool:
    return bool(_MAIL.search(name or ""))


def reads_outside(name: str) -> bool:
    """Whether a tool's result is outside text by its name alone (a relayed tool or a procedure
    step can be outside text by class too: callers pass that as `outside`)."""
    return name in READ_THROUGH_TOOLS or is_mail_tool(name)


def _mask(value: str) -> str:
    return _ID_SHAPED.sub(lambda m: "#" * len(m.group(0)), value)[:ARG_CHARS]


def redact_args(name: str, args: dict, after_outside: bool = False) -> dict:
    """`after_outside`: the call was made once outside text was in the turn, so its arguments may
    carry that text: none are kept."""
    if is_mail_tool(name):
        return {}
    if after_outside:
        return dict(OUTSIDE_ARGS)

    def clean(key, value):
        if isinstance(key, str) and _SECRET_KEYS.search(key):
            return "[redacted]"
        if isinstance(value, str):
            return _mask(value)
        if isinstance(value, dict):
            return {k: clean(k, v) for k, v in value.items()}
        if isinstance(value, list):
            return [clean(key, v) for v in value[:20]]
        return value

    return {k: clean(k, v) for k, v in (args or {}).items()}


def trim_result(name: str, text: str, outside: bool = False) -> str:
    """`outside`: the result is outside text (its class, not its name): only the stub is kept."""
    if is_mail_tool(name):
        return MAIL_STUB
    if outside:
        return OUTSIDE_STUB
    return (text or "")[:RESULT_CHARS]


def _stub_response(resp: dict, note: str) -> dict:
    # A relayed call's id stays (an engine-made uuid, no text), so a later result can find it.
    cid = (resp.get("response") or {}).get("call_id") if isinstance(resp, dict) else None
    return {
        "functionResponse": {
            **{k: v for k, v in resp.items() if k != "response"},
            "response": {"note": note, **({"call_id": cid} if isinstance(cid, str) else {})},
        }
    }


def history_for_storage(messages: list[dict], outside=()) -> list[dict]:
    """The turn's history items as they may be stored: every mail tool response replaced by the
    stub, and the call's arguments with it; every `outside` call's arguments and response
    stubbed, and from the first of them on, every later response and call's arguments too; the
    model's words after it become one `OUTSIDE_ANSWER_NOTE` per reply."""
    outside = frozenset(outside)
    seen_outside = False
    out = []
    for content in messages:
        parts = []
        for part in content.get("parts", []):
            call = part.get("functionCall")
            resp = part.get("functionResponse")
            if isinstance(call, dict) and is_mail_tool(call.get("name", "")):
                part = {**part, "functionCall": {**call, "args": {}}}
            elif isinstance(call, dict) and (seen_outside or call.get("name") in outside):
                # The call that read outside text keeps its name, not its query: the query names
                # the topic, and a replayed topic is one the model goes back to.
                part = {**part, "functionCall": {**call, "args": dict(OUTSIDE_ARGS)}}
            elif isinstance(resp, dict) and is_mail_tool(resp.get("name", "")):
                part = _stub_response(resp, MAIL_STUB)
            elif isinstance(resp, dict) and (seen_outside or resp.get("name") in outside):
                # From the first outside result on, every result: a later call may echo text its
                # arguments carried on.
                seen_outside = True
                part = _stub_response(resp, OUTSIDE_STUB)
            elif seen_outside and "text" in part and content.get("role") == "model":
                # One note per reply: it was answered and is done (a bare placeholder read as an
                # unanswered question the model kept going back to).
                if any(p.get("text") == OUTSIDE_ANSWER_NOTE for p in parts):
                    continue
                part = {**part, "text": OUTSIDE_ANSWER_NOTE}
            parts.append(part)
        out.append({**content, "parts": parts})
    return json.loads(json.dumps(out, default=str))
