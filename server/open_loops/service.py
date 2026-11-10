"""Open loops, every operation once (ADR 0085). REST (`open_loops/views.py`) and the nightly memory
review (`member_memory/review.py`, which calls `context_block` and `submit_block`) both come here.

**Who may do what.**
- The member, from the phone (his tap or his voice): list, track, dismiss, the switch.
- The Claude worker on his Alienware: only `context_block` and `submit_block`, through the
  review's own routes. **It may write proposals (it is their source) and may never answer one**:
  every route here refuses any token the engine ties to a Claude machine
  (`claude_session.tokens.is_claude_token`), whatever its scope.
- Never a recruiter (ADR 0068), never the work laptop, never a demo household.

**Off by default.** `LEGION_OPEN_LOOPS` must be `on` (and the memory review not `off`) before a
review is offered his words or writes a proposal, so deploying this changes nothing until Kevin
flips it. His own switch (`OpenLoopSettings.proposing`, "stop proposing tasks") stops extraction
too, not only the line.

**Nothing here logs.** Titles travel only in JSON answers to their owner.
"""

from __future__ import annotations

import datetime
import json
import uuid

from django.conf import settings
from django.db import transaction
from django.db.models import F
from django.db.models.functions import Now
from django.utils import timezone

from household.tenancy import household_of, visible
from open_loops import gate, words
from open_loops.models import (
    STATUS_DISMISSED,
    STATUS_PENDING,
    STATUS_TRACKED,
    TASK_EXISTING,
    TASK_REMINDER,
    TASK_TODO,
    OpenLoop,
    OpenLoopRun,
    OpenLoopSettings,
)

CAP_DEFAULT = 5
EXPIRE_DAYS_DEFAULT = 14
# A proposal rides at most this many spoken briefs; after that it waits on the list until it
# expires (a question asked every morning for two weeks is a nag; ADR 0085).
OFFER_MAX = 2
OWN_WORDS_MAX_CHARS = 20_000
KNOWN_MAX = 100
PROPOSALS_MAX = 20
TODO_LIST_NAME = "Todo"  # where a spoken plain to-do lands (`notes/ReminderChecklistMigration.kt`)

OFF_WORDS = "Proposed to-dos are off on this engine."
PAUSED_WORDS = "You asked Alfred to stop proposing to-dos."
CLAUDE_REFUSAL = (
    "Nothing was changed. A Claude worker may propose a to-do but never answer one; only the "
    "member does, on his phone."
)
DEMO_REFUSAL = "Nothing was read or changed. A demo household has no proposed to-dos."
NOT_FOUND = "Nothing was changed. There is no proposed to-do with that id that you can see."


