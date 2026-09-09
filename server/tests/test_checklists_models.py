"""Empirical checks for `checklists/migrations/0001_initial.py`'s hand-edited
half - the parts `makemigrations` did not generate and that a model-only
review cannot confirm: the search_path swap actually lands the three tables
in `public` (not `django`, the default for every other Django-owned
table), the `updated_at` touch trigger actually fires on UPDATE, the
`(item, day)` unique constraint holds even across a soft-delete, and the
measured-tick guard trigger backstops the rule for a writer that bypasses
`checklists/serializers.py` entirely (a raw `.objects.create()`, exactly
like this test does).
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from django.db import IntegrityError, ProgrammingError, connection
from django.utils import timezone

from checklists.models import Checklist, ChecklistItem, ChecklistTick

pytestmark = pytest.mark.django_db

# ADR 0045: a `Checklist` names a household. Every test below that builds one
# takes the `household_a` fixture (`tests/conftest.py`) and passes it; ITEMS
# and TICKS still take none, because `ChecklistItem.save`/`ChecklistTick.save`
# derive the household from their parent - an item's household IS its
# checklist's, by definition, and there is no state in which they may differ.


def test_tables_physically_live_in_public_not_django():
    with connection.cursor() as cursor:
        cursor.execute(
            "select table_schema from information_schema.tables where table_name = %s",
            ["checklists"],
        )
        (schema,) = cursor.fetchone()
    assert schema == "public"


def test_updated_at_touch_trigger_overrides_whatever_the_caller_supplies(household_a):
    """Not a before/after timestamp comparison - Postgres's `now()` is fixed
    for the lifetime of one transaction, and pytest-django wraps a test in
    exactly one, so an INSERT's `now()` and a later UPDATE's `now()` in the
    SAME test would read identical regardless of any real wall-clock gap
    (confirmed the hard way: a `time.sleep()`-based version of this test
    failed even though the trigger itself was firing correctly). Supplying
    an obviously-stale value INSIDE the UPDATE's own SET clause and proving
    the trigger overwrote it anyway is a real test of this class's own doc
    comment claim ("regardless of what a caller's own UPDATE statement
    supplies") and has no timing dependency at all.
    """
    checklist = Checklist.objects.create(household=household_a, name="bio")
    stale = timezone.now() - timedelta(days=30)

    checklist.updated_at = stale
    checklist.name = "bio (renamed)"
    checklist.save(update_fields=["name", "updated_at"])
    checklist.refresh_from_db()

    assert checklist.updated_at != stale
    assert checklist.updated_at > stale


def test_created_at_and_updated_at_are_stamped_by_the_database_not_left_null(household_a):
    checklist = Checklist.objects.create(household=household_a, name="bio")
    assert checklist.created_at is not None
    assert checklist.updated_at is not None


def test_item_day_unique_constraint_holds_even_across_a_soft_delete(household_a):
    checklist = Checklist.objects.create(household=household_a, name="bio")
    item = ChecklistItem.objects.create(checklist=checklist, text="squats")
    ChecklistTick.objects.create(item=item, day=20000)

    with pytest.raises(IntegrityError):
        ChecklistTick.objects.create(item=item, day=20000)


def test_revive_a_soft_deleted_tick_via_update_not_a_second_insert(household_a):
    checklist = Checklist.objects.create(household=household_a, name="bio")
    item = ChecklistItem.objects.create(checklist=checklist, text="squats")
    tick = ChecklistTick.objects.create(item=item, day=20000)

    tick.deleted_at = timezone.now()
    tick.save(update_fields=["deleted_at"])

    # The revival path this ticket's server API uses: UPDATE the existing
    # tombstoned row, never a second INSERT (which the unique constraint
    # would reject anyway - test_item_day_unique_constraint_holds already
    # proves that half).
    tick.deleted_at = None
    tick.save(update_fields=["deleted_at"])
    tick.refresh_from_db()

    assert tick.deleted_at is None
    assert ChecklistTick.objects.filter(item=item, day=20000).count() == 1


def test_measured_tick_guard_trigger_refuses_a_valueless_tick_bypassing_the_serializer(household_a):
    """The backstop, not the primary guard - `checklists/serializers.py`'s
    own validation is what a normal API write goes through and is tested
    against the exact 400 wording separately. This proves the DB-level
    trigger fires even for a raw ORM write that skips the serializer
    entirely (django-engine ticket 04's own instruction: "add a trigger in
    RunSQL if it is cheap")."""
    checklist = Checklist.objects.create(household=household_a, name="fitness")
    measured_item = ChecklistItem.objects.create(
        checklist=checklist, text="walk 10k steps", measure_unit="steps"
    )

    # Postgres RAISE EXCEPTION with no explicit SQLSTATE (the plain form
    # used in this trigger) surfaces through psycopg/Django as
    # ProgrammingError, not IntegrityError - confirmed empirically rather
    # than assumed (a real CHECK constraint violation, e.g. the (item, day)
    # unique index above, raises IntegrityError instead; the two are
    # genuinely different exception classes for genuinely different kinds
    # of refusal).
    with pytest.raises(ProgrammingError, match="is measured in"):
        ChecklistTick.objects.create(item=measured_item, day=20000, value=None)


def test_measured_tick_guard_trigger_allows_a_valued_tick(household_a):
    checklist = Checklist.objects.create(household=household_a, name="fitness")
    measured_item = ChecklistItem.objects.create(
        checklist=checklist, text="walk 10k steps", measure_unit="steps"
    )
    tick = ChecklistTick.objects.create(item=measured_item, day=20000, value=8400.0)
    assert tick.value == 8400.0


def test_measured_tick_guard_trigger_leaves_a_binary_item_untouched(household_a):
    checklist = Checklist.objects.create(household=household_a, name="chores")
    binary_item = ChecklistItem.objects.create(checklist=checklist, text="take out trash")
    tick = ChecklistTick.objects.create(item=binary_item, day=20000, value=None)
    assert tick.value is None
