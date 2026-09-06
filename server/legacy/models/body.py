"""Bodyweight, sleep, workouts, and cross-aspect goals. Seven tables -
`Goal.aspect` is a free-text label naming which OTHER aspect a goal belongs
to (fleet, ledger, pantry, ...), not a foreign key to any table here."""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance


class BodyweightLog(models.Model):
    id = models.UUIDField(primary_key=True)
    weight_value = models.FloatField()
    weight_unit = models.TextField()  # CHECK: lbs | kg
    logged_at = models.DateTimeField()
    trust_tier = models.TextField()  # CHECK: PROVEN | REPORTED
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "bodyweight_logs"


class SleepLog(models.Model):
    id = models.UUIDField(primary_key=True)
    sleep_date = models.DateField()
    duration_minutes = models.IntegerField()
    quality = models.IntegerField(null=True)
    notes = models.TextField(null=True)
    logged_at = models.DateTimeField()
    trust_tier = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "sleep_logs"


class SleepTarget(models.Model):
    id = models.UUIDField(primary_key=True)
    target_minutes = models.IntegerField()
    effective_from_date = models.DateField(unique=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "sleep_targets"


class WorkoutPlan(models.Model):
    id = models.UUIDField(primary_key=True)
    sessions_per_week = models.IntegerField()
    effective_from_week = models.DateField(unique=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "workout_plans"


class WorkoutPlanItem(models.Model):
    """No foreign key to `WorkoutPlan` - confirmed against the live schema,
    not an inspectdb miss. This table is keyed by `(exercise,
    effective_from_week)` on its own, standing alongside `WorkoutPlan`
    rather than under it."""

    id = models.UUIDField(primary_key=True)
    exercise = models.TextField()
    target_sets_per_week = models.IntegerField()
    effective_from_week = models.DateField()
    reps_per_set = models.IntegerField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "workout_plan_items"
        unique_together = (("exercise", "effective_from_week"),)


class WorkoutSetLog(models.Model):
    id = models.UUIDField(primary_key=True)
    exercise = models.TextField()
    sets = models.IntegerField()
    reps = models.IntegerField(null=True)
    weight_value = models.FloatField(null=True)
    weight_unit = models.TextField(null=True)  # CHECK: lbs | kg, or null
    logged_at = models.DateTimeField()
    trust_tier = models.TextField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "workout_set_logs"


class Goal(models.Model):
    id = models.UUIDField(primary_key=True)
    lineage_id = models.BigIntegerField()
    aspect = models.TextField()
    statement = models.TextField()
    target_value = models.FloatField(null=True)
    unit = models.TextField(null=True)
    metric_key = models.TextField(null=True)
    deadline_epoch = models.DateTimeField(null=True)
    status = models.TextField()  # DB default 'active'
    supersedes_guid = models.TextField(null=True)
    closed_at = models.DateTimeField(null=True)
    created_at_client = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "goals"
