"""Third-party text kept out of a conversation record (CLAUDE.md section 7, ticket #475).

The phone redacts on the device before upload (`LiveAudit.kt`): a read-through tool's own
`tool_result` carries `response="[redacted: third-party content]"`, and every assistant line from
that turn on is that marker too. The web record is written by the engine, so the engine does the
same at its one write site (`web_conversation.append`), before anything is stored, by the rule the
engine's own agent trace keeps (`agents.legion.redact`, the source of its stubs and mail names):

- a `tool_result` of a read-through tool, or of a procedure that reads one, keeps its name,
  success and timing; its reply becomes the marker;
- from the start of the turn that first read outside text (such a result, a `read_through` line,
  or a line already redacted) to the end of the conversation, every `assistant` and
  `assistant_replay` line becomes the marker, and every `tool_call`'s arguments become
  `redact.OUTSIDE_ARGS` (a query names the topic; a later call's arguments can carry the page's
  text on). Sticky, as on the phone.

His own words (`user` lines) always stay: they are first party. Lines already stored are never
rewritten here; `count_read_through_web` counts them.
"""

from __future__ import annotations

import json
import re

from agents.legion import redact as trace
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
RUN_PROCEDURE = "run_procedure"

_QUOTED = json.dumps(REDACTED)
_OUTSIDE_ARGS = json.dumps(trace.OUTSIDE_ARGS, separators=(",", ":"))
_AUDIT = re.compile(
    r"^(?P<head>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ [VDIWEF] LIVE_AUDIT: )(?P<msg>.*)$"
)
_NAME = re.compile(r" name=(\S+)")
_ID = re.compile(r" id=(\S+)")
_PROCEDURE = re.compile(r'"name":\s*"([^"]+)"')
# Where a turn starts: everything after the last of these before the first read is tainted.
_TURN_START = ("user", "turn_complete", "session_start")
# "MM-DD HH:MM:SS.mmm": a line's stamp. It has no year.
_STAMP_LEN = 18


def sort_key(opened: str, text: str) -> tuple[int, str]:
    """A line's place in its record. A record is opened by its first line (`opened`) and lives
    far less than a year, so a month-day before the opening's is in the next year: a read just
    before midnight on 12-31 still sorts before the answer just after it."""
    stamp = text[:_STAMP_LEN]
    return (int(stamp[:5] < opened[:5]), stamp)


def _parts(text: str) -> tuple[str, str, str] | None:
    """(head, event, msg) of a LIVE_AUDIT line, or None for any other line."""
    found = _AUDIT.match(text)
    if found is None:
        return None
    msg = found.group("msg")
    return found.group("head"), msg.partition(" ")[0], msg


def _field(pattern: re.Pattern, msg: str) -> str:
    found = pattern.search(msg)
    return found.group(1) if found else ""


def redact_tool_result(text: str) -> str:
    """A `tool_result` line with its reply replaced by the marker; any other line unchanged."""
    parts = _parts(text)
    if parts is None or parts[1] != "tool_result" or " response=" not in parts[2]:
        return text
    head, _, msg = parts
    return f"{head}{msg.partition(' response=')[0]} response={_QUOTED}"


def _reads_outside(msg: str, procedures: frozenset[str]) -> bool:
    """A tool call whose result is outside text: a read-through tool, or a procedure named in
    `procedures` (one whose steps call one)."""
    name = _field(_NAME, msg)
    if name in READ_THROUGH_TOOLS or trace.is_mail_tool(name):
        return True
    args = msg.partition(" args=")[2]
    return name == RUN_PROCEDURE and _field(_PROCEDURE, args) in procedures


def _outside_results(parsed, procedures: frozenset[str]) -> set[int]:
    """Positions of the `tool_result` lines whose replies are outside text, each paired with the
    latest call before it under the same id (a cascade reuses `c1` every turn)."""
    calls: dict[str, bool] = {}
    out = set()
    for n, p in enumerate(parsed):
        if p is None or p[1] not in ("tool_call", "tool_result"):
            continue
        call_id = _field(_ID, p[2])
        if p[1] == "tool_call":
            calls[call_id] = _reads_outside(p[2], procedures)
        elif calls.get(call_id) or _reads_outside(p[2], procedures):
            out.add(n)
    return out


def redact(
    existing: list[str], fresh: list[tuple], *, procedures: frozenset[str] = frozenset()
) -> list[tuple]:
    """`fresh` (stamped lines about to be stored) with third-party text replaced, judged in the
    order the record will hold them beside `existing`. `existing` is only read. `procedures`:
    names of procedures that read outside text, for counting old records; a live procedure call
    is caught by its request's flag (`web_conversation.record_tool`)."""
    opened = existing[0] if existing else (fresh[0][1] if fresh else "")
    merged = [(text, False, i) for i, text in enumerate(existing)]
    merged += [(text, True, i) for i, (_, text) in enumerate(fresh)]
    merged.sort(key=lambda item: sort_key(opened, item[0]))  # stable: existing first
    parsed = [_parts(item[0]) for item in merged]
    outside = _outside_results(parsed, procedures)

    def reads(n: int) -> bool:
        p = parsed[n]
        if p is None or p[1] == "user":
            return False
        return n in outside or p[1] == "read_through" or REDACTED in p[2]

    first = next((n for n in range(len(merged)) if reads(n)), None)
    taint_from = len(merged)
    if first is not None:
        taint_from = 0
        for n in range(first, -1, -1):
            if parsed[n] is not None and parsed[n][1] in _TURN_START:
                taint_from = n + 1
                break
    out = list(fresh)
    for n, (text, is_fresh, i) in enumerate(merged):
        p = parsed[n]
        if not is_fresh or p is None:
            continue
        head, event, msg = p
        if n in outside:
            text = redact_tool_result(text)
        elif n >= taint_from and event == "tool_call" and " args=" in msg:
            text = f"{head}{msg.partition(' args=')[0]} args={_OUTSIDE_ARGS}"
        elif n >= taint_from and event in ("assistant", "assistant_replay"):
            text = f"{head}{event} text={_QUOTED}"
        out[i] = (fresh[i][0], text)
    return out
