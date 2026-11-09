"""The daily memory review, engine half (memory-review tickets 05, 06, 07, 08 and 15).

Claude judges on the Alienware (the Claude worker, ADR 0067, Kevin's subscription); the engine
picks the rows, filters the transcripts, and decides what applies. **Claude proposes, the engine
decides** (CLAUDE.md section 4's posture): every change Claude sends passes a deterministic gate
here, or it becomes a proposal Kevin answers on the Memory screen. The gate never depends on a
prompt.

- `build_context` answers `GET review/context`: the member's private live memories with short
  handles (`m1`..`mN`), the household facts about them as context only, the last 7 days of their
  conversations through `consolidate.transcript()` (the person's words and tool-free replies only,
  so mail and web content never reach the laptop), and the exact-text duplicate groups the engine
  merges itself. It writes a `memory_reviews` row holding the handle map (ids and stamps only).
- `submit` answers `POST review/changes`: exact-text merges by code first (ruling 03), then each
  of Claude's changes is mapped back, gated and applied or proposed, in one transaction under the
  household's memory lock (the hourly consolidation job's own advisory lock key).
- `accept` / `dismiss` / `revert` are Kevin's answers (the Memory screen, ruling 02).
- `maybe_mint` is the catch-up hook (ticket 15): inside a claim from the member's own idle
  Alienware, a review task is minted when one is due. It does nothing while
  `LEGION_MEMORY_REVIEW` is `off`, which is the default.

Kevin's rulings (2026-11-09, all as proposed): R1 shadow first, then only gated merges and
past-date expiries apply on their own, corrections never; R2 the Memory screen plus a recall flag,
never raised unprompted; R3 exact duplicates merged by code; R4 a paraphrased reason plus record
ids, never a verbatim quote.

**Nothing here logs.** No logger is imported. Memory text travels only in JSON bodies and answers;
`detail`, `gate` and every refusal are words about the change, never a quote of a row.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import re
import uuid
from dataclasses import dataclass, field

from django.conf import settings
from django.db import connection, transaction
from django.db.models import Max
from django.db.models.functions import Now
from django.utils import timezone

from household.tenancy import household_of
from member_memory import service
from member_memory.consolidate import _norm, transcript
from member_memory.models import (
    ACTION_CORRECT,
    ACTION_EXPIRE,
    ACTION_MERGE,
    ACTION_RELABEL,
    ACTION_RETIRE,
    ACTIONS,
    CHANGE_ACCEPTED,
    CHANGE_APPLIED,
    CHANGE_DISMISSED,
    CHANGE_PENDING,
    CHANGE_REVERTED,
    CHANGE_STALE,
    DECAY_DURABLE,
    DECAY_EPISODIC,
    IMPORTANCE_MAX,
    IMPORTANCE_MIN,
    KINDS,
    REASON_MAX_CHARS,
    RETIRED_CONTRADICTED,
    RETIRED_EXPIRED,
    RETIRED_MERGED,
    RETIRED_NOT_ABOUT_PERSON,
    RETIRED_RELABELLED,
    REVIEW_CONTEXT_ONLY,
    REVIEW_FAILED,
    REVIEW_OK,
    SOURCE_CLAUDE,
    SOURCE_CONSOLIDATION,
    SOURCE_MANUAL,
    SOURCE_PHONE,
    SOURCE_REVIEW,
    SOURCE_WEB,
    TEXT_MAX_CHARS,
    MemberMemory,
    MemoryReview,
    MemoryReviewChange,
)
from member_memory.sensitive import looks_sensitive

MODE_OFF = "off"
MODE_SHADOW = "shadow"
MODE_ON = "on"
MODES = (MODE_OFF, MODE_SHADOW, MODE_ON)

REVIEW_DAYS = 7
REVIEW_MAX_CHARS = 40_000
CONTEXT_TTL = datetime.timedelta(hours=1)
AUTO_CAP = 10
CHANGES_MAX = 200
# Ruling 01: auto-apply only after this many shadow runs, and only these actions.
SHADOW_RUNS_BEFORE_AUTO = 14
AUTO_ACTIONS = frozenset({ACTION_MERGE, ACTION_EXPIRE})
# Ticket 07's dismissal guard: over the last 14 runs, more than 30% of decided proposals
# dismissed (with at least 10 decided) and `on` behaves as `shadow`.
GUARD_RUNS = 14
GUARD_RATE = 0.30
GUARD_MIN_DECIDED = 10
EXPIRE_MIN_DAYS = 2
# Ticket 15's due rule.
DUE_AFTER = datetime.timedelta(hours=24)
FAILURE_WAIT = datetime.timedelta(hours=6)
OVERDUE_AFTER = datetime.timedelta(hours=36)
IDLE_INPUT_SECONDS = 600
TASK_REQUEST = "Run the daily memory review (task kind memory_review)."

# Strongest first: "you asked me to keep this" survives a merge (map, "Shape of a change").
SOURCE_RANK = (SOURCE_MANUAL, SOURCE_PHONE, SOURCE_WEB, SOURCE_CLAUDE, SOURCE_CONSOLIDATION,
               SOURCE_REVIEW)

OFF_REFUSAL = (
    "Nothing was read or changed. The memory review is off on this engine "
    "(LEGION_MEMORY_REVIEW=off)."
)
DEMO_REFUSAL = (
    "Nothing was read or changed. A demo household's memory is never reviewed."
)
LOCKED = (
    "Nothing was changed. The hourly memory job is writing this household's memories right now. "
    "Try again in a minute."
)
STALE_ACCEPT = "Nothing was changed. Those memories changed since the review."


class ReviewRefused(Exception):
    """Nothing was read or written. The message says why, in words."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


# ---------------------------------------------------------------- switches and doors


def configured_mode() -> str:
    value = str(getattr(settings, "LEGION_MEMORY_REVIEW", MODE_OFF) or MODE_OFF).strip().lower()
    return value if value in MODES else MODE_OFF


def _ok_runs(member) -> int:
    return MemoryReview.objects.filter(member=member, outcome=REVIEW_OK).count()


