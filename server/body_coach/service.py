"""The fat-loss loop's reads and writes: the views gather rows here and hand them to the pure
math (`energy`, `trend`, `adaptation`, `weekly`). One place, so the web, the phone and the engine
tools (which call the routed views) say the same figures.

Every read is scoped to the request's household (`household.tenancy.scoped`); the profile reads
through `visible()` and is always the caller's own. Deleted rows (`deleted_at` set) never count.
Days are household-local (the household's zone, UTC when it has none).
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from django.db import IntegrityError, transaction
from django.db.models.functions import Now
from django.utils import timezone

from body_coach import adaptation, energy, trend, weekly
from body_coach.models import (
    STATUS_ACCEPTED,
    STATUS_DISMISSED,
    STATUS_EXPIRED,
    STATUS_PENDING,
    BodyProfile,
    EnergyProposal,
    WaistLog,
)
from household.tenancy import household_of, scoped, visible
from legacy.enums import Provenance
from legacy.models.body import BodyweightLog, Goal
from legacy.models.pantry import MealLog, MealTarget

WEIGHT_GOAL_METRIC = "bodyweight_lbs"
TRUST_REPORTED = "REPORTED"


# =============================================================================
# Clock and zone
# =============================================================================


def zone_of(request) -> ZoneInfo:
    from household.timezones import zone_named

    return zone_named(getattr(household_of(request), "timezone", None)) or ZoneInfo("UTC")


def today_of(request) -> date:
    return datetime.now(UTC).astimezone(zone_of(request)).date()


def local_day(instant: datetime, zone: ZoneInfo) -> date:
    return instant.astimezone(zone).date()


# =============================================================================
# The profile
# =============================================================================


def own_profile(request) -> BodyProfile | None:
    """The caller's own profile. Another member's is never returned (ADR 0052)."""
    return visible(BodyProfile, request).filter(owner_user=request.user).first()


def profile_inputs(row: BodyProfile | None) -> energy.ProfileInputs | None:
    if row is None:
        return None
    return energy.ProfileInputs(
        height_mm=row.height_mm,
        sex=row.sex,
        birth_year=row.birth_year,
        activity_level=row.activity_level,
        target_rate_bp=row.target_rate_bp,
        protein_tenths_g_per_kg=row.protein_tenths_g_per_kg,
    )


def has_weigh_in(request) -> bool:
    return scoped(BodyweightLog, request).filter(deleted_at__isnull=True).exists()


def profile_body(request, row: BodyProfile | None = None) -> dict:
    row = row if row is not None else own_profile(request)
    inputs = profile_inputs(row) or energy.ProfileInputs()
    return {
        "exists": row is not None,
        "height_cm": round(row.height_mm / 10, 1) if row and row.height_mm is not None else None,
        "sex": row.sex if row else None,
        "birth_year": row.birth_year if row else None,
        "activity_level": row.activity_level if row else None,
        "target_rate_pct": round(inputs.target_rate_bp / 100, 2),
        "protein_g_per_kg": round(inputs.protein_tenths_g_per_kg / 10, 1),
        "weekly_line_on": row.weekly_line_on if row else True,
        "goal_weight_lbs": (
            round(row.goal_weight_lbs, 1) if row and row.goal_weight_lbs is not None else None
        ),
        "missing": energy.missing_inputs(profile_inputs(row), has_weigh_in(request)),
    }


# =============================================================================
# Weight, the trend and the goal
# =============================================================================


def weight_readings(request, zone: ZoneInfo) -> list[tuple[date, float]]:
    rows = scoped(BodyweightLog, request).filter(deleted_at__isnull=True)
    out = []
    for logged_at, value, unit in rows.values_list("logged_at", "weight_value", "weight_unit"):
        lbs = trend.to_lbs(value, unit)
        if lbs is not None:
            out.append((local_day(logged_at, zone), lbs))
    return out


class TrendData:
    """The household's whole trend, from its first reading through today."""

    def __init__(self, request):
        self.zone = zone_of(request)
        self.today = today_of(request)
        self.daily = trend.daily_means(weight_readings(request, self.zone))
        self.points = trend.ewma(self.daily, self.today)
        self.first_day = min(self.daily) if self.daily else None
        self.rate = trend.rate(self.points, self.today)
        self.early_water = trend.early_water(self.first_day, self.today)

    @property
    def latest_trend_lbs(self) -> float | None:
        return trend.trend_on(self.points, self.today)

    @property
    def latest_reading_lbs(self) -> float | None:
        return self.daily[max(self.daily)] if self.daily else None

    def weigh_in_days_between(self, start: date, end: date) -> int:
        return sum(1 for day in self.daily if start <= day <= end)


