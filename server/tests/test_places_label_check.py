"""`places_label_length`: the 30-character place label cap held by Postgres,
not only by `api/places.validate_place_label` (django-engine ticket 14).

Inserts here are raw SQL on purpose. The API already refuses a long label in
words (`test_places_api.py`), so a test through the API passes with or
without the CHECK and proves nothing about it. These go around the
serializer, the way a second writer would.

The second half covers a live database that already holds an overlong label,
which nobody can rule out from here: the migration must not fail on it, the
place must still be deletable and renamable, and the nightly job must
validate the CHECK once the row is gone.
"""
from __future__ import annotations

import logging
import uuid

import pytest
from django.core.management import call_command
from django.db import IntegrityError, connection, transaction

from api.place_columns import LABEL_CHECK_NAME, add_place_label_check
from api.places import LABEL_MAX_LENGTH
from ingest import backup
from legacy.models.places import Place

pytestmark = pytest.mark.django_db

OVERLONG = "y" * (LABEL_MAX_LENGTH + 5)


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


@pytest.fixture
def legacy_overlong(household_a):
    """A table as live might be: one overlong label written before the CHECK,
    then the migration run over it."""
    with connection.cursor() as cursor:
        cursor.execute(f"alter table public.places drop constraint {LABEL_CHECK_NAME}")
    _insert(household_a, OVERLONG)
    with connection.cursor() as cursor:
        message = add_place_label_check(cursor)
    assert message is not None and "1 place" in message
    assert _validated() is False
    return OVERLONG


@pytest.fixture
def no_dump(monkeypatch):
    """`backup_nightly` without the pg_dump and the Drive upload."""
    monkeypatch.setattr(backup, "run_nightly", lambda job, write: None)


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


def test_an_overlong_row_never_fails_the_migration_and_new_writes_are_still_held(
    household_a, legacy_overlong
):
    with pytest.raises(IntegrityError), transaction.atomic():
        _insert(household_a, "z" * (LABEL_MAX_LENGTH + 1))


def test_an_overlong_legacy_place_can_be_deleted(auth_client, legacy_overlong):
    response = auth_client.delete(f"/api/places/{legacy_overlong}/")
    assert response.status_code in (200, 204), response.content
    assert Place.objects.get(label=legacy_overlong).deleted_at is not None


def test_an_overlong_legacy_place_can_be_renamed_to_a_short_name(auth_client, legacy_overlong):
    response = auth_client.post(
        f"/api/places/{legacy_overlong}/rename/", {"to": "the gym"}, format="json"
    )
    assert response.status_code == 200, response.content
    assert Place.objects.get(label="the gym").deleted_at is None
    assert Place.objects.get(label=legacy_overlong).deleted_at is not None


def test_a_rename_to_another_overlong_name_is_refused_in_words(auth_client, legacy_overlong):
    response = auth_client.post(
        f"/api/places/{legacy_overlong}/rename/",
        {"to": "w" * (LABEL_MAX_LENGTH + 1)},
        format="json",
    )
    assert response.status_code == 400
    text = str(response.data)
    assert "at most 30" in text
    assert LABEL_CHECK_NAME not in text and "constraint" not in text
    assert Place.objects.get(label=legacy_overlong).deleted_at is None


def test_the_nightly_job_validates_once_no_live_row_breaks_it(
    auth_client, legacy_overlong, no_dump
):
    auth_client.delete(f"/api/places/{legacy_overlong}/")
    call_command("backup_nightly")
    assert _validated() is True


def test_the_nightly_job_warns_with_the_count_while_a_row_breaks_it(
    legacy_overlong, no_dump, caplog
):
    with caplog.at_level(logging.WARNING):
        call_command("backup_nightly")
    assert _validated() is False
    warned = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("1 place" in m and LABEL_CHECK_NAME in m for m in warned), warned


def test_the_nightly_job_is_quiet_when_the_check_is_already_valid(no_dump, caplog):
    with caplog.at_level(logging.WARNING):
        call_command("backup_nightly")
    assert _validated() is True
    assert not [r for r in caplog.records if LABEL_CHECK_NAME in r.getMessage()]
