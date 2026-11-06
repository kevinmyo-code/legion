"""`/api/body/coach/`: the fat-loss loop over REST (fat-loss tickets 03 to 07).

| Route | Does |
|---|---|
| `GET/PUT profile/` | the caller's own profile (private, ADR 0052); PUT is partial |
| `GET energy/` | maintenance, target, protein, current target, pending proposal (made lazily) |
| `GET trend/?days=90` | daily raw and EWMA points, the 14-day rate, milestone, waist |
| `GET/POST waist/`, `DELETE waist/<id>/` | the household's waist log |
| `GET/POST saved-meals/`, `DELETE saved-meals/<id>/`, `POST saved-meals/<id>/log/` | saved meals |
| `POST proposals/<id>/accept/`, `POST proposals/<id>/dismiss/` | decide a proposal |
| `GET weekly-line/` | the Monday line's facts, or why not |

Authentication is the API default (a device token or a browser session). A recruiter is refused
every route by `household.recruiter_routes` (the body aspect is not part of the demo). Weights on
the wire are lbs, lengths cm and inches, rates percent of bodyweight per week. Every figure that
is an estimate says so (`"estimate": true`, and in words). The engine tools call these same views
(`engine_mcp.dispatch.call_route`), so there is no second write path.
"""

from __future__ import annotations

from datetime import date

from django.db import IntegrityError, transaction
from django.db.models.functions import Lower, Now
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.body import MealLogSerializer, MealTargetSerializer
from api.schema import DetailSerializer
from body_coach import service
from body_coach.models import (
    ACTIVITY_FACTORS,
    ACTIVITY_LEVELS,
    BIRTH_YEAR_MIN,
    ESTIMATE_HELP,
    GOAL_WEIGHT_LBS_MAX,
    HEIGHT_MM_MAX,
    HEIGHT_MM_MIN,
    PROTEIN_TENTHS_MAX,
    PROTEIN_TENTHS_MIN,
    SEXES,
    STATUS_PENDING,
    TARGET_RATE_BP_MAX,
    TARGET_RATE_BP_MIN,
    WAIST_MM_MAX,
    BodyProfile,
    EnergyProposal,
    SavedMeal,
    WaistLog,
)
from body_coach.trend import LBS_PER_KG, MM_PER_INCH
from household.tenancy import household_of, scoped
from legacy.models.pantry import MealLog

TAGS = ["body-coach"]
TREND_DAYS_DEFAULT = 90
TREND_DAYS_MIN, TREND_DAYS_MAX = 7, 730
WAIST_LIMIT = 52
NAME_MAX = 80
DESCRIPTION_MAX = 500
ACTIVITY_WORDS = ", ".join(f"{name} ({factor})" for name, factor in ACTIVITY_FACTORS.items())


class Refused(Exception):
    def __init__(self, message: str, code: int = status.HTTP_400_BAD_REQUEST):
        super().__init__(message)
        self.message = message
        self.code = code


def _refusal(exc: Refused) -> Response:
    return Response({"detail": exc.message}, status=exc.code)


def _number(data: dict, name: str, verb: str = "saved") -> float | None:
    if name not in data or data[name] is None:
        return None
    value = data[name]
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        raise Refused(f"Nothing was {verb}. {name} must be a number; got {value!r}.")
    try:
        number = float(value)
    except ValueError as exc:
        raise Refused(f"Nothing was {verb}. {name} must be a number; got {value!r}.") from exc
    if number != number or number in (float("inf"), float("-inf")):
        raise Refused(f"Nothing was {verb}. {name} must be a finite number.")
    return number


def _unknown(data, allowed: tuple[str, ...], verb: str = "saved") -> None:
    if not isinstance(data, dict):
        raise Refused(f"Nothing was {verb}. The body must be a JSON object.")
    unknown = sorted(set(data) - set(allowed))
    if unknown:
        raise Refused(
            f"Nothing was {verb}. This route does not know {', '.join(unknown)}. "
            f"Fields it takes: {', '.join(allowed)}."
        )


