"""Coursework, reminders, tasks and calendar events - the aspect the
execution plan (Phase 1) names as the first slice to cross to Django end to
end, precisely because it already has a well-tested merge on the phone to
compare a Django implementation against."""
from __future__ import annotations

from django.conf import settings
from django.db import models

from legacy.enums import Provenance
from legacy.models.fleet import Vehicle
from legacy.models.tenancy import household_field, household_unique


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
    google_event_id = models.TextField(null=True)
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
    origin_guid = models.TextField(null=True)
    structured_meta = models.JSONField(null=True)
    vehicle = models.ForeignKey(
        Vehicle, db_column="vehicle_id", null=True, on_delete=models.DO_NOTHING, related_name="+"
    )
    kind = models.TextField()  # CHECK: reminder | event | task | suggestion; DB default 'reminder'
    # web-revamp ticket 14. Added by `ingest/migrations/0009` (SQL in
    # `api/event_columns.py`), not by Supabase. CHECK in (0, 5, 10, 15, 30,
    # 60, 120, 1440); null is no reminder.
    remind_minutes_before = models.IntegerField(null=True)
    # ADR 0052 (web-revamp ticket 06). Added by `ingest/migrations/0010`
    # (SQL in `household/visibility_sql.py`), not by Supabase. Null owner is
    # shared; set is private to that member. `created_by` is set from the
    # request on create and never accepted from a body. Neither is ever on
    # the wire: the serializer renders `visibility` instead. The owner is
    # ON DELETE RESTRICT in the SQL (`ingest/migrations/0014`): a user who
    # owns private rows cannot be hard-deleted, because SET NULL would make
    # them shared. Django is told RESTRICT too, so it refuses before the SQL
    # does. `created_by` is ON DELETE SET NULL in the SQL (attribution only).
    owner_user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        db_column="owner_user_id",
        null=True,
        on_delete=models.RESTRICT,
        related_name="+",
    )
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        db_column="created_by_id",
        null=True,
        on_delete=models.DO_NOTHING,
        related_name="+",
    )

    household = household_field()

    class Meta:
        managed = False
        db_table = "events"
        constraints = [
            household_unique("events", "google_event_id"),
            household_unique("events", "origin_guid"),
        ]


class EventSkip(models.Model):
    id = models.UUIDField(primary_key=True)
    event = models.ForeignKey(
        Event, db_column="event_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    skip_date = models.DateField()
    created_at = models.DateTimeField()
    # web-revamp ticket 08. Added by `ingest/migrations/0013` (SQL in
    # `api/event_columns.py`), so a skip travels `/api/changes` and DELETE
    # tombstones it. `updated_at` is stamped by the touch trigger on UPDATE.
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "event_skips"
        unique_together = (("event", "skip_date"),)


class SuggestionPin(models.Model):
    """One member's "I want to go" on one suggestion (Kevin, 2026-10-09).

    Created by `ingest/migrations/0019` (SQL in `api/suggestion_pins.py`), not
    by Supabase, so `managed = False` like every table here. One row per
    (event, member), revived rather than duplicated on a second pin; an unpin
    tombstones it. The event and the pin share a household by composite
    foreign key, and the database refuses a pin on anything but a live
    suggestion or by anyone but a member of that household.
    """

    id = models.UUIDField(primary_key=True)
    event = models.ForeignKey(
        Event, db_column="event_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    # The member who wants to go. Their `id` and name ARE on the wire
    # (`pinned_by`), the same way `GET /api/households/me` lists the roster;
    # this is not an owner, and no privacy is decided by it.
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL, db_column="user_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "suggestion_pins"
        unique_together = (("event", "user"),)
