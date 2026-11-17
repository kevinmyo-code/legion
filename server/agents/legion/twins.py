"""Engine twins of the phone's two day reads (engine-brain ticket 02): `read_calendar` and
`read_list`.

They are `EngineTool`s but **kept out of `engine_mcp.tools.TOOLS`** on purpose: the registry feeds
`/mcp`, the web assistant's Live token and the recruiter classification, and this stage changes
nothing for any of them (dark). The agent gateway runs them through `run_engine_tool`, the same
door `assistant.surface.RECRUITER_ONLY_TOOLS` uses, so they get the write fence, the schema check
and the read-through hook like every registry tool.

**`read_calendar`** ports `calendar/DueList.kt` and `DueReader.kt` (2026-11-15 rules, verbatim
wording): everything DUE in the window from both places a due date lives on the engine (calendar
tasks, `kind = task`, and dated reminders, `kind = reminder`), overdue ones from before the
window added whenever the window reaches today (tasks only within `TASK_OVERDUE_LOOKBACK_DAYS`),
one of two same-titled rows on the same day dropped, done ones only counted, and `says`: the
summary in the phone's own sentences. Then the window's rows, open tasks first, every time on
the household's clock. A Canvas task is done when ticked or when its `structured_meta` says
submitted or excused (`DueReader.handedIn`).

**`read_list`** ports `LiveToolbox.readList`: the member's reminders (`kind = reminder`), each
with its due day in words or "no date set", open first; plus the open lines of the household's
"Todo" checklist under `todo_list` (`ListRouting`: "what's on my to-do list" is ambiguous, both
ride along).

Every read goes through `visible()`: shared rows and the member's own private ones only.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, replace
from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from engine_mcp import tools as reg
from engine_mcp.tools import EngineTool, ok, refused
from household.tenancy import visible

TASK_OVERDUE_LOOKBACK_DAYS = 30
CALENDAR = "calendar"
TODO = "todo"
TODO_KEY = "todo_list"

_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)


@dataclass(frozen=True)
class DueInput:
    title: str
    at: datetime
    all_day: bool
    date: date
    done: bool
    source: str
    recurring: bool
    when_label: str


@dataclass(frozen=True)
class DueEntry:
    item: DueInput
    overdue: bool


@dataclass(frozen=True)
class DueSummary:
    from_day: date
    to_day: date
    open: list
    done_count: int
    unreadable: list
    today: date | None


def normalise(title: str) -> str:
    return _NON_WORD.sub(" ", title.lower()).replace("_", " ").strip()


def is_overdue(item: DueInput, today: date, now: datetime) -> bool:
    if item.done or item.recurring:
        return False
    if item.all_day:
        return item.date < today
    return item.at < now


def dedupe(inputs: list[DueInput]) -> list[DueInput]:
    out: dict[tuple[str, date], DueInput] = {}
    for item in inputs:
        key = (normalise(item.title), item.date)
        seen = out.get(key)
        if seen is None:
            out[key] = item
        elif seen.source == CALENDAR or item.source != CALENDAR:
            out[key] = replace(seen, done=seen.done or item.done)
        else:
            out[key] = replace(item, done=item.done or seen.done)
    return list(out.values())


def summarize(inputs, from_day, to_day, today, now, unreadable=()) -> DueSummary:
    task_floor = today - timedelta(days=TASK_OVERDUE_LOOKBACK_DAYS)
    kept: list[DueEntry] = []
    for item in dedupe(inputs):
        overdue = is_overdue(item, today, now)
        in_window = from_day <= item.date <= to_day
        before = (
            item.date < from_day
            and overdue
            and not to_day < today
            and (item.source == TODO or not item.date < task_floor)
        )
        if in_window or before:
            kept.append(DueEntry(item, overdue))
    open_ = sorted((e for e in kept if not e.item.done), key=lambda e: (not e.overdue, e.item.at))
    done = sum(1 for e in kept if e.item.done and from_day <= e.item.date <= to_day)
    return DueSummary(from_day, to_day, open_, done, list(unreadable), today)


def due_when(item: DueInput, today: date | None) -> str:
    """ "today, Sun Nov 15, 2026 23:59": the weekday always, and the word for the day when it has
    one."""
    word = {0: "today, ", 1: "tomorrow, ", -1: "yesterday, "}.get(
        (item.date - today).days if today else None, ""
    )
    return f"{word}{item.date:%a} {item.when_label}"


def _line(e: DueEntry, today) -> str:
    return f"{e.item.title} (due {due_when(e.item, today)}, {e.item.source})"


def open_says(entries, overdue: bool, today) -> str:
    head = "OVERDUE (past due, not done)" if overdue else "open and due, not done, not overdue yet"
    return f"{len(entries)} {head}: " + "; ".join(_line(e, today) for e in entries) + "."


def says(s: DueSummary) -> str:
    parts: list[str] = []
    overdue = [e for e in s.open if e.overdue]
    not_yet = [e for e in s.open if not e.overdue]
    if overdue:
        parts.append(open_says(overdue, True, s.today))
    if not_yet:
        parts.append(open_says(not_yet, False, s.today))
    elif not overdue:
        parts.append("Nothing open is due in this window.")
    else:
        parts.append("Nothing else open is due in this window.")
    looked = s.today is not None and not s.to_day < s.today and not s.unreadable
    if not overdue and looked:
        parts.append("Nothing is overdue.")
    if s.done_count > 0:
        parts.append(f"{s.done_count} already done.")
    if s.unreadable:
        parts.append(
            f"Could not read {' or '.join(s.unreadable)}, so this may be missing items: say so."
        )
    return " ".join(parts)


def handed_in(meta) -> bool:
    if not isinstance(meta, dict):
        return False
    submitted = meta.get("submitted_at")
    return bool(
        meta.get("canvas_submitted")
        or (isinstance(submitted, str) and submitted.strip())
        or meta.get("excused")
    )


def _label(at: datetime, all_day: bool, zone) -> str:
    if all_day:
        return f"{at.astimezone(UTC):%b} {at.astimezone(UTC).day}, {at.astimezone(UTC).year}"
    local = at.astimezone(zone)
    return f"{local:%b} {local.day}, {local.year} {local:%H:%M}"


def _row_date(at: datetime, all_day: bool, zone) -> date:
    # A calendar row's all-day value is UTC midnight of its date; anything else is an instant.
    return at.astimezone(UTC).date() if all_day else at.astimezone(zone).date()


def _input(event, zone) -> DueInput | None:
    if event.starts_at is None or event.deleted_at is not None:
        return None
    if event.kind not in ("task", "reminder"):
        return None
    day = _row_date(event.starts_at, event.all_day, zone)
    end_of_day = datetime.combine(day + timedelta(days=1), datetime.min.time(), zone) - timedelta(
        milliseconds=1
    )
    source = CALENDAR if event.kind == "task" else TODO
    return DueInput(
        title=event.title,
        at=end_of_day if event.all_day else event.starts_at,
        all_day=event.all_day,
        date=day,
        done=bool(event.done) or (source == CALENDAR and handed_in(event.structured_meta)),
        source=source,
        recurring=source == TODO and event.repeat_kind is not None,
        when_label=_label(event.starts_at, event.all_day, zone),
    )


def _zone_and_now(request):
    zone = reg._household_zone(request) or ZoneInfo("UTC")
    return zone, reg._now()


def _day(raw, zone, name) -> date:
    if not isinstance(raw, str) or not raw.strip():
        raise reg.BadArgument(f"`{name}` is required, as YYYY-MM-DD")
    day = reg._named_day(raw, zone)
    if day is None:
        raise reg.BadArgument(f"`{name}` must be a day, YYYY-MM-DD (or today / tomorrow)")
    return day


def _read_calendar(request, args) -> object:
    from legacy.models.dates import Event

    zone, now = _zone_and_now(request)
    try:
        from_day = _day(args.get("from"), zone, "from")
        to_day = _day(args.get("to"), zone, "to")
    except reg.BadArgument as exc:
        return refused(f"Nothing was read. {exc}.")
    if to_day < from_day:
        return refused("Nothing was read. `to` must be on or after `from`.")
    today = now.astimezone(zone).date()
    earliest = min(from_day, today - timedelta(days=TASK_OVERDUE_LOOKBACK_DAYS))
    lo = datetime.combine(earliest - timedelta(days=1), datetime.min.time(), UTC)
    hi = datetime.combine(to_day + timedelta(days=2), datetime.min.time(), UTC)
    rows = list(
        visible(Event, request)
        .filter(deleted_at__isnull=True, starts_at__gte=lo, starts_at__lt=hi)
        .exclude(kind="suggestion")
        .order_by("starts_at")[: reg.READ_LIMIT_MAX * 2]
    )
    # Dated reminders before the look back are still overdue (a to-do has no look back bound).
    old_todos = list(
        visible(Event, request).filter(
            deleted_at__isnull=True, kind="reminder", done=False, starts_at__lt=lo
        )[: reg.READ_LIMIT_MAX]
    )
    inputs = [i for i in (_input(e, zone) for e in [*rows, *old_todos]) if i is not None]
    summary = summarize(inputs, from_day, to_day, today, now)
    window = [
        e
        for e in rows
        if from_day <= _row_date(e.starts_at, e.all_day, zone) <= to_day and e.kind != "reminder"
    ]
    window.sort(key=lambda e: (not (e.kind == "task" and not e.done), e.starts_at))
    out_rows = []
    for e in window:
        row = {
            "title": e.title,
            "start": _label(e.starts_at, e.all_day, zone),
            "end": _label(e.ends_at or e.starts_at, e.all_day, zone),
            "all_day": e.all_day,
            "kind": e.kind,
        }
        if e.kind == "task":
            row["done"] = bool(e.done) or handed_in(e.structured_meta)
        if e.location:
            row["location"] = e.location
        out_rows.append(row)
    due = {
        "open": len(summary.open),
        "overdue": sum(1 for e in summary.open if e.overdue),
        "done": summary.done_count,
        "today": f"{today:%A} {today.isoformat()}",
        "says": says(summary),
        "open_items": [
            {
                "title": e.item.title,
                "due": due_when(e.item, today),
                "source": e.item.source,
                "overdue": e.overdue,
            }
            for e in summary.open
        ],
    }

    def label(d: date) -> str:
        word = {0: "today", 1: "tomorrow", -1: "yesterday"}.get((d - today).days)
        return f"{word} ({d:%a} {d.isoformat()})" if word else f"{d:%a} {d.isoformat()}"

    window_words = label(from_day) + ("" if from_day == to_day else f" to {label(to_day)}")
    result = {
        "success": True,
        "window": window_words,
        "due": due,
        "events_count": sum(1 for e in window if e.kind == "event"),
        "count": len(out_rows),
        "events": out_rows,
    }
    if not out_rows:
        result["message"] = (
            "No calendar rows in this window. Answer what is due from `due`."
            if summary.open
            else "Nothing on the calendar in this window and nothing open is due."
        )
    return ok(json.dumps(result, ensure_ascii=False), structured=result)


def _read_list(request, args) -> object:
    from checklists.models import Checklist, ChecklistItem, ChecklistTick
    from legacy.models.dates import Event

    zone, now = _zone_and_now(request)
    today = now.astimezone(zone).date()
    reminders = list(
        visible(Event, request)
        .filter(deleted_at__isnull=True, kind="reminder")
        .order_by("done", "starts_at")[: reg.READ_LIMIT_MAX]
    )
    items = []
    for e in reminders:
        row = {"text": e.title, "done": bool(e.done)}
        if e.starts_at is None:
            row["due"] = "no date set"
        else:
            d = _row_date(e.starts_at, e.all_day, zone)
            row["due"] = due_when(
                DueInput(
                    e.title,
                    e.starts_at,
                    e.all_day,
                    d,
                    e.done,
                    TODO,
                    False,
                    _label(e.starts_at, e.all_day, zone),
                ),
                today,
            )
            if (
                not e.done
                and e.repeat_kind is None
                and ((e.all_day and d < today) or (not e.all_day and e.starts_at < now))
            ):
                row["overdue"] = True
        if e.trigger_place_label:
            row["place"] = e.trigger_place_label
        if e.repeat_kind:
            row["repeats"] = True
        items.append(row)
    out = {
        "success": True,
        "count": len(items),
        "open_count": sum(1 for i in items if not i["done"]),
        "items": items,
    }
    todo = (
        visible(Checklist, request)
        .filter(deleted_at__isnull=True, archived=False, name__iexact="todo")
        .first()
    )
    if todo is not None:
        ticked = set(
            ChecklistTick.objects.filter(item__checklist=todo, deleted_at__isnull=True).values_list(
                "item_id", flat=True
            )
        )
        lines = [
            i.text
            for i in ChecklistItem.objects.filter(checklist=todo, deleted_at__isnull=True).order_by(
                "sort_order", "created_at"
            )
            if i.id not in ticked
        ]
        if lines:
            out[TODO_KEY] = lines
    if not items and TODO_KEY not in out:
        out["message"] = "No reminders and no open to-dos. That is a real empty result."
    return ok(json.dumps(out, ensure_ascii=False), structured=out)


READ_CALENDAR = EngineTool(
    name="read_calendar",
    title="What is on the calendar and what is due",
    description=(
        "Read the calendar over a date range - LEGION's own calendar, the only one there is. "
        'Returns TWO kinds of row and the difference matters: a `kind` of "event" is '
        'something that HAPPENS at a time and simply passes, while "task" is something DUE '
        "that has to be done - an assignment, a quiz, a homework deadline - and carries `done`. "
        "**Coursework deadlines are tasks, so use this tool for 'what's due', 'what do I have "
        "due Sunday', 'what's due for school this week', not only for 'what's on my "
        "calendar'.** A `due` block comes first: everything due in the window from calendar "
        "tasks and dated reminders, overdue ones marked, done ones only counted; answer what is "
        "due from `due.says`. Undated reminders are `read_list`, checklists `list_checklists`. "
        "Read-only."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "from": {
                "type": "string",
                "description": "First day of the window, YYYY-MM-DD (or today / tomorrow).",
            },
            "to": {
                "type": "string",
                "description": "Last day, YYYY-MM-DD, inclusive. One day: the same as from.",
            },
        },
        "required": ["from", "to"],
        "additionalProperties": False,
    },
    handler=_read_calendar,
    writes=False,
)

READ_LIST = EngineTool(
    name="read_list",
    title="Reminders and the to-do list",
    description=(
        "Read back the open REMINDERS - each with the day or place that triggers it, soonest "
        "first - and the open lines of the Todo checklist under `todo_list`. Use for 'what "
        "reminders do I have', 'what's on my to-do list'. What's due is read_calendar."
    ),
    input_schema={"type": "object", "properties": {}, "additionalProperties": False},
    handler=_read_list,
    writes=False,
)

TWINS: dict[str, EngineTool] = {READ_CALENDAR.name: READ_CALENDAR, READ_LIST.name: READ_LIST}
