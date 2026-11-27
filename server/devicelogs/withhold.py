"""Withhold third-party text from a stored batch before the conversations page renders it (#160).

CLAUDE.md section 7: what other people wrote to Kevin is read through and dropped. The phone
redacts mail on the device before it uploads, but the web record stores a tool's reply and the
assistant's words as the engine saw them (`devicelogs/web_conversation.py`), and an older or
other device may not redact at all. So the engine never relies on the device: this runs on the
stored text at render time and replaces what a read-through turn produced with `WITHHELD`.

The rule is the consolidation job's (`member_memory.consolidate.transcript`), so the page and
the memory job agree on which turns are tainted:

- a turn (the lines up to `turn_complete`, or to a session boundary) is tainted when it calls a
  tool in `READ_THROUGH_TOOLS`, carries a `read_through` mark, or already holds a redaction mark;
- from a tainted turn to the end of its session, the assistant's words are withheld (they may
  paraphrase what was read), and so is every tool call's arguments (the model may have filled
  them from what it read); a tool's reply is withheld in a tainted turn;
- the person's own words always show, and the next session starts clean;
- a session is not a stored batch: the phone seals a batch by size, so one that opens without a
  `session_start` carries the taint of the batch before it (`opens_session`, `carry`).

Stored data is never edited: this only changes what is shown.
"""

from __future__ import annotations

import json

from devicelogs import voice_audit
from member_memory.consolidate import READ_THROUGH_EVENT, READ_THROUGH_TOOLS, REDACTION_MARK

WITHHELD = "withheld: read from mail"


def _marked(value) -> bool:
    return isinstance(value, str) and (voice_audit.REDACTED in value or REDACTION_MARK in value)


def _taints(event: str, fields: dict) -> bool:
    if event == READ_THROUGH_EVENT:
        return True
    if event in ("tool_call", "tool_result") and fields.get("name") in READ_THROUGH_TOOLS:
        return True
    return _marked(fields.get("text")) or _marked(fields.get("response"))


def _with_text(msg: str) -> str:
    """`msg` with its quoted `text=` value replaced."""
    start = msg.find("text=")
    if start < 0 or not msg.startswith('"', start + 5):
        return f"assistant text={json.dumps(WITHHELD)}"
    _value, end = voice_audit._DECODER.raw_decode(msg, start + 5)
    return msg[: start + 5] + json.dumps(WITHHELD) + msg[end:]


def _with_response(msg: str) -> str:
    """`msg` with its `response=` value (always the last field) replaced."""
    start = msg.find("response=")
    return msg if start < 0 else msg[: start + 9] + json.dumps(WITHHELD)


def _with_args(msg: str) -> str:
    """`msg` with its `args=` value (always the last field) replaced."""
    start = msg.find(" args=")
    return msg if start < 0 else msg[: start + 6] + json.dumps(WITHHELD)


def _parsed(text: str) -> tuple[list, list]:
    records = voice_audit._records(text.splitlines())
    return records, [
        voice_audit.parse_audit(msg) if tag == voice_audit.AUDIT else (None, {})
        for _date, _time, tag, msg in records
    ]


def opens_session(text: str) -> bool:
    """True when the batch's first audit line is a `session_start`. A phone batch sealed by size
    can open mid-session, and then it continues the previous batch's session."""
    _records, parsed = _parsed(text)
    return next((event for event, _ in parsed if event), None) == "session_start"


def withhold(text: str, carry: bool = False) -> tuple[str, bool]:
    """(`text` with third-party content replaced by `WITHHELD`, whether the session is still
    tainted at the batch's end). `carry` is that flag from the batch before, for a batch that
    opens mid-session."""
    records, parsed = _parsed(text)
    # Pass 1: mark every record in a tainted turn. A turn ends at its `turn_complete`, at a
    # `session_end`, or just before the next `session_start`.
    tainted = [False] * len(records)
    start = 0

    def close(end: int) -> None:
        nonlocal start
        if any(_taints(e, f) for e, f in parsed[start:end] if e):
            tainted[start:end] = [True] * (end - start)
        start = end

    for i, (event, _fields) in enumerate(parsed):
        if event == "session_start":
            close(i)
        elif event in ("turn_complete", "session_end"):
            close(i + 1)
    close(len(records))
    out: list[str] = []
    session_tainted = carry
    previous_withheld = False
    for (date, time, tag, msg), (event, fields), turn_tainted in zip(
        records, parsed, tainted, strict=True
    ):
        if event in ("session_start", "session_end"):
            session_tainted = False
        session_tainted = session_tainted or turn_tainted
        if event == "assistant" and session_tainted and not _marked(fields.get("text")):
            # An already-redacted line stays as the device wrote it.
            if not previous_withheld:
                out.append(f"{date} {time} 0 0 I {tag}: {_with_text(msg)}")
            previous_withheld = True
            continue
        if event == "tool_result" and turn_tainted:
            msg = _with_response(msg)
        if event == "tool_call" and session_tainted:
            msg = _with_args(msg)
        if tag == voice_audit.AUDIT:
            previous_withheld = False
        out.append(f"{date} {time} 0 0 I {tag}: {msg}")
    return "\n".join(out), session_tainted
