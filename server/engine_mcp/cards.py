"""Cards: what a tool result shows on a screen, built here from the tool's own rows.

Generative UI (Kevin, 2026-11-04: "go for it"). A tool result may carry `card`, a small JSON
object the web chat (members' and the recruiter demo's) and the phone draw beside the
transcript. The format is shared with the phone, so its field names are fixed:

- common: `card` ("bars" | "timeline" | "car" | "steps" | "checklist"), `title`, optional
  `scope`, `period`, `note` (plain words, e.g. "Includes $12.40 unverified").
- bars: `unit` ("usd", values in integer cents, or "" for counts), `points: [{label, value,
  flag?}]` (flag "unverified" or "estimate"), at most 8, and `more` (rows not shown).
- timeline: `date` (YYYY-MM-DD in the household's zone), `zone` ("UTC-06:00", never an IANA
  id), `items: [{start, end, all_day, title, kind}]`, `more`.
- car: `name`, `mileage`, `mileage_as_of`, `next_service`, `recalls: [{summary}]`,
  `codes: [{code, meaning}]`.
- steps: `name`, `items: [{label, status, ms}]`, `total_ms`.
- checklist: `list_name`, `items: [{text, done}]`, `more`.

Rules every builder keeps:

- **Built from rows, never from model text.** The model never sees a card: the tool door puts
  it in the HTTP response beside `response`, which is what goes to Gemini, unchanged.
- **Money is integer cents** (CLAUDE.md section 4), and what no gate checked says so in words
  (`flag`, `note`); an estimate says it is one.
- **Nothing unobserved is drawn as a finding** (section 7): a recall lookup that did not run is
  a note saying so, never an empty list that reads as "no recalls".
- **A card is a garnish.** `safe()` turns any builder error into no card, so a bug here can
  never turn a good read into a failed one.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import UTC, date, datetime, timedelta
from functools import cache
from pathlib import Path

log = logging.getLogger(__name__)

BARS_MAX = 8
TIMELINE_MAX = 12
CHECKLIST_MAX = 12
RECALLS_MAX = 5
CODES_MAX = 8

KIND_WORDS = {"event": "event", "reminder": "event", "task": "todo", "suggestion": "suggestion"}


def safe(builder, *args, **kwargs) -> dict | None:
    """`builder(*args, **kwargs)`, or None when it raises. Logged, never re-raised."""
    try:
        return builder(*args, **kwargs)
    except Exception:  # noqa: BLE001 - a card must never fail the tool it decorates
        log.exception("card builder %s failed; the result goes out without a card", builder)
        return None


def money_words(cents: int) -> str:
    """$1,234.56 from integer cents. Display words only; nothing adds these back up."""
    sign = "-" if cents < 0 else ""
    cents = abs(int(cents))
    return f"{sign}${cents // 100:,}.{cents % 100:02d}"


def zone_words(offset: timedelta | None) -> str:
    """"UTC-06:00" from a UTC offset. Never an IANA id (CLAUDE.md section 1)."""
    minutes = int((offset or timedelta()).total_seconds() // 60)
    sign = "+" if minutes >= 0 else "-"
    minutes = abs(minutes)
    return f"UTC{sign}{minutes // 60:02d}:{minutes % 60:02d}"


def humanize(identifier: str) -> str:
    """`car_check` -> "Car check". For step ids and procedure names, never tool names."""
    words = re.sub(r"[_\s]+", " ", str(identifier or "")).strip()
    return words[:1].upper() + words[1:] if words else "Step"


def _month_words(month: str) -> str:
    try:
        return datetime.strptime(month, "%Y-%m").strftime("%B %Y")
    except (TypeError, ValueError):
        return str(month or "")


# =============================================================================
# bars: monthly_spend
# =============================================================================


def spend_bars(body: dict, currency: str) -> dict | None:
    """Spending by category for one month, from `api.ledger_spend.spend_for`'s body. A
    category with any row no gate checked is flagged "unverified". USD only: the card's unit
    is "usd", so another currency gets no card rather than a mislabelled one."""
    if currency.upper() != "USD":
        return None
    spent = sorted(
        (c for c in body.get("categories") or [] if c.get("spend_cents")),
        key=lambda c: (-c["spend_cents"], c["category"]),
    )
    if not spent:
        return None
    points = []
    for c in spent[:BARS_MAX]:
        point = {"label": c["category"], "value": int(c["spend_cents"])}
        if c.get("unverified"):
            point["flag"] = "unverified"
        points.append(point)
    total = sum(int(a["spend_cents"]) for a in body.get("accounts") or [])
    unverified = sum(int(a.get("unverified_cents") or 0) for a in body.get("accounts") or [])
    notes = [f"Total {money_words(total)}."]
    if unverified:
        notes.append(f"Includes {money_words(unverified)} unverified.")
    if body.get("uncategorised_cents"):
        notes.append(
            f"{money_words(body['uncategorised_cents'])} has no category yet and is not in the "
            f"total."
        )
    if not body.get("complete", True):
        notes.append("The bank data on file does not cover the whole month.")
    return {
        "card": "bars",
        "title": "Spending by category",
        "period": _month_words(body.get("month")),
        "unit": "usd",
        "points": points,
        "more": max(len(spent) - BARS_MAX, 0),
        "note": " ".join(notes),
    }


# =============================================================================
# timeline: list_events
# =============================================================================


def _clock(moment: datetime | None) -> str | None:
    return f"{moment.hour:02d}:{moment.minute:02d}" if moment is not None else None


def _field(row, name):
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def all_day_bounds(window_start: datetime, window_end: datetime, zone) -> tuple[datetime, datetime]:
    """The instants an all-day row's `starts_at` (UTC midnight of its date) may take to fall on
    one of the window's local days: UTC midnight of the first, to the last instant of the last."""
    first = window_start.astimezone(zone).date()
    last = window_end.astimezone(zone).date()
    return (
        datetime.combine(first, datetime.min.time(), tzinfo=UTC),
        datetime.combine(last + timedelta(days=1), datetime.min.time(), tzinfo=UTC)
        - timedelta(microseconds=1),
    )


