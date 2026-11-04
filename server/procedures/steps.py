"""The named Python functions a `compute` or `check` step may call (ruling 01).

Pure and deterministic: sums, date maths, sentence composition the way `brief/compose.py` does
it. No I/O, no model, no network: a function reads only the values earlier steps produced, which
came through the fenced `tool` steps (so a recruiter's visit and the household's tenancy hold
for them too). A `check` step names one of these too; its function returns True to pass, and a
failing check ends the run with the step's `fail_sentence`.

A definition naming a function that is not here is refused at load, so a typo never reaches the
runner.

The built-ins' functions (procedures ticket 15) follow CLAUDE.md sections 4 and 7: an empty
result is said as "on file", never as "none exists"; a figure that is an estimate is labelled
one; nothing here says UNVERIFIED (the tools do, where their rows are; a bank-feed row is fact).
"""

from __future__ import annotations

import calendar
import datetime as dt
from collections.abc import Callable

FUNCTIONS: dict[str, Callable] = {}


def step_function(name: str):
    """Register `fn` under `name`, refusing a second registration of the same name."""

    def register(fn: Callable) -> Callable:
        if name in FUNCTIONS:
            raise ValueError(f"A step function named {name!r} is already registered.")
        FUNCTIONS[name] = fn
        return fn

    return register


# =============================================================================
# Words
# =============================================================================


def _long_date(day: dt.date) -> str:
    """ "Tue Nov 3, 2026", the engine tools' own date words (`engine_mcp.tools._long_date`)."""
    return f"{day:%a} {day:%b} {day.day}, {day.year}"


def _clock(moment: dt.datetime) -> str:
    hour = moment.hour % 12 or 12
    return f"{hour}:{moment.minute:02d} {'AM' if moment.hour < 12 else 'PM'}"


def _and(items: list[str]) -> str:
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


def _instant(raw) -> dt.datetime | None:
    if not isinstance(raw, str) or not raw:
        return None
    try:
        parsed = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=dt.UTC)


def _local(moment: dt.datetime, offset_minutes) -> dt.datetime:
    zone = dt.timezone(dt.timedelta(minutes=int(offset_minutes or 0)))
    return moment.astimezone(zone)


# =============================================================================
# car_check
# =============================================================================


@step_function("car_lead")
def car_lead(vehicles=None) -> str:
    """Names the car (or the cars) `next_service` read, and says whether anything is past due
    by the records on file. `vehicles` is `next_service`'s structured `vehicles`."""
    vehicles = vehicles or []
    if not vehicles:
        return "No vehicle is on file."
    names = [v.get("name") or "a vehicle with no name" for v in vehicles]
    lead = (
        f"The car: {names[0]}."
        if len(names) == 1
        else (f"{len(names)} vehicles are on file: {_and(names)}.")
    )
    late = []
    for v in vehicles:
        for row in v.get("overdue") or []:
            if row.get("due_on"):
                when = f"due {_long_date(dt.date.fromisoformat(row['due_on']))}"
            else:
                when = f"due at {int(row.get('due_at_miles') or 0):,} mi"
            late.append(f"{row.get('service_name')} on {v.get('name')} ({when})")
    if late:
        return f"{lead} Past due by the records on file: {'; '.join(late)}."
    return f"{lead} Nothing is past due by the records on file."


@step_function("one_car")
def one_car(vehicles=None) -> bool:
    """True when `next_service` read at most one car, so recalls know which car to look up:
    either one is on file or the caller named one (`next_service` then reads only that car).
    False with several cars and none named: `vehicle_recalls` takes one car, and the runner has
    no fan-out (Kevin, 2026-11-03, ticket 15's open item)."""
    return len(vehicles or []) <= 1


def _freeze_words(frame) -> str:
    # Null and an empty object are different facts (`api/fleet.py` CodeEventSerializer).
    if frame is None:
        return "no freeze frame came back with it"
    if isinstance(frame, dict) and not frame:
        return "its freeze frame came back empty"
    return "a freeze frame was stored with it"


def _since_clear(vehicle_id, events, clears):
    """(codes, latest read with codes, last clear instant) for one vehicle, or None when no
    code read is on file for it. Codes are those logged after the last clear on file (every
    read, with no clear), sorted. The one computation `trouble_codes`' sentence and the car
    card (`engine_mcp.cards.car_card`) both use, so they cannot disagree."""
    events = [e for e in (events or []) if not e.get("deleted_at")]
    clears = [c for c in (clears or []) if not c.get("deleted_at")]
    mine = sorted(
        (
            e
            for e in events
            if str(e.get("vehicle_id")) == str(vehicle_id) and _instant(e.get("occurred_at"))
        ),
        key=lambda e: _instant(e["occurred_at"]),
    )
    if not mine:
        return None
    cleared = max(
        (
            _instant(c.get("occurred_at"))
            for c in clears
            if str(c.get("vehicle_id")) == str(vehicle_id) and _instant(c.get("occurred_at"))
        ),
        default=None,
    )
    if cleared is not None:
        mine = [e for e in mine if _instant(e["occurred_at"]) > cleared]
    codes = sorted({str(c) for e in mine for c in (e.get("codes") or []) if c})
    with_codes = [e for e in mine if e.get("codes")]
    return codes, (with_codes[-1] if with_codes else None), cleared


def codes_since_clear(vehicle_id, events, clears) -> list[str]:
    """The trouble codes logged for one vehicle since the last clear on file, sorted."""
    found = _since_clear(vehicle_id, events, clears)
    return found[0] if found else []