def active_weight_goal(request):
    """(goal row, target in lbs) for the newest active `bodyweight_lbs` goal, or None."""
    rows = (
        scoped(Goal, request)
        .filter(
            deleted_at__isnull=True,
            status="active",
            metric_key=WEIGHT_GOAL_METRIC,
            target_value__isnull=False,
        )
        .order_by("-created_at")
    )
    for goal in rows:
        lbs = trend.to_lbs(goal.target_value, goal.unit)
        if lbs is not None and lbs > 0:
            return goal, lbs
    return None


PROFILE_GOAL_STATEMENT = "Goal weight"


def goal_weight(request, profile: BodyProfile | None = None):
    """(goal_id or None, statement, target lbs) for the weight the coach aims at, or None.

    The active `bodyweight_lbs` goal on the engine wins; else the caller's own profile goal
    weight (private, like the rest of the profile). Today the phone's goals do not reach the
    engine, so the profile is usually the source."""
    found = active_weight_goal(request)
    if found is not None:
        goal, lbs = found
        return str(goal.id), goal.statement, lbs
    profile = profile if profile is not None else own_profile(request)
    if profile is not None and profile.goal_weight_lbs is not None:
        return None, PROFILE_GOAL_STATEMENT, profile.goal_weight_lbs
    return None


def milestone_for(request, data: TrendData):
    found = goal_weight(request)
    if found is None or not data.points:
        return None
    goal_id, statement, target_lbs = found
    return trend.milestone(
        goal_id=goal_id,
        statement=statement,
        target_lbs=target_lbs,
        start_lbs=data.points[0].trend_lbs,
        current_lbs=data.latest_trend_lbs,
        rate_lbs_per_week=data.rate.lbs_per_week if data.rate else None,
        water=data.early_water,
    )


# =============================================================================
# Waist
# =============================================================================


def waist_dict(row: WaistLog) -> dict:
    return {
        "id": str(row.id),
        "measured_on": row.measured_on.isoformat(),
        "waist_in": round(row.waist_mm / trend.MM_PER_INCH, 1),
        "waist_cm": round(row.waist_mm / 10, 1),
    }


def waist_change_mm(request) -> int | None:
    return trend.waist_change_mm(
        scoped(WaistLog, request).values_list("measured_on", "waist_mm")
    )


# =============================================================================
# Meal targets and intake
# =============================================================================


def current_target(request, today: date) -> MealTarget | None:
    return (
        scoped(MealTarget, request)
        .filter(deleted_at__isnull=True, effective_from_date__lte=today)
        .order_by("-effective_from_date")
        .first()
    )


def target_dict(row: MealTarget | None) -> dict | None:
    if row is None:
        return None
    return {
        "calories_kcal": row.calories_kcal,
        "protein_g": row.protein_g,
        "effective_from": row.effective_from_date.isoformat(),
    }