# =============================================================================
# Profile
# =============================================================================


class ProfileSerializer(serializers.Serializer):
    exists = serializers.BooleanField()
    height_cm = serializers.FloatField(allow_null=True)
    sex = serializers.ChoiceField(choices=SEXES, allow_null=True)
    birth_year = serializers.IntegerField(allow_null=True)
    activity_level = serializers.ChoiceField(choices=ACTIVITY_LEVELS, allow_null=True)
    target_rate_pct = serializers.FloatField(help_text="Percent of bodyweight a week, 0.25 to 1.0.")
    protein_g_per_kg = serializers.FloatField(help_text="1.6 to 2.2.")
    weekly_line_on = serializers.BooleanField()
    goal_weight_lbs = serializers.FloatField(
        allow_null=True,
        help_text="The member's own goal or milestone weight. An active bodyweight_lbs goal on "
        "the engine wins over it.",
    )
    missing = serializers.ListField(
        child=serializers.CharField(),
        help_text="What the calculator still needs, in words: height, sex, birth year, "
        "activity level, a weigh-in.",
    )


class ProfileUpdateSerializer(serializers.Serializer):
    height_cm = serializers.FloatField(required=False)
    height_in = serializers.FloatField(required=False)
    sex = serializers.ChoiceField(choices=SEXES, required=False)
    birth_year = serializers.IntegerField(required=False)
    activity_level = serializers.ChoiceField(choices=ACTIVITY_LEVELS, required=False)
    target_rate_pct = serializers.FloatField(required=False)
    protein_g_per_kg = serializers.FloatField(required=False)
    weekly_line_on = serializers.BooleanField(required=False)
    goal_weight_lbs = serializers.FloatField(
        required=False, allow_null=True, help_text="null clears it."
    )
    goal_weight_kg = serializers.FloatField(
        required=False, allow_null=True, help_text="Converted to lbs; null clears it."
    )


PROFILE_FIELDS = tuple(ProfileUpdateSerializer().fields)


