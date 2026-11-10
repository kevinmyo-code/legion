"""The consolidation job: conversation records become memories (memory-and-vault ticket 03).

Kevin, 2026-11-05: saving is **automatic from conversations**, visible on a Memory screen, and
forgettable. `manage.py consolidate_memories` reads each finished conversation record
(`device_logs`, reason `conversation`, from the phone, the web and the head unit) once, asks the
engine's small model for at most five memories, and writes them private to the member whose
device uploaded it (`DeviceLog.user`).

## What the model is shown (map decision D, CLAUDE.md section 7)

**Only what the person said, and what the assistant said back when no tool was involved.**

- `user final=true` lines: the person's own words.
- `assistant` lines, only from a turn that called no tool and received no tool result. A turn
  that ran a tool is a turn whose answer may be quoting the tool, and a tool may have read mail or
  a web page; dropping all of them needs no list kept in step with new tools.
- **A turn that called a read-through tool is dropped whole**, the person's words included, as the
  phone's `captureEpisodicTurn` does. `READ_THROUGH_TOOLS` is the phone's
  `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` plus `web_search`. After one, the assistant's words are
  dropped for the rest of the session, because an async result can be spoken turns later.
- Never a `tool_result` line, never a line carrying the redaction marker.
- **A turn the source marked read-through is dropped whole, and so is a turn with a redacted line**
  (open-loops ticket 07, 2026-11-10): any `read_through` event (a camera look, a spoken brief or
  watch line, Live search grounding, `fleet_web` / ask_engine's web or GitHub text, and the `carry`
  turn after one) and any `text` or tool `response` carrying the redaction marker. The same inputs
  rule `open_loops.words.own_lines` applies. A mark also ends tool-free replies for the rest of the
  session, as a listed tool does. The turn is decided when it closes (`turn_complete`,
  `session_start`, `session_end`, or the end of the record): a mark can be logged after his line.
- **A source that cannot mark a turn gives the person's words only** (`log_transcript`): the head
  unit declares Google Search grounding and logs no `read_through` event, so a "tool-free" reply
  there may be a search answer. `open_loops.words.can_mark_turns` decides, the same allowlist open
  loops uses (web records, phone records with a `run_id`).

So mail never reaches the prompt, and the guarantee is the shape of the extraction, not a
reminder in it. The prompt also asks the model never to keep anything from mail or the web, never
an opinion about the assistant, and never an identity or bank number; `sensitive.looks_sensitive`
checks the last one in code.

## Superseding (map decision C)

The member's own live memories go to the model with their ids. An item may name one it
contradicts in `supersedes`; an id that was not sent is ignored. The older row is marked
`superseded_at` and leaves recall. An item whose text equals a live memory's is skipped.

## Failures

No key: nothing is marked, so the records wait for one. A refused or unreadable call marks the
record `failed`; it is tried again on the next run, `MAX_ATTEMPTS` in all, then left, and every
run report counts the ones left (`given_up`). Nothing here logs or stores transcript text;
`detail` carries the reason in words only.

## Health (2026-11-09)

`manage.py consolidate_memories` runs once per household through `ingest.jobs.run_job`, source
`memory`, so `/api/freshness` says when memory was last updated and goes stale after three hours
without an `ok` run. No key records `skipped` with the reason; a run where every record failed
records `failed`.

`_transport` is replaced in every test (`tests/conftest.py`), so no test reaches Google.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import timedelta

from django.db import transaction
from django.db.models import Q
from django.db.models.functions import Now
from django.utils import timezone

from assistant.read_through import THIRD_PARTY_TOOLS
from devicelogs import voice_audit
from devicelogs.models import REASON_CONVERSATION, DeviceLog
from devicelogs.render import batch_text
from ingest import statements
from ingest.drive import _OPENER, Response, Transport
from llm.client import retrying
from member_memory.models import (
    DECAY_DURABLE,
    DECAY_EPISODIC,
    IMPORTANCE_MAX,
    IMPORTANCE_MIN,
    KINDS,
    MAX_ATTEMPTS,
    OUTCOME_DONE,
    OUTCOME_FAILED,
    OUTCOME_SKIPPED,
    SOURCE_CONSOLIDATION,
    TEXT_MAX_CHARS,
    MemberMemory,
    MemoryConsolidation,
)
from member_memory.sensitive import looks_sensitive
from open_loops.words import can_mark_turns

# The phone's `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` (`service/LiveToolbox.kt:3239-3241`, read
# 2026-11-05), plus the engine's third-party tools (`assistant.read_through.THIRD_PARTY_TOOLS`:
# `web_search`, `brief_latest`, `github_status`), which the web record logs by name and writes no
# `read_through` line for. A copy of the phone's half: the engine image holds no Kotlin.
READ_THROUGH_TOOLS = frozenset(
    {
        "search_mail",
        "read_mail",
        "ask_mail",
        "get_sitrep",
        "track_package",
        "flight_status",
        "browser",
    }
    | THIRD_PARTY_TOOLS
)
READ_THROUGH_EVENT = "read_through"
REDACTION_MARK = "[redacted"

SETTLE_MINUTES = 15  # a record this new may still be growing
BATCH_LIMIT = 50
MAX_ITEMS = 5
MAX_TRANSCRIPT_CHARS = 12_000
MAX_KNOWN = 60  # live memories shown to the model for superseding
TIMEOUT_SECONDS = 30


@dataclass
class Transcript:
    lines: list[str] = field(default_factory=list)
    dropped_turns: int = 0

    @property
    def text(self) -> str:
        return "\n".join(self.lines)[-MAX_TRANSCRIPT_CHARS:]


def _redacted(value) -> bool:
    return isinstance(value, str) and (voice_audit.REDACTED in value or REDACTION_MARK in value)


def transcript(raw: str, *, marks_turns: bool = True) -> Transcript:
    """The person's words and the tool-free replies, from one batch's raw lines (decision D).
    `marks_turns=False` (a source that logs no read-through marks): the person's words only."""
    out = Transcript()
    user: list[str] = []
    assistant: list[str] = []
    tool_seen = False
    read_through = False
    tainted_session = False

    def close_turn():
        nonlocal user, assistant, tool_seen, read_through
        if read_through:
            out.dropped_turns += 1
        else:
            out.lines.extend(f"User: {text}" for text in user)
            if assistant and marks_turns and not tool_seen and not tainted_session:
                joined = "".join(assistant).strip()
                if joined:
                    out.lines.append(f"Assistant: {joined}")
        user, assistant, tool_seen, read_through = [], [], False, False

    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag != "LIVE_AUDIT":
            continue
        event, fields = voice_audit.parse_audit(msg)
        if event == READ_THROUGH_EVENT:
            read_through = True
            tainted_session = True
            continue
        if event in ("tool_call", "tool_result"):
            tool_seen = True
            if fields.get("name") in READ_THROUGH_TOOLS or _redacted(fields.get("response")):
                read_through = True
                tainted_session = True
            continue
        if event in ("turn_complete", "session_start"):
            close_turn()
            continue
        if event == "session_end":
            close_turn()
            tainted_session = False
            continue
        text = fields.get("text")
        if _redacted(text):
            read_through = True
            continue
        if not isinstance(text, str):
            continue
        if event == "user" and fields.get("final") == "true":
            cleaned = " ".join(text.split())
            if cleaned:
                user.append(cleaned)
        elif event == "assistant":
            assistant.append(text)
    close_turn()
    return out