def _expand(row, skips: dict, window_start: datetime, window_end: datetime, zone):
    """(start, end) for each occurrence of one stored event in the window, skips removed.
    `api.recurrence.series_occurrences` is the engine's one expansion (push reminders use it).
    An ALL-DAY row's `starts_at` is UTC midnight of the date it names, so it is expanded in UTC
    and its date is the UTC date, never converted to the zone (the "advanced by one day" bug
    `frontend/src/lib/day.ts` documents). `end` carries the row's own length, or None."""
    from api.recurrence import series_occurrences

    all_day = bool(_field(row, "all_day"))
    starts_at, ends_at = _field(row, "starts_at"), _field(row, "ends_at")
    length = ends_at - starts_at if starts_at is not None and ends_at is not None else None
    if all_day:
        window_start, window_end = all_day_bounds(window_start, window_end, zone)
    for start in series_occurrences(
        row, skips.get(_field(row, "id"), ()), window_start, window_end, UTC if all_day else zone
    ):
        yield start, (start + length if length is not None else None)


def occurrences(events, skips: dict, window_start: datetime, window_end: datetime, zone) -> list:
    """The occurrences of repeating series in the window, in the household's clock, for the
    tool's own result: the same expansion `events_timeline` draws, so text and card agree."""
    out = []
    for row in events:
        all_day = bool(_field(row, "all_day"))
        for start, end in _expand(row, skips, window_start, window_end, zone):
            out.append(
                {
                    "event_id": str(_field(row, "id")),
                    "title": (_field(row, "title") or "Untitled").strip() or "Untitled",
                    "kind": _field(row, "kind"),
                    "all_day": all_day,
                    "date": (start.astimezone(UTC) if all_day else start.astimezone(zone))
                    .date()
                    .isoformat(),
                    "starts_at": None if all_day else start.astimezone(zone).isoformat(),
                    "ends_at": None if all_day or end is None else end.astimezone(zone).isoformat(),
                }
            )
    out.sort(key=lambda o: (o["date"], o["starts_at"] or ""))
    return out


def events_timeline(
    events,
    skips: dict,
    window_start: datetime,
    window_end: datetime,
    zone,
    day: date,
    last_day: date | None = None,
) -> dict:
    """The events and tasks of `day` (through `last_day` for a window of several days), in the
    household's clock, from the rows `list_events` read (one-offs, repeating series and
    suggestions; done ones only if the tool kept them).

    Repeating series are expanded by `_expand`, skips subtracted. Over several days each item
    carries its own `date` (additive to the shared format: the phone may ignore it) and the
    top-level `date` is the first day. `more` counts the occurrences in the tool's window that
    are not shown, on days outside the range or past the cap."""
    last_day = last_day if last_day is not None and last_day > day else day
    several = last_day > day
    range_start = datetime.combine(day, datetime.min.time(), tzinfo=zone)
    range_end = datetime.combine(last_day + timedelta(days=1), datetime.min.time(), tzinfo=zone)
    found, other = [], 0
    for row in events:
        all_day = bool(_field(row, "all_day"))
        for start, ends in _expand(row, skips, window_start, window_end, zone):
            if all_day:
                on = start.astimezone(UTC).date()
                hit = day <= on <= last_day
            else:
                on = start.astimezone(zone).date()
                hit = range_start <= start < range_end
            if not hit:
                other += 1
                continue
            end = None
            if not all_day and ends is not None and ends.astimezone(zone).date() == on:
                end = ends.astimezone(zone)
            item = {
                "start": None if all_day else _clock(start.astimezone(zone)),
                "end": _clock(end),
                "all_day": all_day,
                "title": (_field(row, "title") or "Untitled").strip() or "Untitled",
                "kind": KIND_WORDS.get(_field(row, "kind"), "event"),
                "_sort": (on, not all_day, start),
            }
            if several:
                item["date"] = on.isoformat()
            found.append(item)
    found.sort(key=lambda i: i["_sort"])
    shown = found[:TIMELINE_MAX]
    for item in shown:
        del item["_sort"]
    card = {
        "card": "timeline",
        "title": "What's on",
        "date": day.isoformat(),
        "zone": zone_words(range_start.utcoffset()),
        "items": shown,
        "more": (len(found) - len(shown)) + other,
    }
    if several:
        card["period"] = f"{_short_day(day)} to {_short_day(last_day)}"
    if any(i["kind"] == "suggestion" for i in shown):
        card["note"] = "Suggestions are ideas, not plans."
    return card


