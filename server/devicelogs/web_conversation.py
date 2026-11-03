"""The web assistant's conversation records (2026-11-03).

Kevin: "recorded to improve and train for future conversations ... i dont need to read it, its
more for improving and seeing what tool calls etc went wrong", and earlier "make sure every convo
is recorded even if im not home" and "all logs on engine. both headunit and phone". The phone and
the head unit already upload `reason=conversation` batches of LIVE_AUDIT / LIVE_LATENCY lines;
the web assistant's record is one `DeviceLog` row per conversation in the SAME line format, so
`manage.py device_logs --reason conversation --render` and `tools/voice_audit.py` read it as
they read a phone's.

Who writes what:

- **The mint** (`POST /api/assistant/session`) opens the row: `batch_id` is a fresh conversation
  id, handed back to the browser, and the first line is `session_start model=<the model>`.
- **The tool door** (`POST /api/assistant/tool`) writes each tool call itself, `tool_call` with
  the arguments and `tool_result` with the outcome, the engine's words (trimmed) and the latency it
  measured. The engine ran the tool, so it is the authority; a tab that dies mid-conversation
  still leaves every tool call on the record.
- **The browser** (`POST /api/assistant/conversation`) sends what only it hears: the visitor's and
  the assistant's words (Gemini's input and output transcriptions, or the typed text), turn ends,
  interruptions, cancelled tool calls, mic changes, the end, and two timings (cold start, reply).
  It sends EVENTS, never lines: this module writes every line, so a browser cannot forge a tool
  line or a tag the parser would believe.

No audio, ever. The `pid tid` columns of a line name its writer: `0 1` the browser, `0 2` the
engine. Times are UTC. A browser's times are its own clock plus `clock_offset_ms`, measured once at
the mint, so a retried upload yields the same lines and is deduplicated line by line, and the two
writers' lines interleave on one clock.

A member's row lives in their household and goes with `devicelogs.views.prune` (60 days). A
recruiter visit's row has no household (`DeviceLog.demo_visitor`), so it survives the visit's
household being deleted, and `manage.py prune_demo_visitors` deletes it after 30 days.
"""

from __future__ import annotations

import datetime
import gzip
import json
import re
import uuid

from django.db import transaction
from django.utils import timezone

from devicelogs.models import (
    DEVICE_LABEL_WEB,
    MAX_UPLOAD_BYTES,
    REASON_CONVERSATION,
    VISIT_RETENTION_DAYS,
    DeviceLog,
)

AUDIT = "LIVE_AUDIT"
LATENCY = "LIVE_LATENCY"
TID_BROWSER = 1
TID_ENGINE = 2
# The phone caps its free-text field at 400 characters with a "..." marker; a tool's result and
# arguments get the same. A turn's words are kept longer: a whole web turn is one line.
CAP_TOOL = 400
CAP_TURN = 2000
_WORD = re.compile(r"\s+")
# "MM-DD HH:MM:SS.mmm": a line's sort key.
_STAMP_LEN = 18


def _trim(text: str, cap: int) -> str:
    return text if len(text) <= cap else text[:cap] + "..."


def quoted(text: str, cap: int) -> str:
    return json.dumps(_trim(text, cap), ensure_ascii=False)


def word(text: str, cap: int = 100) -> str:
    """A bare `key=value` value: no spaces, never empty."""
    return _WORD.sub("_", text.strip())[:cap] or "-"


def line(at: datetime.datetime, tid: int, tag: str, msg: str) -> str:
    """One threadtime line, the shape `voice_audit._LINE` reads, in UTC."""
    at = at.astimezone(datetime.UTC)
    return f"{at:%m-%d %H:%M:%S}.{at.microsecond // 1000:03d}  0  {tid} I {tag}: {msg}"


def _ms(at: datetime.datetime) -> int:
    return int(at.timestamp() * 1000)


def _body(lines: list[str]) -> bytes:
    return ("\n".join(lines) + "\n").encode("utf-8") if lines else b""


