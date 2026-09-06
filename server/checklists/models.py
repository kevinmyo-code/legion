"""Checklists, owned by Django end to end - the first tables this server
creates rather than inherits from Supabase (ADR 0044; django-engine ticket
04, the Phase 1 slice named in `execution-plan.md`: "checklists have no
server tables yet, so their Django models are the FIRST tables Django owns
end to end, no legacy to honour"). Mirrors the phone's
`app/.../data/local/Checklist.kt`, `ChecklistItem.kt`, `ChecklistTick.kt`
column for column: same three entities, same tick-per-day mechanism ("no
reset and no nightly job" - see `Checklist.kt`'s own doc comment for why),
same measured-item rule (`ChecklistController.tick`'s refusal message,
reused verbatim in `checklists/serializers.py`).

**Physically created in the `public` schema, not `django`** - unlike every
other Django-owned table so far (`household_*`), because these are DOMAIN
data every limb reads and writes, sitting alongside the 41 legacy tables
`public` already holds, not server-only bookkeeping. See `0001_initial.py`'s
own comment for the mechanism (a temporary `search_path` swap during that
one migration) and `core/migrations/0001_create_django_schema.py` for why
the opposite default (`django` first) exists at all.

**Room's four sync columns become three here, deliberately.** `sync_id`
(nullable, unique - a client-minted idempotency key a POST retry can be
recognised by, same role as `legacy.Event.origin_guid`), `updated_at`
(server-stamped: `db_default=Now()` on INSERT, a Postgres trigger on every
UPDATE regardless of what the UPDATE statement itself supplies), and
`deleted_at` (a tombstone TIMESTAMP, replacing Room's `deleted` BOOLEAN so a
delete carries a "when" like every other table in this schema does - the
phone's own sync layer already treats `deleted_at IS NOT NULL` as the
tombstone predicate for `public.events`, so this keeps the two shapes
uniform). There is no `server_id` column: unlike Room, this row's own `id`
already IS the server identity - that column existed on the phone only
because Supabase minted an id Room had to store separately from its own
local autoincrement `id`.
"""
from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now


class Checklist(models.Model):
    """A named, reusable checklist - "bio", "morning routine". See this
    module's own doc comment and `Checklist.kt`'s doc comment for the full
    "record and reset with no clock" design this table exists to serve.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.TextField()
    # Null: a plain todo list, done once ever - the moment any tick exists
    # on any day for one of its items (ChecklistController's own rule,
    # moved to the server per this ticket's rule 2). "DAILY"/"WEEKLY", same
    # encoding as Checklist.kt's scheduleKind. No CHECK constraint here,
    # matching that column's own "widening this later needs no migration"
    # posture - this is a vocabulary, not a closed enum.
    schedule_kind = models.TextField(null=True, blank=True)
    schedule_every = models.IntegerField(null=True, blank=True)
    schedule_days_of_week = models.TextField(null=True, blank=True)
    sort_order = models.IntegerField(default=0)
    archived = models.BooleanField(default=False)
    created_at = models.DateTimeField(db_default=Now())
    # Stamped by `checklists_touch_updated_at()` (0001_initial.py) on every
    # UPDATE, regardless of what a caller's own UPDATE statement supplies -
    # same posture as `private.touch_updated_at()` on the legacy tables,
    # REIMPLEMENTED locally here rather than reused: that function lives in
    # a schema (`private`) that a fresh pytest test database - created from
    # a bare Postgres template, never from the live Supabase project - does
    # not have. This app's own tests run exclusively against such a
    # database (never the live one; see this migration's own module doc).
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)
    sync_id = models.CharField(max_length=64, null=True, blank=True, unique=True)

    class Meta:
        db_table = "checklists"

    def __str__(self) -> str:
        return self.name


class ChecklistItem(models.Model):
    """One line inside a `Checklist` - "3 sets goblet squats" under "bio".
    Carries no `done` state of its own, deliberately: "done" is a fact
    about an (item, day) PAIR, stored once per tick in `ChecklistTick`,
    never a column here - see that model's own doc comment for why one
    tick model serves both recurring and non-recurring checklists.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    checklist = models.ForeignKey(Checklist, on_delete=models.CASCADE, related_name="items")
    text = models.TextField()
    sort_order = models.IntegerField(default=0)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)
    sync_id = models.CharField(max_length=64, null=True, blank=True, unique=True)
    # Null: a plain binary item. Non-null ("steps", "kg", "min"): every
    # tick against this item must carry a number - enforced in
    # `checklists/serializers.py` (`ChecklistController.tick`'s own
    # refusal message, reused verbatim: "a number is the point") and
    # backstopped by the `checklists_enforce_measured_tick()` trigger on
    # `checklist_ticks` (0001_initial.py) for any writer that bypasses the
    # serializer - CLAUDE.md section 4 rule 6's posture ("a check that
    # passes when nothing parsed is not a gate") applied to a write path
    # instead of an ingestion one: a rule enforced only in application code
    # is not enforced against every writer.
    measure_unit = models.CharField(max_length=64, null=True, blank=True)
    # The number to aim at, in measure_unit. Null even with measure_unit
    # set means "just record the number, no target to compare against".
    measure_target = models.FloatField(null=True, blank=True)
    # "AT_LEAST" or "AT_MOST" - stored as TEXT with no CHECK constraint,
    # same posture as measure_unit's own vocabulary-not-enum choice.
    measure_direction = models.CharField(max_length=16, null=True, blank=True)

    class Meta:
        db_table = "checklist_items"

    def __str__(self) -> str:
        return self.text


class ChecklistTick(models.Model):
    """One completion of a `ChecklistItem` on one day - see `ChecklistTick.kt`'s
    own doc comment for why `day` (the day the tick COUNTS FOR) and
    `ticked_at` (the real-world instant of the tap) are deliberately
    different, both-stored facts, and why there is one tick model rather
    than a second "done once" table for a non-recurring checklist.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    item = models.ForeignKey(ChecklistItem, on_delete=models.CASCADE, related_name="ticks")
    # Local epoch day (`LocalDate.toEpochDay()`), matching ChecklistTick.day
    # exactly - never a millisecond timestamp, so a timezone change cannot
    # shift which day a tick counts for.
    day = models.IntegerField()
    ticked_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)
    sync_id = models.CharField(max_length=64, null=True, blank=True, unique=True)
    # The actual measured number, or null on a binary item's tick - see
    # ChecklistItem.measure_unit's own doc comment for why a measured
    # item's valueless tick is REFUSED (in the serializer, backstopped by
    # the DB trigger) rather than stored null.
    value = models.FloatField(null=True, blank=True)
    # Provenance of `value` - "USER_REPORTED" today; named so a future
    # Health Connect/scale integration has a vocabulary to write into
    # rather than inventing one at the call site (matches
    # `TickSource.kt`'s own reasoning). Not NULL: every tick has a source,
    # binary items included, because "the user tapped it" is itself a
    # provenance fact worth keeping.
    source = models.TextField(default="USER_REPORTED")

    class Meta:
        db_table = "checklist_ticks"
        constraints = [
            # Unconditional, not partial on deleted_at IS NULL - matching
            # Room's own posture exactly (ChecklistTick.kt's own doc
            # comment): a soft-deleted (item, day) row still occupies this
            # slot forever, so re-ticking the same day revives that row
            # via UPDATE rather than inserting a second one.
            models.UniqueConstraint(fields=["item", "day"], name="checklist_ticks_item_day_uniq"),
        ]

    def __str__(self) -> str:
        return f"item={self.item_id} day={self.day}"
