"""The smoothed weight trend, its rate, and milestone progress (fat-loss ticket 05). Pure: no ORM.

- **One value per household-local day**: the mean of that day's readings, each converted to lbs
  first (a kg reading is converted, never averaged raw against a lbs one).
- **EWMA**: seeded with the first day's mean; on a day with a reading `trend += 0.1 x (w - trend)`;
  carried unchanged over a gap day.
- **Rate**: the trend's change over the last 14 days, as percent of bodyweight per week (and lbs
  per week). Negative is losing. None until the first reading is at least 14 days old.
- **Early water**: while the first reading is under 21 days old the first-week water drop is
  flagged, never extrapolated: no milestone ETA is projected.

Nothing is rounded here; the views round once, at the edge.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta

KG_PER_LB = 0.45359237
MM_PER_INCH = 25.4
LBS_PER_KG = 1 / KG_PER_LB
EWMA_ALPHA = 0.1
RATE_DAYS = 14
EARLY_WATER_DAYS = 21
BAND_LOW_PCT = 0.5
BAND_HIGH_PCT = 1.0

KG_UNITS = frozenset({"kg", "kgs", "kilogram", "kilograms"})
LBS_UNITS = frozenset({"lb", "lbs", "pound", "pounds"})


def to_lbs(value: float, unit: str | None) -> float | None:
    """A weight in lbs, or None for a unit this cannot read. No unit is read as lbs, the
    engine's wire unit."""
    name = (unit or "lbs").strip().lower()
    if name in KG_UNITS:
        return value * LBS_PER_KG
    if name in LBS_UNITS:
        return value
    return None


def lbs_to_kg(lbs: float) -> float:
    return lbs * KG_PER_LB


def daily_means(readings) -> dict[date, float]:
    """`readings` is (local_day, lbs) pairs; the result is each day's mean in lbs."""
    sums: dict[date, list[float]] = defaultdict(list)
    for day, lbs in readings:
        sums[day].append(lbs)
    return {day: sum(values) / len(values) for day, values in sums.items()}


@dataclass(frozen=True)
class Point:
    day: date
    weight_lbs: float | None
    trend_lbs: float


def ewma(daily: dict[date, float], through: date) -> list[Point]:
    """One point per local day from the first reading through `through`, gaps carried."""
    if not daily:
        return []
    first = min(daily)
    last = max(through, max(daily))
    points: list[Point] = []
    trend = daily[first]
    day = first
    while day <= last:
        raw = daily.get(day)
        if raw is not None and day != first:
            trend = trend + EWMA_ALPHA * (raw - trend)
        points.append(Point(day=day, weight_lbs=raw, trend_lbs=trend))
        day += timedelta(days=1)
    return points


def trend_on(points: list[Point], day: date) -> float | None:
    """The trend value at the end of `day`, or None before the first reading."""
    if not points or day < points[0].day:
        return None
    index = (day - points[0].day).days
    if index >= len(points):
        return points[-1].trend_lbs
    return points[index].trend_lbs


@dataclass(frozen=True)
class Rate:
    pct_per_week: float
    lbs_per_week: float
    start_lbs: float
    end_lbs: float


def rate_between(start_lbs: float, end_lbs: float, days: int) -> Rate:
    weeks = days / 7
    change = end_lbs - start_lbs
    return Rate(
        pct_per_week=change / start_lbs * 100 / weeks,
        lbs_per_week=change / weeks,
        start_lbs=start_lbs,
        end_lbs=end_lbs,
    )


def rate(points: list[Point], today: date) -> Rate | None:
    """The trend's change over the 14 days ending `today`, or None with too little history."""
    if not points:
        return None
    start_day = today - timedelta(days=RATE_DAYS)
    if points[0].day > start_day:
        return None
    start, end = trend_on(points, start_day), trend_on(points, today)
    if start is None or end is None:
        return None
    return rate_between(start, end, RATE_DAYS)


def in_band(rate_pct_per_week: float | None) -> bool | None:
    """True when losing 0.5 to 1.0 % of bodyweight a week."""
    if rate_pct_per_week is None:
        return None
    loss = -rate_pct_per_week
    return BAND_LOW_PCT <= loss <= BAND_HIGH_PCT


def early_water(first_day: date | None, today: date) -> bool:
    return first_day is not None and (today - first_day).days < EARLY_WATER_DAYS


@dataclass(frozen=True)
class Milestone:
    goal_id: str
    statement: str
    target_lbs: float
    start_lbs: float
    remaining_lbs: float
    progress_pct: float
    weeks_to_go: float | None


def milestone(
    *,
    goal_id: str,
    statement: str,
    target_lbs: float,
    start_lbs: float,
    current_lbs: float,
    rate_lbs_per_week: float | None,
    water: bool,
) -> Milestone:
    """Progress from the first trend point toward the goal weight. `weeks_to_go` only when the
    rate is toward the goal and the early-water flag is down."""
    losing_goal = target_lbs <= start_lbs
    remaining = current_lbs - target_lbs if losing_goal else target_lbs - current_lbs
    remaining = max(remaining, 0.0)
    span = abs(start_lbs - target_lbs)
    if span == 0:
        progress = 100.0
    else:
        done = (start_lbs - current_lbs) if losing_goal else (current_lbs - start_lbs)
        progress = min(max(done / span * 100, 0.0), 100.0)
    weeks = None
    if rate_lbs_per_week is not None and not water and remaining > 0:
        toward = rate_lbs_per_week < 0 if losing_goal else rate_lbs_per_week > 0
        if toward:
            weeks = remaining / abs(rate_lbs_per_week)
    return Milestone(
        goal_id=goal_id,
        statement=statement,
        target_lbs=target_lbs,
        start_lbs=start_lbs,
        remaining_lbs=remaining,
        progress_pct=progress,
        weeks_to_go=weeks,
    )


WAIST_BASELINE_MIN_DAYS = 28
WAIST_BASELINE_MAX_DAYS = 42


def waist_change_mm(readings) -> int | None:
    """Latest waist minus the latest reading 28 to 42 days before it, in mm; None without one.
    `readings` is (measured_on, waist_mm) pairs in any order."""
    rows = sorted(readings)
    if not rows:
        return None
    latest_day, latest_mm = rows[-1]
    baseline = [
        (day, mm)
        for day, mm in rows
        if WAIST_BASELINE_MIN_DAYS <= (latest_day - day).days <= WAIST_BASELINE_MAX_DAYS
    ]
    if not baseline:
        return None
    return latest_mm - baseline[-1][1]
