"""The plan: drafted with Alfred, locked in, adjusted as he goes (body coach 2, Kevin 2026-11-06).

*"basically formulating a plan, working together with alfred, then locking it in and making
adjustments as i go with alfred is the goal"*, and *"i want alfred to set my calorie target"*.

- **Draft.** Built from the active plan (or, for the first, today's programme) and the choices
  asked for: the programme's choices (`programme_change.Choices`), a rate, "let Alfred set it".
  **Alfred sets the target inside the draft**: the engine's estimate at the plan's rate. The rate
  is picked by rule: 0.5% of bodyweight a week for "get lean, keep muscle" while lifting, clamped
  0.25 to 1.0 as everywhere. Protein is the existing rule. Nothing goes live in a draft.
- **First plan.** With a complete profile and no plan of any status, reading the plan builds the
  first draft and asks to lock it (the energy proposal's read-that-proposes pattern).
- **Lock.** The draft becomes the active version: today's meal target is written from it (unless
  the target is the member's own), the programme takes its state, the profile its rate. The words
  that say what changed come back only after those writes.
- **Adjust.** Accepting an energy or programme proposal makes the next version of an active plan,
  with a one-line reason, so "what changed in my plan" reads from the history.
- **His own target stays his.** A meal target written after the lock that is not the plan's is his;
  a new draft or version keeps it until he says "let Alfred set it" (`alfred_sets_target`).

Every figure is an estimate (CLAUDE.md section 4 rule 5). Nothing about a real person is here.
"""

from __future__ import annotations

from django.db import transaction
from django.db.models import Max
from django.db.models.functions import Now
from django.utils import timezone

from body_coach import energy, service
from body_coach import programme as rules
from body_coach import programme_change as change
from body_coach.models import (
    PLAN_ACTIVE,
    PLAN_DISCARDED,
    PLAN_DRAFT,
    PLAN_SUPERSEDED,
    TARGET_BY_ALFRED,
    TARGET_BY_MEMBER,
    TARGET_RATE_BP_MAX,
    TARGET_RATE_BP_MIN,
    BodyPlan,
)
from body_coach.trend import lbs_to_kg
from household.tenancy import household_of, visible

# "get lean, keep muscle" while lifting (Kevin, 2026-11-06): 0.5% of bodyweight a week.
LEAN_KEEP_MUSCLE_RATE_BP = 50
PLAN_KEYS = ("rate_pct", "alfred_sets_target")
DEFAULT_NUDGES = {"evening_protein": True, "monday_check_in": True, "plan_proposals": True}


