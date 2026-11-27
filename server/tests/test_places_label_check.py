"""`places_label_length`: the 30-character place label cap held by Postgres,
not only by `api/places.validate_place_label` (django-engine ticket 14).

Every insert here is raw SQL on purpose. The API already refuses a long label
in words (`test_places_api.py`), so a test through the API passes with or
without the CHECK and proves nothing about it. These go around the serializer,
the way a second writer would.
"""
from __future__ import annotations

import uuid

import pytest
from django.db import IntegrityError, connection, transaction

from api.place_columns import LABEL_CHECK_NAME, add_place_label_check
from api.places import LABEL_MAX_LENGTH

pytestmark = pytest.mark.django_db


def _insert(household, label: str) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            "insert into public.places (id, label, latitude, longitude, household_id) "
            "values (%s, %s, 1.0, 2.0, %s)",
            [uuid.uuid4(), label, household.id],
        )


def _validated() -> bool | None:
    """`convalidated` for the CHECK, or None when it is not there at all."""
    with connection.cursor() as cursor:
        cursor.execute(
            "select convalidated from pg_constraint "
            "where conname = %s and conrelid = 'public.places'::regclass",
            [LABEL_CHECK_NAME],
        )
        row = cursor.fetchone()
    return None if row is None else row[0]


def test_postgres_refuses_a_label_one_past_the_cap(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        _insert(household_a, "x" * (LABEL_MAX_LENGTH + 1))


def test_postgres_accepts_a_label_at_the_cap(household_a):
    _insert(household_a, "x" * LABEL_MAX_LENGTH)


def test_the_check_is_validated_on_a_clean_table():
    assert _validated() is True


def test_running_it_again_is_a_no_op(household_a):
    _insert(household_a, "home")
    with connection.cursor() as cursor:
        assert add_place_label_check(cursor) is None
    assert _validated() is True


def test_an_existing_overlong_row_never_fails_the_migration(household_a):
    """A deploy must not die on a row nobody can see from here. The CHECK
    still goes on, NOT VALID, so every new write is held to it; it is
    validated on the first run that finds no violator."""
    with connection.cursor() as cursor:
        cursor.execute(f"alter table public.places drop constraint {LABEL_CHECK_NAME}")
    _insert(household_a, "y" * (LABEL_MAX_LENGTH + 5))

    with connection.cursor() as cursor:
        message = add_place_label_check(cursor)
    assert message is not None and "1 place" in message
    assert _validated() is False

    with pytest.raises(IntegrityError), transaction.atomic():
        _insert(household_a, "z" * (LABEL_MAX_LENGTH + 1))

    with connection.cursor() as cursor:
        cursor.execute("update public.places set label = 'short' where length(label) > %s",
                       [LABEL_MAX_LENGTH])
        assert add_place_label_check(cursor) is None
    assert _validated() is True