@step_function("trouble_codes")
def trouble_codes(vehicles=None, events=None, clears=None, utc_offset_minutes=0, limit=None):
    """Per vehicle, the trouble codes the OBD dongle has logged since the last clear on file (or
    on record, with no clear), the date of the latest read, and whether a freeze frame was
    stored with it. `events` and `clears` are `read_records` rows of `code_events` and
    `code_clear_events`; `vehicles` is `next_service`'s. Codes are what the dongle read when it
    last connected, never a reading of the car now."""
    vehicles = vehicles or []
    events = [e for e in (events or []) if not e.get("deleted_at")]
    clears = [c for c in (clears or []) if not c.get("deleted_at")]
    lines = []
    for v in vehicles:
        name = v.get("name")
        found = _since_clear(v.get("vehicle_id"), events, clears)
        if found is None:
            lines.append(f"{name}: no trouble-code reads are on file.")
            continue
        codes, latest, cleared = found
        since = (
            f"since the last clear ({_long_date(_local(cleared, utc_offset_minutes).date())})"
            if cleared is not None
            else "on record"
        )
        if not codes:
            lines.append(f"{name}: no trouble codes {since}.")
            continue
        read_on = _long_date(_local(_instant(latest["occurred_at"]), utc_offset_minutes).date())
        lines.append(
            f"{name}: trouble codes {since}: {', '.join(codes)}. The latest read with codes was "
            f"{read_on}, and {_freeze_words(latest.get('freeze_frame'))}."
        )
    if not lines:
        return ""
    text = (
        "Trouble codes, as the OBD dongle logged them when it last connected (not read from the "
        "car now): " + " ".join(lines)
    )
    if limit and len(events) >= int(limit):
        text += f" Only the newest {int(limit)} code reads on file were looked at."
    return text


# =============================================================================
# money_check
# =============================================================================


@step_function("month_window")
def month_window(month=None) -> dict:
    """`{"from", "to"}`, the first and last day of `month` (YYYY-MM, as `monthly_spend` names
    the month it read), so the grocery figure covers the same month as the spend figure."""
    year, number = (int(p) for p in str(month).split("-"))
    last = calendar.monthrange(year, number)[1]
    return {
        "from": dt.date(year, number, 1).isoformat(),
        "to": dt.date(year, number, last).isoformat(),
    }


# =============================================================================
# morning_briefing
# =============================================================================


def _event_words(e: dict, offset) -> str:
    title = (e.get("title") or "an untitled event").strip()
    start = _instant(e.get("starts_at"))
    if e.get("all_day") or start is None:
        return f"{title} (all day)" if e.get("all_day") else title
    return f"{title} at {_clock(_local(start, offset))}"


@step_function("day_events")
def day_events(events=None, utc_offset_minutes=0) -> str:
    """Today's events from `list_events`'s structured sections, in the household's clock.
    One-off events in the window are listed with their times; repeating events are named and
    NOT worked out for today (the tool does not expand them, and nothing here does either);
    suggestions are said to be suggestions, never plans."""
    events = events or {}
    one_off = [e for e in events.get("in_window") or [] if e.get("kind") != "task"]
    tasks = [e for e in events.get("in_window") or [] if e.get("kind") == "task"]
    repeating = events.get("repeating") or []
    suggestions = events.get("suggestions") or []
    undated = events.get("undated") or []
    parts = []
    if one_off:
        parts.append(
            "Today on file: "
            + "; ".join(_event_words(e, utc_offset_minutes) for e in one_off)
            + "."
        )
    else:
        parts.append("No one-off events are on file for today.")
    if tasks:
        parts.append(
            "Tasks dated today: "
            + "; ".join(_event_words(e, utc_offset_minutes) for e in tasks)
            + "."
        )
    if repeating:
        names = [(e.get("title") or "an untitled event").strip() for e in repeating]
        parts.append(
            f"{len(names)} repeating event(s) are on file ({'; '.join(names)}); whether each "
            "falls today is not worked out here, so check the calendar before saying one is on."
        )
    if undated:
        parts.append(f"{len(undated)} task(s) with no date are open.")
    if suggestions:
        parts.append(
            f"{len(suggestions)} suggestion(s) for today are things the household could do, "
            "not plans: "
            + "; ".join((s.get("title") or "untitled").strip() for s in suggestions)
            + "."
        )
    return " ".join(parts)


@step_function("checklist_day")
def checklist_day(checklists=None, day=None) -> str:
    """Per checklist, how many items are ticked on `day` and which are not, from
    `list_checklists` with that `date`. A tick is evidence of a tap, nothing more, so it is said
    as "ticked", never "done"."""
    checklists = checklists or []
    if not checklists:
        return "No checklists are on file."
    when = "today" if day is None else _long_date(dt.date.fromisoformat(str(day)))
    lines = []
    for c in checklists:
        items = c.get("items") or []
        name = c.get("name") or "a checklist"
        if not items:
            lines.append(f"{name}: no items.")
            continue
        open_ = [i.get("text", "") for i in items if not i.get("ticked_on_date")]
        ticked = len(items) - len(open_)
        if not open_:
            lines.append(f"{name}: all {len(items)} ticked {when}.")
        else:
            lines.append(
                f"{name}: {ticked} of {len(items)} ticked {when}; not ticked: {', '.join(open_)}."
            )
    return "Checklists: " + " ".join(lines)
