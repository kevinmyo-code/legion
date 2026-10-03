"""Expanding a repeating event into its occurrences, on the engine (spec D4).

**One algorithm, one set of test vectors.** The phone's
`app/src/main/java/com/kevin/legion/notes/Recurrence.kt` is the reference,
and this is a port of it, rule for rule. The web's `src/lib/recurrence.ts` is
the third reader. All three are held to
`server/tests/fixtures/recurrence_vectors.json`, whose vectors are
transcribed from the phone's own `RecurrenceTest` and `RecurrenceZoneTest`
(each names its Kotlin test); `tests/test_recurrence.py` fails on any vector
this module gets wrong. Push reminders (spec D7) are the first server reader.

What the reference does, kept exactly:

- **Every date is computed in a caller-supplied zone.** The phone pinned its
  day-maths to UTC once and a Monday 00:30 Tokyo series landed on Tuesdays;
  `zone` is a required argument here so no caller can inherit that default.
- **Time of day is a local wall-clock time**, recomposed on each date, so a
  7am daily reminder stays 7am across a DST change. A wall-clock time that
  does not exist on a spring-forward date resolves forward by the gap, the
  way Java's `atZone` does (Python's `fold=0` lands on the same instant).
- **Skips are subtracted during expansion and still count** toward an
  `AFTER_COUNT` end: skipping one of five does not buy a sixth.
- **Clamp, never roll over**: monthly on the 31st fires on the 30th in a
  30-day month; yearly on 29 February fires on the 28th in a common year.
- **A rule that cannot advance yields nothing** (`every <= 0`, no weekdays, a
  day outside 1-31, an unparsable weekday), never a hang and never an error.
- The window is inclusive at both ends, in instants; `ON_DATE` is inclusive,
  as a local date.
"""
from __future__ import annotations

import calendar
import datetime as dt
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from zoneinfo import ZoneInfo

SAFETY_CAP = 100_000

_WEEKDAYS = {
    name: index
    for index, name in enumerate(
        ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
    )
}
_WEEKDAY_ABBREVIATIONS = {name[:3]: index for name, index in _WEEKDAYS.items()}


@dataclass(frozen=True)
class Rule:
    kind: str  # DAILY | WEEKLY | MONTHLY_ON_DATE | YEARLY
    every: int = 1
    days: tuple[int, ...] = ()  # Monday = 0, ascending
    day: int | None = None
    month: int | None = None


@dataclass(frozen=True)
class End:
    kind: str = "NEVER"  # NEVER | ON_DATE | AFTER_COUNT
    on_date: dt.date | None = None
    count: int | None = None


def parse_weekdays(spec: str | None) -> tuple[int, ...] | None:
    """"MON,WED" or "MONDAY,WEDNESDAY", any case. Empty for blank; None on any
    token it does not recognise (`NotesLogic.parseWeekdays`)."""
    if spec is None or not spec.strip():
        return ()
    days: set[int] = set()
    for token in (t.strip().upper() for t in spec.split(",")):
        if not token:
            continue
        index = _WEEKDAY_ABBREVIATIONS.get(token, _WEEKDAYS.get(token))
        if index is None:
            return None
        days.add(index)
    return tuple(sorted(days))


def _get(series, name):
    return series.get(name) if isinstance(series, dict) else getattr(series, name, None)


def rule_from_series(series) -> Rule | None:
    """The stored `repeat_*` fields as a rule, or None when the series does
    not repeat or a required field is missing (`NotesLogic.ruleFromItem`:
    "a defensively-corrupt row should read as not recurring")."""
    kind = _get(series, "repeat_kind")
    every = _get(series, "repeat_every")
    day = _get(series, "repeat_day")
    month = _get(series, "repeat_month")
    if kind == "DAILY":
        return None if every is None else Rule("DAILY", every=every)
    if kind == "WEEKLY":
        days = parse_weekdays(_get(series, "repeat_days_of_week"))
        if every is None or not days:
            return None
        return Rule("WEEKLY", every=every, days=days)
    if kind == "MONTHLY_ON_DATE":
        if every is None or day is None:
            return None
        return Rule("MONTHLY_ON_DATE", every=every, day=day)
    if kind == "YEARLY":
        if month is None or day is None:
            return None
        return Rule("YEARLY", day=day, month=month)
    return None


def end_from_series(series) -> End:
    """`NotesLogic.endFromItem`: NEVER unless a complete end is stored."""
    kind = _get(series, "repeat_end_kind")
    if kind == "ON_DATE":
        on_date = _get(series, "repeat_end_date")
        if isinstance(on_date, str):
            on_date = dt.date.fromisoformat(on_date)
        return End("ON_DATE", on_date=on_date) if on_date is not None else End()
    if kind == "AFTER_COUNT":
        count = _get(series, "repeat_end_count")
        return End("AFTER_COUNT", count=count) if count is not None else End()
    return End()