def log_transcript(log: DeviceLog, raw: str | None = None) -> Transcript:
    """`transcript` for one stored record, with its source's ability to mark turns (module doc).
    Raises `ValueError` when the stored batch cannot be read."""
    return transcript(batch_text(log) if raw is None else raw, marks_turns=can_mark_turns(log))


PROMPT = """You keep a household assistant's long-term memory about one person.
From the conversation below, list at most {max_items} things worth remembering for months: facts
the person stated about themselves, their household, their cars, their plans, and preferences
they stated. Write each in the third person, short, in plain words, as what the person said
("Kevin's gym is on Main Street"), never as your guess.

Never keep:
- anything the person did not say themselves;
- anything from mail, a web page, a search or another tool;
- opinions about the assistant, moods, or small talk;
- any identity or bank number (passport, NRIC, SSN, A-number, account or card number).

kind: fact, preference or event. importance 1-10 (9-10 life facts, 6-8 standing plans and
preferences, 3-5 useful detail, 1-2 trivia). durable true for anything that stays true; false for
a passing event that should fade. When an item corrects or replaces one of the KNOWN memories,
put that memory's id in supersedes; otherwise leave supersedes empty. Do not repeat a KNOWN
memory. An empty list is a good answer when nothing is worth keeping.

KNOWN memories (id: text):
{known}

Conversation:
{transcript}
"""


