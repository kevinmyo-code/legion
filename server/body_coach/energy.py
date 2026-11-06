"""The energy estimate (fat-loss ticket 04). Pure: no ORM; the view gathers rows and calls this.

- **BMR, Mifflin-St Jeor:** `10 kg + 6.25 cm - 5 age + 5` (male) or `- 161` (female).
- **Maintenance** = BMR x the activity factor (`basis: "formula"`), or the observed maintenance
  from the person's own trend when the adaptation gate's evidence is there (`basis: "trend"`,
  ticket 06); the formula figure is returned beside it either way.
- **Suggested target** = maintenance - `rate% x weight_kg x 7700 / 7 / 100`, the rate clamped to
  0.25 to 1.0 %BW/week. **At or below 800 kcal it is not suggested at all** (the plan
  generator's hard floor, `GoalPlanAgent.HARD_FLOOR_CALORIES_KCAL`): the figure is dropped and the
  words say why, never raised to 801.
- **Protein** = g/kg x min(goal kg, current kg).
- A missing input produces no figure for what it feeds, and is named; never a guess.

Every figure is an ESTIMATE (CLAUDE.md section 4 rule 5). Floats throughout; `round_estimate`
rounds once, at the end.
"""

from __future__ import annotations

from dataclasses import dataclass

from body_coach.models import (
    ACTIVITY_FACTORS,
    PROTEIN_TENTHS_DEFAULT,
    SEX_MALE,
    TARGET_RATE_BP_DEFAULT,
    TARGET_RATE_BP_MAX,
    TARGET_RATE_BP_MIN,
)
from body_coach.trend import lbs_to_kg

KCAL_PER_KG = 7700
FLOOR_KCAL = 800
BASIS_DAYS = 28

MISSING_HEIGHT = "height"
MISSING_SEX = "sex"
MISSING_BIRTH_YEAR = "birth year"
MISSING_ACTIVITY = "activity level"
MISSING_WEIGH_IN = "a weigh-in"

FORMULA_WORDS = "from the Mifflin-St Jeor formula and your activity level"
TREND_WORDS = "from your own trend, 28 days"


@dataclass(frozen=True)
class ProfileInputs:
    """What the calculator reads from a `body_profiles` row; every field may be absent."""

    height_mm: int | None = None
    sex: str | None = None
    birth_year: int | None = None
    activity_level: str | None = None
    target_rate_bp: int = TARGET_RATE_BP_DEFAULT
    protein_tenths_g_per_kg: int = PROTEIN_TENTHS_DEFAULT


def clamp_rate_bp(bp: int) -> int:
    return min(max(bp, TARGET_RATE_BP_MIN), TARGET_RATE_BP_MAX)


def missing_inputs(profile: ProfileInputs | None, has_weight: bool) -> list[str]:
    """What the formula still needs, in words, in a fixed order."""
    p = profile or ProfileInputs()
    missing = []
    if p.height_mm is None:
        missing.append(MISSING_HEIGHT)
    if p.sex is None:
        missing.append(MISSING_SEX)
    if p.birth_year is None:
        missing.append(MISSING_BIRTH_YEAR)
    if p.activity_level is None:
        missing.append(MISSING_ACTIVITY)
    if not has_weight:
        missing.append(MISSING_WEIGH_IN)
    return missing


def bmr_kcal(*, weight_kg: float, height_cm: float, age: int, sex: str) -> float:
    base = 10 * weight_kg + 6.25 * height_cm - 5 * age
    return base + 5 if sex == SEX_MALE else base - 161


def deficit_kcal(rate_pct: float, weight_kg: float) -> float:
    """The daily deficit that loses `rate_pct` % of bodyweight a week."""
    return rate_pct * weight_kg * KCAL_PER_KG / 7 / 100


def suggested_target_kcal(maintenance: float, rate_pct: float, weight_kg: float) -> float | None:
    """None at or below the 800 kcal floor."""
    target = maintenance - deficit_kcal(rate_pct, weight_kg)
    return target if round(target) > FLOOR_KCAL else None


def protein_g(
    g_per_kg: float, current_kg: float, goal_kg: float | None
) -> tuple[float, str]:
    """(grams, basis): against the goal weight when it is lower, else the current weight."""
    if goal_kg is not None and goal_kg < current_kg:
        return g_per_kg * goal_kg, "goal weight"
    return g_per_kg * current_kg, "current weight"