def _well_formed(rule: Rule) -> bool:
    if rule.kind == "DAILY":
        return rule.every >= 1
    if rule.kind == "WEEKLY":
        return rule.every >= 1 and bool(rule.days)
    if rule.kind == "MONTHLY_ON_DATE":
        return rule.every >= 1 and rule.day is not None and 1 <= rule.day <= 31
    if rule.kind == "YEARLY":
        return (
            rule.month is not None
            and 1 <= rule.month <= 12
            and rule.day is not None
            and 1 <= rule.day <= 31
        )
    return False


def _add_months(year: int, month: int, months: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + months
    return total // 12, total % 12 + 1


def _clamped(year: int, month: int, day: int) -> dt.date:
    return dt.date(year, month, min(day, calendar.monthrange(year, month)[1]))


def _candidates(rule: Rule, start: dt.date) -> Iterator[dt.date]:
    """Ascending dates on or after `start` where `rule` fires, lazily
    (`Recurrence.candidateDates`)."""
    k = 0
    if rule.kind == "DAILY":
        while True:
            yield start + dt.timedelta(days=k * rule.every)
            k += 1
    elif rule.kind == "WEEKLY":
        week_monday = start - dt.timedelta(days=start.weekday())
        while True:
            monday = week_monday + dt.timedelta(days=k * rule.every * 7)
            for weekday in rule.days:
                candidate = monday + dt.timedelta(days=weekday)
                if candidate >= start:
                    yield candidate
            k += 1
    elif rule.kind == "MONTHLY_ON_DATE":
        while True:
            year, month = _add_months(start.year, start.month, k * rule.every)
            candidate = _clamped(year, month, rule.day)
            if candidate >= start:
                yield candidate
            k += 1
    elif rule.kind == "YEARLY":
        while True:
            candidate = _clamped(start.year + k, rule.month, rule.day)
            if candidate >= start:
                yield candidate
            k += 1


def _at(date: dt.date, time_of_day: dt.time, zone: ZoneInfo) -> dt.datetime:
    return dt.datetime.combine(date, time_of_day, tzinfo=zone).astimezone(dt.UTC)


def occurrences_in_window(
    starts_at: dt.datetime,
    rule: Rule,
    end: End,
    skipped_dates: Iterable[dt.date],
    window_start: dt.datetime,
    window_end: dt.datetime,
    zone: ZoneInfo,
) -> list[dt.datetime]:
    """Every occurrence of `rule` from `starts_at` (aware) within the
    inclusive window, as aware UTC instants, ascending. `skipped_dates` are
    LOCAL dates in `zone`. `Recurrence.occurrencesInWindow`, line for line."""
    if window_end < window_start or not _well_formed(rule):
        return []
    start_local = starts_at.astimezone(zone)
    start_date = start_local.date()
    time_of_day = start_local.time().replace(tzinfo=None)
    window_end_date = window_end.astimezone(zone).date()
    skipped = set(skipped_dates)
    max_count = end.count if end.kind == "AFTER_COUNT" else None
    cutoff = end.on_date if end.kind == "ON_DATE" else None

    result: list[dt.datetime] = []
    index = 0
    for iterations, date in enumerate(_candidates(rule, start_date), start=1):
        if iterations > SAFETY_CAP:
            break
        if max_count is not None and index >= max_count:
            break
        if cutoff is not None and date > cutoff:
            break
        if date > window_end_date:
            break
        index += 1  # counts even a skipped occurrence
        occurrence = _at(date, time_of_day, zone)
        if window_start <= occurrence <= window_end and date not in skipped:
            result.append(occurrence)
    return result


def series_occurrences(
    series,
    skipped_dates: Iterable[dt.date],
    window_start: dt.datetime,
    window_end: dt.datetime,
    zone: ZoneInfo,
) -> list[dt.datetime]:
    """Occurrences of one stored event (a model instance or a dict of its
    fields) in the window. A one-off event is its own single occurrence; an
    event with no start, or a repeat that cannot be read, has none."""
    starts_at = _get(series, "starts_at")
    if isinstance(starts_at, str):
        starts_at = dt.datetime.fromisoformat(starts_at.replace("Z", "+00:00"))
    if starts_at is None:
        return []
    skipped = set(skipped_dates)
    if _get(series, "repeat_kind") is None:
        local_date = starts_at.astimezone(zone).date()
        if window_start <= starts_at <= window_end and local_date not in skipped:
            return [starts_at.astimezone(dt.UTC)]
        return []
    rule = rule_from_series(series)
    if rule is None:
        return []
    return occurrences_in_window(
        starts_at, rule, end_from_series(series), skipped, window_start, window_end, zone
    )
