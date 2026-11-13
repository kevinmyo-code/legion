"""The facts pack: everything a greeting or a check-in may mention, and nothing else (ADR 0091,
alfred-greets ticket 01).

CLAUDE.md section 1: a proactive prompt states its facts or forbids its subject. So the engine
composes, for the signed-in member, five sources from rows it holds:

- `ended_events`: calendar events that ended in the last 24 hours;
- `today_events`: events and reminders still to come today, and today's all-day events;
- `due_soon`: open to-dos and Canvas work due within 24 hours, not ticked and not submitted;
- `body`: today's workout (planned, running or done) and the latest weigh-in's date;
- `money`: each capped budget category, spent against its cap and the pace, in integer cents,
  with the month-end projection marked an ESTIMATE.

The phone adds the current place itself; the engine never holds a location fix.

**Every source is exactly one of three states, and they are different sentences:**
`facts` (items follow), `empty` (read, and nothing is on file) and `unreadable` (the read
failed; `reason` says so in words). An exception while reading a source is `unreadable`, never
`empty`: a refused read rendered as a clear day tells the member he is free when the engine cannot
see.

**Every read goes through the tenancy choke point**: `visible()` for `events`, so a Canvas task
private to one member reaches only that member's pack (ADR 0052), `scoped()` for the
household-wide body and ledger rows, and `visible()` again for `greeting_topics`.

**Times** are ISO 8601 with the household's UTC offset. No IANA zone id appears anywhere in the
payload (CLAUDE.md section 1); a household with no zone set gets UTC, and `zone_note` says so.

**Never the same question twice.** Each item carries a stable `topic` key. A topic the member
answered, or that was raised and silenced, is left out (`left_out` counts them); a raised one
stays, marked `raised`, and is listed in `said`. `closed` lists the answered and silenced keys so
the phone can also leave out the facts it composes itself (place).
"""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

from django.db import transaction

from brief.compose import (
    UNVERIFIED_SUFFIX,
    canvas_freshness_note,
    clock_words,
    day_words,
    money,
)
from household.tenancy import household_of, visible
from household.timezones import zone_named

STATE_FACTS = "facts"
STATE_EMPTY = "empty"
STATE_UNREADABLE = "unreadable"
STATES: tuple[str, ...] = (STATE_FACTS, STATE_EMPTY, STATE_UNREADABLE)
# Whether the record of topics already raised, answered or silenced could be read.
TOPICS_READ = "read"
TOPICS_READ_STATES: tuple[str, ...] = (TOPICS_READ, STATE_UNREADABLE)

SOURCE_NAMES: tuple[str, ...] = ("ended_events", "today_events", "due_soon", "body", "money")

ENDED_HOURS = 24
DUE_HOURS = 24
# How far back an occurrence may have started and still end inside the last 24 hours.
LONGEST_EVENT_DAYS = 3
ITEM_LIMIT = 20
# Topics recorded further back than this are not read: event and due keys name one row (or one
# occurrence), so an old key matches nothing current, and the phone's own place keys age out.
TOPIC_WINDOW_DAYS = 90
TOPIC_LIMIT = 300

EVENT_KINDS = ("event",)
TODAY_KINDS = ("event", "reminder")
DUE_KINDS = ("task",)

SOURCE_WORDS = {
    "ended_events": "calendar events that ended in the last 24 hours",
    "today_events": "today's remaining events",
    "due_soon": "to-dos and Canvas work due within 24 hours",
    "body": "today's workout and the latest weigh-in",
    "money": "this month's budgets",
}
EMPTY_WORDS = {
    "ended_events": "No calendar event ended in the last 24 hours.",
    "today_events": "Nothing else is on the calendar for today.",
    "due_soon": "No open to-do or Canvas work is due within the next 24 hours.",
    "body": "No training programme, workout or weigh-in is on file.",
    "money": "No budget caps are set for this month, so there is no pace to report.",
}
ALL_CLOSED_WORDS = (
    "Everything on file for {what} was already answered or silenced, so there is nothing new "
    "to raise about it."
)


# =============================================================================
# Shapes
# =============================================================================


def source(state: str, items: list[dict] | None = None, *, says: str, reason: str | None = None,
           note: str | None = None, left_out: int = 0) -> dict:
    return {
        "state": state,
        "reason": reason,
        "says": says,
        "note": note,
        "left_out": left_out,
        "items": items or [],
    }