def evidence(request, data: TrendData) -> adaptation.Evidence:
    window = adaptation.window_for(data.today)
    zone = data.zone
    # A day's intake is the sum of its meals that carry an estimate; a day with none is not an
    # intake day, and is not counted as zero.
    start = datetime.combine(window.start - timedelta(days=1), datetime.min.time(), tzinfo=zone)
    end = datetime.combine(window.end + timedelta(days=2), datetime.min.time(), tzinfo=zone)
    meals = scoped(MealLog, request).filter(
        deleted_at__isnull=True,
        calories_kcal__isnull=False,
        logged_at__gte=start,
        logged_at__lt=end,
    )
    by_day: dict[date, int] = {}
    for logged_at, kcal in meals.values_list("logged_at", "calories_kcal"):
        day = local_day(logged_at, zone)
        if window.start <= day <= window.end:
            by_day[day] = by_day.get(day, 0) + kcal
    intake_days = len(by_day)
    return adaptation.Evidence(
        window=window,
        intake_days=intake_days,
        weigh_in_days=data.weigh_in_days_between(window.start, window.end),
        mean_intake_kcal=sum(by_day.values()) / intake_days if intake_days else None,
        first_weigh_in=data.first_day,
        trend_start_lbs=trend.trend_on(data.points, window.start - timedelta(days=1)),
        trend_end_lbs=trend.trend_on(data.points, window.end),
    )


# =============================================================================
# Proposals
# =============================================================================


def proposal_dict(row: EnergyProposal | None) -> dict | None:
    if row is None:
        return None
    rate_pct = row.observed_rate_bp / 100
    return {
        "id": str(row.id),
        "current_target_kcal": row.current_target_kcal,
        "proposed_target_kcal": row.proposed_target_kcal,
        "observed_maintenance_kcal": row.observed_maintenance_kcal,
        "observed_rate_pct": round(rate_pct, 2),
        "window_start": row.window_start.isoformat(),
        "window_end": row.window_end.isoformat(),
        "intake_days": row.intake_days,
        "weigh_in_days": row.weigh_in_days,
        "words": adaptation.proposal_words(
            current=row.current_target_kcal,
            proposed=row.proposed_target_kcal,
            rate_pct=rate_pct,
            maintenance=row.observed_maintenance_kcal,
        ),
    }


def pending_proposal(request) -> EnergyProposal | None:
    return scoped(EnergyProposal, request).filter(status=STATUS_PENDING).first()


def maybe_propose(request, e: adaptation.Evidence, target_rate_bp: int, target: MealTarget | None):
    """Make the proposal the evidence calls for, once (idempotent: a proposal made in the last
    14 days, of any status, blocks another). An older pending one expires."""
    household = household_of(request)
    recent = scoped(EnergyProposal, request).filter(
        created_at__gte=timezone.now() - timedelta(days=adaptation.COOLDOWN_DAYS)
    )
    made = adaptation.propose(
        e,
        target_rate_bp=target_rate_bp,
        current_target_kcal=target.calories_kcal if target else None,
        made_recently=recent.exists(),
    )
    if made is None:
        return
    try:
        with transaction.atomic():
            scoped(EnergyProposal, request).filter(status=STATUS_PENDING).update(
                status=STATUS_EXPIRED, decided_at=Now()
            )
            EnergyProposal.objects.create(
                household=household,
                window_start=made.window_start,
                window_end=made.window_end,
                intake_days=made.intake_days,
                weigh_in_days=made.weigh_in_days,
                mean_intake_kcal=made.mean_intake_kcal,
                trend_start_lbs=made.trend_start_lbs,
                trend_end_lbs=made.trend_end_lbs,
                observed_maintenance_kcal=made.observed_maintenance_kcal,
                observed_rate_bp=made.observed_rate_bp,
                target_rate_bp=made.target_rate_bp,
                current_target_kcal=made.current_target_kcal,
                proposed_target_kcal=made.proposed_target_kcal,
            )
    except IntegrityError:
        # A concurrent GET made it first; the one pending proposal is the one there is.
        return


# =============================================================================
# The three computed reads
# =============================================================================