def start(request, *, model: str, client_now_ms: int | None) -> DeviceLog:
    """Open the record of a conversation just minted. Returns the row; its `batch_id` is the
    conversation id the browser sends back with every tool call and upload."""
    from demo.models import DemoVisit
    from devicelogs.views import prune
    from household.models import is_recruiter
    from household.tenancy import household_of

    user = request.user
    now = timezone.now()
    offset = None if client_now_ms is None else _ms(now) - client_now_ms
    if is_recruiter(user):
        visit = DemoVisit.objects.filter(user=user).first()
        where = {
            "household": None,
            "demo_visitor": True,
            "visit": visit,
            "visit_ref": visit.ref if visit else "",
        }
    else:
        where = {"household": household_of(request)}
    opening = f"session_start model={word(model)} async_set=none"
    body = _body([line(now, TID_ENGINE, AUDIT, opening)])
    row = DeviceLog.objects.create(
        user=user,
        batch_id=uuid.uuid4(),
        device_label=DEVICE_LABEL_WEB,
        reason=REASON_CONVERSATION,
        window_start=now,
        window_end=now,
        line_count=1,
        raw_bytes=len(body),
        body_gz=gzip.compress(body),
        clock_offset_ms=offset,
        **where,
    )
    if row.household is not None:
        prune(row.household)
    return row


def owned(request, conversation_id) -> DeviceLog | None:
    """The caller's own conversation row, locked for the append; None for anyone else's. Call
    inside a transaction. A member's must be theirs in their household; a recruiter's must be
    theirs and a visit record. Nobody reaches another person's conversation, even by its id."""
    from household.models import is_recruiter
    from household.tenancy import household_of

    rows = DeviceLog.objects.select_for_update().filter(
        batch_id=conversation_id,
        user=request.user,
        reason=REASON_CONVERSATION,
        device_label=DEVICE_LABEL_WEB,
    )
    if is_recruiter(request.user):
        rows = rows.filter(demo_visitor=True, household__isnull=True)
    else:
        rows = rows.filter(household=household_of(request), demo_visitor=False)
    return rows.first()


class TooLarge(ValueError):
    pass


def append(row: DeviceLog, stamped: list[tuple[datetime.datetime, str]]) -> int:
    """Merge lines into the row's body: drop exact repeats (a retried upload), sort by time,
    store. Returns how many lines were new. Raises TooLarge, storing nothing, past the cap."""
    from devicelogs.views import inflate_capped

    existing = inflate_capped(bytes(row.body_gz)).decode("utf-8", errors="replace").splitlines()
    seen = set(existing)
    fresh: list[str] = []
    latest = row.window_end
    for at, text in stamped:
        if text in seen:
            continue
        seen.add(text)
        fresh.append(text)
        if latest is None or at > latest:
            latest = at
    if not fresh:
        return 0
    merged = sorted(existing + fresh, key=lambda text: text[:_STAMP_LEN])
    body = _body(merged)
    if len(body) > MAX_UPLOAD_BYTES:
        raise TooLarge(
            f"Nothing was stored. A conversation record is capped at "
            f"{MAX_UPLOAD_BYTES // (1024 * 1024)} MB."
        )
    row.body_gz = gzip.compress(body)
    row.raw_bytes = len(body)
    row.line_count = len(merged)
    row.window_end = latest
    row.save(update_fields=["body_gz", "raw_bytes", "line_count", "window_end"])
    return len(fresh)