def unreadable(name: str, exc: BaseException) -> dict:
    """The words for a source whose read failed. The exception's class only, never its message:
    a database error can quote the row it choked on."""
    reason = (
        f"The engine could not read {SOURCE_WORDS[name]} just now ({type(exc).__name__}). "
        f"This is a failed read, not an empty one: say it could not be checked, never that "
        f"nothing is on."
    )
    return source(STATE_UNREADABLE, says=reason, reason=reason)


def offset_words(moment: dt.datetime) -> str:
    """`-05:00`: the UTC offset at `moment`, the only shape the zone travels in."""
    minutes = int(moment.utcoffset().total_seconds() // 60)
    sign = "-" if minutes < 0 else "+"
    hours, mins = divmod(abs(minutes), 60)
    return f"{sign}{hours:02d}:{mins:02d}"


def zone_for(household) -> tuple[ZoneInfo, str | None]:
    zone = zone_named(getattr(household, "timezone", None))
    if zone is None:
        return ZoneInfo("UTC"), (
            "The household has no timezone set, so every time here is UTC and today is the UTC "
            "date."
        )
    return zone, None


def _local(moment: dt.datetime, zone) -> str:
    return moment.astimezone(zone).isoformat()


def _when(moment: dt.datetime, zone) -> str:
    local = moment.astimezone(zone)
    return f"{day_words(local.date())}, {clock_words(local)}"


# =============================================================================
# Events (one expansion: `engine_mcp.cards.occurrences`, which `list_events` also uses)
# =============================================================================


def _occurrences(request, kinds, window_start, window_end, zone, *, open_only: bool) -> list:
    """(row, start, end, all_day, local_date) for every occurrence of the member's visible rows
    of `kinds` starting in the window, repeating series expanded and skipped dates removed."""
    from engine_mcp.cards import occurrences
    from engine_mcp.tools import _event_skips
    from legacy.models.dates import Event

    live = visible(Event, request).filter(deleted_at__isnull=True, kind__in=kinds)
    if open_only:
        live = live.filter(done=False)
    pad = dt.timedelta(days=1)
    one_off = live.filter(
        repeat_kind__isnull=True,
        starts_at__gte=window_start - pad,
        starts_at__lte=window_end + pad,
    )
    repeating = live.filter(repeat_kind__isnull=False, starts_at__lte=window_end)
    rows = {row.pk: row for row in [*one_off, *repeating]}
    skips = _event_skips([pk for pk, row in rows.items() if row.repeat_kind])
    out = []
    for occ in occurrences(list(rows.values()), skips, window_start, window_end, zone):
        row = rows[_uuid(occ["event_id"])]
        start = dt.datetime.fromisoformat(occ["starts_at"]) if occ["starts_at"] else None
        end = dt.datetime.fromisoformat(occ["ends_at"]) if occ["ends_at"] else None
        out.append((row, start, end, occ["all_day"], dt.date.fromisoformat(occ["date"])))
    return out


def _uuid(value):
    import uuid

    return uuid.UUID(str(value))


def _topic(prefix: str, row, day: dt.date) -> str:
    """`event:<id>` for a one-off row; `event:<id>:<YYYY-MM-DD>` for one occurrence of a
    repeating series, so answering Tuesday's class never silences Thursday's."""
    if row.repeat_kind:
        return f"{prefix}:{row.id}:{day.isoformat()}"
    return f"{prefix}:{row.id}"


def _where(row) -> str | None:
    return (row.location or "").strip() or None


def ended_events(request, now: dt.datetime, zone) -> dict:
    since = now - dt.timedelta(hours=ENDED_HOURS)
    today = now.astimezone(zone).date()
    yesterday = today - dt.timedelta(days=1)
    items = []
    window_start = since - dt.timedelta(days=LONGEST_EVENT_DAYS)
    for row, start, end, all_day, day in _occurrences(
        request, EVENT_KINDS, window_start, now, zone, open_only=False
    ):
        title = (row.title or "").strip() or "Untitled"
        if all_day:
            if day != yesterday:
                continue
            ended_at = dt.datetime.combine(today, dt.time.min, tzinfo=zone)
            says = f"{title} (all day yesterday, {day_words(day)})."
        elif end is not None:
            if not since < end <= now:
                continue
            ended_at = end
            says = f"{title} ended {_when(end, zone)}."
        else:
            # No end on file: counted once it started, and said as a start, never an end.
            if not since < start <= now:
                continue
            ended_at = None
            says = f"{title} started {_when(start, zone)} (no end time on file)."
        where = _where(row)
        if where:
            says = f"{says[:-1]} at {where}."
        items.append(
            {
                "id": str(row.id),
                "topic": _topic("event", row, day),
                "title": title,
                "all_day": all_day,
                "date": day.isoformat(),
                "starts_at": _local(start, zone) if start else None,
                "ended_at": _local(ended_at, zone) if ended_at else None,
                "where": where,
                "says": says,
            }
        )
    items.sort(key=lambda i: i["ended_at"] or i["starts_at"] or i["date"])
    return _finish("ended_events", items[-ITEM_LIMIT:])


def today_events(request, now: dt.datetime, zone) -> dict:
    today = now.astimezone(zone).date()
    day_end = dt.datetime.combine(today, dt.time.max, tzinfo=zone)
    items = []
    for row, start, end, all_day, day in _occurrences(
        request, TODAY_KINDS, now, day_end, zone, open_only=True
    ):
        title = (row.title or "").strip() or "Untitled"
        if all_day:
            if day != today:
                continue
            says = f"{title} (all day today)."
        else:
            if start is None or not now < start <= day_end:
                continue
            says = f"{title} at {clock_words(start.astimezone(zone))}"
            says += f" until {clock_words(end.astimezone(zone))}." if end else "."
        where = _where(row)
        if where:
            says = f"{says[:-1]}, at {where}."
        items.append(
            {
                "id": str(row.id),
                "topic": _topic("event", row, day),
                "title": title,
                "kind": row.kind,
                "all_day": all_day,
                "date": day.isoformat(),
                "starts_at": _local(start, zone) if start else None,
                "ends_at": _local(end, zone) if end else None,
                "where": where,
                "says": says,
            }
        )
    items.sort(key=lambda i: (not i["all_day"], i["starts_at"] or ""))
    return _finish("today_events", items[:ITEM_LIMIT])


def _is_canvas(meta: dict) -> bool:
    return "canvas_assignment_id" in meta or "parent_canvas_assignment_id" in meta


def due_soon(request, now: dt.datetime, zone) -> dict:
    """Open tasks (to-dos and Canvas work, both `kind = 'task'`) due in the next 24 hours. A task
    Canvas says is submitted is left out; one ticked done never reaches here."""
    until = now + dt.timedelta(hours=DUE_HOURS)
    items = []
    any_canvas = False
    for row, start, _end, all_day, day in _occurrences(
        request, DUE_KINDS, now, until, zone, open_only=True
    ):
        meta = row.structured_meta or {}
        canvas = _is_canvas(meta)
        any_canvas = any_canvas or canvas
        if canvas and meta.get("canvas_submitted") is True:
            continue
        if not all_day and (start is None or not now < start <= until):
            continue
        title = (row.title or "").strip() or "Untitled"
        due_words = f"by end of {day_words(day)}" if all_day else _when(start, zone)
        says = f"{title} is due {due_words}."
        if canvas:
            submitted = meta.get("canvas_submitted")
            says += (
                " Canvas says not submitted."
                if submitted is False
                else " Canvas has not said whether it is submitted."
            )
        items.append(
            {
                "id": str(row.id),
                "topic": _topic("due", row, day),
                "title": title,
                "canvas": canvas,
                "canvas_submitted": meta.get("canvas_submitted") if canvas else None,
                "all_day": all_day,
                "date": day.isoformat(),
                "due_at": _local(start, zone) if start and not all_day else None,
                "says": says,
            }
        )
    items.sort(key=lambda i: (i["due_at"] or i["date"]))
    note = None
    if any_canvas or _has_canvas_rows(request):
        note = canvas_freshness_note(household_of(request), now)
    return _finish("due_soon", items[:ITEM_LIMIT], note=note)


def _has_canvas_rows(request) -> bool:
    from django.db.models import Q

    from legacy.models.dates import Event

    return (
        visible(Event, request)
        .filter(
            Q(structured_meta__has_key="canvas_assignment_id")
            | Q(structured_meta__has_key="parent_canvas_assignment_id")
        )
        .exists()
    )


# =============================================================================
# Body (`body_coach.workouts`: the same reads `get_todays_workout` makes, without the drafting
# `today_body` does as a side effect, so this pack never writes)
# =============================================================================


def body(request, now: dt.datetime, zone) -> dict:
    from body_coach import programme as rules
    from body_coach import workouts
    from body_coach.models import SESSION_FINISHED, WorkoutSession
    from household.tenancy import scoped
    from legacy.models.body import BodyweightLog

    today = now.astimezone(zone).date()
    topic = f"body:{today.isoformat()}"
    items = []

    programme = workouts.active_programme(request)
    running = workouts.in_progress(request)
    finished_today = (
        scoped(WorkoutSession, request)
        .filter(status=SESSION_FINISHED, session_date=today)
        .order_by("-finished_at")
        .first()
    )
    if programme is not None or running is not None or finished_today is not None:
        week = workouts.week_dict(request, programme, today)
        tail = f" This week: {week['done']} of {week['planned']} sessions done."
        if running is not None:
            d = workouts.session_dict(running)
            status, label = workouts.TODAY_IN_PROGRESS, d["day_label"]
            says = f"{label} is in progress: {d['sets_done']} of {d['set_count']} sets ticked."
        elif finished_today is not None:
            d = workouts.session_dict(finished_today)
            status, label = workouts.TODAY_DONE, d["day_label"]
            says = f"Today's workout ({label}) is logged."
        elif week["done"] < week["planned"]:
            plan = workouts.next_plan(request, programme)
            status = workouts.TODAY_NOT_STARTED
            label = rules.day_label(workouts.state_of(programme).template, plan.day_key)
            says = f"A session is due this week and not started today; the next one is {label}."
        else:
            status, label = workouts.TODAY_REST, None
            says = "This week's sessions are all done; nothing is due today."
        items.append(
            {
                "kind": "workout",
                "topic": topic,
                "status": status,
                "done_today": status == workouts.TODAY_DONE,
                "session": label,
                "week_done": week["done"],
                "week_planned": week["planned"],
                "says": says + tail,
            }
        )

    last = (
        scoped(BodyweightLog, request)
        .filter(deleted_at__isnull=True)
        .order_by("-logged_at")
        .values_list("logged_at", flat=True)
        .first()
    )
    if last is not None:
        day = last.astimezone(zone).date()
        items.append(
            {
                "kind": "weigh_in",
                "topic": topic,
                "last_on": day.isoformat(),
                "last_at": _local(last, zone),
                "today": day == today,
                "says": (
                    "Weighed in today."
                    if day == today
                    else f"Last weigh-in on file: {day_words(day)}. None yet today."
                ),
            }
        )
    return _finish("body", items)


# =============================================================================
# Money (`finance.budgets`, the figures the Money tab shows)
# =============================================================================


def money_source(request, now: dt.datetime, zone) -> dict:
    from api.spend import Month
    from finance.budgets import budgets

    today = now.astimezone(zone).date()
    month = Month.of(today)
    body_ = budgets(request, household_of(request), month, today)
    items = []
    for line in body_["categories"]:
        cap = line["cap_cents"]
        if not cap:
            continue
        spent = line["spent_cents"]
        says = (
            f"{line['category']}: {money(spent)} of {money(cap)} spent by day "
            f"{body_['days_elapsed']} of {body_['days_in_month']}"
        )
        if line["pace_cents"] is not None:
            says += f"; an even pace would be {money(line['pace_cents'])} by now"
        says += "."
        if line["status"] == "over":
            says += " Over the cap."
        elif line["status"] == "near":
            says += " Past 80% of the cap."
        if line["projection_cents"] is not None:
            says += (
                f" On this pace about {money(line['projection_cents'])} by month end "
                f"(an estimate)."
            )
        if line["pending_count"]:
            says += f" {money(line['pending_cents'])} of it is still pending at the bank."
        if line["unverified"]:
            says += UNVERIFIED_SUFFIX
        items.append(
            {
                "topic": f"money:{month}:{line['category']}",
                "category": line["category"],
                "month": str(month),
                "spent_cents": int(spent),
                "cap_cents": int(cap),
                "pace_cents": None if line["pace_cents"] is None else int(line["pace_cents"]),
                "projection_cents": (
                    None if line["projection_cents"] is None else int(line["projection_cents"])
                ),
                "projection_is_estimate": True,
                "status": line["status"],
                "pending_cents": int(line["pending_cents"]),
                "unverified": bool(line["unverified"]),
                "days_elapsed": body_["days_elapsed"],
                "days_in_month": body_["days_in_month"],
                "says": says,
            }
        )
    note = None
    if items and body_["days_elapsed"] < body_["projection_from_day"]:
        note = (
            f"Too early in the month to project (before day {body_['projection_from_day']}); "
            f"no month-end figure is given."
        )
    return _finish("money", items, note=note)


# =============================================================================
# The pack
# =============================================================================


def _finish(name: str, items: list[dict], note: str | None = None) -> dict:
    if not items:
        return source(STATE_EMPTY, says=EMPTY_WORDS[name], note=note)
    return source(STATE_FACTS, items, says=f"{len(items)} on file.", note=note)


READERS = {
    "ended_events": ended_events,
    "today_events": today_events,
    "due_soon": due_soon,
    "body": body,
    "money": money_source,
}


def topics(request, now: dt.datetime) -> tuple[set[str], set[str]]:
    """(raised, closed) topic keys the member recorded within the window."""
    from greeting.models import CLOSED_STATES, STATE_RAISED, GreetingTopic

    rows = (
        visible(GreetingTopic, request)
        .filter(at__gte=now - dt.timedelta(days=TOPIC_WINDOW_DAYS))
        .order_by("-at")
        .values_list("topic_key", "state")[:TOPIC_LIMIT]
    )
    raised, closed = set(), set()
    for key, state in rows:
        if state in CLOSED_STATES:
            closed.add(key)
        elif state == STATE_RAISED:
            raised.add(key)
    return raised, closed


def _apply_topics(name: str, block: dict, raised: set[str], closed: set[str]) -> dict:
    if block["state"] != STATE_FACTS:
        return block
    kept = []
    left_out = 0
    for item in block["items"]:
        if item["topic"] in closed:
            left_out += 1
            continue
        kept.append({**item, "raised": item["topic"] in raised})
    if not kept:
        return source(
            STATE_EMPTY,
            says=ALL_CLOSED_WORDS.format(what=SOURCE_WORDS[name]),
            note=block["note"],
            left_out=left_out,
        )
    return {**block, "items": kept, "left_out": left_out, "says": f"{len(kept)} on file."}


def _now() -> dt.datetime:
    return dt.datetime.now(dt.UTC)


def pack(request, now: dt.datetime | None = None) -> dict:
    """The whole pack for the signed-in member."""
    now = now or _now()
    zone, zone_note = zone_for(household_of(request))
    local_now = now.astimezone(zone)

    sources = {}
    for name in SOURCE_NAMES:
        try:
            # A savepoint each, so one source's failed query never poisons the next one's read.
            with transaction.atomic():
                sources[name] = READERS[name](request, now, zone)
        except Exception as exc:  # noqa: BLE001 - every failure is reported, in words
            sources[name] = unreadable(name, exc)

    topics_block = {"state": TOPICS_READ, "reason": None}
    raised: set[str] = set()
    closed: set[str] = set()
    try:
        with transaction.atomic():
            raised, closed = topics(request, now)
    except Exception as exc:  # noqa: BLE001
        topics_block = {
            "state": STATE_UNREADABLE,
            "reason": (
                f"The engine could not read which topics were already raised or answered "
                f"({type(exc).__name__}). Nothing was left out on their account."
            ),
        }
    for name in SOURCE_NAMES:
        sources[name] = _apply_topics(name, sources[name], raised, closed)

    return {
        "generated_at": local_now.isoformat(),
        "today": local_now.date().isoformat(),
        "utc_offset": offset_words(local_now),
        "zone_note": zone_note,
        "sources": sources,
        "said": sorted(raised),
        "closed": sorted(closed),
        "topics": topics_block,
    }


def words(body: dict) -> str:
    """The pack in sentences, for the engine tool's text: each source's state and its lines."""
    from engine_mcp.tools import _json

    lines = [
        f"Greeting facts for {body['today']} (UTC offset {body['utc_offset']}). These are the "
        f"ONLY things a greeting or check-in may mention. A source marked empty has nothing on "
        f"file; one marked unreadable could not be checked, and must be said as unchecked, never "
        f"as nothing on. Topics in `said` were already raised: do not ask about them again."
    ]
    if body["zone_note"]:
        lines.append(body["zone_note"])
    for name in SOURCE_NAMES:
        block = body["sources"][name]
        head = f"- {name} ({block['state']}): {block['says']}"
        if block["note"]:
            head += f" {block['note']}"
        lines.append(head)
        for item in block["items"]:
            raised = " (already raised)" if item.get("raised") else ""
            lines.append(f"  - {item['says']}{raised}")
    lines.append(_json(body))
    return "\n".join(lines)
