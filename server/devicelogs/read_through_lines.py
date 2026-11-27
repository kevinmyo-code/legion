"""Third-party text kept out of a conversation record (CLAUDE.md section 7, ticket #475).

The phone redacts on the device before upload (`LiveAudit.kt`): a read-through tool's own
`tool_result` carries `response="[redacted: third-party content]"`, and the assistant's lines in
that turn are that marker too, until the turn ends (`resetTurn`). The web record is written by
the engine, so the engine does the same at its one write site (`web_conversation.append`), before
anything is stored, by the agent trace's rule (`agents.legion.redact`: which tools read outside
text, and the stub for arguments):

- a `tool_result` of a read-through tool, or of a procedure that reads one, keeps its name,
  success and timing; its reply becomes the marker;
- in the turn that read it and in the next one (the carry, ADR 0090 amended: that reply may echo
  the page through the turn before), every `assistant` and `assistant_replay` line becomes the
  marker and every `tool_call`'s arguments become `redact.OUTSIDE_ARGS` (a query names the topic;
  a later call's arguments can carry the page's text on). Then it resets: later turns are stored
  as they were said. A `read_through` line covers its own turn.
- a withheld engine reply keeps its echo fingerprint beside it (`assistant_echo print=`,
  `assistant.echo.fingerprint`): keyed hashes only, so the echo check still works and no word can
  be read back.

His own words (`user` lines) always stay: they are first party. Lines already stored are never
rewritten here; `count_read_through_web` counts them.
"""

from __future__ import annotations

import datetime
import json
import re

from agents.legion import redact as trace
from assistant import echo
from devicelogs.voice_audit import REDACTED

RUN_PROCEDURE = "run_procedure"
ECHO_EVENT = "assistant_echo"

_QUOTED = json.dumps(REDACTED)
_OUTSIDE_ARGS = json.dumps(trace.OUTSIDE_ARGS, separators=(",", ":"))
_AUDIT = re.compile(
    r"^(?P<head>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+(?P<pid>\d+)\s+(?P<tid>\d+) [VDIWEF] "
    r"LIVE_AUDIT: )(?P<msg>.*)$"
)
_NAME = re.compile(r" name=(\S+)")
_ID = re.compile(r" id=(\S+)")
_PROCEDURE = re.compile(r'"name":\s*"([^"]+)"')
_PRINT = re.compile(r" print=(\S*)$")
# A turn starts with his words (or the engine's note that they were not kept).
_TURN_START = ("user", "user_withheld")
# "MM-DD HH:MM:SS.mmm": a line's stamp. It has no year.
_STAMP_LEN = 18
_ENGINE_TID = "2"


def sort_key(opened: str, text: str) -> tuple[int, str]:
    """A line's place in its record. A record lives far less than a year, so in a record opened
    in December a January stamp is the next year: a read just before midnight on 12-31 still
    sorts before the answer just after it. Any other stamp sorts as it reads."""
    stamp = text[:_STAMP_LEN]
    return (int(opened[:2] == "12" and stamp[:2] == "01"), stamp)


def _parts(text: str):
    """(head, event, msg, tid) of a LIVE_AUDIT line, or None for any other line."""
    found = _AUDIT.match(text)
    if found is None:
        return None
    msg = found.group("msg")
    return found.group("head"), msg.partition(" ")[0], msg, found.group("tid")


def _field(pattern: re.Pattern, msg: str) -> str:
    found = pattern.search(msg)
    return found.group(1) if found else ""


def redact_tool_result(text: str) -> str:
    """A `tool_result` line with its reply replaced by the marker; any other line unchanged."""
    parts = _parts(text)
    if parts is None or parts[1] != "tool_result" or " response=" not in parts[2]:
        return text
    head, _, msg, _ = parts
    return f"{head}{msg.partition(' response=')[0]} response={_QUOTED}"


def echo_print(text: str) -> tuple[str, ...]:
    """The hashes an `assistant_echo` line holds, or () for any other line."""
    parts = _parts(text)
    if parts is None or parts[1] != ECHO_EVENT:
        return ()
    return tuple(h for h in _field(_PRINT, parts[2]).split(",") if h)


def _reads_outside(msg: str, procedures: frozenset[str]) -> bool:
    """A tool call whose result is outside text: a read-through tool, or a procedure named in
    `procedures` (one whose steps call one)."""
    name = _field(_NAME, msg)
    if trace.reads_outside(name):
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


def _tainted_turns(parsed, outside: set[int]) -> tuple[list[int], set[int]]:
    """(each line's turn number, the turns whose assistant words and call arguments are kept
    out): a read's turn and the next; a `read_through` line's own turn."""
    turns, turn = [], 0
    tainted: set[int] = set()
    for n, p in enumerate(parsed):
        if p is not None and p[1] in _TURN_START:
            turn += 1
        turns.append(turn)
        if p is None or p[1] in _TURN_START:
            continue
        if n in outside or (p[1] == "tool_result" and REDACTED in p[2]):
            tainted |= {turn, turn + 1}
        elif p[1] == "read_through":
            tainted.add(turn)
    return turns, tainted