def dismissal_rate(member) -> tuple[float, int]:
    """Dismissed / decided over the proposals of the last `GUARD_RUNS` successful runs."""
    runs = list(
        MemoryReview.objects.filter(member=member, outcome=REVIEW_OK)
        .order_by("-submitted_at")
        .values_list("id", flat=True)[:GUARD_RUNS]
    )
    decided = MemoryReviewChange.objects.filter(
        review_id__in=runs, by_code=False, status__in=(CHANGE_ACCEPTED, CHANGE_DISMISSED)
    )
    total = decided.count()
    if not total:
        return 0.0, 0
    return decided.filter(status=CHANGE_DISMISSED).count() / total, total


def effective_mode(member) -> tuple[str, str]:
    """The mode a run for `member` works in now, and why in words. `on` is only ever a manual
    flip, and even then it behaves as `shadow` until 14 shadow runs exist (ruling 01) and while
    the dismissal guard holds."""
    mode = configured_mode()
    if mode != MODE_ON:
        return mode, ""
    runs = _ok_runs(member)
    if runs < SHADOW_RUNS_BEFORE_AUTO:
        return MODE_SHADOW, (
            f"Auto-apply waits for {SHADOW_RUNS_BEFORE_AUTO} shadow runs; {runs} so far."
        )
    rate, decided = dismissal_rate(member)
    if decided >= GUARD_MIN_DECIDED and rate > GUARD_RATE:
        return MODE_SHADOW, (
            f"Auto-apply is paused: {round(rate * 100)}% of the last {decided} proposals were "
            "dismissed, over the 30% line."
        )
    return MODE_ON, ""


def door(request) -> None:
    """Raises `ReviewRefused` when this caller may not use the review at all."""
    reason = service.refusal(request)
    if reason:
        raise ReviewRefused(reason, status=403)
    household = household_of(request)
    if getattr(household, "is_demo_visit", False):
        raise ReviewRefused(DEMO_REFUSAL, status=403)
    from household.demo import find_demo_household

    demo = find_demo_household()
    if demo is not None and demo.pk == household.pk:
        raise ReviewRefused(DEMO_REFUSAL, status=403)
    if configured_mode() == MODE_OFF:
        raise ReviewRefused(OFF_REFUSAL, status=403)


def _memory_lock(household_id) -> bool:
    """The household's memory lock, the same key `consolidate_memories` runs under
    (`ingest.jobs.run_job`, source `memory`). Transaction-scoped: released at commit or rollback.
    A session lock and a transaction lock on one key exclude each other, so the hourly job is
    `skipped_locked` while a submit holds it, and a submit is refused while the job runs."""
    from ingest.jobs import lock_key
    from ingest.models import Source

    with connection.cursor() as cursor:
        cursor.execute(
            "select pg_try_advisory_xact_lock(%s)", [lock_key(Source.MEMORY, household_id)]
        )
        return bool(cursor.fetchone()[0])


# ---------------------------------------------------------------- small helpers

_WORD = re.compile(r"[a-z0-9]+")


def tokens(text: str) -> set[str]:
    """Lower-case words and numbers. No stopword list: the gate stays deterministic, and a merge
    that needs a word none of its rows has is a proposal, whatever the word."""
    return set(_WORD.findall((text or "").lower()))


def _stamp(value) -> str:
    return value.isoformat() if value else ""


def _live_private(member, household):
    return MemberMemory.objects.filter(
        household=household, member=member, owner_user=member, superseded_at__isnull=True
    )


def _strongest(rows) -> str:
    def rank(source):
        return SOURCE_RANK.index(source) if source in SOURCE_RANK else len(SOURCE_RANK)

    return min((row.source for row in rows), key=rank)


def _clean_text(value) -> str | None:
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    return text or None


# ---------------------------------------------------------------- context


def _transcripts(member, household, now):
    """Label -> (device_log, filtered text), newest first, capped at `REVIEW_MAX_CHARS`."""
    from devicelogs.models import REASON_CONVERSATION, DeviceLog
    from devicelogs.render import batch_text

    logs = DeviceLog.objects.filter(
        household=household,
        user=member,
        reason=REASON_CONVERSATION,
        demo_visitor=False,
        received_at__gte=now - datetime.timedelta(days=REVIEW_DAYS),
    ).order_by("-received_at", "-pk")
    out, used = [], 0
    for log in logs:
        try:
            lines = transcript(batch_text(log)).lines
        except ValueError:
            continue
        text = "\n".join(lines)
        if not text:
            continue
        room = REVIEW_MAX_CHARS - used
        if room <= 0:
            break
        text = text[:room]
        used += len(text)
        out.append((f"L{len(out) + 1}", log, text))
    return out


def _filtered_text(log) -> str:
    from devicelogs.render import batch_text

    try:
        return "\n".join(transcript(batch_text(log)).lines)
    except ValueError:
        return ""


ACTION_SCHEMA = {
    "merge": "{action, handles: [2+], new_text, kind, importance, durable, reason, confidence}",
    "correct": "{action, handle, new_text, evidence: [L labels], reason, confidence}",
    "expire": "{action, handle, event_date: YYYY-MM-DD, reason, confidence}",
    "relabel": "{action, handle, kind, importance, durable, reason, confidence}",
    "retire": "{action, handle, reason, confidence}",
}


