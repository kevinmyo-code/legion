"""The Monday weekly weight line's facts (fat-loss map decision 6, ticket 13). Pure: no ORM.

CLAUDE.md section 7's compulsion test binds this line: (a) it is anchored to his own trend and the
goal he set; (c) **the facts never mention absence, missed weigh-ins, logging, days, streaks or
engagement** - they state where the trend is, not how often he showed up; (d) one instruction
("stop the weekly weight line") sets `weekly_line_on` false and it stops.

`reason` is for the phone's raise registry, never spoken.
"""

from __future__ import annotations

from dataclasses import dataclass

from body_coach.trend import BAND_HIGH_PCT, BAND_LOW_PCT, MM_PER_INCH, in_band

MIN_WEIGH_IN_DAYS = 8
WINDOW_DAYS = 14


@dataclass(frozen=True)
class WeeklyInputs:
    weekly_line_on: bool
    weigh_in_days_last_14: int
    trend_lbs: float | None
    rate_pct_per_week: float | None
    rate_lbs_per_week: float | None
    goal_remaining_lbs: float | None
    goal_target_lbs: float | None
    waist_change_mm: int | None


def weekly_line(i: WeeklyInputs) -> dict:
    if not i.weekly_line_on:
        return {
            "say": False,
            "facts": "",
            "reason": "The weekly weight line is turned off on the profile.",
        }
    if i.weigh_in_days_last_14 < MIN_WEIGH_IN_DAYS:
        return {
            "say": False,
            "facts": "",
            "reason": (
                f"Fewer than {MIN_WEIGH_IN_DAYS} of the last {WINDOW_DAYS} local days carry a "
                f"weigh-in, so the trend is too thin to state."
            ),
        }
    if i.rate_pct_per_week is None or i.trend_lbs is None:
        return {
            "say": False,
            "facts": "",
            "reason": "There is no two-week rate yet, so there is nothing to state.",
        }
    return {"say": True, "facts": facts(i), "reason": ""}


def facts(i: WeeklyInputs) -> str:
    parts = [f"Trend weight {i.trend_lbs:.1f} lbs (an estimate from the smoothed trend)."]
    pct, lbs = i.rate_pct_per_week, i.rate_lbs_per_week
    if pct < 0:
        moving = f"Losing {abs(lbs):.1f} lbs a week ({abs(pct):.2f}% of bodyweight)"
    elif pct > 0:
        moving = f"Gaining {abs(lbs):.1f} lbs a week ({abs(pct):.2f}% of bodyweight)"
    else:
        moving = "Holding steady"
    where = "inside" if in_band(pct) else "outside"
    parts.append(f"{moving}, {where} the {BAND_LOW_PCT:.1f} to {BAND_HIGH_PCT:.1f}% loss band.")
    if i.goal_remaining_lbs is not None and i.goal_target_lbs is not None:
        if i.goal_remaining_lbs <= 0:
            parts.append(f"The goal of {i.goal_target_lbs:.1f} lbs is reached.")
        else:
            parts.append(
                f"{i.goal_remaining_lbs:.1f} lbs to go to the goal of {i.goal_target_lbs:.1f} lbs."
            )
    if i.waist_change_mm is not None:
        inches = i.waist_change_mm / MM_PER_INCH
        if abs(inches) < 0.05:
            parts.append("Waist unchanged over the last four weeks.")
        else:
            way = "down" if inches < 0 else "up"
            parts.append(f"Waist {way} {abs(inches):.1f} in over the last four weeks.")
    return " ".join(parts)
