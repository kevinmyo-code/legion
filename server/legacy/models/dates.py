"""Coursework, reminders, tasks and calendar events - the aspect the
execution plan (Phase 1) names as the first slice to cross to Django end to
end, precisely because it already has a well-tested merge on the phone to
compare a Django implementation against."""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.fleet import Vehicle


class Event(models.Model):
    id = models.UUIDField(primary_key=True)
    title = models.TextField()
    starts_at = models.DateTimeField(null=True)
    ends_at = models.DateTimeField(null=True)
    all_day = models.BooleanField()
    location = models.TextField(null=True)
    notes = models.TextField(null=True)
    source = models.TextField()  # CHECK: legion | google
    # Real index (`events_google_event_id_idx`) is a PARTIAL unique index,
    # `WHERE google_event_id IS NOT NULL` - `unique=True` here is Django's
    # closest built-in approximation (it never emits DDL for this model
    # anyway) and is behaviourally equivalent for reads: Postgres already
    # treats every NULL as distinct under a plain unique constraint too.
    google_event_id = models.TextField(null=True, unique=True)
    done = models.BooleanField()
    done_at = models.DateTimeField(null=True)
    sort_order = models.IntegerField(null=True)
    trigger_place_label = models.TextField(null=True)
    repeat_kind = models.TextField(null=True)  # CHECK: DAILY|WEEKLY|MONTHLY_ON_DATE|YEARLY
    repeat_every = models.IntegerField(null=True)
    repeat_days_of_week = models.TextField(null=True)
    repeat_day = models.IntegerField(null=True)
    repeat_month = models.IntegerField(null=True)
    repeat_end_kind = models.TextField(null=True)  # CHECK: NEVER|ON_DATE|AFTER_COUNT
    repeat_end_date = models.DateField(null=True)
    repeat_end_count = models.IntegerField(null=True)
    exact = models.BooleanField()
    exact_downgraded = models.BooleanField()
    missed_at = models.DateTimeField(null=True)
    missed_dismissed_at = models.DateTimeField(null=True)
    logged_at = models.DateTimeField(null=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(null=True, unique=True)
    structured_meta = models.JSONField(null=True)
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", null=True, on_delete=models.DO_NOTHING, related_name="+"
    )
    kind = models.TextField()  # CHECK: reminder | event | task; DB default 'reminder'

    class Meta:
        managed = False
        db_table = "events"


class EventSkip(models.Model):
    id = models.UUIDField(primary_key=True)
    event = models.ForeignKey(
        Event, db_column="event_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    skip_date = models.DateField()
    created_at = models.DateTimeField()

    class Meta:
        managed = False
        db_table = "event_skips"
        unique_together = (("event", "skip_date"),)