@dataclass(frozen=True)
class Observed:
    """The trend-based maintenance and the window it came from (ticket 06)."""

    maintenance_kcal: float
    days: int = BASIS_DAYS


def _r1(value: float | None) -> float | None:
    return None if value is None else round(value, 1)


def _r(value: float | None) -> int | None:
    return None if value is None else int(round(value))


def estimate(
    *,
    profile: ProfileInputs | None,
    this_year: int,
    weight_lbs: float | None,
    weight_source: str | None,
    goal_lbs: float | None,
    observed: Observed | None,
) -> dict:
    """The wire body of `GET energy/` less `current_target` and `proposal`, which the view adds."""
    p = profile or ProfileInputs()
    rate_bp = clamp_rate_bp(p.target_rate_bp)
    rate_pct = rate_bp / 100
    g_per_kg = p.protein_tenths_g_per_kg / 10
    missing = missing_inputs(profile, weight_lbs is not None)
    weight_kg = lbs_to_kg(weight_lbs) if weight_lbs is not None else None
    factor = ACTIVITY_FACTORS.get(p.activity_level) if p.activity_level else None

    bmr = None
    formula = None
    formula_ready = weight_kg is not None and not (
        set(missing) - {MISSING_WEIGH_IN}
    )
    if formula_ready:
        bmr = bmr_kcal(
            weight_kg=weight_kg,
            height_cm=p.height_mm / 10,
            age=this_year - p.birth_year,
            sex=p.sex,
        )
        formula = bmr * factor

    if observed is not None:
        maintenance, basis, basis_days, basis_words = (
            observed.maintenance_kcal, "trend", observed.days, TREND_WORDS,
        )
    elif formula is not None:
        maintenance, basis, basis_days, basis_words = formula, "formula", None, FORMULA_WORDS
    else:
        maintenance, basis, basis_days = None, None, None
        basis_words = "No maintenance estimate yet: " + _need_words(missing) + "."

    target = None
    floor_note = ""
    if maintenance is not None and weight_kg is not None:
        target = suggested_target_kcal(maintenance, rate_pct, weight_kg)
        if target is None:
            floor_note = (
                f" No target is suggested: at {rate_pct:.2f}% a week it would be at or below "
                f"{FLOOR_KCAL} kcal, which is a medically supervised intervention, not a "
                f"self-directed target."
            )

    protein = None
    protein_basis = None
    if weight_kg is not None:
        goal_kg = lbs_to_kg(goal_lbs) if goal_lbs is not None else None
        protein, protein_basis = protein_g(g_per_kg, weight_kg, goal_kg)

    words = _words(basis, basis_words, missing, target is not None, rate_pct) + floor_note
    return {
        "estimate": True,
        "basis": basis,
        "basis_days": basis_days,
        "basis_words": basis_words,
        "missing": missing,
        "weight_lbs": _r1(weight_lbs),
        "weight_source": weight_source if weight_lbs is not None else None,
        "bmr_kcal": _r(bmr),
        "activity_level": p.activity_level,
        "activity_factor": factor,
        "formula_maintenance_kcal": _r(formula),
        "observed_maintenance_kcal": _r(observed.maintenance_kcal) if observed else None,
        "maintenance_kcal": _r(maintenance),
        "target_rate_pct": round(rate_pct, 2),
        "suggested_target_kcal": _r(target),
        "protein_g": _r(protein),
        "protein_g_per_kg": round(g_per_kg, 1),
        "protein_basis": protein_basis,
        "words": words,
    }


def _need_words(missing: list[str]) -> str:
    if not missing:
        return "nothing is missing"
    if len(missing) == 1:
        return f"it needs {missing[0]}"
    return f"it needs {', '.join(missing[:-1])} and {missing[-1]}"


def _words(basis, basis_words, missing, has_target, rate_pct) -> str:
    if basis is None:
        return (
            f"Every figure here is an estimate, and there is no maintenance figure yet: "
            f"{_need_words(missing)}."
        )
    target = (
        f" The suggested target is for losing {rate_pct:.2f}% of bodyweight a week."
        if has_target
        else ""
    )
    return f"Every figure here is an estimate. Maintenance is {basis_words}.{target}"