def energy_body(request) -> dict:
    profile = own_profile(request)
    inputs = profile_inputs(profile)
    data = TrendData(request)
    e = evidence(request, data)
    target = current_target(request, data.today)
    rate_bp = energy.clamp_rate_bp((inputs or energy.ProfileInputs()).target_rate_bp)
    maybe_propose(request, e, rate_bp, target)

    if len(data.daily) >= 2:
        weight, source = data.latest_trend_lbs, "trend"
    elif data.daily:
        weight, source = data.latest_reading_lbs, "latest"
    else:
        weight, source = None, None
    observed = (
        energy.Observed(maintenance_kcal=adaptation.observed_maintenance_kcal(e))
        if adaptation.evidence_met(e)
        else None
    )
    goal = goal_weight(request, profile)
    body = energy.estimate(
        profile=inputs,
        this_year=data.today.year,
        weight_lbs=weight,
        weight_source=source,
        goal_lbs=goal[2] if goal else None,
        observed=observed,
    )
    body["current_target"] = target_dict(target)
    body["proposal"] = proposal_dict(pending_proposal(request))
    return body


def trend_body(request, days: int) -> dict:
    data = TrendData(request)
    first_shown = data.today - timedelta(days=days - 1)
    points = [
        {
            "day": p.day.isoformat(),
            "weight_lbs": round(p.weight_lbs, 1) if p.weight_lbs is not None else None,
            "trend_lbs": round(p.trend_lbs, 1),
        }
        for p in data.points
        if p.day >= first_shown
    ]
    rate = data.rate
    stone = milestone_for(request, data)
    waist_rows = list(scoped(WaistLog, request).order_by("-measured_on", "-created_at")[:52])
    change = waist_change_mm(request)
    latest = data.latest_trend_lbs
    return {
        "unit": "lbs",
        "points": points,
        "latest_trend_lbs": round(latest, 1) if latest is not None else None,
        "rate_pct_per_week": round(rate.pct_per_week, 2) if rate else None,
        "rate_lbs_per_week": round(rate.lbs_per_week, 1) if rate else None,
        "rate_days": trend.RATE_DAYS,
        "band": {"low_pct": trend.BAND_LOW_PCT, "high_pct": trend.BAND_HIGH_PCT},
        "in_band": trend.in_band(rate.pct_per_week) if rate else None,
        "early_water": data.early_water,
        "milestone": _milestone_dict(stone),
        "waist": [waist_dict(row) for row in waist_rows],
        "waist_change_in_28d": (
            round(change / trend.MM_PER_INCH, 1) if change is not None else None
        ),
        "words": _trend_words(data, rate, stone),
    }


def _milestone_dict(stone) -> dict | None:
    if stone is None:
        return None
    return {
        "goal_id": stone.goal_id,
        "statement": stone.statement,
        "target_lbs": round(stone.target_lbs, 1),
        "start_lbs": round(stone.start_lbs, 1),
        "remaining_lbs": round(stone.remaining_lbs, 1),
        "progress_pct": int(round(stone.progress_pct)),
        "weeks_to_go": round(stone.weeks_to_go, 1) if stone.weeks_to_go is not None else None,
    }


def _trend_words(data: TrendData, rate, stone) -> str:
    if not data.points:
        return "No weigh-ins yet, so there is no trend. Log a weight and the trend starts there."
    parts = [
        f"Trend weight {data.latest_trend_lbs:.1f} lbs, smoothed from your weigh-ins; an "
        f"estimate, not a single reading."
    ]
    if rate is None:
        parts.append(
            "There are not 14 days of history yet, so there is no rate per week."
        )
    else:
        way = "losing" if rate.lbs_per_week < 0 else "gaining" if rate.lbs_per_week > 0 else "at"
        parts.append(
            f"Over the last 14 days the trend is {way} {abs(rate.lbs_per_week):.1f} lbs a week "
            f"({abs(rate.pct_per_week):.2f}% of bodyweight)."
        )
    if data.early_water:
        parts.append(
            "Your first weigh-in is under three weeks old: early loss is mostly water, so it is "
            "flagged rather than projected, and no date is given for the goal."
        )
    if stone is not None:
        if stone.remaining_lbs <= 0:
            parts.append("The goal weight is reached.")
        elif stone.weeks_to_go is not None:
            parts.append(
                f"{stone.remaining_lbs:.1f} lbs to the goal; about {stone.weeks_to_go:.0f} weeks "
                f"at this rate, an estimate."
            )
        else:
            parts.append(f"{stone.remaining_lbs:.1f} lbs to the goal.")
    return " ".join(parts)