def profile_changes(data, this_year: int) -> dict:
    """The model fields a partial PUT sets, or `Refused` naming the allowed values in words."""
    _unknown(data, PROFILE_FIELDS)
    out: dict = {}
    if "height_cm" in data and "height_in" in data:
        raise Refused("Nothing was saved. Give height_cm or height_in, not both.")
    for name, mm_per_unit in (("height_cm", 10.0), ("height_in", MM_PER_INCH)):
        value = _number(data, name)
        if name in data:
            if value is None:
                raise Refused(f"Nothing was saved. {name} cannot be cleared, only changed.")
            mm = int(round(value * mm_per_unit))
            if not HEIGHT_MM_MIN <= mm <= HEIGHT_MM_MAX:
                raise Refused(
                    f"Nothing was saved. Height must be between {HEIGHT_MM_MIN / 10:.0f} and "
                    f"{HEIGHT_MM_MAX / 10:.0f} cm ({HEIGHT_MM_MIN / MM_PER_INCH:.0f} to "
                    f"{HEIGHT_MM_MAX / MM_PER_INCH:.0f} in); got {value} via {name}."
                )
            out["height_mm"] = mm
    if "sex" in data:
        if data["sex"] not in SEXES:
            raise Refused(
                f"Nothing was saved. sex must be male or female (the formula needs one of the "
                f"two); got {data['sex']!r}."
            )
        out["sex"] = data["sex"]
    if "birth_year" in data:
        year = data["birth_year"]
        if isinstance(year, bool) or not isinstance(year, int):
            raise Refused(f"Nothing was saved. birth_year must be a whole year; got {year!r}.")
        if not BIRTH_YEAR_MIN <= year <= this_year - 18:
            raise Refused(
                f"Nothing was saved. birth_year must be between {BIRTH_YEAR_MIN} and "
                f"{this_year - 18}: the formula is for adults. Got {year}."
            )
        out["birth_year"] = year
    if "activity_level" in data:
        if data["activity_level"] not in ACTIVITY_LEVELS:
            raise Refused(
                f"Nothing was saved. activity_level must be one of {ACTIVITY_WORDS}; got "
                f"{data['activity_level']!r}."
            )
        out["activity_level"] = data["activity_level"]
    rate = _number(data, "target_rate_pct")
    if "target_rate_pct" in data:
        bp = None if rate is None else int(round(rate * 100))
        if bp is None or not TARGET_RATE_BP_MIN <= bp <= TARGET_RATE_BP_MAX:
            raise Refused(
                f"Nothing was saved. target_rate_pct is percent of bodyweight a week and must "
                f"be between {TARGET_RATE_BP_MIN / 100:.2f} and {TARGET_RATE_BP_MAX / 100:.2f}; "
                f"got {data['target_rate_pct']!r}."
            )
        out["target_rate_bp"] = bp
    protein = _number(data, "protein_g_per_kg")
    if "protein_g_per_kg" in data:
        tenths = None if protein is None else int(round(protein * 10))
        if tenths is None or not PROTEIN_TENTHS_MIN <= tenths <= PROTEIN_TENTHS_MAX:
            raise Refused(
                f"Nothing was saved. protein_g_per_kg must be between "
                f"{PROTEIN_TENTHS_MIN / 10:.1f} and {PROTEIN_TENTHS_MAX / 10:.1f}; got "
                f"{data['protein_g_per_kg']!r}."
            )
        out["protein_tenths_g_per_kg"] = tenths
    if "weekly_line_on" in data:
        if not isinstance(data["weekly_line_on"], bool):
            raise Refused(
                f"Nothing was saved. weekly_line_on must be true or false; got "
                f"{data['weekly_line_on']!r}."
            )
        out["weekly_line_on"] = data["weekly_line_on"]
    if "goal_weight_lbs" in data and "goal_weight_kg" in data:
        raise Refused("Nothing was saved. Give goal_weight_lbs or goal_weight_kg, not both.")
    for name, lbs_per_unit in (("goal_weight_lbs", 1.0), ("goal_weight_kg", LBS_PER_KG)):
        if name not in data:
            continue
        value = _number(data, name)
        if value is None:
            out["goal_weight_lbs"] = None
            continue
        lbs = value * lbs_per_unit
        if not 0 < lbs < GOAL_WEIGHT_LBS_MAX:
            raise Refused(
                f"Nothing was saved. A goal weight must be more than 0 and under "
                f"{GOAL_WEIGHT_LBS_MAX} lbs ({GOAL_WEIGHT_LBS_MAX / LBS_PER_KG:.0f} kg); got "
                f"{value} via {name}."
            )
        out["goal_weight_lbs"] = lbs
    return out


class ProfileView(APIView):
    @extend_schema(
        operation_id="api_body_coach_profile_retrieve",
        tags=TAGS,
        responses={200: ProfileSerializer},
        description="The caller's own body profile. Private: no member reads another's.",
    )
    def get(self, request):
        return Response(service.profile_body(request))

    @extend_schema(
        operation_id="api_body_coach_profile_update",
        tags=TAGS,
        request=ProfileUpdateSerializer,
        responses={200: ProfileSerializer, 400: DetailSerializer},
        description=(
            "Partial update of the caller's own profile; creates it on first use. height_cm or "
            "height_in; goal_weight_lbs or goal_weight_kg (null clears it). weekly_line_on false "
            "stops the Monday weight line."
        ),
    )
    def put(self, request):
        today = service.today_of(request)
        try:
            changes = profile_changes(request.data, today.year)
        except Refused as exc:
            return _refusal(exc)
        household = household_of(request)
        with transaction.atomic():
            row = service.own_profile(request)
            if row is None:
                row = BodyProfile.objects.create(
                    household=household, owner_user=request.user, **changes
                )
            elif changes:
                BodyProfile.objects.filter(pk=row.pk).update(**changes, updated_at=Now())
            row = BodyProfile.objects.get(pk=row.pk)
        return Response(service.profile_body(request, row))


