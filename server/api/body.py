"""`/api/body/<table>` - eight tables, one interface.

This is the aspect that proves the shape in `api/synced.py` scales to "many
small tables, one interface": bodyweight, meals, sleep and workouts are
eight uniform tables with no departures at all - same identity column
(`origin_guid`), same tombstone, same feed - so each one costs a serializer
and a registry line, and nothing else. `BodyBackend.kt` is the phone-side
contract (24 functions over the same eight tables) and every serializer
here is field-for-field its matching `Remote*` shape.

`meal_logs` and `meal_targets` are modelled in `legacy/models/pantry.py`
rather than `legacy/models/body.py`, because ticket 02 split the model
files by domain and meals read as pantry. Their DDL is in
`20260902000200_aspect_body.sql` with the other six, and `BodyBackend`
carries them, so they are routed here. The split is confusing enough to be
worth this paragraph rather than a silent import.

## The four macro columns on `meal_logs` are ESTIMATES

CLAUDE.md section 4 rule 5, and the body migration's own header comment: a
plate of food never prints its own calorie count, so `calories_kcal`,
`protein_g`, `carbs_g` and `fat_g` are the LLM's guess from the meal's
description. They are excluded from every gate (there is none here - these
rows are authored, not ingested) and must read as estimates on every
surface. This API's user-facing surface is its generated schema, so each of
the four carries a `help_text` saying so, and `meal_targets`' identically
named columns deliberately do not: a target is a number Kevin chose.
"""
from __future__ import annotations

from api.synced import (
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    choice_error,
    minimum_error,
    range_error,
)
from legacy.models.body import (
    BodyweightLog,
    SleepLog,
    SleepTarget,
    WorkoutPlan,
    WorkoutPlanItem,
    WorkoutSetLog,
)
from legacy.models.pantry import MealLog, MealTarget

# `legacy/CONSTRAINTS.md`, read from the live schema. Every value below is a
# CHECK Postgres itself enforces; the serializers refuse the same values
# first so the 400 can name the allowed set instead of quoting a constraint
# name at a phone.
TRUST_TIER_CHOICES = ("PROVEN", "REPORTED")
WEIGHT_UNIT_CHOICES = ("lbs", "kg")
SLEEP_QUALITY_BOUNDS = (1, 5)
SLEEP_MINUTES_BOUNDS = (0, 1440)

ESTIMATE_HELP = (
    "An estimate, not a measurement: the model's guess from the meal description. "
    "Never gated and never treated as a reconciled figure (CLAUDE.md section 4 rule 5)."
)


def _validate_trust_tier(value: str) -> str:
    if value not in TRUST_TIER_CHOICES:
        raise choice_error("trust_tier", value, TRUST_TIER_CHOICES)
    return value