def weekly_body(request) -> dict:
    profile = own_profile(request)
    data = TrendData(request)
    stone = milestone_for(request, data)
    rate = data.rate
    return weekly.weekly_line(
        weekly.WeeklyInputs(
            weekly_line_on=profile.weekly_line_on if profile else True,
            weigh_in_days_last_14=data.weigh_in_days_between(
                data.today - timedelta(days=weekly.WINDOW_DAYS - 1), data.today
            ),
            trend_lbs=data.latest_trend_lbs,
            rate_pct_per_week=rate.pct_per_week if rate else None,
            rate_lbs_per_week=rate.lbs_per_week if rate else None,
            goal_remaining_lbs=stone.remaining_lbs if stone else None,
            goal_target_lbs=stone.target_lbs if stone else None,
            waist_change_mm=waist_change_mm(request),
        )
    )


# =============================================================================
# Writes into the legacy body tables
# =============================================================================


def log_meal(request, *, description, calories_kcal, protein_g, carbs_g, fat_g) -> MealLog:
    """One `meal_logs` row, as the body API writes a user-authored one: REPORTED, `USER`
    provenance, a fresh `origin_guid`, logged now."""
    row = MealLog.objects.create(
        id=uuid.uuid4(),
        household=household_of(request),
        description=description,
        calories_kcal=calories_kcal,
        protein_g=protein_g,
        carbs_g=carbs_g,
        fat_g=fat_g,
        logged_at=timezone.now(),
        source_image_path=None,
        trust_tier=TRUST_REPORTED,
        provenance=Provenance.USER,
        created_at=Now(),
        updated_at=Now(),
        deleted_at=None,
        origin_guid=str(uuid.uuid4()),
    )
    row.refresh_from_db()
    return row


def accept(request, proposal: EnergyProposal) -> MealTarget:
    """Copy the current target forward to today with the proposed calories. A row already dated
    today is updated in place (the table is unique per household and day, tombstones included,
    so a tombstoned one is brought back)."""
    today = today_of(request)
    household = household_of(request)
    with transaction.atomic():
        carried = current_target(request, today)
        same_day = (
            scoped(MealTarget, request)
            .select_for_update()
            .filter(effective_from_date=today)
            .first()
        )
        if same_day is not None:
            fields = {"calories_kcal": proposal.proposed_target_kcal, "updated_at": Now()}
            if same_day.deleted_at is not None:
                fields["deleted_at"] = None
                if carried is not None:
                    fields.update(
                        protein_g=carried.protein_g, carbs_g=carried.carbs_g, fat_g=carried.fat_g
                    )
            MealTarget.objects.filter(pk=same_day.pk).update(**fields)
            row = MealTarget.objects.get(pk=same_day.pk)
        else:
            row = MealTarget.objects.create(
                id=uuid.uuid4(),
                household=household,
                calories_kcal=proposal.proposed_target_kcal,
                protein_g=carried.protein_g if carried else 0.0,
                carbs_g=carried.carbs_g if carried else 0.0,
                fat_g=carried.fat_g if carried else 0.0,
                effective_from_date=today,
                provenance=Provenance.USER,
                created_at=Now(),
                updated_at=Now(),
                deleted_at=None,
                origin_guid=str(uuid.uuid4()),
            )
            row.refresh_from_db()
        EnergyProposal.objects.filter(pk=proposal.pk).update(
            status=STATUS_ACCEPTED,
            decided_at=Now(),
            decided_by=request.user,
            meal_target_origin_guid=row.origin_guid,
        )
    return row


def dismiss(request, proposal: EnergyProposal) -> None:
    EnergyProposal.objects.filter(pk=proposal.pk).update(
        status=STATUS_DISMISSED, decided_at=Now(), decided_by=request.user
    )