class LoopRefused(Exception):
    """Nothing was read or written. The message says why, in words."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


# ---------------------------------------------------------------- switches and doors


def enabled() -> bool:
    flag = str(getattr(settings, "LEGION_OPEN_LOOPS", "off") or "off").strip().lower()
    if flag != "on":
        return False
    from member_memory.review import MODE_OFF, configured_mode

    return configured_mode() != MODE_OFF


def cap() -> int:
    try:
        return max(0, int(getattr(settings, "LEGION_OPEN_LOOPS_CAP", CAP_DEFAULT)))
    except (TypeError, ValueError):
        return CAP_DEFAULT


def expire_after() -> datetime.timedelta:
    try:
        days = max(1, int(getattr(settings, "LEGION_OPEN_LOOPS_EXPIRE_DAYS", EXPIRE_DAYS_DEFAULT)))
    except (TypeError, ValueError):
        days = EXPIRE_DAYS_DEFAULT
    return datetime.timedelta(days=days)


def proposing(owner, household) -> bool:
    row = OpenLoopSettings.objects.filter(household=household, owner_user=owner).first()
    return True if row is None else row.proposing


def door(request) -> None:
    """Every REST route: members only, never a Claude token, a recruiter or a demo household."""
    from claude_session.tokens import is_claude_token
    from member_memory import service as memory

    reason = memory.refusal(request)
    if reason:
        raise LoopRefused(reason, status=403)
    if is_claude_token(request):
        raise LoopRefused(CLAUDE_REFUSAL, status=403)
    household = household_of(request)
    if getattr(household, "is_demo_visit", False):
        raise LoopRefused(DEMO_REFUSAL, status=403)
    from household.demo import find_demo_household

    demo = find_demo_household()
    if demo is not None and demo.pk == household.pk:
        raise LoopRefused(DEMO_REFUSAL, status=403)


# ---------------------------------------------------------------- reading


def _live_pending(request, now=None):
    now = now or timezone.now()
    return visible(OpenLoop, request).filter(status=STATUS_PENDING, expires_at__gt=now)


def tracked_keys(request) -> set[str]:
    """Keys of what is already on his list: open reminders and tasks, and unticked lines on
    non-recurring checklists he can see."""
    from checklists.models import Checklist, ChecklistItem, ChecklistTick
    from legacy.models import Event

    keys = {
        gate.dedupe_key(title)
        for title in visible(Event, request)
        .filter(deleted_at__isnull=True, done=False, kind__in=("reminder", "task"))
        .values_list("title", flat=True)
    }
    lists = visible(Checklist, request).filter(
        deleted_at__isnull=True, archived=False, schedule_kind__isnull=True
    )
    ticked = ChecklistTick.objects.filter(deleted_at__isnull=True).values("item_id")
    keys |= {
        gate.dedupe_key(text)
        for text in ChecklistItem.objects.filter(checklist__in=lists, deleted_at__isnull=True)
        .exclude(pk__in=ticked)
        .values_list("text", flat=True)
    }
    return keys


def _close_already_tracked(request, rows) -> list[OpenLoop]:
    """A pending proposal he has since tracked himself ("remind me to call the dentist") closes as
    tracked, with no new task. Returns the rows still open."""
    if not rows:
        return rows
    have = tracked_keys(request)
    still = []
    for row in rows:
        if row.dedupe_key in have:
            OpenLoop.objects.filter(pk=row.pk, status=STATUS_PENDING).update(
                status=STATUS_TRACKED, task_kind=TASK_EXISTING, decided_at=Now(), updated_at=Now()
            )
        else:
            still.append(row)
    return still


def wire(row: OpenLoop) -> dict:
    """What a surface may see: the task and its date. Never when he said it."""
    return {
        "id": str(row.id),
        "title": row.title,
        "due_date": row.due_date.isoformat() if row.due_date else None,
        "status": row.status,
    }


def _listing(request, rows, *, on: bool, paused: bool) -> dict:
    if not on:
        said = OFF_WORDS
    elif paused:
        said = PAUSED_WORDS
    elif not rows:
        said = "No proposed to-dos."
    else:
        said = f"{len(rows)} proposed to-do{'s' if len(rows) != 1 else ''}."
    return {
        "on": on,
        "proposing": not paused,
        "proposals": [wire(r) for r in rows],
        "words": said,
    }


def list_for(request) -> dict:
    door(request)
    household = household_of(request)
    on = enabled()
    paused = not proposing(request.user, household)
    rows = []
    if on and not paused:
        rows = list(_live_pending(request).order_by("created_at", "id")[:PROPOSALS_MAX])
        rows = _close_already_tracked(request, rows)
    return _listing(request, rows, on=on, paused=paused)


def offer(request) -> dict:
    """The spoken brief's batch: pending proposals carried by fewer than `OFFER_MAX` briefs, each
    counted as carried now. The Brief modal and the list use `list_for`, which counts nothing."""
    door(request)
    household = household_of(request)
    on = enabled()
    paused = not proposing(request.user, household)
    rows = []
    if on and not paused:
        with transaction.atomic():
            rows = list(
                _live_pending(request)
                .filter(offered_count__lt=OFFER_MAX)
                .select_for_update()
                .order_by("created_at", "id")[:PROPOSALS_MAX]
            )
            rows = _close_already_tracked(request, rows)
            if rows:
                OpenLoop.objects.filter(pk__in=[r.pk for r in rows]).update(
                    offered_count=F("offered_count") + 1
                )
    return _listing(request, rows, on=on, paused=paused)


# ---------------------------------------------------------------- answers (the member only)


def _find_for_answer(request, loop_id) -> OpenLoop:
    try:
        pk = uuid.UUID(str(loop_id))
    except ValueError:
        raise LoopRefused(NOT_FOUND, status=404) from None
    row = visible(OpenLoop, request).select_for_update().filter(pk=pk).first()
    if row is None:
        raise LoopRefused(NOT_FOUND, status=404)
    return row


def _zone(household):
    from household.timezones import zone_named

    return zone_named(getattr(household, "timezone", None)) or timezone.get_current_timezone()


def _today(household) -> datetime.date:
    return timezone.now().astimezone(_zone(household)).date()


def _make_reminder(request, row: OpenLoop, day: datetime.date) -> uuid.UUID:
    """A dated reminder, as "remind me to X on <day>" makes one: `public.events`, kind reminder,
    all day, through the events API's own serializer. Idempotent on `origin_guid`."""
    from api.events import EventSerializer
    from legacy.models import Event

    guid = f"open-loop:{row.id}"
    existing = visible(Event, request).filter(origin_guid=guid).first()
    if existing is not None:
        return existing.id
    zone = _zone(row.household)
    starts = datetime.datetime.combine(day, datetime.time.min, tzinfo=zone)
    serializer = EventSerializer(
        data={
            "title": row.title,
            "starts_at": starts.isoformat(),
            "all_day": True,
            "kind": "reminder",
            "origin_guid": guid,
        },
        context={"household": row.household, "user": request.user},
    )
    serializer.is_valid(raise_exception=True)
    return serializer.save().id