def build_context(request, *, task_id=None, machine: str = "") -> dict:
    door(request)
    member = request.user
    household = household_of(request)
    now = timezone.now()
    mode, why = effective_mode(member)

    rows = list(_live_private(member, household).order_by("created_at", "pk"))
    groups: dict[str, list[MemberMemory]] = {}
    for row in rows:
        groups.setdefault(_norm(row.text), []).append(row)
    representatives, code_groups = [], []
    for row in rows:
        group = groups[_norm(row.text)]
        keep = max(group, key=lambda r: (r.created_at, str(r.pk)))
        if row.pk != keep.pk:
            continue
        representatives.append((row, len(group)))
        if len(group) > 1:
            code_groups.append(
                {
                    "keep": str(keep.pk),
                    "rows": {str(r.pk): _stamp(r.updated_at) for r in group},
                }
            )

    pending_ids = set()
    for targets in MemoryReviewChange.objects.filter(
        member=member, household=household, status=CHANGE_PENDING
    ).values_list("targets", flat=True):
        pending_ids.update(targets or [])

    handles, memories, pending_handles, duplicates = {}, [], [], []
    for index, (row, copies) in enumerate(representatives, start=1):
        handle = f"m{index}"
        handles[handle] = {"id": str(row.pk), "updated_at": _stamp(row.updated_at)}
        memories.append(
            {
                "handle": handle,
                "text": row.text,
                "kind": row.kind,
                "durable": row.decay_class == DECAY_DURABLE,
                "importance": row.importance,
                "source": row.source,
                "written": row.created_at.date().isoformat(),
                "recalled": row.recall_count,
            }
        )
        if copies > 1:
            duplicates.append({"handle": handle, "copies": copies, "handled_by": "code"})
        if str(row.pk) in pending_ids:
            pending_handles.append(handle)

    shared = [
        {"text": row.text, "kind": row.kind, "context_only": True}
        for row in MemberMemory.objects.filter(
            household=household, member=member, owner_user__isnull=True,
            superseded_at__isnull=True,
        ).order_by("created_at")
    ]
    conversations, logs = [], {}
    for label, log, text in _transcripts(member, household, now):
        logs[label] = {"id": str(log.pk), "at": _stamp(log.received_at)}
        conversations.append(
            {"label": label, "date": log.received_at.date().isoformat(), "text": text}
        )

    review = MemoryReview.objects.create(
        household=household,
        member=member,
        owner_user=member,
        task_id=task_id,
        mode=mode,
        machine=machine[:80],
        rows_seen=len(rows),
        logs_read=len(conversations),
        handles=handles,
        logs=logs,
        code_groups=code_groups,
    )
    answer = {
        "context_id": str(review.context_id),
        "today": timezone.localdate().isoformat(),
        "mode": mode,
        "mode_note": why,
        "auto_cap": AUTO_CAP,
        "memories": memories,
        "shared_context": shared,
        "exact_duplicates": duplicates,
        "pending_handles": pending_handles,
        "conversations": conversations,
        "actions": ACTION_SCHEMA,
    }
    review.chars_in = len(json.dumps(answer))
    review.save(update_fields=["chars_in"])
    return answer


# ---------------------------------------------------------------- applying one change


def _carry(new: MemberMemory, rows) -> None:
    new.recall_count = sum(r.recall_count for r in rows)
    stamps = [r.last_recalled_at for r in rows if r.last_recalled_at]
    new.last_recalled_at = max(stamps) if stamps else None


def _retire(rows, reason: str, by=None) -> None:
    MemberMemory.objects.filter(pk__in=[r.pk for r in rows]).update(
        superseded_at=Now(), updated_at=Now(), retired_by=by, retired_reason=reason
    )


def _write_row(rows, *, text, kind, decay_class, importance, source, supersedes):
    """Every row the review writes goes through here. `embedding` (hybrid recall) is never set
    by the review: the embed sweep fills a row it finds empty."""
    newest = max(rows, key=lambda r: (r.created_at, str(r.pk)))
    new = MemberMemory(
        household_id=newest.household_id,
        member_id=newest.member_id,
        owner_user_id=newest.member_id,
        text=text,
        kind=kind,
        decay_class=decay_class,
        importance=importance,
        source=source,
        supersedes=supersedes,
        source_log_id=newest.source_log_id,
        created_at=newest.created_at,
    )
    _carry(new, rows)
    new.save()
    return new


def apply(action: str, rows: list[MemberMemory], proposed: dict):
    """Applies one gated change to live `rows`. Returns (result row or None, `before`)."""
    if action == ACTION_MERGE:
        newest = max(rows, key=lambda r: (r.created_at, str(r.pk)))
        new = _write_row(
            rows,
            text=proposed["text"],
            kind=proposed["kind"],
            decay_class=proposed["decay_class"],
            importance=proposed["importance"],
            source=_strongest(rows),
            supersedes=newest,
        )
        _retire(rows, RETIRED_MERGED, by=new)
        return new, {}
    old = rows[0]
    if action == ACTION_CORRECT:
        new = _write_row(
            rows, text=proposed["text"], kind=old.kind, decay_class=old.decay_class,
            importance=old.importance, source=SOURCE_REVIEW, supersedes=old,
        )
        _retire(rows, RETIRED_CONTRADICTED, by=new)
        return new, {}
    if action == ACTION_RELABEL:
        new = _write_row(
            rows, text=old.text, kind=proposed["kind"], decay_class=proposed["decay_class"],
            importance=proposed["importance"], source=old.source, supersedes=old,
        )
        _retire(rows, RETIRED_RELABELLED, by=new)
        return new, {}
    if action == ACTION_EXPIRE:
        _retire(rows, RETIRED_EXPIRED)
        return None, {}
    if action == ACTION_RETIRE:
        _retire(rows, RETIRED_NOT_ABOUT_PERSON)
        return None, {}
    raise ValueError(action)


def _code_merge(keep: MemberMemory, dupes: list[MemberMemory]) -> dict:
    """Ruling 03: identical text after `_norm`, no judgement. The newest row stays, takes the
    strongest source and the recall count, and the copies retire into it."""
    group = [keep, *dupes]
    before = {"source": keep.source, "recall_count": keep.recall_count}
    MemberMemory.objects.filter(pk=keep.pk).update(
        source=_strongest(group),
        recall_count=sum(r.recall_count for r in group),
    )
    _retire(dupes, RETIRED_MERGED, by=keep)
    return before


RETIRED_FOR = {
    ACTION_MERGE: RETIRED_MERGED,
    ACTION_CORRECT: RETIRED_CONTRADICTED,
    ACTION_RELABEL: RETIRED_RELABELLED,
    ACTION_EXPIRE: RETIRED_EXPIRED,
    ACTION_RETIRE: RETIRED_NOT_ABOUT_PERSON,
}


# ---------------------------------------------------------------- the gate


MONTHS = {
    m: i
    for i, names in enumerate(
        [
            ("january", "jan"), ("february", "feb"), ("march", "mar"), ("april", "apr"),
            ("may",), ("june", "jun"), ("july", "jul"), ("august", "aug"),
            ("september", "sep", "sept"), ("october", "oct"), ("november", "nov"),
            ("december", "dec"),
        ],
        start=1,
    )
    for m in names
}
_MONTH_RE = "|".join(sorted(MONTHS, key=len, reverse=True))
_MONTH_DAY = re.compile(
    rf"\b({_MONTH_RE})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?\b(?:,?\s+(\d{{4}}))?", re.I
)
_DAY_MONTH = re.compile(
    rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s+(?:of\s+)?({_MONTH_RE})\b\.?(?:,?\s+(\d{{4}}))?", re.I
)
_ISO = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")