def _short_day(day: date) -> str:
    return f"{day:%a} {day:%b} {day.day}"


# =============================================================================
# checklist: list_checklists
# =============================================================================


def checklist_card(checklists: list[dict], on_day: date | None) -> dict | None:
    """The first checklist the tool read (the one asked for by name, or the first in the
    household's order) with its items: ticked on `on_day` when the tool was given a day, else
    ticked now (`ticked_now`, the Lists screen's own rule). An item with neither is not drawn
    as unticked: the card is skipped rather than claim something nobody read."""
    if not checklists:
        return None
    first = checklists[0]
    key = "ticked_on_date" if on_day is not None else "ticked_now"
    items = first.get("items") or []
    if any(key not in i for i in items):
        return None
    shown = [{"text": i.get("text") or "", "done": bool(i[key])} for i in items[:CHECKLIST_MAX]]
    card = {
        "card": "checklist",
        "title": first.get("name") or "Checklist",
        "list_name": first.get("name") or "Checklist",
        "items": shown,
        "more": max(len(items) - len(shown), 0),
    }
    if on_day is not None:
        card["period"] = on_day.isoformat()
    if len(checklists) > 1:
        others = len(checklists) - 1
        card["note"] = f"{others} other list{'s' if others != 1 else ''} not shown."
    return card


# =============================================================================
# car and steps: run_procedure
# =============================================================================

_DTC_FILE = Path(__file__).with_name("dtc_titles.json")


@cache
def dtc_titles() -> dict[str, str]:
    """Code -> title, trimmed from the phone's `dtc_descriptions_seed.json` (titles only).
    `tests/test_cards.py` holds the two together."""
    return json.loads(_DTC_FILE.read_text(encoding="utf-8"))


def _value(values: dict, step: str) -> dict:
    value = values.get(step)
    return value if isinstance(value, dict) else {}


def _ok(value: dict) -> bool:
    return value.get("outcome") == "ok" and isinstance(value.get("structured"), dict)


def _service_part(v: dict) -> tuple[dict, str]:
    """Mileage on file and the next service, from one `next_service` vehicle."""
    odometer = v.get("odometer") or None
    next_service = None
    overdue = v.get("overdue") or []
    by_date, by_miles = v.get("next_by_date"), v.get("next_by_miles")
    if overdue:
        row = overdue[0]
        next_service = {"label": f"{row['service_name']}, past due", "estimate": True}
        if row.get("due_on"):
            next_service["due_date"] = row["due_on"]
        if row.get("due_at_miles") is not None:
            next_service["due_miles"] = int(row["due_at_miles"])
    elif by_date or by_miles:
        lead = by_date or by_miles
        next_service = {"label": lead["service_name"], "estimate": True}
        if by_date:
            next_service["due_date"] = by_date["due_on"]
        if by_miles and by_miles["service_name"] == lead["service_name"]:
            next_service["due_miles"] = int(by_miles["due_at_miles"])
    fields = {
        "mileage": int(odometer["miles"]) if odometer else None,
        "mileage_as_of": odometer["read_on"] if odometer else None,
        "next_service": next_service,
    }
    note = "Next service is an estimate from the records on file." if next_service else ""
    return fields, note


def _codes_part(found: list[str] | None) -> tuple[list, str]:
    if found is None:
        return [], "Trouble codes were not read."
    titles = dtc_titles()
    codes = [{"code": c, "meaning": titles.get(c, "")} for c in found[:CODES_MAX]]
    notes = []
    if len(found) > CODES_MAX:
        notes.append(f"{len(found) - CODES_MAX} more trouble codes not shown.")
    if codes:
        notes.append("Codes are what the OBD dongle logged when it last connected.")
    return codes, " ".join(notes)