def _make_todo(request, row: OpenLoop) -> uuid.UUID:
    """An undated line on his Todo checklist, where a spoken plain to-do lands. Idempotent on
    `sync_id`. Creates the list (shared, as a hand-made one is) when he has none."""
    from checklists.models import Checklist, ChecklistItem
    from checklists.serializers import ChecklistItemSerializer

    sync_id = f"open-loop:{row.id}"
    existing = visible(ChecklistItem, request).filter(sync_id=sync_id).first()
    if existing is not None:
        return existing.id
    lists = visible(Checklist, request).filter(
        deleted_at__isnull=True, archived=False, name__iexact=TODO_LIST_NAME
    )
    todo = lists.order_by("created_at", "id").first()
    if todo is None:
        todo = Checklist.objects.create(
            household=row.household, name=TODO_LIST_NAME, created_by=request.user
        )
    order = ChecklistItem.objects.filter(checklist=todo, deleted_at__isnull=True).count()
    serializer = ChecklistItemSerializer(
        data={"checklist": str(todo.pk), "text": row.title, "sync_id": sync_id, "sort_order": order}
    )
    serializer.is_valid(raise_exception=True)
    return serializer.save(created_by=request.user).id


def _parse_day(value) -> datetime.date | None:
    if value in (None, ""):
        return None
    try:
        return datetime.date.fromisoformat(str(value))
    except ValueError:
        raise LoopRefused("Nothing was tracked. due_date must be a date, YYYY-MM-DD.") from None


def track(request, loop_id, due_date=None) -> dict:
    """His yes. A dated reminder when he named a date (in the words the proposal came from, or in
    this answer), otherwise an undated to-do; never an invented date. Idempotent: a second yes
    answers with the task the first one made."""
    door(request)
    said_day = _parse_day(due_date)
    with transaction.atomic():
        row = _find_for_answer(request, loop_id)
        if row.status == STATUS_TRACKED:
            return {"proposal": wire(row), "task_kind": row.task_kind,
                    "task_id": str(row.task_id) if row.task_id else None,
                    "words": f"Already tracked: {row.title}."}
        if row.status == STATUS_DISMISSED:
            raise LoopRefused(
                "Nothing was tracked. You dismissed that one, and a dismissed to-do stays gone.",
                status=409,
            )
        if row.expires_at <= timezone.now():
            raise LoopRefused(NOT_FOUND, status=404)
        today = _today(row.household)
        if said_day is not None and said_day < today:
            raise LoopRefused("Nothing was tracked. That date has passed.")
        day = said_day or (row.due_date if row.due_date and row.due_date >= today else None)
        if day is not None:
            task_id, kind = _make_reminder(request, row, day), TASK_REMINDER
            when = f" for {day.strftime('%a %d %b').replace(' 0', ' ')}"
        else:
            task_id, kind, when = _make_todo(request, row), TASK_TODO, " on your Todo list, no date"
        OpenLoop.objects.filter(pk=row.pk).update(
            status=STATUS_TRACKED, task_kind=kind, task_id=task_id,
            due_date=day, decided_at=Now(), decided_by=request.user, updated_at=Now(),
        )
        row.refresh_from_db()
    return {"proposal": wire(row), "task_kind": kind, "task_id": str(task_id),
            "words": f"Tracked: {row.title}{when}."}