def printed_dates(text: str, written: datetime.date) -> list[datetime.date]:
    """The calendar dates `text` prints. A month and day with no year is read as its first
    occurrence on or after the day the row was written (an event mentioned as coming up), never
    an earlier year, so a guess can only make an expiry wait, never come early."""
    found = []
    for m in _ISO.finditer(text):
        try:
            found.append(datetime.date(int(m[1]), int(m[2]), int(m[3])))
        except ValueError:
            pass
    pairs = [(m[1], m[2], m[3]) for m in _MONTH_DAY.finditer(text)]
    pairs += [(m[2], m[1], m[3]) for m in _DAY_MONTH.finditer(text)]
    for month_name, day, year in pairs:
        month = MONTHS[month_name.lower()]
        try:
            if year:
                found.append(datetime.date(int(year), month, int(day)))
                continue
            guess = datetime.date(written.year, month, int(day))
            if guess < written:
                guess = datetime.date(written.year + 1, month, int(day))
            found.append(guess)
        except ValueError:
            pass
    return found


@dataclass
class Verdict:
    index: int
    action: str
    handles: list[str] = field(default_factory=list)
    status: str = ""
    gate: str = ""
    passed: bool = False
    rows: list = field(default_factory=list)
    proposed: dict = field(default_factory=dict)
    reason: str = ""
    confidence: str = "low"
    evidence: list[str] = field(default_factory=list)
    cluster: int = 0
    result: object = None
    before: dict = field(default_factory=dict)

    def public(self) -> dict:
        """What the laptop may print: handles, 8-character id prefixes (so a dry run can be
        compared with a private list kept off the repo), action and verdict. Never text."""
        return {
            "index": self.index,
            "action": self.action,
            "handles": self.handles,
            "ids": [str(r.pk)[:8] for r in self.rows],
            "status": self.status,
            "gate": self.gate,
            "cluster": self.cluster,
        }


REFUSED = "refused"
STALE = "stale"


def _refuse(v: Verdict, why: str) -> Verdict:
    v.status, v.gate = REFUSED, why
    return v


def _labels(raw: dict, base: MemberMemory) -> tuple[dict | None, str]:
    kind = raw.get("kind", base.kind)
    if kind not in KINDS:
        return None, f"kind must be one of {', '.join(KINDS)}."
    importance = raw.get("importance", base.importance)
    if not isinstance(importance, int) or isinstance(importance, bool) or not (
        IMPORTANCE_MIN <= importance <= IMPORTANCE_MAX
    ):
        return None, f"importance must be a whole number from {IMPORTANCE_MIN} to {IMPORTANCE_MAX}."
    durable = raw.get("durable", base.decay_class == DECAY_DURABLE)
    if not isinstance(durable, bool):
        return None, "durable must be true or false."
    return {
        "kind": kind,
        "importance": importance,
        "decay_class": DECAY_DURABLE if durable else DECAY_EPISODIC,
    }, ""


def judge(
    index: int,
    raw,
    *,
    review: MemoryReview,
    live: dict[str, MemberMemory],
    expected: dict[str, str],
    gone: set[str],
    today: datetime.date,
) -> Verdict:
    """One change against its gate. `live` is every private live row by id; `expected` is the
    `updated_at` each was judged at; `gone` holds ids an earlier change in this submit retired."""
    if not isinstance(raw, dict):
        return _refuse(Verdict(index, "?"), "A change must be an object.")
    action = raw.get("action")
    v = Verdict(index, action if isinstance(action, str) else "?")
    if action not in ACTIONS:
        return _refuse(v, f"action must be one of {', '.join(ACTIONS)}.")
    names = raw.get("handles") if action == ACTION_MERGE else [raw.get("handle")]
    if not isinstance(names, list) or not all(isinstance(n, str) for n in names):
        return _refuse(v, "handles must name memories from the context.")
    v.handles = list(dict.fromkeys(names))
    if action == ACTION_MERGE and len(v.handles) < 2:
        return _refuse(v, "A merge names two or more memories.")
    reason = _clean_text(raw.get("reason")) or ""
    if len(reason) > REASON_MAX_CHARS:
        reason = reason[: REASON_MAX_CHARS - 1] + "…"
    if reason and looks_sensitive(reason):
        return _refuse(v, "The reason looked like an identity or bank number.")
    v.reason = reason
    v.confidence = "high" if raw.get("confidence") == "high" else "low"

    ids = []
    for name in v.handles:
        entry = review.handles.get(name)
        if not isinstance(entry, dict):
            return _refuse(v, f"{name} is not a handle from this context.")
        ids.append(entry["id"])
    for memory_id in ids:
        row = live.get(memory_id)
        if row is None or memory_id in gone or expected.get(memory_id) != _stamp(row.updated_at):
            v.status, v.gate = STALE, "A memory it names changed or left since the review read it."
            return v
        v.rows.append(row)
    base = max(v.rows, key=lambda r: (r.created_at, str(r.pk)))

    if action in (ACTION_MERGE, ACTION_CORRECT):
        text = _clean_text(raw.get("new_text"))
        if not text:
            return _refuse(v, "new_text is missing.")
        if len(text) > TEXT_MAX_CHARS:
            return _refuse(v, f"new_text is over {TEXT_MAX_CHARS} characters.")
        if looks_sensitive(text):
            return _refuse(v, "new_text looked like an identity or bank number.")
        v.proposed["text"] = text

    if action == ACTION_MERGE:
        labels, why = _labels(raw, base)
        if labels is None:
            return _refuse(v, why)
        v.proposed.update(labels)
        added = tokens(v.proposed["text"]) - set().union(*(tokens(r.text) for r in v.rows))
        if added:
            v.gate = f"The merged text adds {len(added)} word(s) none of the rows has."
        elif labels["decay_class"] == DECAY_EPISODIC and any(
            r.decay_class == DECAY_DURABLE for r in v.rows
        ):
            v.gate = "The merge would make a lasting fact fade."
        else:
            v.passed, v.gate = True, "passed"
    elif action == ACTION_CORRECT:
        old = v.rows[0]
        labels = [e for e in (raw.get("evidence") or []) if isinstance(e, str)]
        known = [label for label in labels if label in review.logs]
        v.evidence = [review.logs[label]["id"] for label in known]
        added = tokens(v.proposed["text"]) - tokens(old.text)
        if not known:
            v.gate = "It cites no conversation from the context."
        else:
            from devicelogs.models import DeviceLog

            cited = list(DeviceLog.objects.filter(pk__in=v.evidence))
            newer = [log for log in cited if log.received_at > old.created_at]
            heard = set().union(*(tokens(_filtered_text(log)) for log in newer)) if newer else set()
            if not newer:
                v.gate = "The cited conversation is not newer than the memory."
            elif added - heard:
                v.gate = (
                    f"{len(added - heard)} new word(s) do not appear in the cited conversation."
                )
            else:
                v.passed, v.gate = True, "passed"
    elif action == ACTION_EXPIRE:
        old = v.rows[0]
        try:
            claimed = datetime.date.fromisoformat(str(raw.get("event_date") or ""))
        except ValueError:
            return _refuse(v, "event_date must be a date, YYYY-MM-DD.")
        if claimed > today:
            return _refuse(v, "That date has not come yet.")
        v.proposed["event_date"] = claimed.isoformat()
        printed = printed_dates(old.text, old.created_at.date())
        if claimed not in printed:
            v.gate = "The memory does not print that date (a relative day never passes)."
        elif (today - claimed).days < EXPIRE_MIN_DAYS:
            v.gate = f"The date is less than {EXPIRE_MIN_DAYS} days ago."
        else:
            v.passed, v.gate = True, "passed"
    elif action == ACTION_RELABEL:
        old = v.rows[0]
        labels, why = _labels(raw, old)
        if labels is None:
            return _refuse(v, why)
        if (labels["kind"], labels["importance"], labels["decay_class"]) == (
            old.kind, old.importance, old.decay_class
        ):
            return _refuse(v, "The relabel changes nothing.")
        v.proposed.update(labels)
        if old.decay_class == DECAY_DURABLE and labels["decay_class"] == DECAY_EPISODIC:
            v.gate = "Making a lasting fact fade is always Kevin's call."
        else:
            v.passed, v.gate = True, "passed"
    elif action == ACTION_RETIRE:
        v.gate = "Retiring a memory is always Kevin's call."
    return v