def tool_lines(
    *,
    call_id: str,
    name: str,
    args: object,
    started: datetime.datetime,
    ended: datetime.datetime,
    success: bool,
    outcome: str,
    text: str,
) -> list[tuple[datetime.datetime, str]]:
    """The engine's lines for one tool call: the call with its arguments, its latency, and the
    result with the outcome and the engine's words, trimmed."""
    ms = max(int((ended - started).total_seconds() * 1000), 0)
    call, tool = word(call_id), word(name)
    try:
        args_json = json.dumps(args, ensure_ascii=False, separators=(",", ":"), default=str)
    except (TypeError, ValueError):
        args_json = json.dumps(str(args))
    response = json.dumps(
        {"success": success, "outcome": outcome, "message": _trim(text, CAP_TOOL)},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    flag = "true" if success else "false"
    called = f"tool_call id={call} name={tool} async=false args={_trim(args_json, CAP_TOOL)}"
    timed = f"tool_ms={ms} tool={tool} sent=true async=false"
    result = (
        f"tool_result id={call} name={tool} success={flag} ms={ms} scheduling=none "
        f"sent=true response={response}"
    )
    return [
        (started, line(started, TID_ENGINE, AUDIT, called)),
        (ended, line(ended, TID_ENGINE, LATENCY, timed)),
        (ended, line(ended, TID_ENGINE, AUDIT, result)),
    ]


def record_tool(request, conversation_id, **call) -> None:
    """Append one tool call to the caller's conversation. Silent when the id is absent or not
    theirs: the tool has already run, and its answer must not depend on the recorder."""
    if conversation_id is None:
        return
    with transaction.atomic():
        row = owned(request, conversation_id)
        if row is None:
            return
        try:
            append(row, tool_lines(**call))
        except TooLarge:
            return


# ---- the browser's events -----------------------------------------------------

EVENT_KINDS: tuple[str, ...] = (
    "user",
    "assistant",
    "interrupted",
    "turn_complete",
    "tool_cancelled",
    "mic_open",
    "mic_closed",
    "session_end",
    "latency",
)
LATENCY_METRICS: tuple[str, ...] = ("cold_start", "reply")
MAX_EVENTS = 500


def event_line(event: dict, at: datetime.datetime) -> str | None:
    """One browser event as its line, or None when there is nothing to write."""
    kind = event["kind"]
    if kind == "user":
        text = event.get("text") or ""
        if not text.strip():
            return None
        typed = " typed=true" if event.get("typed") else ""
        msg = f"user final=true{typed} text={quoted(text, CAP_TURN)}"
    elif kind == "assistant":
        text = event.get("text") or ""
        if not text.strip():
            return None
        msg = f"assistant text={quoted(text, CAP_TURN)}"
    elif kind == "interrupted":
        msg = f"interrupted source={word(event.get('reason') or 'server')}"
    elif kind == "turn_complete":
        msg = "turn_complete"
    elif kind == "tool_cancelled":
        call, tool = word(event.get("id") or ""), word(event.get("name") or "")
        msg = f"tool_cancelled id={call} name={tool}"
    elif kind in ("mic_open", "mic_closed"):
        msg = f"{kind} reason={word(event.get('reason') or 'unknown')}"
    elif kind == "session_end":
        msg = f"session_end reason={quoted(event.get('reason') or '', CAP_TOOL)}"
    elif kind == "latency":
        metric, ms = event.get("metric"), event.get("ms")
        if metric not in LATENCY_METRICS or ms is None:
            return None
        return line(at, TID_BROWSER, LATENCY, f"{metric}_ms={int(ms)}")
    else:
        return None
    return line(at, TID_BROWSER, AUDIT, msg)


def browser_lines(row: DeviceLog, events: list[dict]) -> list[tuple[datetime.datetime, str]]:
    """The browser's events as stamped lines on the engine's clock. An event whose time cannot be
    a date is dropped, not refused: the rest of the upload is still worth keeping."""
    offset = row.clock_offset_ms or 0
    out = []
    for event in events:
        try:
            at = datetime.datetime.fromtimestamp((event["at_ms"] + offset) / 1000, tz=datetime.UTC)
        except (OverflowError, OSError, ValueError):
            continue
        text = event_line(event, at)
        if text is not None:
            out.append((at, text))
    return out


def prune_visit_records(now: datetime.datetime | None = None) -> int:
    """Delete recruiter-visit conversation records older than VISIT_RETENTION_DAYS, whether or not
    the visit has ended. Returns the count. Members' records are never touched here."""
    cutoff = (now or timezone.now()) - datetime.timedelta(days=VISIT_RETENTION_DAYS)
    deleted, _ = DeviceLog.objects.filter(
        demo_visitor=True, household__isnull=True, received_at__lt=cutoff
    ).delete()
    return deleted
