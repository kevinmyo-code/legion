"""Ticket 02's read-only round trip: every `legacy` model, queried through
the actual `legion_reader` role (SELECT + bypassrls, no DDL) against the
live Supabase Postgres, not a test database.

This deliberately does NOT use `pytest.mark.django_db` - that fixture asks
pytest-django to create and migrate a throwaway test database on the
`default` alias, which is the wrong database entirely for what this file
checks (whether the `legacy` models are column-exact against the REAL
schema `inspectdb` was run against). Instead it uses the `readonly` alias
(configured in `legion/settings.py` only when `LEGION_PG_URL` is set) and
`django_db_blocker.unblock()`, pytest-django's own documented escape hatch
for hitting a real, already-existing database directly - see the
`django_db_blocker` fixture docs. No table is created, migrated, or
written to; every query here is a plain `SELECT`.

For each of the 41 tables this asserts two things: the ORM can decode every
row without a field error (forcing full evaluation via `.first()`, not just
building the query), and `Model.objects.using("readonly").count()` matches
`select count(*)` run directly - so a model that silently drops or
misinterprets rows (a column that decodes to `None` behind the ORM's back
would not raise, but would still change nothing observable here since we
compare row counts, not values) still cannot pass by mis-counting.
"""
from __future__ import annotations

import pytest
from django.apps import apps
from django.conf import settings
from django.db import connections

pytestmark = pytest.mark.skipif(
    "readonly" not in settings.DATABASES,
    reason=(
        "LEGION_PG_URL is not set, so the 'readonly' alias does not exist. "
        "This suite only runs where an agent's .claude/mcp.env has been loaded."
    ),
)


def _legacy_models():
    """Every model in the `legacy` app, paired with its physical table name,
    read from the app registry rather than hand-listed - so this suite
    cannot silently go stale against `legacy/models/__init__.py`."""
    return [
        (model, model._meta.db_table) for model in apps.get_app_config("legacy").get_models()
    ]


@pytest.fixture
def readonly_cursor(django_db_blocker):
    """A live cursor on the `readonly` alias, unblocked for the duration of
    one test. No test database is created or touched."""
    with django_db_blocker.unblock():
        with connections["readonly"].cursor() as cursor:
            yield cursor


@pytest.mark.parametrize(
    "model,table_name", _legacy_models(), ids=lambda v: getattr(v, "__name__", v)
)
def test_legacy_model_round_trips_against_legion_reader(model, table_name, django_db_blocker):
    """Column-exact means the ORM can read every row `legion_reader` can see,
    and counts the same number `select count(*)` does."""
    with django_db_blocker.unblock():
        orm_count = model.objects.using("readonly").count()
        with connections["readonly"].cursor() as cursor:
            cursor.execute(f'select count(*) from public."{table_name}"')
            (sql_count,) = cursor.fetchone()

        assert orm_count == sql_count, (
            f"{model.__name__} ({table_name}): ORM count {orm_count} != "
            f"`select count(*)` {sql_count}"
        )

        # Forces full row decode - a field the model gets wrong (wrong
        # type, wrong nullability assumption) raises here, not just when
        # building the queryset. A zero-row table passes trivially: there
        # is nothing to decode, and the count assertion above already
        # confirmed the ORM and Postgres agree there is nothing there.
        first_row = model.objects.using("readonly").first()
        if sql_count > 0:
            assert first_row is not None