def request_body(text: str, known: list[MemberMemory]) -> bytes:
    listed = "\n".join(f"{row.id}: {row.text}" for row in known) or "(none)"
    return json.dumps(
        {
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {
                            "text": PROMPT.format(
                                max_items=MAX_ITEMS, known=listed, transcript=text
                            )
                        }
                    ],
                }
            ],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": {
                    "type": "OBJECT",
                    "properties": {
                        "memories": {
                            "type": "ARRAY",
                            "items": {
                                "type": "OBJECT",
                                "properties": {
                                    "text": {"type": "STRING"},
                                    "kind": {"type": "STRING", "enum": list(KINDS)},
                                    "importance": {"type": "INTEGER"},
                                    "durable": {"type": "BOOLEAN"},
                                    "supersedes": {"type": "STRING"},
                                },
                                "required": ["text", "kind", "importance", "durable"],
                            },
                        }
                    },
                    "required": ["memories"],
                },
                "temperature": 0,
            },
        }
    ).encode()


def _transport(method: str, url: str, headers: dict[str, str], body: bytes | None) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


class ConsolidationFailed(Exception):
    """The model gave no usable answer. The message says why, in words, never transcript text."""


def parse(payload: dict) -> list[dict]:
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    candidate = candidates[0] if isinstance(candidates, list) and candidates else {}
    content = candidate.get("content") if isinstance(candidate, dict) else None
    parts = content.get("parts") if isinstance(content, dict) else None
    text = "".join(
        p["text"]
        for p in (parts if isinstance(parts, list) else [])
        if isinstance(p, dict) and isinstance(p.get("text"), str) and p.get("thought") is not True
    )
    try:
        answer = json.loads(text)
    except ValueError:
        raise ConsolidationFailed("The model's answer was not JSON.") from None
    items = answer.get("memories") if isinstance(answer, dict) else None
    if not isinstance(items, list):
        raise ConsolidationFailed("The model's answer had no memories list.")
    return items[:MAX_ITEMS]


def _norm(text: str) -> str:
    return " ".join(text.lower().split()).rstrip(".")


def _known(log: DeviceLog) -> list[MemberMemory]:
    return list(
        MemberMemory.objects.filter(
            household_id=log.household_id, member_id=log.user_id, superseded_at__isnull=True
        ).order_by("-importance", "-created_at")[:MAX_KNOWN]
    )


def _write(log: DeviceLog, items: list[dict], known: list[MemberMemory]) -> int:
    by_id = {str(row.id): row for row in known}
    seen = {_norm(row.text) for row in known}
    written = 0
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            continue
        text = " ".join(str(item.get("text") or "").split())
        if not text or len(text) > TEXT_MAX_CHARS or looks_sensitive(text):
            continue
        if _norm(text) in seen:
            continue
        kind = item.get("kind") if item.get("kind") in KINDS else KINDS[0]
        importance = item.get("importance")
        if not isinstance(importance, int) or isinstance(importance, bool):
            importance = 5
        importance = max(IMPORTANCE_MIN, min(IMPORTANCE_MAX, importance))
        older = by_id.get(str(item.get("supersedes") or "").strip())
        row = MemberMemory.objects.create(
            household_id=log.household_id,
            member_id=log.user_id,
            owner_user_id=log.user_id,  # map decision B: consolidation never shares
            text=text,
            kind=kind,
            importance=importance,
            decay_class=DECAY_EPISODIC if item.get("durable") is False else DECAY_DURABLE,
            source=SOURCE_CONSOLIDATION,
            supersedes=older,
            source_log=log,
            origin_key=f"log:{log.id}:{index}",
        )
        if older is not None:
            MemberMemory.objects.filter(pk=older.pk).update(superseded_at=Now(), updated_at=Now())
            by_id.pop(str(older.pk), None)
        seen.add(_norm(row.text))
        written += 1
    return written


def pending(now=None, household=None):
    """Records ready to read: finished, a member's, never consolidated or failed under the cap.
    Every household's, or only `household`'s when one is named."""
    now = now or timezone.now()
    done = MemoryConsolidation.objects.filter(
        Q(outcome__in=(OUTCOME_DONE, OUTCOME_SKIPPED)) | Q(attempts__gte=MAX_ATTEMPTS)
    ).values("device_log_id")
    logs = DeviceLog.objects.filter(
        reason=REASON_CONVERSATION,
        household__isnull=False,
        user__isnull=False,
        demo_visitor=False,
        received_at__lte=now - timedelta(minutes=SETTLE_MINUTES),
    )
    if household is not None:
        logs = logs.filter(household=household)
    return logs.exclude(pk__in=done).order_by("received_at")