class BodyweightLogSerializer(SyncedSerializer):
    class Meta:
        model = BodyweightLog
        fields = [
            "id",
            "weight_value",
            "weight_unit",
            "logged_at",
            "trust_tier",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_weight_value(self, value: float) -> float:
        if value <= 0:
            raise minimum_error("weight_value", value, 0, exclusive=True)
        return value

    def validate_weight_unit(self, value: str) -> str:
        if value not in WEIGHT_UNIT_CHOICES:
            raise choice_error("weight_unit", value, WEIGHT_UNIT_CHOICES)
        return value

    def validate_trust_tier(self, value: str) -> str:
        return _validate_trust_tier(value)


class MealLogSerializer(SyncedSerializer):
    class Meta:
        model = MealLog
        fields = [
            "id",
            "description",
            "calories_kcal",
            "protein_g",
            "carbs_g",
            "fat_g",
            "logged_at",
            "source_image_path",
            "trust_tier",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        # See this module's own doc comment - the label travels with the
        # column into the generated schema, so a limb reading the API
        # contract sees the word "estimate" without having to know
        # section 4 rule 5 by heart.
        extra_kwargs = {
            "calories_kcal": {"help_text": ESTIMATE_HELP},
            "protein_g": {"help_text": ESTIMATE_HELP},
            "carbs_g": {"help_text": ESTIMATE_HELP},
            "fat_g": {"help_text": ESTIMATE_HELP},
        }

    def validate_description(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("description")
        return value

    def validate_trust_tier(self, value: str) -> str:
        return _validate_trust_tier(value)


class MealTargetSerializer(SyncedSerializer):
    class Meta:
        model = MealTarget
        fields = [
            "id",
            "calories_kcal",
            "protein_g",
            "carbs_g",
            "fat_g",
            "effective_from_date",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_calories_kcal(self, value: int) -> int:
        if value <= 0:
            raise minimum_error("calories_kcal", value, 0, exclusive=True)
        return value

    def _non_negative(self, field: str, value: float) -> float:
        if value < 0:
            raise minimum_error(field, value, 0, exclusive=False)
        return value

    def validate_protein_g(self, value: float) -> float:
        return self._non_negative("protein_g", value)

    def validate_carbs_g(self, value: float) -> float:
        return self._non_negative("carbs_g", value)

    def validate_fat_g(self, value: float) -> float:
        return self._non_negative("fat_g", value)


class SleepLogSerializer(SyncedSerializer):
    class Meta:
        model = SleepLog
        fields = [
            "id",
            "sleep_date",
            "duration_minutes",
            "quality",
            "notes",
            "logged_at",
            "trust_tier",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_duration_minutes(self, value: int) -> int:
        low, high = SLEEP_MINUTES_BOUNDS
        if not low <= value <= high:
            raise range_error("duration_minutes", value, low, high)
        return value

    def validate_quality(self, value: int | None) -> int | None:
        low, high = SLEEP_QUALITY_BOUNDS
        if value is not None and not low <= value <= high:
            raise range_error("quality", value, low, high)
        return value

    def validate_trust_tier(self, value: str) -> str:
        return _validate_trust_tier(value)


class SleepTargetSerializer(SyncedSerializer):
    class Meta:
        model = SleepTarget
        fields = [
            "id",
            "target_minutes",
            "effective_from_date",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_target_minutes(self, value: int) -> int:
        low, high = SLEEP_MINUTES_BOUNDS
        if not low <= value <= high:
            raise range_error("target_minutes", value, low, high)
        return value


class WorkoutPlanSerializer(SyncedSerializer):
    class Meta:
        model = WorkoutPlan
        fields = [
            "id",
            "sessions_per_week",
            "effective_from_week",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_sessions_per_week(self, value: int) -> int:
        if value < 0:
            raise minimum_error("sessions_per_week", value, 0, exclusive=False)
        return value


class WorkoutPlanItemSerializer(SyncedSerializer):
    class Meta:
        model = WorkoutPlanItem
        fields = [
            "id",
            "exercise",
            "target_sets_per_week",
            "effective_from_week",
            "reps_per_set",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_exercise(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("exercise")
        return value

    def validate_target_sets_per_week(self, value: int) -> int:
        if value <= 0:
            raise minimum_error("target_sets_per_week", value, 0, exclusive=True)
        return value


class WorkoutSetLogSerializer(SyncedSerializer):
    """No `source_list_item_id`. `WorkoutSetLog.sourceListItemId` names a
    phone-local Room row id from a table that has no server counterpart, so
    it would be a dangling reference here - the body migration's own comment
    on that table, and `RemoteWorkoutSetLog`'s, both say so."""

    class Meta:
        model = WorkoutSetLog
        fields = [
            "id",
            "exercise",
            "sets",
            "reps",
            "weight_value",
            "weight_unit",
            "logged_at",
            "trust_tier",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_exercise(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("exercise")
        return value

    def validate_sets(self, value: int) -> int:
        if value <= 0:
            raise minimum_error("sets", value, 0, exclusive=True)
        return value

    def validate_reps(self, value: int | None) -> int | None:
        if value is not None and value <= 0:
            raise minimum_error("reps", value, 0, exclusive=True)
        return value

    def validate_weight_value(self, value: float | None) -> float | None:
        if value is not None and value < 0:
            raise minimum_error("weight_value", value, 0, exclusive=False)
        return value

    def validate_weight_unit(self, value: str | None) -> str | None:
        if value is not None and value not in WEIGHT_UNIT_CHOICES:
            raise choice_error("weight_unit", value, WEIGHT_UNIT_CHOICES)
        return value

    def validate_trust_tier(self, value: str) -> str:
        return _validate_trust_tier(value)


class _BodyViewSet(SyncedModelViewSet):
    """Every body table is uniform - `origin_guid` identity, soft delete,
    no revive on upsert (`SupabaseBodyBackend`'s upsert DTOs carry no
    `deleted_at`, so `on conflict do update` leaves a tombstone alone).
    The eight subclasses below differ only in `table` and serializer."""

    aspect = "body"


class BodyweightLogViewSet(_BodyViewSet):
    table = "bodyweight_logs"
    serializer_class = BodyweightLogSerializer


class MealLogViewSet(_BodyViewSet):
    table = "meal_logs"
    serializer_class = MealLogSerializer


class MealTargetViewSet(_BodyViewSet):
    table = "meal_targets"
    serializer_class = MealTargetSerializer


class SleepLogViewSet(_BodyViewSet):
    table = "sleep_logs"
    serializer_class = SleepLogSerializer


class SleepTargetViewSet(_BodyViewSet):
    table = "sleep_targets"
    serializer_class = SleepTargetSerializer


class WorkoutPlanViewSet(_BodyViewSet):
    table = "workout_plans"
    serializer_class = WorkoutPlanSerializer


class WorkoutPlanItemViewSet(_BodyViewSet):
    table = "workout_plan_items"
    serializer_class = WorkoutPlanItemSerializer


class WorkoutSetLogViewSet(_BodyViewSet):
    table = "workout_set_logs"
    serializer_class = WorkoutSetLogSerializer


BODY_VIEWSETS = [
    BodyweightLogViewSet,
    MealLogViewSet,
    MealTargetViewSet,
    SleepLogViewSet,
    SleepTargetViewSet,
    WorkoutPlanViewSet,
    WorkoutPlanItemViewSet,
    WorkoutSetLogViewSet,
]