# =============================================================================
# Energy
# =============================================================================


class CurrentTargetSerializer(serializers.Serializer):
    calories_kcal = serializers.IntegerField()
    protein_g = serializers.FloatField()
    effective_from = serializers.DateField()


class ProposalSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    current_target_kcal = serializers.IntegerField()
    proposed_target_kcal = serializers.IntegerField()
    observed_maintenance_kcal = serializers.IntegerField(help_text="An estimate.")
    observed_rate_pct = serializers.FloatField(
        help_text="Percent of bodyweight a week over the window; negative is losing."
    )
    window_start = serializers.DateField()
    window_end = serializers.DateField()
    intake_days = serializers.IntegerField()
    weigh_in_days = serializers.IntegerField()
    words = serializers.CharField()


class EnergySerializer(serializers.Serializer):
    estimate = serializers.BooleanField(help_text="Always true: every figure is an estimate.")
    basis = serializers.ChoiceField(choices=["formula", "trend"], allow_null=True)
    basis_days = serializers.IntegerField(allow_null=True)
    basis_words = serializers.CharField()
    missing = serializers.ListField(child=serializers.CharField())
    weight_lbs = serializers.FloatField(allow_null=True)
    weight_source = serializers.ChoiceField(choices=["trend", "latest"], allow_null=True)
    bmr_kcal = serializers.IntegerField(allow_null=True)
    activity_level = serializers.CharField(allow_null=True)
    activity_factor = serializers.FloatField(allow_null=True)
    formula_maintenance_kcal = serializers.IntegerField(allow_null=True)
    observed_maintenance_kcal = serializers.IntegerField(allow_null=True)
    maintenance_kcal = serializers.IntegerField(allow_null=True)
    target_rate_pct = serializers.FloatField()
    suggested_target_kcal = serializers.IntegerField(allow_null=True)
    protein_g = serializers.IntegerField(allow_null=True)
    protein_g_per_kg = serializers.FloatField()
    protein_basis = serializers.ChoiceField(
        choices=["goal weight", "current weight"], allow_null=True
    )
    current_target = CurrentTargetSerializer(allow_null=True)
    proposal = ProposalSerializer(allow_null=True)
    words = serializers.CharField()


class EnergyView(APIView):
    @extend_schema(
        operation_id="api_body_coach_energy_retrieve",
        tags=TAGS,
        responses={200: EnergySerializer},
        description=(
            "The energy estimate: BMR, maintenance (formula, or the person's own 14-day trend "
            "once there is enough of it), suggested target and protein, all estimates. Makes the "
            "two-weekly proposal when the evidence calls for one (idempotent)."
        ),
    )
    def get(self, request):
        return Response(service.energy_body(request))


# =============================================================================
# Trend
# =============================================================================


class TrendPointSerializer(serializers.Serializer):
    day = serializers.DateField()
    weight_lbs = serializers.FloatField(allow_null=True, help_text="The day's mean; null on a gap.")
    trend_lbs = serializers.FloatField()


class BandSerializer(serializers.Serializer):
    low_pct = serializers.FloatField()
    high_pct = serializers.FloatField()


class MilestoneSerializer(serializers.Serializer):
    goal_id = serializers.CharField(
        allow_null=True, help_text="Null when the target is the profile's own goal weight."
    )
    statement = serializers.CharField()
    target_lbs = serializers.FloatField()
    start_lbs = serializers.FloatField()
    remaining_lbs = serializers.FloatField()
    progress_pct = serializers.IntegerField()
    weeks_to_go = serializers.FloatField(allow_null=True, help_text="An estimate.")


class WaistSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    measured_on = serializers.DateField()
    waist_in = serializers.FloatField()
    waist_cm = serializers.FloatField()