def _recalls_part(found: list[dict] | None) -> tuple[list, str]:
    if found is None:
        return [], "Recalls were not looked up, so whether there are any is not known."
    recalls = [{"summary": _recall_words(r)} for r in found[:RECALLS_MAX]]
    notes = []
    if len(found) > RECALLS_MAX:
        notes.append(f"{len(found) - RECALLS_MAX} more recalls not shown.")
    notes.append("Recalls are NHTSA's list for the model, not a check of this car's VIN.")
    return recalls, " ".join(notes)


CAR_PARTS = ("service", "codes", "recalls")


def _car(name: str, *, service=None, codes=None, recalls=None, read=CAR_PARTS) -> dict:
    """A car card from the parts one call read. `parts` (additive to the shared format) names
    each part this card's call read, with that part's note, so a screen can merge two cards
    for the same car (next_service, then vehicle_recalls) without a part one call never read
    looking like an empty finding. `note` is every part's note, for a screen that does not
    merge."""
    card = {
        "card": "car",
        "title": name,
        "name": name,
        "mileage": None,
        "mileage_as_of": None,
        "next_service": None,
        "recalls": [],
        "codes": [],
    }
    parts = {}
    if "service" in read:
        fields, parts["service"] = _service_part(service or {})
        card.update(fields)
    if "codes" in read:
        card["codes"], parts["codes"] = _codes_part(codes)
    if "recalls" in read:
        card["recalls"], parts["recalls"] = _recalls_part(recalls)
    card["note"] = " ".join(n for n in parts.values() if n)
    card["parts"] = parts
    return card


def service_car(structured: dict) -> dict | None:
    """`next_service` for one car: its mileage on file and next service. Several cars is no
    car card. Codes and recalls are not this call's, so they are not on the card."""
    vehicles = (structured or {}).get("vehicles") or []
    if len(vehicles) != 1:
        return None
    v = vehicles[0]
    return _car(v.get("name") or "The car", service=v, read=("service",))


def recalls_car(name: str, structured: dict) -> dict:
    """`vehicle_recalls` for one car: NHTSA's list for its model. Only the recalls part."""
    return _car(name, recalls=(structured or {}).get("recalls") or [], read=("recalls",))


def car_card(values: dict) -> dict | None:
    """car_check's one car, from its step values (`next_service`, `read_records` of the code
    tables, `vehicle_recalls`). Several cars, or a service read that did not come back, is no
    car card (the caller draws the steps instead). Codes or recalls that did not come back are
    said in the note, never drawn as none."""
    from procedures.steps import codes_since_clear

    service = _value(values, "service")
    if not _ok(service):
        return None
    vehicles = service["structured"].get("vehicles") or []
    if len(vehicles) != 1:
        return None
    v = vehicles[0]
    codes_value, clears_value = _value(values, "codes"), _value(values, "clears")
    codes = None
    if _ok(codes_value) and _ok(clears_value):
        codes = codes_since_clear(
            v.get("vehicle_id"),
            codes_value["structured"].get("rows"),
            clears_value["structured"].get("rows"),
        )
    recalls_value = _value(values, "recalls")
    recalls = (recalls_value["structured"].get("recalls") or []) if _ok(recalls_value) else None
    return _car(v.get("name") or "The car", service=v, codes=codes, recalls=recalls)


def _recall_words(recall: dict) -> str:
    """"Air bags: The passenger air bag may not deploy." NHTSA's own words, trimmed."""
    component = (recall.get("component") or "").strip().capitalize()
    summary = (recall.get("summary") or "").strip()
    if component and summary:
        return f"{component}: {summary}"
    return component or summary or "A recall with no summary"


STEP_STATUS = {"ok": "ok", "failed": "failed", "refused": "failed"}


def steps_card(procedure: str, done: list[dict], not_run: list[str], status: str) -> dict:
    """What a procedure run did, step by step: status in words, time in ms. `done` rows are
    the runner's own (`step_id`, `outcome`, `ms`); the label is the step id in words, never
    the tool it called. An ask's question row is left out; its answer row counts."""
    items = []
    for d in done:
        if d.get("outcome") == "asked":
            continue
        items.append(
            {
                "label": humanize(d["step_id"]),
                "status": STEP_STATUS.get(d.get("outcome"), "failed"),
                "ms": int(d.get("ms") or 0),
            }
        )
    items += [{"label": humanize(s), "status": "skipped", "ms": 0} for s in not_run]
    card = {
        "card": "steps",
        "title": humanize(procedure),
        "name": humanize(procedure),
        "items": items,
        "total_ms": sum(i["ms"] for i in items),
    }
    words = {
        "partial": "Stopped part way.",
        "failed": "Did not finish.",
        "cancelled": "Cancelled.",
        "needs_answer": "Waiting for an answer.",
    }
    if status in words:
        card["note"] = words[status]
    return card