class PlanRefused(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


# =============================================================================
# Reads
# =============================================================================


def _mine(request):
    return visible(BodyPlan, request).filter(owner_user=request.user)


def active(request) -> BodyPlan | None:
    return _mine(request).filter(status=PLAN_ACTIVE).first()


def draft(request) -> BodyPlan | None:
    return _mine(request).filter(status=PLAN_DRAFT).first()


def history(request) -> list[BodyPlan]:
    return list(
        _mine(request).filter(version__isnull=False).order_by("-version")[:20]
    )


def own_target_since_lock(request, plan: BodyPlan | None):
    """The meal target in force today when it is not the plan's: one he set himself after the
    lock (by voice or by hand, the same day or later). Told apart by its figure, since a same-day
    edit keeps the row the lock wrote."""
    if plan is None or plan.locked_at is None or plan.kcal_target is None:
        return None
    current = service.current_target(request, service.today_of(request))
    if current is None or current.calories_kcal == plan.kcal_target:
        return None
    return current


# =============================================================================
# The numbers, from the rules
# =============================================================================


def _figures(request, rate_bp: int) -> dict:
    """kcal and protein at `rate_bp` from the energy estimate (read without proposing), with the
    inputs beside them. Refuses, naming what is missing, when the profile cannot support them."""
    est = service.energy_body(request, propose=False)
    if est["missing"] or est["maintenance_kcal"] is None or est["weight_lbs"] is None:
        need = ", ".join(est["missing"]) or "a maintenance estimate"
        raise PlanRefused(f"Nothing was drafted. The plan's target needs {need} first.")
    weight_kg = lbs_to_kg(est["weight_lbs"])
    target = energy.suggested_target_kcal(est["maintenance_kcal"], rate_bp / 100, weight_kg)
    if target is None:
        raise PlanRefused(
            f"Nothing was drafted. At {rate_bp / 100:.2f}% a week the target would be at or below "
            f"{energy.FLOOR_KCAL} kcal, which is not a self-directed target."
        )
    return {
        "kcal": int(round(target)),
        "protein_g": est["protein_g"],
        "inputs": {
            "maintenance_kcal": est["maintenance_kcal"],
            "basis": est["basis"],
            "basis_words": est["basis_words"],
            "weight_lbs": est["weight_lbs"],
            "rate_bp": rate_bp,
            "deficit_kcal": int(round(energy.deficit_kcal(rate_bp / 100, weight_kg))),
            "lbs_per_week": round(est["weight_lbs"] * rate_bp / 10000, 1),
            "protein_basis": est.get("protein_basis"),
        },
    }


def _rate_bp(data: dict, base: BodyPlan | None) -> int:
    value = data.get("rate_pct")
    if value is None:
        return base.rate_bp if base else LEAN_KEEP_MUSCLE_RATE_BP
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PlanRefused("Nothing was drafted. rate_pct must be a number, e.g. 0.5.")
    bp = int(round(value * 100))
    if not TARGET_RATE_BP_MIN <= bp <= TARGET_RATE_BP_MAX:
        raise PlanRefused(
            f"Nothing was drafted. The rate is {TARGET_RATE_BP_MIN / 100:.2f} to "
            f"{TARGET_RATE_BP_MAX / 100:.2f}% of bodyweight a week; got {value!r}."
        )
    return bp


def _programme_state(request, base: BodyPlan | None) -> change.State:
    if base is not None and base.programme:
        return change.State.from_dict(base.programme)
    from body_coach import workouts

    row = workouts.active_programme(request)
    if row is not None:
        return workouts.state_of(row)
    return change.State(rules.DEFAULT_TEMPLATE, rules.DEFAULT_TRAINING_DAYS)


# =============================================================================
# Draft, lock, discard
# =============================================================================


def build_draft(request, data: dict | None = None, *, reason: str = "") -> BodyPlan:
    """A draft from the active plan and the choices; an older draft is replaced. Writes no
    meal target and changes no programme."""
    if data is not None and not isinstance(data, dict):
        raise PlanRefused("Nothing was drafted. The body must be a JSON object.")
    data = dict(data or {})
    plan_part = {k: data.pop(k) for k in PLAN_KEYS if k in data}
    try:
        choices = change.read_choices(data)
    except change.ChangeRefused as exc:
        raise PlanRefused(exc.message.replace("proposed", "drafted", 1)) from exc
    base = active(request)
    rate = _rate_bp(plan_part, base)
    state = _programme_state(request, base)
    if choices.as_dict():
        try:
            state = change.build_after(state, choices)
        except change.ChangeRefused as exc:
            raise PlanRefused(exc.message.replace("proposed", "drafted", 1)) from exc
    figures = _figures(request, rate)
    own = None if plan_part.get("alfred_sets_target") else own_target_since_lock(request, base)
    if own is None and base is not None and base.target_set_by == TARGET_BY_MEMBER and not plan_part.get(
        "alfred_sets_target"
    ):
        kcal, protein, setter = base.kcal_target, base.protein_g, TARGET_BY_MEMBER
    elif own is not None:
        kcal, protein, setter = own.calories_kcal, int(round(own.protein_g)), TARGET_BY_MEMBER
    else:
        kcal, protein, setter = figures["kcal"], figures["protein_g"], TARGET_BY_ALFRED
    with transaction.atomic():
        _mine(request).filter(status=PLAN_DRAFT).update(status=PLAN_DISCARDED)
        row = BodyPlan.objects.create(
            household=household_of(request),
            owner_user=request.user,
            status=PLAN_DRAFT,
            kcal_target=kcal,
            protein_g=protein,
            rate_bp=rate,
            target_set_by=setter,
            programme=state.as_dict(),
            meal_approach=base.meal_approach if base else "saved_meals",
            nudges=dict(base.nudges) if base and base.nudges else dict(DEFAULT_NUDGES),
            reason=reason or ("first plan" if base is None else _draft_reason(choices, plan_part)),
            inputs=figures["inputs"],
        )
    row.refresh_from_db()
    return row


def _draft_reason(choices: change.Choices, plan_part: dict) -> str:
    bits = [f"{k.replace('_', ' ')}" for k in choices.as_dict()]
    if "rate_pct" in plan_part:
        bits.append(f"rate {plan_part['rate_pct']}% a week")
    if plan_part.get("alfred_sets_target"):
        bits.append("Alfred sets the target")
    return "changed: " + ", ".join(bits) if bits else "redrafted"


def maybe_first_draft(request) -> BodyPlan | None:
    """The first plan: with a complete profile and no plan of any status, build the draft now."""
    if _mine(request).exists():
        return None
    try:
        return build_draft(request)
    except PlanRefused:
        return None


def _get(request, plan_id, verb: str, status: str) -> BodyPlan:
    row = _mine(request).filter(pk=plan_id).first() if plan_id else None
    if row is None and plan_id is None:
        row = _mine(request).filter(status=status).first()
    if row is None:
        raise PlanRefused(f"Nothing was {verb}. There is no {status} plan to {verb[:-2]}.", 404)
    if row.status != status:
        raise PlanRefused(f"Nothing was {verb}. That plan is {row.status}.", 409)
    return row


def _activate(request, row: BodyPlan, *, write_target: bool) -> BodyPlan:
    """Make `row` the active version: number it, supersede the old one, and apply it."""
    from body_coach import workouts

    previous = active(request)
    number = (_mine(request).aggregate(n=Max("version"))["n"] or 0) + 1
    guid = row.meal_target_origin_guid or (previous.meal_target_origin_guid if previous else None)
    if write_target and row.target_set_by == TARGET_BY_ALFRED and row.kcal_target:
        target = service.set_today_target(
            request, kcal=row.kcal_target, protein_g=float(row.protein_g or 0)
        )
        guid = target.origin_guid
    workouts.apply_state(request, change.State.from_dict(row.programme))
    profile = service.own_profile(request)
    if profile is not None and profile.target_rate_bp != row.rate_bp:
        profile.target_rate_bp = row.rate_bp
        profile.updated_at = Now()
        profile.save(update_fields=["target_rate_bp", "updated_at"])
    if previous is not None:
        BodyPlan.objects.filter(pk=previous.pk).update(status=PLAN_SUPERSEDED)
    BodyPlan.objects.filter(pk=row.pk).update(
        status=PLAN_ACTIVE,
        version=number,
        locked_at=timezone.now(),
        locked_by=request.user,
        meal_target_origin_guid=guid,
    )
    row.refresh_from_db()
    return row


def lock(request, plan_id=None) -> BodyPlan:
    with transaction.atomic():
        row = _get(request, plan_id, "locked", PLAN_DRAFT)
        return _activate(request, row, write_target=True)


def discard(request, plan_id=None) -> BodyPlan:
    row = _get(request, plan_id, "discarded", PLAN_DRAFT)
    BodyPlan.objects.filter(pk=row.pk).update(status=PLAN_DISCARDED)
    row.refresh_from_db()
    return row


def new_version(
    request, *, reason: str, kcal: int | None = None, programme: change.State | None = None
):
    """An accepted proposal adjusts the active plan: the next version, with `reason`. Without an
    active plan there is nothing to version and nothing happens. The proposal's own write (the meal
    target, the programme) is already made by its accept; an open draft is discarded, since it was
    drafted against the version this one replaces."""
    base = active(request)
    if base is None:
        return None
    _mine(request).filter(status=PLAN_DRAFT).update(status=PLAN_DISCARDED)
    guid = base.meal_target_origin_guid
    if kcal is not None:
        current = service.current_target(request, service.today_of(request))
        guid = current.origin_guid if current is not None else guid
    row = BodyPlan.objects.create(
        household=base.household,
        owner_user=base.owner_user,
        status=PLAN_DRAFT,
        kcal_target=kcal if kcal is not None else base.kcal_target,
        protein_g=base.protein_g,
        rate_bp=base.rate_bp,
        target_set_by=base.target_set_by,
        programme=programme.as_dict() if programme is not None else base.programme,
        meal_approach=base.meal_approach,
        nudges=base.nudges,
        reason=reason[:300],
        inputs=base.inputs,
        meal_target_origin_guid=guid,
    )
    return _activate(request, row, write_target=False)


# =============================================================================
# Bodies and words
# =============================================================================


def _lbs_week(row: BodyPlan) -> float | None:
    weight = (row.inputs or {}).get("weight_lbs")
    return round(weight * row.rate_bp / 10000, 1) if weight else None


def summary(row: BodyPlan) -> str:
    state = change.State.from_dict(row.programme) if row.programme else None
    food = f"about {row.kcal_target} kcal and {row.protein_g} g protein a day" if row.kcal_target else "no calorie target"
    pace = _lbs_week(row)
    rate = f"{row.rate_bp / 100:.2f}% of bodyweight a week" + (f", about {pace:g} lb" if pace else "")
    training = change.summary(state) if state else "no programme"
    return f"{food} ({rate}); training {training}"


def plan_dict(row: BodyPlan | None) -> dict | None:
    if row is None:
        return None
    return {
        "id": str(row.id),
        "version": row.version,
        "status": row.status,
        "kcal_target": row.kcal_target,
        "protein_g": row.protein_g,
        "rate_pct": round(row.rate_bp / 100, 2),
        "lbs_per_week": _lbs_week(row),
        "target_set_by": row.target_set_by,
        "programme": row.programme,
        "meal_approach": row.meal_approach,
        "nudges": row.nudges,
        "reason": row.reason,
        "inputs": row.inputs,
        "estimate": True,
        "summary": summary(row),
        "locked_at": row.locked_at.isoformat() if row.locked_at else None,
    }


def draft_words(row: BodyPlan) -> str:
    own = (
        " The calorie target is your own and stays; say let Alfred set it to change that."
        if row.target_set_by == TARGET_BY_MEMBER
        else ""
    )
    basis = (row.inputs or {}).get("basis_words")
    tail = f" Estimates, {basis}." if basis else " Estimates."
    return (
        f"Draft plan: {summary(row)}.{own}{tail} Nothing is live until you lock it in."
    )


def lock_words(row: BodyPlan) -> str:
    target = (
        f"Your target is now about {row.kcal_target} kcal and {row.protein_g} g protein a day"
        if row.target_set_by == TARGET_BY_ALFRED
        else f"Your own target of {row.kcal_target} kcal stays"
    )
    pace = _lbs_week(row)
    why = f", to lose about {pace:g} lb a week ({row.rate_bp / 100:.2f}% of bodyweight)" if pace else ""
    state = change.State.from_dict(row.programme)
    return (
        f"Locked in, plan version {row.version}. {target}{why}; estimates. Training: "
        f"{change.summary(state)}."
    )


def history_words(rows: list[BodyPlan]) -> str:
    if not rows:
        return "No plan is locked in yet."
    lines = [
        f"Version {r.version} ({r.locked_at.date().isoformat() if r.locked_at else '?'}): {r.reason or 'no reason given'}."
        for r in rows[:6]
    ]
    return "Plan history, newest first: " + " ".join(lines)


def plan_body(request) -> dict:
    first = maybe_first_draft(request)
    current, pending = active(request), draft(request)
    rows = history(request)
    if pending is not None:
        words = draft_words(pending) + (" This is your first plan." if first else "")
    elif current is not None:
        words = f"Your plan, version {current.version}: {summary(current)}."
        own = own_target_since_lock(request, current)
        if own is not None:
            words += f" The calorie target in force is your own, {own.calories_kcal} kcal."
    else:
        words = (
            "No plan yet. The first draft needs a complete profile: height, sex, birth year, "
            "activity level and a weigh-in."
        )
    return {
        "active": plan_dict(current),
        "draft": plan_dict(pending),
        "history": [
            {"version": r.version, "locked_at": r.locked_at.isoformat() if r.locked_at else None,
             "reason": r.reason, "summary": summary(r)}
            for r in rows
        ],
        "history_words": history_words(rows),
        "words": words,
    }