class TrendSerializer(serializers.Serializer):
    unit = serializers.CharField()
    points = TrendPointSerializer(many=True)
    latest_trend_lbs = serializers.FloatField(allow_null=True)
    rate_pct_per_week = serializers.FloatField(allow_null=True, help_text="Negative is losing.")
    rate_lbs_per_week = serializers.FloatField(allow_null=True, help_text="Negative is losing.")
    rate_days = serializers.IntegerField()
    band = BandSerializer()
    in_band = serializers.BooleanField(allow_null=True)
    early_water = serializers.BooleanField()
    milestone = MilestoneSerializer(allow_null=True)
    waist = WaistSerializer(many=True)
    waist_change_in_28d = serializers.FloatField(allow_null=True)
    words = serializers.CharField()


class TrendView(APIView):
    @extend_schema(
        operation_id="api_body_coach_trend_retrieve",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "days", int, description=f"Days of points, {TREND_DAYS_MIN} to {TREND_DAYS_MAX}."
            )
        ],
        responses={200: TrendSerializer, 400: DetailSerializer},
    )
    def get(self, request):
        raw = request.query_params.get("days") or str(TREND_DAYS_DEFAULT)
        try:
            days = int(raw)
        except ValueError:
            days = -1
        if not TREND_DAYS_MIN <= days <= TREND_DAYS_MAX:
            return Response(
                {
                    "detail": f"Nothing was read. days must be a whole number from "
                    f"{TREND_DAYS_MIN} to {TREND_DAYS_MAX}; got {raw!r}."
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        return Response(service.trend_body(request, days))


# =============================================================================
# Waist
# =============================================================================


class WaistListSerializer(serializers.Serializer):
    results = WaistSerializer(many=True)


class WaistCreateSerializer(serializers.Serializer):
    waist_in = serializers.FloatField(required=False)
    waist_cm = serializers.FloatField(required=False)
    measured_on = serializers.DateField(required=False)


class WaistListCreateView(APIView):
    @extend_schema(
        operation_id="api_body_coach_waist_list",
        tags=TAGS,
        responses={200: WaistListSerializer},
        description=f"The household's waist measurements, latest first, at most {WAIST_LIMIT}.",
    )
    def get(self, request):
        rows = scoped(WaistLog, request).order_by("-measured_on", "-created_at")[:WAIST_LIMIT]
        return Response({"results": [service.waist_dict(row) for row in rows]})

    @extend_schema(
        operation_id="api_body_coach_waist_create",
        tags=TAGS,
        request=WaistCreateSerializer,
        responses={201: WaistSerializer, 400: DetailSerializer},
        description="waist_in or waist_cm; measured_on defaults to today in the household's zone.",
    )
    def post(self, request):
        data = request.data
        try:
            _unknown(data, ("waist_in", "waist_cm", "measured_on"))
            inches, cm = _number(data, "waist_in"), _number(data, "waist_cm")
            if (inches is None) == (cm is None):
                raise Refused("Nothing was saved. Give waist_in or waist_cm, exactly one.")
            mm = int(round(inches * MM_PER_INCH)) if inches is not None else int(round(cm * 10))
            if not 0 < mm <= WAIST_MM_MAX:
                raise Refused(
                    f"Nothing was saved. A waist must be more than 0 and at most "
                    f"{WAIST_MM_MAX / 10:.0f} cm."
                )
            today = service.today_of(request)
            day = today
            if data.get("measured_on") is not None:
                try:
                    day = date.fromisoformat(str(data["measured_on"]))
                except ValueError as exc:
                    raise Refused(
                        f"Nothing was saved. measured_on must be a date like 2026-11-05; got "
                        f"{data['measured_on']!r}."
                    ) from exc
                if day > today:
                    raise Refused(
                        f"Nothing was saved. measured_on {day.isoformat()} is after today "
                        f"({today.isoformat()})."
                    )
        except Refused as exc:
            return _refusal(exc)
        row = WaistLog.objects.create(household=household_of(request), measured_on=day, waist_mm=mm)
        return Response(service.waist_dict(row), status=status.HTTP_201_CREATED)


class WaistDetailView(APIView):
    @extend_schema(
        operation_id="api_body_coach_waist_destroy",
        tags=TAGS,
        responses={204: OpenApiResponse(description="Deleted for good."), 404: DetailSerializer},
    )
    def delete(self, request, waist_id):
        deleted, _ = scoped(WaistLog, request).filter(pk=waist_id).delete()
        if not deleted:
            return Response(
                {"detail": "Nothing was deleted. There is no waist measurement with that id."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(status=status.HTTP_204_NO_CONTENT)


# =============================================================================
# Saved meals
# =============================================================================


class SavedMealSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    name = serializers.CharField()
    description = serializers.CharField(allow_blank=True)
    calories_kcal = serializers.IntegerField(help_text=ESTIMATE_HELP)
    protein_g = serializers.FloatField(help_text=ESTIMATE_HELP)
    carbs_g = serializers.FloatField(allow_null=True, help_text=ESTIMATE_HELP)
    fat_g = serializers.FloatField(allow_null=True, help_text=ESTIMATE_HELP)
    estimate = serializers.BooleanField(help_text="Always true: the macros are estimates.")
    updated_at = serializers.DateTimeField()


class SavedMealListSerializer(serializers.Serializer):
    results = SavedMealSerializer(many=True)


class SavedMealCreateSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=NAME_MAX)
    description = serializers.CharField(required=False, allow_blank=True)
    calories_kcal = serializers.IntegerField(required=False, help_text=ESTIMATE_HELP)
    protein_g = serializers.FloatField(required=False, help_text=ESTIMATE_HELP)
    carbs_g = serializers.FloatField(required=False, allow_null=True, help_text=ESTIMATE_HELP)
    fat_g = serializers.FloatField(required=False, allow_null=True, help_text=ESTIMATE_HELP)
    from_meal_log = serializers.CharField(
        required=False,
        help_text="A logged meal's origin_guid or id: save what was just eaten, its estimate "
        "copied.",
    )


class SavedMealLogSerializer(serializers.Serializer):
    meal_log = MealLogSerializer()
    words = serializers.CharField()


def saved_meal_dict(row: SavedMeal) -> dict:
    return {
        "id": str(row.id),
        "name": row.name,
        "description": row.description,
        "calories_kcal": row.calories_kcal,
        "protein_g": row.protein_g,
        "carbs_g": row.carbs_g,
        "fat_g": row.fat_g,
        "estimate": True,
        "updated_at": serializers.DateTimeField().to_representation(row.updated_at),
    }


def _meal_log_named(request, ref: str):
    rows = scoped(MealLog, request).filter(deleted_at__isnull=True)
    found = rows.filter(origin_guid=ref).first()
    if found is None:
        import uuid as _uuid

        try:
            found = rows.filter(pk=_uuid.UUID(ref)).first()
        except ValueError:
            found = None
    return found


def saved_meal_fields(request, data) -> dict:
    _unknown(data, tuple(SavedMealCreateSerializer().fields))
    name = data.get("name")
    if not isinstance(name, str) or not name.strip():
        raise Refused("Nothing was saved. A saved meal needs a name, like \"usual breakfast\".")
    name = " ".join(name.split())
    if len(name) > NAME_MAX:
        raise Refused(f"Nothing was saved. A name is at most {NAME_MAX} characters.")
    description = data.get("description") or ""
    if not isinstance(description, str) or len(description) > DESCRIPTION_MAX:
        raise Refused(
            f"Nothing was saved. description must be text of at most {DESCRIPTION_MAX} "
            f"characters."
        )
    ref = data.get("from_meal_log")
    if ref is not None:
        if any(data.get(k) is not None for k in ("calories_kcal", "protein_g", "carbs_g", "fat_g")):
            raise Refused(
                "Nothing was saved. Give from_meal_log or the numbers, not both: the saved "
                "estimate is copied from the logged meal."
            )
        meal = _meal_log_named(request, str(ref))
        if meal is None:
            raise Refused(
                f"Nothing was saved. There is no logged meal {ref!r} in this household.",
                status.HTTP_404_NOT_FOUND,
            )
        if meal.calories_kcal is None or meal.protein_g is None:
            raise Refused(
                "Nothing was saved. That logged meal has no calorie or protein estimate, and a "
                "saved meal needs both. Give the numbers instead."
            )
        return {
            "name": name,
            "description": description or meal.description,
            "calories_kcal": meal.calories_kcal,
            "protein_g": meal.protein_g,
            "carbs_g": meal.carbs_g,
            "fat_g": meal.fat_g,
        }
    calories = _number(data, "calories_kcal")
    protein = _number(data, "protein_g")
    if calories is None or protein is None:
        raise Refused(
            "Nothing was saved. A saved meal needs calories_kcal and protein_g (estimates), or "
            "from_meal_log naming a logged meal. Carbs and fat are optional."
        )
    carbs, fat = _number(data, "carbs_g"), _number(data, "fat_g")
    for label, value in (("calories_kcal", calories), ("protein_g", protein),
                         ("carbs_g", carbs), ("fat_g", fat)):
        if value is not None and value < 0:
            raise Refused(f"Nothing was saved. {label} cannot be negative; got {value}.")
    return {
        "name": name,
        "description": description,
        "calories_kcal": int(round(calories)),
        "protein_g": protein,
        "carbs_g": carbs,
        "fat_g": fat,
    }


class SavedMealListCreateView(APIView):
    @extend_schema(
        operation_id="api_body_coach_saved_meals_list",
        tags=TAGS,
        responses={200: SavedMealListSerializer},
        description="The household's saved meals by name. Every macro is an estimate.",
    )
    def get(self, request):
        rows = scoped(SavedMeal, request).order_by(Lower("name"))
        return Response({"results": [saved_meal_dict(row) for row in rows]})

    @extend_schema(
        operation_id="api_body_coach_saved_meals_create",
        tags=TAGS,
        request=SavedMealCreateSerializer,
        responses={201: SavedMealSerializer, 400: DetailSerializer, 404: DetailSerializer},
        description=(
            "Saves a meal by name (unique per household, any case) with its estimated macros, "
            "or copies a logged meal's estimate with from_meal_log."
        ),
    )
    def post(self, request):
        try:
            fields = saved_meal_fields(request, request.data)
        except Refused as exc:
            return _refusal(exc)
        household = household_of(request)
        taken = scoped(SavedMeal, request).filter(name__iexact=fields["name"]).exists()
        if taken:
            return Response(
                {
                    "detail": f"Nothing was saved. There is already a saved meal called "
                    f"{fields['name']!r}; delete it first or pick another name."
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            with transaction.atomic():
                row = SavedMeal.objects.create(household=household, **fields)
        except IntegrityError:
            return Response(
                {
                    "detail": f"Nothing was saved. {fields['name']!r} was just saved by "
                    f"another call."
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        row.refresh_from_db()
        return Response(saved_meal_dict(row), status=status.HTTP_201_CREATED)


class SavedMealDetailView(APIView):
    @extend_schema(
        operation_id="api_body_coach_saved_meals_destroy",
        tags=TAGS,
        responses={
            204: OpenApiResponse(description="The preset is deleted; logged meals are untouched."),
            404: DetailSerializer,
        },
    )
    def delete(self, request, meal_id):
        deleted, _ = scoped(SavedMeal, request).filter(pk=meal_id).delete()
        if not deleted:
            return Response(
                {"detail": "Nothing was deleted. There is no saved meal with that id."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(status=status.HTTP_204_NO_CONTENT)


class SavedMealLogView(APIView):
    @extend_schema(
        operation_id="api_body_coach_saved_meals_log",
        tags=TAGS,
        request=None,
        responses={201: SavedMealLogSerializer, 404: DetailSerializer},
        description="Logs the saved meal now: one meal_logs row with its estimated macros.",
    )
    def post(self, request, meal_id):
        meal = scoped(SavedMeal, request).filter(pk=meal_id).first()
        if meal is None:
            return Response(
                {"detail": "Nothing was logged. There is no saved meal with that id."},
                status=status.HTTP_404_NOT_FOUND,
            )
        row = service.log_meal(
            request,
            description=meal.description or meal.name,
            calories_kcal=meal.calories_kcal,
            protein_g=meal.protein_g,
            carbs_g=meal.carbs_g,
            fat_g=meal.fat_g,
        )
        words = (
            f"Logged {meal.name!r}: {meal.calories_kcal} kcal and {meal.protein_g:g} g protein, "
            f"estimates saved with the meal, not measurements."
        )
        return Response(
            {"meal_log": MealLogSerializer(row).data, "words": words},
            status=status.HTTP_201_CREATED,
        )


# =============================================================================
# Proposals
# =============================================================================


class AcceptSerializer(serializers.Serializer):
    accepted = serializers.BooleanField()
    meal_target = MealTargetSerializer()
    words = serializers.CharField()


class DismissSerializer(serializers.Serializer):
    dismissed = serializers.BooleanField()
    words = serializers.CharField()


def _proposal_or_refusal(request, proposal_id, verb: str):
    row = scoped(EnergyProposal, request).filter(pk=proposal_id).first()
    if row is None:
        raise Refused(
            f"Nothing was {verb}. There is no energy proposal with that id.",
            status.HTTP_404_NOT_FOUND,
        )
    if row.status != STATUS_PENDING:
        raise Refused(
            f"Nothing was {verb}. That proposal is already {row.status}, so it can no longer be "
            f"{verb}; the meal target is as it was.",
            status.HTTP_409_CONFLICT,
        )
    return row


class ProposalAcceptView(APIView):
    @extend_schema(
        operation_id="api_body_coach_proposal_accept",
        tags=TAGS,
        request=None,
        responses={200: AcceptSerializer, 404: DetailSerializer, 409: DetailSerializer},
        description=(
            "Accepts a pending proposal: a meal target effective today with the proposed "
            "calories, protein, carbs and fat carried from the current target."
        ),
    )
    def post(self, request, proposal_id):
        try:
            proposal = _proposal_or_refusal(request, proposal_id, "accepted")
        except Refused as exc:
            return _refusal(exc)
        target = service.accept(request, proposal)
        words = (
            f"Accepted. The daily target is {target.calories_kcal} kcal from "
            f"{target.effective_from_date.isoformat()}, protein {target.protein_g:g} g carried "
            f"from the target before. It came from an estimate; the next check is in two weeks."
        )
        return Response(
            {"accepted": True, "meal_target": MealTargetSerializer(target).data, "words": words}
        )


class ProposalDismissView(APIView):
    @extend_schema(
        operation_id="api_body_coach_proposal_dismiss",
        tags=TAGS,
        request=None,
        responses={200: DismissSerializer, 404: DetailSerializer, 409: DetailSerializer},
        description="Dismisses a pending proposal. The meal target is not touched.",
    )
    def post(self, request, proposal_id):
        try:
            proposal = _proposal_or_refusal(request, proposal_id, "dismissed")
        except Refused as exc:
            return _refusal(exc)
        service.dismiss(request, proposal)
        return Response(
            {
                "dismissed": True,
                "words": "Dismissed. The meal target is unchanged; nothing else was written.",
            }
        )


# =============================================================================
# Weekly line
# =============================================================================


class WeeklyLineSerializer(serializers.Serializer):
    say = serializers.BooleanField()
    facts = serializers.CharField(allow_blank=True)
    reason = serializers.CharField(allow_blank=True, help_text="Why not, when say is false.")


class WeeklyLineView(APIView):
    @extend_schema(
        operation_id="api_body_coach_weekly_line_retrieve",
        tags=TAGS,
        responses={200: WeeklyLineSerializer},
        description=(
            "The Monday weight line: trend, rate against the 0.5 to 1.0% band, distance to the "
            "goal, waist change. say is false when the line is off or the trend is too thin."
        ),
    )
    def get(self, request):
        return Response(service.weekly_body(request))