# ---------------------------------------------------------------- submit


def _clusters(verdicts: list[Verdict]) -> None:
    """Changes that touch a shared memory share a cluster number (for Accept all)."""
    parent: dict[str, str] = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for v in verdicts:
        ids = [str(r.pk) for r in v.rows]
        for other in ids[1:]:
            parent[find(other)] = find(ids[0])
    numbers: dict[str, int] = {}
    for v in verdicts:
        if v.rows:
            root = find(str(v.rows[0].pk))
            v.cluster = numbers.setdefault(root, len(numbers) + 1)


def _usage(raw) -> dict:
    if not isinstance(raw, dict):
        return {}
    return {
        str(k)[:40]: int(v)
        for k, v in list(raw.items())[:20]
        if isinstance(v, (int, float)) and not isinstance(v, bool)
    }


def words(counts: dict, by_action: dict, code_merged: int) -> str:
    parts = []
    merged = by_action.get(ACTION_MERGE, 0) + code_merged
    if merged:
        parts.append(f"{merged} merged")
    for action, word in ((ACTION_EXPIRE, "expired"), (ACTION_CORRECT, "corrected"),
                         (ACTION_RELABEL, "relabelled"), (ACTION_RETIRE, "retired")):
        if by_action.get(action):
            parts.append(f"{by_action[action]} {word}")
    if counts.get("proposed"):
        parts.append(f"{counts['proposed']} to check")
    if counts.get("stale"):
        parts.append(f"{counts['stale']} stale")
    if counts.get("refused"):
        parts.append(f"{counts['refused']} refused")
    return (", ".join(parts) or "nothing to change") + "."