def dismiss(request, loop_id) -> dict:
    """His no, for good: this commitment is never proposed again."""
    door(request)
    with transaction.atomic():
        row = _find_for_answer(request, loop_id)
        if row.status == STATUS_TRACKED:
            raise LoopRefused(
                "Nothing was changed. That one is already tracked; "
                "remove it from your list instead.",
                status=409,
            )
        if row.status == STATUS_PENDING:
            OpenLoop.objects.filter(pk=row.pk).update(
                status=STATUS_DISMISSED, decided_at=Now(), decided_by=request.user, updated_at=Now()
            )
            row.refresh_from_db()
    return {"proposal": wire(row), "words": f"Dropped: {row.title}. It won't be proposed again."}


def set_proposing(request, on) -> dict:
    door(request)
    if not isinstance(on, bool):
        raise LoopRefused("Nothing was changed. proposing must be true or false.")
    household = household_of(request)
    OpenLoopSettings.objects.update_or_create(
        household=household, owner_user=request.user,
        defaults={"proposing": on, "updated_at": Now()},
    )
    said = (
        "Alfred will propose to-dos from what you say again."
        if on
        else (
            "Alfred will not propose to-dos any more. "
            "Turn it back on in Settings, Proactive speech."
        )
    )
    return {"proposing": on, "words": said}


# ---------------------------------------------------------------- the nightly review


def _read_logs(owner, household, since) -> set[str]:
    read: set[str] = set()
    for logs in OpenLoopRun.objects.filter(
        household=household, owner_user=owner, submitted_at__isnull=False, created_at__gte=since
    ).values_list("read_logs", flat=True):
        read.update(logs or [])
    return read


def context_block(request, *, context_id, now=None) -> dict:
    """The `open_loops` part of `GET review/context`: his own words from records no submitted
    run has read, under W labels, and the titles already proposed. Records the run in the ledger.
    Empty, with why, when the feature or his switch is off."""
    from devicelogs.models import REASON_CONVERSATION, DeviceLog
    from member_memory.review import REVIEW_DAYS

    owner, household = request.user, household_of(request)
    now = now or timezone.now()
    if not enabled():
        return {"on": False, "why": OFF_WORDS}
    if not proposing(owner, household):
        return {"on": False, "why": PAUSED_WORDS}
    since = now - datetime.timedelta(days=REVIEW_DAYS)
    already = _read_logs(owner, household, since - datetime.timedelta(days=1))
    logs = DeviceLog.objects.filter(
        household=household, user=owner, reason=REASON_CONVERSATION, demo_visitor=False,
        received_at__gte=since,
    ).order_by("received_at", "pk")
    labels, own, used = {}, [], 0
    for log in logs:
        if str(log.pk) in already:
            continue
        text = words.own_text(log)
        if not text:
            continue
        if used + len(text) > OWN_WORDS_MAX_CHARS:
            break
        used += len(text)
        label = f"W{len(own) + 1}"
        labels[label] = str(log.pk)
        own.append({"label": label, "text": text})
    OpenLoopRun.objects.create(
        household=household, owner_user=owner, context_id=context_id, labels=labels
    )
    known = list(
        OpenLoop.objects.filter(household=household, owner_user=owner)
        .order_by("-created_at")
        .values_list("title", flat=True)[:KNOWN_MAX]
    )
    left = max(0, cap() - _proposed_today(owner, household, now))
    return {"on": True, "cap": left, "own_words": own, "already_proposed": known}


def _proposed_today(owner, household, now) -> int:
    zone = _zone(household)
    start = datetime.datetime.combine(now.astimezone(zone).date(), datetime.time.min, tzinfo=zone)
    return OpenLoop.objects.filter(
        household=household, owner_user=owner, created_at__gte=start
    ).count()


def _verdict(index, status, why="") -> dict:
    return {"index": index, "status": status, "gate": why}