def redact(
    existing: list[str],
    fresh: list[tuple],
    *,
    procedures: frozenset[str] = frozenset(),
    prints: bool = True,
) -> list[tuple]:
    """`fresh` (stamped lines about to be stored) with third-party text replaced, judged in the
    order the record will hold them beside `existing`. `existing` is only read. `procedures`:
    names of procedures that read outside text, for counting old records (a live procedure call
    is caught by its request's flag, `web_conversation.record_tool`). `prints`: put each withheld
    engine reply's echo fingerprint just before it (off when counting: nothing is written)."""
    opened = existing[0] if existing else (fresh[0][1] if fresh else "")
    merged = [(text, False, i) for i, text in enumerate(existing)]
    # A line already stored (a retried upload) is not counted twice: a repeated `user` line would
    # start a turn that never happened and move the lines after it out of the read's turns.
    stored = set(existing)
    merged += [(text, True, i) for i, (_, text) in enumerate(fresh) if text not in stored]
    merged.sort(key=lambda item: sort_key(opened, item[0]))  # stable: existing first
    parsed = [_parts(item[0]) for item in merged]
    outside = _outside_results(parsed, procedures)
    turns, tainted = _tainted_turns(parsed, outside)
    out: list[list[tuple]] = [[item] for item in fresh]
    for n, (text, is_fresh, i) in enumerate(merged):
        p = parsed[n]
        if not is_fresh or p is None:
            continue
        head, event, msg, tid = p
        at = fresh[i][0]
        hidden = turns[n] in tainted
        if n in outside:
            text = redact_tool_result(text)
        elif hidden and event == "tool_call" and " args=" in msg:
            text = f"{head}{msg.partition(' args=')[0]} args={_OUTSIDE_ARGS}"
        elif hidden and event in ("assistant", "assistant_replay"):
            withheld = f"{head}{event} text={_QUOTED}"
            if prints and event == "assistant" and tid == _ENGINE_TID and text != withheld:
                try:
                    said = json.loads(msg.partition(" text=")[2])
                except ValueError:
                    said = ""
                hashes = ",".join(echo.fingerprint(said if isinstance(said, str) else ""))
                out[i] = [(at, f"{head}{ECHO_EVENT} print={hashes}")]
                out[i].append((at, withheld))
                continue
            text = withheld
        out[i] = [(at, text)]
    return [line for group in out for line in group]


# ---- the fingerprint is transient ---------------------------------------------------------------
#
# A print is derived from the withheld text, and anyone holding SECRET_KEY could test guesses
# against it, so it lives only as long as the echo check needs it: until the next turn after the
# reply has completed (his words, then that turn's `cascade_turn` timing line), or ECHO_TTL,
# whichever comes first. `web_conversation.append` drops spent prints at every write;
# `sweep_prints` (the hourly `prune_demo_visitors`) drops any left behind.

ECHO_TTL = datetime.timedelta(minutes=5)
_TURN_DONE = " LIVE_LATENCY: cascade_turn status="


def _stamp_time(text: str, now: datetime.datetime) -> datetime.datetime | None:
    try:
        at = datetime.datetime.strptime(f"{now.year}-{text[:_STAMP_LEN]}", "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None
    at = at.replace(tzinfo=datetime.UTC)
    # A stamp from December read in January is last year's.
    return at.replace(year=now.year - 1) if at > now + datetime.timedelta(days=1) else at


def drop_spent_prints(lines: list[str], now: datetime.datetime) -> list[str]:
    """`lines` (in record order) without the echo prints whose window has closed."""
    keep = []
    for n, text in enumerate(lines):
        p = _parts(text)
        if p is not None and p[1] == ECHO_EVENT:
            at = _stamp_time(text, now)
            if at is None or now - at >= ECHO_TTL or _next_turn_done(lines, n):
                continue
        keep.append(text)
    return keep


def _next_turn_done(lines: list[str], n: int) -> bool:
    heard = False
    for text in lines[n + 1 :]:
        p = _parts(text)
        if p is not None and p[1] in _TURN_START:
            heard = True
        elif heard and _TURN_DONE in text:
            return True
    return False


def sweep_prints(now: datetime.datetime | None = None) -> int:
    """Remove every spent echo print from the stored web conversation records. Returns how many
    prints were removed. Nothing else in a record changes."""
    import gzip

    from django.db import transaction
    from django.utils import timezone

    from devicelogs.models import DEVICE_LABEL_WEB, REASON_CONVERSATION, DeviceLog
    from devicelogs.views import inflate_capped

    now = now or timezone.now()
    removed = 0
    rows = DeviceLog.objects.filter(device_label=DEVICE_LABEL_WEB, reason=REASON_CONVERSATION)
    for pk in rows.values_list("pk", flat=True).iterator():
        with transaction.atomic():
            row = DeviceLog.objects.select_for_update().filter(pk=pk).first()
            if row is None:
                continue
            body = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace")
            if f" {ECHO_EVENT} " not in body:
                continue
            lines = body.splitlines()
            kept = drop_spent_prints(lines, now)
            if len(kept) == len(lines):
                continue
            removed += len(lines) - len(kept)
            raw = ("\n".join(kept) + "\n").encode("utf-8") if kept else b""
            row.body_gz = gzip.compress(raw)
            row.raw_bytes = len(raw)
            row.line_count = len(kept)
            row.save(update_fields=["body_gz", "raw_bytes", "line_count"])
    return removed