def submit(request, body) -> dict:
    """`POST review/changes`. Raises `ReviewRefused`; answers counts and verdicts, never text."""
    door(request)
    member = request.user
    household = household_of(request)
    if not isinstance(body, dict):
        raise ReviewRefused("Nothing was changed. The body must be an object.")
    try:
        context_id = uuid.UUID(str(body.get("context_id")))
    except ValueError:
        raise ReviewRefused("Nothing was changed. context_id is missing or not an id.") from None
    review = MemoryReview.objects.filter(
        context_id=context_id, member=member, household=household
    ).first()
    if review is None:
        raise ReviewRefused(
            "Nothing was changed. No review context with that id is yours.", status=404
        )
    if review.submitted_at is not None:
        return review.answer
    dry_run = body.get("dry_run") is True
    now = timezone.now()
    if now - review.context_at > CONTEXT_TTL:
        if not dry_run and review.outcome == REVIEW_CONTEXT_ONLY:
            review.outcome = REVIEW_FAILED
            review.detail = "The context lapsed: the helper never sent its changes within an hour."
            review.save(update_fields=["outcome", "detail"])
        raise ReviewRefused(
            "Nothing was changed. That context is more than an hour old; fetch a new one.",
            status=410,
        )
    changes = body.get("changes")
    if not isinstance(changes, list):
        raise ReviewRefused("Nothing was changed. changes must be a list.")
    if len(changes) > CHANGES_MAX:
        raise ReviewRefused(f"Nothing was changed. At most {CHANGES_MAX} changes a review.")
    mode, _why = effective_mode(member)
    today = timezone.localdate()

    with transaction.atomic():
        if not _memory_lock(household.pk):
            raise ReviewRefused(LOCKED, status=409)
        live = {
            str(r.pk): r
            for r in _live_private(member, household).select_for_update()
        }
        expected = {entry["id"]: entry["updated_at"] for entry in review.handles.values()}
        gone: set[str] = set()
        counts = {"applied": 0, "proposed": 0, "refused": 0, "stale": 0}
        by_action: dict[str, int] = {}

        # Ruling 03: exact-text duplicates, merged by code in every mode but off.
        code_merged = 0
        for group in review.code_groups:
            ids = list(group["rows"])
            rows = [live.get(i) for i in ids]
            if any(r is None or _stamp(r.updated_at) != group["rows"][str(r.pk)] for r in rows):
                counts["stale"] += 1
                continue
            keep = live[group["keep"]]
            dupes = [r for r in rows if r.pk != keep.pk]
            before = _code_merge(keep, dupes)
            MemoryReviewChange.objects.create(
                review=review, household=household, member=member, owner_user=member,
                action=ACTION_MERGE, status=CHANGE_APPLIED, by_code=True,
                targets=[str(r.pk) for r in dupes],
                targets_updated_at=[_stamp(r.updated_at) for r in dupes],
                result=keep, before=before, gate="passed", confidence="high",
                reason=f"{len(rows)} copies of the same words.", decided_at=now,
            )
            code_merged += len(dupes)
            gone.update(str(r.pk) for r in dupes)
            keep.refresh_from_db()
            live[str(keep.pk)] = keep
            expected[str(keep.pk)] = _stamp(keep.updated_at)

        verdicts = []
        auto = 0
        for index, raw in enumerate(changes):
            v = judge(index, raw, review=review, live=live, expected=expected, gone=gone,
                      today=today)
            verdicts.append(v)
            if v.status in (REFUSED, STALE):
                counts["refused" if v.status == REFUSED else "stale"] += 1
                continue
            may_apply = (
                mode == MODE_ON and v.passed and v.action in AUTO_ACTIONS
                and v.confidence == "high" and auto < AUTO_CAP
            )
            if may_apply:
                result, before = apply(v.action, v.rows, v.proposed)
                v.status = CHANGE_APPLIED
                auto += 1
                counts["applied"] += 1
                by_action[v.action] = by_action.get(v.action, 0) + 1
                gone.update(str(r.pk) for r in v.rows)
            else:
                result, before = None, {}
                v.status = CHANGE_PENDING
                counts["proposed"] += 1
                if v.passed and v.action in AUTO_ACTIONS and mode == MODE_ON and auto >= AUTO_CAP:
                    v.gate = f"passed; over this run's cap of {AUTO_CAP}"
            v.result, v.before = result, before
        _clusters([v for v in verdicts if v.status in (CHANGE_APPLIED, CHANGE_PENDING)])
        for v in verdicts:
            if v.status not in (CHANGE_APPLIED, CHANGE_PENDING):
                continue
            MemoryReviewChange.objects.create(
                review=review, household=household, member=member, owner_user=member,
                action=v.action, status=v.status,
                targets=[str(r.pk) for r in v.rows],
                targets_updated_at=[_stamp(r.updated_at) for r in v.rows],
                result=v.result, before=v.before, proposed=v.proposed, reason=v.reason,
                confidence=v.confidence, evidence_logs=v.evidence, gate=v.gate,
                cluster=v.cluster,
                decided_at=now if v.status == CHANGE_APPLIED else None,
            )

        answer = {
            "context_id": str(review.context_id),
            "dry_run": dry_run,
            "mode": mode,
            "code_merged": code_merged,
            "counts": counts,
            "by_action": by_action,
            "words": words(counts, by_action, code_merged),
            "verdicts": [v.public() for v in verdicts],
        }
        if dry_run:
            transaction.set_rollback(True)
            return answer
        review.submitted_at = now
        review.outcome = REVIEW_OK
        review.mode = mode
        review.applied, review.proposed = counts["applied"], counts["proposed"]
        review.refused, review.stale = counts["refused"], counts["stale"]
        review.counts = {**by_action, "code_merged": code_merged}
        review.usage = _usage(body.get("usage"))
        review.model = str(body.get("model") or "")[:80]
        review.chars_out = len(json.dumps(changes))
        review.answer = answer
        review.detail = ""
        review.save()
    return answer


# ---------------------------------------------------------------- Kevin's answers


def visible_changes(request):
    return MemoryReviewChange.objects.filter(
        household=household_of(request), member=request.user, owner_user=request.user
    )


def _find(request, change_id) -> MemoryReviewChange:
    try:
        pk = int(change_id)
    except (TypeError, ValueError):
        pk = None
    change = visible_changes(request).filter(pk=pk).first() if pk is not None else None
    if change is None:
        raise ReviewRefused(
            "Nothing was changed. There is no review change with that id that you can see.",
            status=404,
        )
    return change


def _answer_door(request) -> None:
    """Accept, dismiss and revert run while the switch is `off` too: Kevin may still clear what
    an earlier run proposed, or undo what it did."""
    reason = service.refusal(request)
    if reason:
        raise ReviewRefused(reason, status=403)


def _accept_one(change: MemoryReviewChange, user, now) -> bool:
    """True when applied; False (and marked stale) when its rows moved."""
    rows = {
        str(r.pk): r
        for r in MemberMemory.objects.select_for_update().filter(
            pk__in=change.targets, member=change.member, owner_user=change.member,
            superseded_at__isnull=True,
        )
    }
    fresh = len(rows) == len(change.targets) and all(
        _stamp(rows[i].updated_at) == stamp
        for i, stamp in zip(change.targets, change.targets_updated_at, strict=False)
        if i in rows
    )
    if not fresh:
        change.status, change.decided_at, change.decided_by = CHANGE_STALE, now, user
        change.save(update_fields=["status", "decided_at", "decided_by"])
        return False
    result, before = apply(change.action, [rows[i] for i in change.targets], change.proposed)
    change.status, change.result, change.before = CHANGE_ACCEPTED, result, before
    change.decided_at, change.decided_by = now, user
    change.save(update_fields=["status", "result", "before", "decided_at", "decided_by"])
    return True


def accept(request, change_id) -> dict:
    _answer_door(request)
    change = _find(request, change_id)
    if change.status != CHANGE_PENDING:
        raise ReviewRefused(f"Nothing was changed. That change is {change.status}, not pending.",
                            status=409)
    now = timezone.now()
    with transaction.atomic():
        if not _memory_lock(change.household_id):
            raise ReviewRefused(LOCKED, status=409)
        ok = _accept_one(change, request.user, now)
    if not ok:
        raise ReviewRefused(STALE_ACCEPT, status=409)
    return {"id": change.id, "status": change.status, "words": "Done. Your memory is updated."}


def accept_cluster(request, cluster: str) -> dict:
    _answer_door(request)
    try:
        review_id, number = (int(part) for part in str(cluster).split("."))
    except ValueError:
        raise ReviewRefused("Nothing was changed. A cluster is named like 12.3.", 404) from None
    changes = list(
        visible_changes(request)
        .filter(review_id=review_id, cluster=number, status=CHANGE_PENDING)
        .order_by("id")
    )
    if not changes:
        raise ReviewRefused("Nothing was changed. That group has nothing waiting.", status=404)
    now = timezone.now()
    done = stale = 0
    with transaction.atomic():
        if not _memory_lock(changes[0].household_id):
            raise ReviewRefused(LOCKED, status=409)
        for change in changes:
            if _accept_one(change, request.user, now):
                done += 1
            else:
                stale += 1
    said = f"Accepted {done}."
    if stale:
        said += f" {stale} could not be: those memories changed since the review."
    return {"accepted": done, "stale": stale, "words": said}