def submit_block(request, raw, *, context_id, dry_run: bool, now=None) -> dict:
    """The `open_loops` part of `POST review/changes`, inside the review's transaction. Each
    proposal is gated (`open_loops.gate`); the answer is counts and verdicts, never text."""
    from devicelogs.models import DeviceLog

    owner, household = request.user, household_of(request)
    now = now or timezone.now()
    empty = {"proposed": 0, "duplicate": 0, "refused": 0, "deferred": 0, "verdicts": []}
    if raw is None:
        raw = []
    run = OpenLoopRun.objects.filter(
        context_id=context_id, owner_user=owner, household=household
    ).select_for_update().first()
    if run is None or not enabled() or not proposing(owner, household):
        if raw:
            empty["refused"] = len(raw) if isinstance(raw, list) else 1
            empty["note"] = OFF_WORDS if not enabled() else PAUSED_WORDS
        return empty
    if not isinstance(raw, list):
        empty["refused"] = 1
        empty["note"] = "open_loops must be a list."
        return empty
    counts = {"proposed": 0, "duplicate": 0, "refused": 0, "deferred": 0}
    verdicts = []
    room = max(0, cap() - _proposed_today(owner, household, now))
    today = _today(household)
    have = tracked_keys(request)
    held_back: set[str] = set()
    heard_cache: dict[str, str] = {}

    def heard(log_id: str) -> str:
        if log_id not in heard_cache:
            log = DeviceLog.objects.filter(pk=log_id, household=household, user=owner).first()
            heard_cache[log_id] = words.own_text(log) if log is not None else ""
        return heard_cache[log_id]

    for index, item in enumerate(raw[:PROPOSALS_MAX * 2]):
        if not isinstance(item, dict):
            counts["refused"] += 1
            verdicts.append(_verdict(index, "refused", "a proposal must be an object."))
            continue
        text = gate.clean(item.get("text"))
        cited = [e for e in (item.get("evidence") or []) if isinstance(e, str) and e in run.labels]
        log_ids = [run.labels[e] for e in dict.fromkeys(cited)]
        said = "\n".join(heard(i) for i in log_ids)
        why = gate.check_text(text, said)
        if why:
            counts["refused"] += 1
            verdicts.append(_verdict(index, "refused", why))
            continue
        key = gate.dedupe_key(text)
        if key in have:
            counts["duplicate"] += 1
            verdicts.append(_verdict(index, "duplicate", "already on his list."))
            continue
        existing = OpenLoop.objects.filter(
            household=household, owner_user=owner, dedupe_key=key
        ).first()
        if existing is not None and existing.status != STATUS_PENDING:
            counts["refused"] += 1
            verdicts.append(_verdict(index, "refused", f"he already {existing.status} it."))
            continue
        if existing is not None and existing.expires_at > now:
            counts["duplicate"] += 1
            verdicts.append(_verdict(index, "duplicate", "already proposed."))
            continue
        if room <= 0:
            counts["deferred"] += 1
            held_back.update(log_ids)
            verdicts.append(_verdict(index, "deferred", "over tonight's cap; read again tomorrow."))
            continue
        reason, note = gate.keep_reason(item.get("reason"), said)
        fields = {
            "title": text[0].upper() + text[1:],
            "due_date": gate.stated_date(text, today),
            "status": STATUS_PENDING,
            "reason": reason,
            "evidence_logs": log_ids,
            "review_context": context_id,
            "expires_at": now + expire_after(),
            "offered_count": 0,
            "decided_at": None,
            "decided_by": None,
        }
        if existing is not None:  # an expired one, said again in a new conversation
            OpenLoop.objects.filter(pk=existing.pk).update(
                created_at=Now(), updated_at=Now(), **fields
            )
        else:
            OpenLoop.objects.create(
                household=household, owner_user=owner, dedupe_key=key, **fields
            )
        have.add(key)
        room -= 1
        counts["proposed"] += 1
        verdicts.append(_verdict(index, "proposed", note or "passed"))

    if not dry_run:
        run.submitted_at = now
        run.read_logs = [i for i in run.labels.values() if i not in held_back]
        run.proposed, run.refused = counts["proposed"], counts["refused"]
        run.save(update_fields=["submitted_at", "read_logs", "proposed", "refused"])
    return {**counts, "verdicts": verdicts, "chars_in": len(json.dumps(raw))}


def words_for(block: dict) -> str:
    """The counts sentence appended to the review's summary line."""
    if not block or not any(block.get(k) for k in ("proposed", "duplicate", "refused", "deferred")):
        return ""
    parts = []
    if block.get("proposed"):
        parts.append(f"{block['proposed']} to-do{'s' if block['proposed'] != 1 else ''} proposed")
    if block.get("duplicate"):
        parts.append(f"{block['duplicate']} already known")
    if block.get("deferred"):
        parts.append(f"{block['deferred']} left for tomorrow")
    if block.get("refused"):
        parts.append(f"{block['refused']} refused")
    return "Open loops: " + ", ".join(parts) + "."
