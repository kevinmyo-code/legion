"""Cards: what a tool result shows on a screen, built here from the tool's own rows.

Generative UI (Kevin, 2026-11-04: "go for it"). A tool result may carry `card`, a small JSON
object the web chat (members' and the recruiter demo's) and the phone draw beside the
transcript. The format is shared with the phone, so its field names are fixed:

- common: `card` ("bars" | "timeline" | "car" | "steps" | "checklist" | "diagram" | "resume"),
  `title`, optional `scope`, `period`, `note` (plain words, e.g. "Includes $12.40 unverified").
- bars: `unit` ("usd", values in integer cents, or "" for counts), `points: [{label, value,
  flag?}]` (flag "unverified" or "estimate"), at most 8, and `more` (rows not shown).
- timeline: `date` (YYYY-MM-DD in the household's zone), `zone` ("UTC-06:00", never an IANA
  id), `items: [{start, end, all_day, title, kind}]`, `more`.
- car: `name`, `mileage`, `mileage_as_of`, `next_service`, `recalls: [{summary}]`,
  `codes: [{code, meaning}]`.
- steps: `name`, `items: [{label, status, ms}]`, `total_ms`.
- checklist: `list_name`, `items: [{text, done}]`, `more`.
- diagram: `project`, `view`, `caption` (the picture in words, its alt text), `svg` (inline,
  pre-rendered by `diagrams/render.py`), `highlight: [node id]`, `nodes: [{id, label}]`. Built
  by `diagrams.catalogue.diagram_card` from a fixed catalogue, never from model text. The phone
  draws no diagram: `ServerCards.parse` answers null for a kind it does not know.
- resume: `image` (the page as a PNG, `/api/demo/asset/resume_image`, or null), `pdf`
  (`/api/demo/asset/resume`), `alt`. Links, never bytes; built by `demo.tools.resume_card` for
  `show_resume`, recruiters only. The phone draws none, like `diagram`.

Three general kinds (2026-11-15), drawn by the web and the phone alike. Their field names are
single words, so the engine's snake_case and the phone's camelCase spell them the same, and
`shared/cards/fixtures.json` holds one example of each that all three test suites read:

- readings: labelled values with units, for a snapshot of measurements. `at` (optional, the "as
  of" time, "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM" on the household's clock), `items: [{label,
  value, unit, flag?, was?, text?, note?}]` at most 12, `more`. `value` is a number, or null when
  it could not be read (said "not read", never drawn as zero); `text` is a value that is words,
  not a number (a VIN, "Closed loop", "No total shown"), with `value` null; `unit` "usd" means
  integer cents, "" a bare number, "%" sits on the number, anything else follows it after a
  space. `was` is an earlier value of the same reading (a before and after). `flag` as on bars.
- status_list: `at` (optional), `items: [{name, status, note?}]` at most 12, `more`. `status`
  is one of ok, attention, failed, unknown, not_reached, always drawn in WORDS ("OK", "Needs
  attention", "Failed", "Unknown", "Not reached"), never by colour or a glyph alone.
- trend: `unit` (as on readings), `series: [{name, points: [{at, value}], flag?}]`, one or two
  series sharing the unit, each at most 120 points, oldest first. The screen draws a line and
  says each series' latest value, its low and its high in words, computed from the points.

Optional picture fields (2026-11-15, "tell me things at a quick glance"). Every one is optional
and absent means the card draws exactly as before; each carries a figure the source already has,
so a screen never works out a target, a range or a goal of its own:

- readings: `lead` (index of the item that answers the card's question, drawn as its headline).
  On an item: `scale: {min, max}` (the value drawn as a bar on that span, same unit; a percent's
  0 to 100, a budget's 0 to its cap), `mark: {value, label}` (one reference on the scale, an
  alert threshold, "Alert at 95%"), `split: {unit, parts: [{label, value}]}` (2 to 4 parts of
  one whole drawn as one stacked bar, e.g. macros in grams; the item's `flag` covers every part)
  and `attention: true` (the source says this value needs attention: said "needs attention" in
  words, drawn in the accent).
- status_list item: `meter: {value, max, unit, mark?}` (an amount against its limit: spent
  against the cap, the mark where an even pace would be today) and `level: {value, of}` (a rank
  on a fixed scale the source names, 1 to 5: an NWS alert's severity, Minor 1 to Extreme 4).
- trend: `guide: {value, label}` (one reference line: a sleep target, a goal weight, an alert
  threshold) and `style` ("line", the default, or "bars": one column per point from zero, for
  per-night or per-day totals).

`check()` holds these three to their shape; `safe()` drops one that does not fit.

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

READINGS_MAX = 12
STATUS_MAX = 12
TREND_MAX = 120
TREND_SERIES_MAX = 2
FLAGS = ("unverified", "estimate")
SPLIT_MIN = 2
SPLIT_MAX = 4
LEVEL_MAX = 5
TREND_STYLES = ("line", "bars")
STATUSES = ("ok", "attention", "failed", "unknown", "not_reached")
STATUS_WORDS = {
    "ok": "OK",
    "attention": "Needs attention",
    "failed": "Failed",
    "unknown": "Unknown",
    "not_reached": "Not reached",
}
_AT = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$")


def safe(builder, *args, **kwargs) -> dict | None:
    """`builder(*args, **kwargs)`, or None when it raises or makes a card of a general kind
    (readings, status_list, trend) that does not fit its shape. Logged, never re-raised."""
    try:
        card = builder(*args, **kwargs)
    except Exception:  # noqa: BLE001 - a card must never fail the tool it decorates
        log.exception("card builder %s failed; the result goes out without a card", builder)
        return None
    if card is not None and card.get("card") in GENERAL_KINDS and not check(card):
        log.error("card builder %s made a %s card that does not fit", builder, card.get("card"))
        return None
    return card


# =============================================================================
# The general kinds: readings, status_list, trend
# =============================================================================


def _number(value) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _at_ok(value) -> bool:
    return value is None or (isinstance(value, str) and bool(_AT.match(value)))


def _common_ok(card: dict) -> bool:
    if not isinstance(card.get("title"), str) or not card["title"]:
        return False
    return all(card.get(k) is None or isinstance(card[k], str) for k in ("scope", "period", "note"))


def _value_ok(value, unit: str) -> bool:
    """A number, integer cents for usd. None is allowed by the caller where it means unread."""
    return _number(value) and (unit != "usd" or isinstance(value, int))


def _mark_ok(mark, unit: str) -> bool:
    return (
        mark is None
        or isinstance(mark, dict)
        and _value_ok(mark.get("value"), unit)
        and isinstance(mark.get("label"), str)
        and bool(mark["label"])
    )


def _scale_ok(scale, unit: str) -> bool:
    return (
        scale is None
        or isinstance(scale, dict)
        and _value_ok(scale.get("min"), unit)
        and _value_ok(scale.get("max"), unit)
        and scale["min"] < scale["max"]
    )


def _split_ok(split) -> bool:
    if split is None:
        return True
    if not isinstance(split, dict) or not isinstance(split.get("unit"), str):
        return False
    parts = split.get("parts")
    return (
        isinstance(parts, list)
        and SPLIT_MIN <= len(parts) <= SPLIT_MAX
        and all(
            isinstance(p, dict)
            and isinstance(p.get("label"), str)
            and _number(p.get("value"))
            and p["value"] >= 0
            for p in parts
        )
    )


def _reading_ok(item) -> bool:
    if not isinstance(item, dict) or not isinstance(item.get("label"), str):
        return False
    unit = item.get("unit")
    if not isinstance(unit, str):
        return False
    if item.get("value") is not None and not _value_ok(item["value"], unit):
        return False
    if item.get("was") is not None and not _value_ok(item["was"], unit):
        return False
    if item.get("flag") is not None and item["flag"] not in FLAGS:
        return False
    text = item.get("text")
    if text is not None and (not isinstance(text, str) or item.get("value") is not None):
        return False
    if not (_scale_ok(item.get("scale"), unit) and _split_ok(item.get("split"))):
        return False
    # A mark sits on a scale; without one there is nothing to place it on.
    mark = item.get("mark")
    if mark is not None and (item.get("scale") is None or not _mark_ok(mark, unit)):
        return False
    if item.get("attention") is not None and not isinstance(item["attention"], bool):
        return False
    return item.get("note") is None or isinstance(item["note"], str)


def _meter_ok(meter) -> bool:
    if meter is None:
        return True
    if not isinstance(meter, dict) or not isinstance(meter.get("unit"), str):
        return False
    unit = meter["unit"]
    return (
        _value_ok(meter.get("value"), unit)
        and meter["value"] >= 0
        and _value_ok(meter.get("max"), unit)
        and meter["max"] > 0
        and _mark_ok(meter.get("mark"), unit)
    )


def _level_ok(level) -> bool:
    if level is None:
        return True
    if not isinstance(level, dict):
        return False
    value, of = level.get("value"), level.get("of")
    return (
        isinstance(value, int)
        and isinstance(of, int)
        and not isinstance(value, bool)
        and not isinstance(of, bool)
        and 1 <= value <= of <= LEVEL_MAX
    )


def _status_ok(item) -> bool:
    return (
        isinstance(item, dict)
        and isinstance(item.get("name"), str)
        and item.get("status") in STATUSES
        and (item.get("note") is None or isinstance(item["note"], str))
        and _meter_ok(item.get("meter"))
        and _level_ok(item.get("level"))
    )


def _series_ok(series, unit: str) -> bool:
    if not isinstance(series, dict) or not isinstance(series.get("name"), str):
        return False
    points = series.get("points")
    if not isinstance(points, list) or len(points) > TREND_MAX:
        return False
    if series.get("flag") is not None and series["flag"] not in FLAGS:
        return False
    return all(
        isinstance(p, dict)
        and bool(p.get("at"))
        and _at_ok(p.get("at"))
        and _value_ok(p.get("value"), unit)
        for p in points
    )


def check(card: dict) -> bool:
    """Whether a readings, status_list or trend card fits its shape (the module docstring)."""
    if not isinstance(card, dict) or not _common_ok(card):
        return False
    kind = card.get("card")
    if kind == "readings":
        items = card.get("items")
        lead = card.get("lead")
        return (
            _at_ok(card.get("at"))
            and isinstance(items, list)
            and len(items) <= READINGS_MAX
            and all(_reading_ok(i) for i in items)
            and isinstance(card.get("more"), int)
            and (
                lead is None
                or isinstance(lead, int) and not isinstance(lead, bool) and 0 <= lead < len(items)
            )
        )
    if kind == "status_list":
        items = card.get("items")
        return (
            _at_ok(card.get("at"))
            and isinstance(items, list)
            and len(items) <= STATUS_MAX
            and all(_status_ok(i) for i in items)
            and isinstance(card.get("more"), int)
        )
    if kind == "trend":
        series = card.get("series")
        unit = card.get("unit")
        return (
            isinstance(unit, str)
            and isinstance(series, list)
            and 1 <= len(series) <= TREND_SERIES_MAX
            and all(_series_ok(s, unit) for s in series)
            and _mark_ok(card.get("guide"), unit)
            and card.get("style") in (None, *TREND_STYLES)
            # Bars draw one series: a second would be silently left out.
            and (card.get("style") != "bars" or len(series) == 1)
        )
    return False


GENERAL_KINDS = ("readings", "status_list", "trend")

# The kinds a recruiter's visit was shown before the general kinds existed (ADR 0068: a
# recruiter's surface only ever narrows). A card of any other kind is dropped for a recruiter
# at the tool door (`assistant/views.py`), whatever tool made it, so wiring a general card to
# a tool on `RECRUITER_TOOLS` never widens what /try shows.
RECRUITER_KINDS = frozenset({"bars", "timeline", "car", "steps", "checklist", "diagram", "resume"})


def for_viewer(card: dict | None, recruiter: bool) -> dict | None:
    """`card`, or None when the viewer is a recruiter and the kind is not one /try had."""
    if card is None or not recruiter:
        return card
    return card if card.get("card") in RECRUITER_KINDS else None


def _common(kind: str, title: str, scope, period, note) -> dict:
    card = {"card": kind, "title": title}
    for key, value in (("scope", scope), ("period", period), ("note", note)):
        if value:
            card[key] = value
    return card


def reading(
    label: str,
    value,
    unit: str,
    *,
    flag=None,
    was=None,
    note=None,
    text=None,
    scale=None,
    mark=None,
    split=None,
    attention=False,
) -> dict:
    """One readings item. `value` None means it could not be read, unless `text` says the value
    in words; money is integer cents. `scale` is a (min, max) pair, `mark` a (value, label) pair
    on it, `split` a (unit, [(label, value)]) pair: the optional picture fields."""
    item = {"label": label, "value": value, "unit": unit}
    if text:
        item["text"] = text
    if flag:
        item["flag"] = flag
    if was is not None:
        item["was"] = was
    if note:
        item["note"] = note
    if scale is not None:
        item["scale"] = {"min": scale[0], "max": scale[1]}
        if mark is not None:
            item["mark"] = {"value": mark[0], "label": mark[1]}
    if split is not None:
        item["split"] = {
            "unit": split[0],
            "parts": [{"label": name, "value": amount} for name, amount in split[1]],
        }
    if attention:
        item["attention"] = True
    return item


def readings_card(
    title: str, items: list[dict], *, at=None, scope=None, period=None, note=None, lead=None
):
    """A readings card, the first READINGS_MAX items shown and the rest counted in `more`.
    `lead` is the index of the item drawn as the headline; dropped when it is not shown."""
    card = _common("readings", title, scope, period, note)
    if at:
        card["at"] = at
    card["items"] = items[:READINGS_MAX]
    card["more"] = max(len(items) - READINGS_MAX, 0)
    if lead is not None and 0 <= lead < len(card["items"]):
        card["lead"] = lead
    return card


def status(name: str, state: str, note=None, *, meter=None, level=None) -> dict:
    """One status row. `meter` is (value, max, unit, mark or None), mark a (value, label) pair;
    `level` is (value, of)."""
    item = {"name": name, "status": state}
    if note:
        item["note"] = note
    if meter is not None:
        value, top, unit, mark = meter
        item["meter"] = {"value": value, "max": top, "unit": unit}
        if mark is not None:
            item["meter"]["mark"] = {"value": mark[0], "label": mark[1]}
    if level is not None:
        item["level"] = {"value": level[0], "of": level[1]}
    return item


def status_list_card(title: str, items: list[dict], *, at=None, scope=None, period=None, note=None):
    """A status_list card, the first STATUS_MAX rows shown and the rest counted in `more`."""
    card = _common("status_list", title, scope, period, note)
    if at:
        card["at"] = at
    card["items"] = items[:STATUS_MAX]
    card["more"] = max(len(items) - STATUS_MAX, 0)
    return card


def series(name: str, points: list[tuple[str, float]], *, flag=None) -> dict:
    """One trend series from (at, value) pairs, oldest first; only the newest TREND_MAX kept."""
    ordered = sorted(points, key=lambda p: p[0])[-TREND_MAX:]
    out = {"name": name, "points": [{"at": at, "value": value} for at, value in ordered]}
    if flag:
        out["flag"] = flag
    return out


def trend_card(
    title: str,
    unit: str,
    lines: list[dict],
    *,
    scope=None,
    period=None,
    note=None,
    guide=None,
    style=None,
):
    """`guide` is a (value, label) pair: one reference line the source states."""
    card = _common("trend", title, scope, period, note)
    card["unit"] = unit
    card["series"] = lines[:TREND_SERIES_MAX]
    if guide is not None:
        card["guide"] = {"value": guide[0], "label": guide[1]}
    if style:
        card["style"] = style
    return card


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
    # Coursework and to-dos alone (a "what is due this week" read) say so in the title.
    due_only = bool(shown) and all(i["kind"] == "todo" for i in shown)
    card = {
        "card": "timeline",
        "title": "Due" if due_only else "What's on",
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


# =============================================================================
# School: canvas_grades (readings)
# =============================================================================


def local_at(moment, zone) -> str | None:
    """"YYYY-MM-DDTHH:MM" on the household's clock, for a card's `at`; None for no time."""
    if moment is None:
        return None
    if isinstance(moment, str):
        from django.utils.dateparse import parse_datetime

        moment = parse_datetime(moment)
        if moment is None:
            return None
    return moment.astimezone(zone).strftime("%Y-%m-%dT%H:%M")


def _grade_note(course: dict) -> str | None:
    """The letter Canvas gives beside the percent, or why no percent is shown."""
    if course.get("canvas_current_score") is None:
        return None
    letter = course.get("canvas_current_grade")
    return f"Canvas says {letter}." if letter else None


def _assignment_reading(a: dict) -> dict:
    if a.get("excused"):
        return reading(a["name"], None, "", text="Excused")
    if a.get("missing"):
        return reading(a["name"], None, "", text="Missing")
    if a.get("score") is None:
        return reading(a["name"], None, "", text="Not graded")
    possible = a.get("points_possible")
    unit = f"of {possible:g}" if possible is not None else "points"
    scale = (0, max(possible, a["score"])) if possible else None
    return reading(
        a["name"], a["score"], unit, note="Late." if a.get("late") else None, scale=scale
    )


def grades_card(results: list[dict], zone) -> dict | None:
    """`canvas_grades`: Canvas's current percent per course, as Canvas states it, and for one
    course asked by name its assignments' scores. A course whose total Canvas hides says so in
    words, never a figure; nothing here is worked out (that is `what_i_need`'s, an estimate)."""
    if not results:
        return None
    items = []
    for course in results:
        if course.get("canvas_current_score") is None:
            items.append(reading(course["course"], None, "", text="No total shown"))
        else:
            score = course["canvas_current_score"]
            # A percent on 0 to 100, stretched only for extra credit past 100.
            items.append(
                reading(
                    course["course"],
                    score,
                    "%",
                    note=_grade_note(course),
                    scale=(0, max(100, score)),
                )
            )
    # The headline is the lowest course Canvas gives a percent for: the one to look at.
    scored = [i for i, item in enumerate(items) if item["value"] is not None]
    lead = min(scored, key=lambda i: items[i]["value"]) if scored else None
    detail = len(results) == 1 and "assignments" in results[0]
    if detail:
        items += [_assignment_reading(a) for a in results[0]["assignments"]]
    reads = [c.get("read_at") for c in results if c.get("read_at")]
    return readings_card(
        results[0]["course"] if detail else "Grades",
        items,
        at=local_at(min(reads), zone) if reads else None,
        note="Canvas's own current grades: work not yet graded does not count.",
        lead=lead,
    )


# =============================================================================
# Money: budgets (status_list, budget pace)
# =============================================================================


def _pace_mark(cap: int, body: dict):
    """Where an even pace through the month would be today: the cap times the share of the
    month gone, in whole cents. None when the month's days are not in the body."""
    elapsed, days = body.get("days_elapsed"), body.get("days_in_month")
    if not isinstance(elapsed, int) or not isinstance(days, int) or days <= 0:
        return None
    return (cap * min(elapsed, days) + days // 2) // days, f"Even pace, day {elapsed}"


def _pace_row(line: dict, body: dict | None = None) -> dict:
    """One capped category: its state in words ("On track", "Over") in the note, since the
    five status words have none for money; the projection is said to be an estimate."""
    spent, cap = line["spent_cents"], line["cap_cents"]
    of = f"{money_words(spent)} of {money_words(cap)}"
    projected = line.get("projection_cents")
    heading_over = projected is not None and projected > cap
    if line["status"] == "over":
        state, words = "attention", f"Over: {of}."
    elif line["status"] == "near":
        state, words = "attention", f"Past 80%: {of}."
    elif heading_over:
        state, words = "attention", f"On pace to go over: {of}."
    else:
        state, words = "ok", f"On track: {of}."
    extra = []
    if projected is not None:
        extra.append(f"About {money_words(projected)} by month end (estimate).")
    if line.get("pending_count"):
        extra.append(f"{money_words(line['pending_cents'])} of it pending.")
    if line.get("unverified"):
        extra.append("Includes unverified rows.")
    # A net refund (spent below zero) has no bar to draw: the words carry it alone.
    meter = (int(spent), int(cap), "usd", _pace_mark(int(cap), body or {})) if spent >= 0 else None
    return status(line["category"], state, " ".join([words, *extra]), meter=meter)


def budget_pace_card(body: dict) -> dict | None:
    """`budgets`: each capped category's pace, the ones that need attention first. USD only
    (`finance.budgets` is), and a month with no caps is no card: the words say so."""
    if body.get("currency", "USD").upper() != "USD":
        return None
    capped = [c for c in body.get("categories") or [] if c.get("cap_cents")]
    if not capped:
        return None
    rows = [_pace_row(c, body) for c in capped]
    rows.sort(key=lambda r: (r["status"] != "attention", r["name"]))
    total = body.get("total") or {}
    notes = []
    if total.get("cap_cents"):
        notes.append(
            f"Altogether {money_words(total['spent_cents'])} of {money_words(total['cap_cents'])}."
        )
    if body.get("days_elapsed", 0) < body.get("projection_from_day", 0):
        notes.append("Too early in the month to project a month end.")
    return status_list_card(
        "Budget pace",
        rows,
        period=f"{_month_words(body.get('month'))}, day {body.get('days_elapsed')} of "
        f"{body.get('days_in_month')}",
        note=" ".join(notes) or None,
    )


# =============================================================================
# Weather: check_conditions alerts (status_list)
# =============================================================================


# The National Weather Service's own severity scale, lowest first. "Unknown" has no rank.
NWS_SEVERITY = ("Minor", "Moderate", "Severe", "Extreme")


def _alert_row(alert: dict) -> dict:
    from conditions.report import _clock

    until = _clock(alert.get("ends") or alert.get("expires") or "")
    bits = [alert.get("severity") or "Severity not given"]
    if until:
        bits.append(f"until {until}")
    if alert.get("sender"):
        bits.append(f"from {alert['sender']}")
    severity = alert.get("severity")
    level = (
        (NWS_SEVERITY.index(severity) + 1, len(NWS_SEVERITY))
        if severity in NWS_SEVERITY
        else None
    )
    return status(alert.get("event") or "Alert", "attention", ", ".join(bits) + ".", level=level)


def conditions_card(structured: dict) -> dict | None:
    """`check_conditions`' weather alerts for the point, as the National Weather Service lists
    them. None listed is one OK row; a read that failed is Unknown, never all clear. No card
    when the call did not ask for alerts. There is no forecast: no tool reads one."""
    section = next(
        (s for s in (structured or {}).get("sections") or [] if s.get("kind") == "alerts"), None
    )
    if section is None:
        return None
    state = section.get("status")
    if state == "found":
        found = section.get("rows") or []
        # Most severe first, so the top of the card is the one that matters.
        ranked = sorted(
            found,
            key=lambda a: -(NWS_SEVERITY.index(a["severity"]) + 1)
            if a.get("severity") in NWS_SEVERITY
            else 0,
        )
        rows = [_alert_row(a) for a in ranked]
    elif state == "empty":
        rows = [status("NWS alerts", "ok", "None active for this point right now.")]
    elif state == "not_configured":
        rows = [status("NWS alerts", "unknown", "Not set up, so nothing was read.")]
    else:
        rows = [
            status(
                "NWS alerts",
                "unknown",
                "Could not be read, so whether any alert is active here is not known.",
            )
        ]
    return status_list_card(
        "Weather alerts",
        rows,
        scope=structured.get("where") or None,
        note="Read just now from the National Weather Service.",
    )


# =============================================================================
# Body: get_weight_trend (trend)
# =============================================================================


def weight_trend_card(data: dict) -> dict | None:
    """`get_weight_trend`'s points, built BEFORE the tool drops them from `structured`: the
    weighed line, and the trend line flagged an estimate. Days with no weigh-in are absent."""
    points = data.get("points") or []
    weighed = [(p["day"], p["weight_lbs"]) for p in points if p.get("weight_lbs") is not None]
    smoothed = [(p["day"], p["trend_lbs"]) for p in points if p.get("trend_lbs") is not None]
    if not weighed and not smoothed:
        return None
    notes = []
    rate = data.get("rate_lbs_per_week")
    if rate is not None:
        way = "Losing" if rate < 0 else "Gaining"
        notes.append(f"{way} {abs(rate):g} lb a week (estimate).")
    notes.append("Days with no weigh-in are left out.")
    goal = (data.get("milestone") or {}).get("target_lbs")
    return trend_card(
        "Weight",
        "lb",
        [series("Weighed", weighed), series("Trend", smoothed, flag="estimate")],
        period=f"Last {len(points)} days",
        note=" ".join(notes),
        guide=(goal, f"Goal {goal:g} lb") if _number(goal) else None,
    )


# =============================================================================
# Memory: list_memories (readings, read only on the web)
# =============================================================================


def memories_card(rows: list[dict]) -> dict:
    """`list_memories`: each memory's text in words under its day and whether it is shared. The
    web draws no Forget: forgetting stays `forget_memory` and the phone's Memory screen."""
    from engine_mcp.tools import provenance

    items = []
    for row in rows:
        day = (row.get("created_at") or "")[:10]
        who = "shared" if row.get("shared") else "private"
        items.append(reading(f"{day}, {who}, {provenance(row)}", None, "", text=row["text"]))
    return readings_card(
        "Memories",
        items,
        note=None if rows else "Nothing is remembered yet.",
    )


# =============================================================================
# Car: stored drives, read_records("drives") (readings)
# =============================================================================


def drives_card(rows: list[dict], zone, more: bool = False) -> dict | None:
    """The drives `read_records` read, as miles per day, newest day first, with the total as the
    headline: sums of the stored rows' own miles. A drive with no miles stored is counted in
    words and never as zero. `more` (the read stopped at its limit) says the total and the
    oldest day cover only the drives read. No fuel economy: the phone withholds mpg on purpose,
    so the card never works one out."""
    from django.utils.dateparse import parse_datetime

    days: dict[date, dict] = {}
    for row in rows or []:
        started = parse_datetime(str(row.get("started_at") or ""))
        if started is None:
            continue
        ended = parse_datetime(str(row.get("ended_at") or ""))
        day = days.setdefault(
            started.astimezone(zone).date(),
            {"miles": 0.0, "drives": 0, "unread": 0, "min": 0, "unended": 0},
        )
        day["drives"] += 1
        if row.get("miles") is None:
            day["unread"] += 1
        else:
            day["miles"] += float(row["miles"])
        if ended is not None and ended >= started:
            day["min"] += int((ended - started).total_seconds() // 60)
        else:
            day["unended"] += 1
    if not days:
        return None
    ordered = sorted(days.items(), reverse=True)
    shown = ordered[:READINGS_MAX - 1]
    top = max((round(d["miles"], 1) for _, d in shown), default=0.0)

    def counted(d: dict) -> str:
        words = f"{d['drives']} drive{'s' if d['drives'] != 1 else ''}"
        if d["unended"] == d["drives"]:
            words += ", no end time stored."
        elif d["unended"]:
            words += f", {d['min']} min ({d['unended']} with no end time)."
        else:
            words += f", {d['min']} min."
        if d["unread"]:
            words += f" {d['unread']} with no miles stored, not counted."
        return words

    items = []
    for day, d in shown:
        miles = round(d["miles"], 1) if d["drives"] > d["unread"] else None
        items.append(
            reading(
                f"{day:%a} {day:%b} {day.day}",
                miles,
                "mi",
                note=counted(d),
                scale=(0, top) if miles is not None and top > 0 else None,
            )
        )
    total = {
        "miles": sum(d["miles"] for _, d in ordered),
        "drives": sum(d["drives"] for _, d in ordered),
        "unread": sum(d["unread"] for _, d in ordered),
        "min": sum(d["min"] for _, d in ordered),
        "unended": sum(d["unended"] for _, d in ordered),
    }
    total_miles = round(total["miles"], 1) if total["drives"] > total["unread"] else None
    items.insert(0, reading("Total", total_miles, "mi", note=counted(total)))
    note = "Miles as the phone logged them; no fuel economy."
    if more:
        note += (
            f" Only the newest {total['drives']} drives were read, so the total and the oldest"
            " day may be short."
        )
    card = readings_card(
        "Drives",
        items,
        period=f"{len(ordered)} day{'s' if len(ordered) != 1 else ''} with drives",
        note=note,
        lead=0,
    )
    card["more"] = max(len(ordered) - len(shown), 0)
    return card
