"""The two-weekly adjustment, proposed and never applied (fat-loss ticket 06). Pure: no ORM.

- **Window**: the 28 full household-local days ending yesterday. **Kevin, 2026-11-06: weigh-ins
  are weekly** (extra ones still count), so the evidence is **4 weekly points**, about 28 days,
  where it was 14 days with 8 weigh-ins.
- **Evidence gate**: at least 22 days with a meal's kcal logged (the old 11 of 14, scaled), a
  weigh-in in each of the window's four weeks, and the window starting at least 7 days after the
  first weigh-in (the water week). When it holds, the energy estimate's maintenance comes from the
  trend (`basis: "trend"`).
- **Proposal gate**: the evidence gate, plus a current meal target and no proposal made in the
  last 14 days (any status). Below it there is no proposal and nothing says why unprompted.
- **Observed maintenance** = mean logged intake + trend loss (kg) x 7700 / 28.
- **Observed rate**: the trend's change across the window, % of bodyweight per week, negative is
  losing. Losing 0.5 to 1.0 %: no proposal.
- **Off band**: desired = observed maintenance - the profile rate's deficit; proposed = desired
  clamped to the current target +/- 150 kcal. At or below 800 kcal, or equal to the current
  target after rounding: no proposal.

Every number is carried unrounded and rounded once, in `propose`.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

from body_coach.energy import FLOOR_KCAL, KCAL_PER_KG, deficit_kcal
from body_coach.trend import in_band, lbs_to_kg, rate_between

WINDOW_DAYS = 28
MIN_INTAKE_DAYS = 22
WEEKS_IN_WINDOW = 4
MIN_WEIGH_IN_WEEKS = 4
WATER_WEEK_DAYS = 7
STEP_KCAL = 150
COOLDOWN_DAYS = 14


@dataclass(frozen=True)
class Window:
    start: date
    end: date

    def days(self):
        return [self.start + timedelta(days=i) for i in range((self.end - self.start).days + 1)]

    def weeks(self) -> list[tuple[date, date]]:
        """The window's four 7-day weeks, oldest first, each (first day, last day)."""
        return [
            (self.start + timedelta(days=7 * i), self.start + timedelta(days=7 * i + 6))
            for i in range(WEEKS_IN_WINDOW)
        ]


def window_for(today: date) -> Window:
    return Window(start=today - timedelta(days=WINDOW_DAYS), end=today - timedelta(days=1))


@dataclass(frozen=True)
class Evidence:
    window: Window
    intake_days: int
    weigh_in_days: int
    mean_intake_kcal: float | None
    first_weigh_in: date | None
    trend_start_lbs: float | None  # the trend at the end of the day before the window
    trend_end_lbs: float | None  # the trend at the end of the window's last day
    # Of the window's four 7-day weeks, how many hold at least one weigh-in.
    weigh_in_weeks: int = 0


def evidence_met(e: Evidence) -> bool:
    return (
        e.intake_days >= MIN_INTAKE_DAYS
        and e.weigh_in_weeks >= MIN_WEIGH_IN_WEEKS
        and e.first_weigh_in is not None
        and e.window.start >= e.first_weigh_in + timedelta(days=WATER_WEEK_DAYS)
        and e.mean_intake_kcal is not None
        and e.trend_start_lbs is not None
        and e.trend_end_lbs is not None
    )


def observed_maintenance_kcal(e: Evidence) -> float:
    loss_kg = lbs_to_kg(e.trend_start_lbs - e.trend_end_lbs)
    return e.mean_intake_kcal + loss_kg * KCAL_PER_KG / WINDOW_DAYS


def observed_rate_pct(e: Evidence) -> float:
    return rate_between(e.trend_start_lbs, e.trend_end_lbs, WINDOW_DAYS).pct_per_week


@dataclass(frozen=True)
class Proposal:
    """Everything an `energy_proposals` row stores, rounded once."""

    window_start: date
    window_end: date
    intake_days: int
    weigh_in_days: int
    mean_intake_kcal: float
    trend_start_lbs: float
    trend_end_lbs: float
    observed_maintenance_kcal: int
    observed_rate_bp: int
    target_rate_bp: int
    current_target_kcal: int
    proposed_target_kcal: int


def propose(
    e: Evidence,
    *,
    target_rate_bp: int,
    current_target_kcal: int | None,
    made_recently: bool,
) -> Proposal | None:
    """The proposal the evidence calls for, or None (under the gate, in band, clamped to the
    current target, or at the floor)."""
    if not evidence_met(e) or current_target_kcal is None or made_recently:
        return None
    rate = observed_rate_pct(e)
    if in_band(rate):
        return None
    maintenance = observed_maintenance_kcal(e)
    desired = maintenance - deficit_kcal(target_rate_bp / 100, lbs_to_kg(e.trend_end_lbs))
    clamped = min(max(desired, current_target_kcal - STEP_KCAL), current_target_kcal + STEP_KCAL)
    proposed = int(round(clamped))
    if proposed <= FLOOR_KCAL or proposed == current_target_kcal:
        return None
    return Proposal(
        window_start=e.window.start,
        window_end=e.window.end,
        intake_days=e.intake_days,
        weigh_in_days=e.weigh_in_days,
        mean_intake_kcal=e.mean_intake_kcal,
        trend_start_lbs=e.trend_start_lbs,
        trend_end_lbs=e.trend_end_lbs,
        observed_maintenance_kcal=int(round(maintenance)),
        observed_rate_bp=int(round(rate * 100)),
        target_rate_bp=target_rate_bp,
        current_target_kcal=current_target_kcal,
        proposed_target_kcal=proposed,
    )


def proposal_words(*, current: int, proposed: int, rate_pct: float, maintenance: int) -> str:
    direction = "lower" if proposed < current else "raise"
    if rate_pct < 0:
        pace = f"losing {abs(rate_pct):.2f}% of bodyweight a week"
    elif rate_pct > 0:
        pace = f"gaining {rate_pct:.2f}% of bodyweight a week"
    else:
        pace = "holding steady"
    return (
        f"Over the last four weeks your trend says you were {pace}, outside the 0.5 to 1.0% band, "
        f"on an estimated maintenance of {maintenance} kcal. Proposed: {direction} the daily "
        f"target from {current} to {proposed} kcal. An estimate; nothing changes unless you "
        f"accept it."
    )