def dismiss(request, change_id) -> dict:
    _answer_door(request)
    change = _find(request, change_id)
    if change.status != CHANGE_PENDING:
        raise ReviewRefused(f"Nothing was changed. That change is {change.status}, not pending.",
                            status=409)
    change.status, change.decided_at, change.decided_by = (
        CHANGE_DISMISSED, timezone.now(), request.user,
    )
    change.save(update_fields=["status", "decided_at", "decided_by"])
    return {"id": change.id, "status": change.status, "words": "Dismissed. Nothing was changed."}


def revert(request, change_id) -> dict:
    """Restores the rows a change retired and deletes only the row the review itself wrote."""
    _answer_door(request)
    change = _find(request, change_id)
    if change.status not in (CHANGE_APPLIED, CHANGE_ACCEPTED):
        raise ReviewRefused(
            f"Nothing was changed. Only an applied change can be undone; that one is "
            f"{change.status}.",
            status=409,
        )
    now = timezone.now()
    with transaction.atomic():
        if not _memory_lock(change.household_id):
            raise ReviewRefused(LOCKED, status=409)
        result = (
            MemberMemory.objects.select_for_update().filter(pk=change.result_id).first()
            if change.result_id else None
        )
        if change.result_id and (result is None or result.superseded_at is not None):
            raise ReviewRefused(
                "Nothing was changed. The memory this change wrote has since been replaced or "
                "forgotten.",
                status=409,
            )
        restored = MemberMemory.objects.filter(
            pk__in=change.targets, member=change.member,
            retired_reason=RETIRED_FOR[change.action],
        ).update(superseded_at=None, retired_by=None, retired_reason=None, updated_at=Now())
        if change.by_code and result is not None:
            MemberMemory.objects.filter(pk=result.pk).update(
                source=change.before.get("source", result.source),
                recall_count=change.before.get("recall_count", result.recall_count),
                updated_at=Now(),
            )
        elif result is not None:
            result.delete()
        change.status, change.decided_at, change.decided_by = CHANGE_REVERTED, now, request.user
        change.save(update_fields=["status", "decided_at", "decided_by"])
    return {
        "id": change.id,
        "status": change.status,
        "restored": restored,
        "words": f"Undone. {restored} memor{'y' if restored == 1 else 'ies'} restored.",
    }


# ---------------------------------------------------------------- the phone's shapes

ACTION_WORDS = {
    ACTION_MERGE: "These say the same thing; keep one.",
    ACTION_CORRECT: "A later conversation says otherwise.",
    ACTION_EXPIRE: "The date has passed.",
    ACTION_RELABEL: "Same words, different labels.",
    ACTION_RETIRE: "This is not about you.",
}


def after_words(change: MemoryReviewChange) -> str:
    """The short "after" the recall flag and the screen say."""
    if change.action in (ACTION_MERGE, ACTION_CORRECT):
        return change.proposed.get("text", "")
    if change.action == ACTION_EXPIRE:
        return "the date has passed, so it would be retired"
    if change.action == ACTION_RETIRE:
        return "it is not about you, so it would be retired"
    labels = change.proposed
    lasting = "lasting" if labels.get("decay_class") == DECAY_DURABLE else "fading"
    return f"same words, kept as a {lasting} {labels.get('kind', 'fact')}"


def change_dict(change: MemoryReviewChange, rows: dict) -> dict:
    review = change.review
    stamps = [
        entry.get("at") for entry in (review.logs or {}).values()
        if entry.get("id") in (change.evidence_logs or [])
    ]
    return {
        "id": change.id,
        "action": change.action,
        "status": change.status,
        "by_code": change.by_code,
        "cluster": f"{change.review_id}.{change.cluster}",
        "before": [
            {
                "id": target,
                "text": rows[target].text if target in rows else None,
                "kind": rows[target].kind if target in rows else None,
                "durable": rows[target].decay_class == DECAY_DURABLE if target in rows else None,
            }
            for target in change.targets
        ],
        "after": change.proposed or None,
        "after_words": after_words(change),
        "reason": change.reason,
        "action_words": ACTION_WORDS[change.action],
        "confidence": change.confidence,
        "gate": change.gate,
        "evidence_date": max(stamps)[:10] if stamps else None,
        "created_at": _stamp(change.created_at),
        "result": str(change.result_id) if change.result_id else None,
    }


def changes_listing(request, status: str | None = CHANGE_PENDING, limit: int = 200) -> list:
    _answer_door(request)
    changes = visible_changes(request).select_related("review").order_by("-id")
    if status:
        changes = changes.filter(status=status)
    changes = list(changes[:limit])
    ids = {t for c in changes for t in (c.targets or [])}
    rows = {
        str(r.pk): r
        for r in service.readable(request).filter(pk__in=ids)
    }
    return [change_dict(c, rows) for c in changes]


def pending_flags(request, rows) -> dict:
    """Row id -> `{change_id, action, after}` for rows named in a pending change (ruling 02's
    recall flag). One query for the whole list."""
    ids = {str(r.pk) for r in rows if r.member_id == request.user.pk}
    if not ids:
        return {}
    flags = {}
    for change in visible_changes(request).filter(status=CHANGE_PENDING).order_by("id"):
        for target in change.targets or []:
            if target in ids and target not in flags:
                flags[target] = {
                    "change_id": change.id,
                    "action": change.action,
                    "after": after_words(change),
                }
    return flags


def retired_by_change(request, rows) -> dict:
    """Row id -> the applied or accepted change that retired it, so Restore can name it."""
    ids = {str(r.pk) for r in rows if r.retired_reason}
    if not ids:
        return {}
    found = {}
    for change in visible_changes(request).filter(
        status__in=(CHANGE_APPLIED, CHANGE_ACCEPTED)
    ).order_by("-id"):
        for target in change.targets or []:
            if target in ids and target not in found:
                found[target] = change.id
    return found


# ---------------------------------------------------------------- catch-up (ticket 15)


def _mint_lock(member_id) -> None:
    material = f"legion.memory_review.mint:{member_id}".encode()
    key = int.from_bytes(hashlib.sha256(material).digest()[:8], "big", signed=True)
    with connection.cursor() as cursor:
        cursor.execute("select pg_advisory_xact_lock(%s)", [key])


