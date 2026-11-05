"""The income plan as dated payments (household-finance ticket 07).

`occurrences(line, start, end)` lists the days one `IncomePlan` line pays
inside `[start, end]`. A monthly line pays on its start day's day of month (the
1st when it has no start; clamped to a short month's last day); weekly and
biweekly lines step from their start day; a yearly line on its start day's
anniversary; a `once` line on its start day. Nothing pays before `start_on` or
after `end_on`.

Scenarios: `expected` counts confirmed and expected lines; `fallback` counts
confirmed and fallback lines, as if every expected line never arrived. Every
figure built on the plan is an ESTIMATE: it is what the household expects, not
what a bank has stated.
"""

from __future__ import annotations

import calendar
import datetime as dt
from collections.abc import Iterable

SCENARIOS = {
    "expected": frozenset({"confirmed", "expected"}),
    "fallback": frozenset({"confirmed", "fallback"}),
}


def _clamped(year: int, month: int, day: int) -> dt.date:
    return dt.date(year, month, min(day, calendar.monthrange(year, month)[1]))


def occurrences(line, start: dt.date, end: dt.date) -> list[dt.date]:
    first = max(start, line.start_on) if line.start_on else start
    last = min(end, line.end_on) if line.end_on else end
    if first > last:
        return []
    cadence = line.cadence
    out: list[dt.date] = []
    if cadence == "once":
        if line.start_on and first <= line.start_on <= last:
            out.append(line.start_on)
    elif cadence in ("weekly", "biweekly"):
        if line.start_on is None:
            return []
        step = 7 if cadence == "weekly" else 14
        day = line.start_on
        if day < first:
            day += dt.timedelta(days=((first - day).days + step - 1) // step * step)
        while day <= last:
            out.append(day)
            day += dt.timedelta(days=step)
    elif cadence == "monthly":
        anchor = line.start_on.day if line.start_on else 1
        year, month = first.year, first.month
        while True:
            day = _clamped(year, month, anchor)
            if day > last:
                break
            if day >= first:
                out.append(day)
            year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    elif cadence == "yearly":
        if line.start_on is None:
            return []
        for year in range(first.year, last.year + 1):
            day = _clamped(year, line.start_on.month, line.start_on.day)
            if first <= day <= last:
                out.append(day)
    return out


def payments(lines: Iterable, start: dt.date, end: dt.date, scenario: str = "expected"):
    """[(day, line)] for every payment in the window, in date order."""
    wanted = SCENARIOS[scenario]
    out = [
        (day, line)
        for line in lines
        if line.confidence in wanted
        for day in occurrences(line, start, end)
    ]
    return sorted(out, key=lambda pair: (pair[0], pair[1].key))


def month_bounds(year: int, month: int) -> tuple[dt.date, dt.date]:
    return dt.date(year, month, 1), dt.date(year, month, calendar.monthrange(year, month)[1])


def add_months(day: dt.date, months: int) -> dt.date:
    total = day.year * 12 + day.month - 1 + months
    return dt.date(total // 12, total % 12 + 1, 1)
