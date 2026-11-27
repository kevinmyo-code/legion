"""Third-party text kept out of a conversation record (CLAUDE.md section 7, ticket #475).

The phone redacts on the device before upload (`LiveAudit.kt`): a read-through tool's own
`tool_result` carries `response="[redacted: third-party content]"`, and every assistant line from
that turn on is that marker too. The web record is written by the engine, so the engine does the
same at its one write site (`web_conversation.append`), before anything is stored:

- a `tool_result` of a tool in `READ_THROUGH_TOOLS` keeps its name, success and timing, and its
  reply becomes the marker;
- from the start of the turn that first read outside text (a read-through tool, a `read_through`
  line, or a line already redacted) to the end of the conversation, every `assistant` and
  `assistant_replay` line becomes the marker. Sticky, as on the phone: a later answer may still
  repeat what the page said.

His own words (`user` lines) always stay: they are first party. Tool arguments stay too, as on the
phone. Lines already stored are never rewritten here; `count_read_through_web` counts them.
"""

from __future__ import annotations

import re

from assistant.read_through import THIRD_PARTY_TOOLS
from devicelogs.voice_audit import REDACTED

# The phone's `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` (`service/LiveToolbox.kt`, read 2026-11-05),
# plus the engine's third-party tools (`assistant.read_through.THIRD_PARTY_TOOLS`). A copy of the
# phone's half: the engine image holds no Kotlin. Memory consolidation reads the same set.
READ_THROUGH_TOOLS = frozenset(
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
    | THIRD_PARTY_TOOLS
)

_QUOTED = '"' + REDACTED + '"'
_AUDIT = re.compile(
    r"^(?P<head>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ [VDIWEF] LIVE_AUDIT: )(?P<msg>.*)$"
)
_NAME = re.compile(r" name=(\S+)")
# Where a turn starts: everything after the last of these before the first read is tainted.
_TURN_START = ("user", "turn_complete", "session_start")
# "MM-DD HH:MM:SS.mmm", the sort key `web_conversation.append` orders lines by.
_STAMP_LEN = 18


def _parts(text: str) -> tuple[str, str, str] | None:
    """(head, event, msg) of a LIVE_AUDIT line, or None for any other line."""
    found = _AUDIT.match(text)
    if found is None:
        return None
    msg = found.group("msg")
    return found.group("head"), msg.partition(" ")[0], msg


def _name(msg: str) -> str:
    found = _NAME.search(msg)
    return found.group(1) if found else ""


def redact_tool_result(text: str) -> str:
    """A `tool_result` line with its reply replaced by the marker; any other line unchanged."""
    parts = _parts(text)
    if parts is None or parts[1] != "tool_result" or " response=" not in parts[2]:
        return text
    head, _, msg = parts
    return f"{head}{msg.partition(' response=')[0]} response={_QUOTED}"


def _reads_outside(event: str, msg: str) -> bool:
    if event == "read_through":
        return True
    if event in ("tool_call", "tool_result") and _name(msg) in READ_THROUGH_TOOLS:
        return True
    return REDACTED in msg


def _redacted(text: str, tainted: bool) -> str:
    parts = _parts(text)
    if parts is None:
        return text
    head, event, msg = parts
    if event == "tool_result" and _name(msg) in READ_THROUGH_TOOLS:
        return redact_tool_result(text)
    if tainted and event in ("assistant", "assistant_replay"):
        return f"{head}{event} text={_QUOTED}"
    return text


def redact(existing: list[str], fresh: list[tuple]) -> list[tuple]:
    """`fresh` (stamped lines about to be stored) with third-party text replaced by the marker,
    judged in the order the record will hold them beside `existing`. `existing` is only read."""
    merged = [(text[:_STAMP_LEN], False, i, text) for i, text in enumerate(existing)]
    merged += [(text[:_STAMP_LEN], True, i, text) for i, (_, text) in enumerate(fresh)]
    merged.sort(key=lambda item: item[0])  # stable: existing first, as `append` sorts
    events = [_parts(item[3]) for item in merged]
    first = next(
        (n for n, p in enumerate(events) if p is not None and _reads_outside(p[1], p[2])), None
    )
    taint_from = len(merged)
    if first is not None:
        taint_from = 0
        for n in range(first, -1, -1):
            if events[n] is not None and events[n][1] in _TURN_START:
                taint_from = n + 1
                break
    out = list(fresh)
    for n, (_, is_fresh, i, text) in enumerate(merged):
        if is_fresh:
            out[i] = (fresh[i][0], _redacted(text, n >= taint_from))
    return out