def given_up(household=None) -> int:
    """Records that failed `MAX_ATTEMPTS` times and are never tried again (2026-11-09: they were
    excluded from `pending` and named nowhere, so a long outage lost conversations silently)."""
    rows = MemoryConsolidation.objects.filter(outcome=OUTCOME_FAILED, attempts__gte=MAX_ATTEMPTS)
    if household is not None:
        rows = rows.filter(household=household)
    return rows.count()


def _mark(log: DeviceLog, outcome: str, *, written: int = 0, detail: str = "") -> None:
    row = MemoryConsolidation.objects.filter(device_log=log).first()
    if row is None:
        MemoryConsolidation.objects.create(
            household_id=log.household_id,
            device_log=log,
            outcome=outcome,
            memories_written=written,
            detail=detail,
        )
        return
    row.outcome = outcome
    row.attempts += 1
    row.memories_written = written
    row.detail = detail
    row.processed_at = timezone.now()
    row.save()


@dataclass
class RunReport:
    read: int = 0
    written: int = 0
    skipped: int = 0
    failed: int = 0
    no_key: bool = False
    # Records set aside for good after MAX_ATTEMPTS failures, all of them, not only this run's.
    gave_up: int = 0

    @property
    def all_failed(self) -> bool:
        """Every record read this run failed: the model is down or refusing, not one bad record."""
        return self.read > 0 and self.failed == self.read

    def sentence(self) -> str:
        if self.no_key:
            return (
                f"Nothing was consolidated: {statements.GEMINI_KEY_ENV} is not set, so no model "
                "can be asked. The conversation records wait for a key; none was marked."
            )
        said = (
            f"Read {self.read} conversation records: {self.written} memories written, "
            f"{self.skipped} with nothing to read, {self.failed} failed (retried next run)."
        )
        if self.gave_up:
            noun = "record has" if self.gave_up == 1 else "records have"
            said += (
                f" {self.gave_up} {noun} failed {MAX_ATTEMPTS} times and will not be tried "
                "again; no memories came from them."
            )
        return said


def consolidate_one(log: DeviceLog, *, key: str, transport: Transport) -> tuple[str, int]:
    try:
        raw = batch_text(log)
    except ValueError:
        _mark(log, OUTCOME_FAILED, detail="The stored batch could not be read.")
        return OUTCOME_FAILED, 0
    words = log_transcript(log, raw)
    if not words.lines:
        _mark(log, OUTCOME_SKIPPED, detail="No words of the person's own to read.")
        return OUTCOME_SKIPPED, 0
    known = _known(log)
    try:
        response = transport(
            "POST",
            statements.GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            request_body(words.text, known),
        )
    except (TimeoutError, urllib.error.URLError, ConnectionError, OSError) as exc:
        _mark(log, OUTCOME_FAILED, detail=f"The model could not be reached ({type(exc).__name__}).")
        return OUTCOME_FAILED, 0
    if response.status != 200:
        _mark(log, OUTCOME_FAILED, detail=f"Google answered HTTP {response.status}.")
        return OUTCOME_FAILED, 0
    try:
        payload = response.json()
        items = parse(payload)
    except (ValueError, UnicodeDecodeError, ConsolidationFailed) as exc:
        detail = exc.args[0] if isinstance(exc, ConsolidationFailed) else "Unreadable answer."
        _mark(log, OUTCOME_FAILED, detail=detail)
        return OUTCOME_FAILED, 0
    with transaction.atomic():
        written = _write(log, items, known)
        _mark(log, OUTCOME_DONE, written=written)
    return OUTCOME_DONE, written


def run(
    *, limit: int = BATCH_LIMIT, key: str | None = None, transport=None, household=None
) -> RunReport:
    """One pass over every household's pending records, or only `household`'s (the command runs
    one per household, so each gets its own `ingest_runs` row and freshness line)."""
    report = RunReport()
    key = key if key is not None else statements.gemini_key()
    if not key:
        report.no_key = True
        return report
    # Item 15: a nightly job: up to three attempts per call on 429/5xx.
    send = transport or retrying(_transport, attempts=3)
    for log in pending(household=household)[:limit]:
        outcome, written = consolidate_one(log, key=key, transport=send)
        report.read += 1
        report.written += written
        if outcome == OUTCOME_SKIPPED:
            report.skipped += 1
        elif outcome == OUTCOME_FAILED:
            report.failed += 1
    report.gave_up = given_up(household)
    return report
