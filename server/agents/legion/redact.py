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
"""

from __future__ import annotations

import json
import re

RESULT_CHARS = 400
ARG_CHARS = 200
MAIL_STUB = "A mail tool ran. Its content is never stored (ADR 0099)."
# Stage 4's tools (`mail_*`, `gmail_*`, `read_mail`, `send_mail`, `draft_mail`).
_MAIL = re.compile(r"(^|_)(mail|gmail|email|inbox)(_|$)", re.IGNORECASE)
# Nine or more digits, spaces or dashes between them allowed (an SSN, an account number), never a
# YYYY-MM-DD date (a calendar read's window must stay readable in its trace).
_ID_SHAPED = re.compile(r"(?<![\d-])(?!\d{4}-\d{2}-\d{2}(?!\d))\d(?:[ -]?\d){8,}(?![\d-])")
_SECRET_KEYS = re.compile(r"(token|secret|password|key|passport|ssn|account_number)", re.I)


def is_mail_tool(name: str) -> bool:
    return bool(_MAIL.search(name or ""))


def _mask(value: str) -> str:
    return _ID_SHAPED.sub(lambda m: "#" * len(m.group(0)), value)[:ARG_CHARS]


def redact_args(name: str, args: dict) -> dict:
    if is_mail_tool(name):
        return {}

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


def trim_result(name: str, text: str) -> str:
    if is_mail_tool(name):
        return MAIL_STUB
    return (text or "")[:RESULT_CHARS]


def history_for_storage(messages: list[dict]) -> list[dict]:
    """The turn's history items as they may be stored: every mail tool response replaced by the
    stub, and the call's arguments with it."""
    out = []
    for content in messages:
        parts = []
        for part in content.get("parts", []):
            call = part.get("functionCall")
            resp = part.get("functionResponse")
            if isinstance(call, dict) and is_mail_tool(call.get("name", "")):
                part = {**part, "functionCall": {**call, "args": {}}}
            elif isinstance(resp, dict) and is_mail_tool(resp.get("name", "")):
                part = {
                    "functionResponse": {
                        **{k: v for k, v in resp.items() if k != "response"},
                        "response": {"note": MAIL_STUB},
                    }
                }
            parts.append(part)
        out.append({**content, "parts": parts})
    return json.loads(json.dumps(out, default=str))