def last_ok_at(member, household):
    return MemoryReview.objects.filter(
        member=member, household=household, outcome=REVIEW_OK
    ).aggregate(at=Max("submitted_at"))["at"]


def last_failure_at(member, household, now):
    from claude_session.models import CANCELLED, FAILED, NEEDS_CONFIRMATION

    stamps = []
    task_end = (
        _review_tasks(member, household)
        .filter(status__in=(FAILED, CANCELLED, NEEDS_CONFIRMATION))
        .aggregate(at=Max("finished_at"))["at"]
    )
    stamps.append(task_end)
    stamps.append(
        MemoryReview.objects.filter(member=member, household=household, outcome=REVIEW_FAILED)
        .aggregate(at=Max("context_at"))["at"]
    )
    stamps.append(
        MemoryReview.objects.filter(
            member=member, household=household, outcome=REVIEW_CONTEXT_ONLY,
            context_at__lt=now - CONTEXT_TTL,
        ).aggregate(at=Max("context_at"))["at"]
    )
    # A running review the clock already calls failed (30 minute cap, silent machine).
    from claude_session.state import failure_reason

    for task in _review_tasks(member, household).filter(status__in=("running", "cancel_requested")):
        if failure_reason(task, now):
            stamps.append(task.started_at or task.created_at)
    stamps = [s for s in stamps if s is not None]
    return max(stamps) if stamps else None


def _review_tasks(member, household):
    from claude_session.models import KIND_MEMORY_REVIEW, ClaudeTask

    return ClaudeTask.objects.filter(household=household, user=member, kind=KIND_MEMORY_REVIEW)


def _in_flight_review(member, household, now) -> bool:
    from claude_session.models import CANCEL_REQUESTED, QUEUED, RUNNING
    from claude_session.state import failure_reason

    for task in _review_tasks(member, household).filter(
        status__in=(QUEUED, RUNNING, CANCEL_REQUESTED)
    ):
        if not failure_reason(task, now):
            return True
    return False


@dataclass(frozen=True)
class Due:
    due: bool
    overdue: bool = False
    why: str = ""


def review_due(member, household, now) -> Due:
    """Computed when read, never written by a job (the ADR 0067 pattern)."""
    if configured_mode() == MODE_OFF:
        return Due(False, why="off")
    if not _live_private(member, household).exists():
        return Due(False, why="no private memories")
    if _in_flight_review(member, household, now):
        return Due(False, why="one is queued or running")
    last_ok = last_ok_at(member, household)
    if last_ok is not None and now - last_ok < DUE_AFTER:
        return Due(False, why="ran in the last 24 hours")
    failed = last_failure_at(member, household, now)
    if failed is not None and now - failed < FAILURE_WAIT:
        return Due(False, why="failed in the last 6 hours")
    overdue = last_ok is None or now - last_ok >= OVERDUE_AFTER
    return Due(True, overdue=overdue)


def _machine_busy(machine, now) -> bool:
    from claude_session.models import IN_FLIGHT, ClaudeTask
    from claude_session.state import failure_reason

    return any(
        not failure_reason(task, now)
        for task in ClaudeTask.objects.filter(machine=machine, status__in=IN_FLIGHT)
    )


def maybe_mint(request, machine, now):
    """Inside a claim's transaction: mint one review task for this machine when the token's
    member is due and the machine is idle. Returns the task or None. Never for the work laptop,
    never for a machine bound to another member's token, never while the switch is off."""
    from claude_session.models import (
        KIND_MEMORY_REVIEW,
        ORIGIN_SCHEDULE,
        QUEUED,
        SCOPE_FULL,
        ClaudeTask,
    )
    from household.models import DeviceToken, is_recruiter

    if configured_mode() == MODE_OFF:
        return None
    token = getattr(request, "auth", None)
    member = getattr(request, "user", None)
    if machine.scope != SCOPE_FULL or not isinstance(token, DeviceToken):
        return None
    if machine.token_id != token.pk or token.user_id != getattr(member, "pk", None):
        return None
    if is_recruiter(member) or service.refusal(request):
        return None
    household = machine.household
    if getattr(household, "is_demo_visit", False):
        return None
    if machine.input_idle_s is None:
        # A worker that sends no idle reading predates the review: it would run the task as an
        # ordinary ask, with no fixed progress phrase and no hook rule. Never hand it one.
        return None
    _mint_lock(member.pk)
    due = review_due(member, household, now)
    if not due.due or _machine_busy(machine, now):
        return None
    idle = (machine.input_idle_s or 0) >= IDLE_INPUT_SECONDS
    if not idle and not due.overdue:
        return None
    return ClaudeTask.objects.create(
        household=household,
        user=member,
        machine=machine,
        origin=ORIGIN_SCHEDULE,
        kind=KIND_MEMORY_REVIEW,
        request=TASK_REQUEST,
        status=QUEUED,
    )


def run_now(request) -> dict:
    """`POST review/run`: queue one review for the caller's own Alienware, whatever the 24 hours
    say (ticket 12). Still one at a time."""
    from claude_session.models import (
        KIND_MEMORY_REVIEW,
        ORIGIN_SCHEDULE,
        QUEUED,
        SCOPE_FULL,
        ClaudeMachine,
        ClaudeTask,
    )

    door(request)
    member = request.user
    household = household_of(request)
    machine = (
        ClaudeMachine.objects.filter(
            household=household, scope=SCOPE_FULL, token__user=member,
            input_idle_s__isnull=False,
        ).order_by("-last_seen_at").first()
    )
    if machine is None:
        raise ReviewRefused(
            "Nothing was queued. None of your Claude machines runs a worker that knows the "
            "memory review yet.",
            status=404,
        )
    now = timezone.now()
    with transaction.atomic():
        _mint_lock(member.pk)
        if _in_flight_review(member, household, now):
            raise ReviewRefused(
                "Nothing was queued. A memory review is already queued or running.", status=409
            )
        task = ClaudeTask.objects.create(
            household=household, user=member, machine=machine, origin=ORIGIN_SCHEDULE,
            kind=KIND_MEMORY_REVIEW, request=TASK_REQUEST, status=QUEUED,
        )
    return {"task": task.id, "words": f"Queued a memory review on the {machine.name}."}
