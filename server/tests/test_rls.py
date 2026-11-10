"""Row-level security, the backstop under the Python choke point (household/rls.py).

The whole suite already runs every request as `legion_app` under the policies (conftest's
`_rls_on`). This file checks the layer on its own terms:

- the catalog: every RLS table that exists has RLS on and the policy, by name;
- SQL: as `legion_app` bound to household B, no row of household A is visible, writable or
  insertable, and with no household bound nothing is visible at all (fail closed);
- the backstop: with the Python filter BROKEN on purpose (a view's `scoped()` replaced by an
  unscoped query), household B's request still sees none of A's rows. And the same broken view
  with RLS off DOES leak, which is what proves the first half tests something.
"""
from __future__ import annotations

import uuid

import pytest
from django.db import connection, transaction
from django.db.utils import DatabaseError

from household import rls
from household.tenancy import TENANT_TABLES

pytestmark = pytest.mark.django_db


def _existing_rls_tables() -> list[str]:
    with connection.cursor() as cursor:
        return [t for t in rls.RLS_TABLES if rls._schema_of(cursor, t)]


def test_rls_tables_are_tenant_tables():
    assert set(rls.RLS_TABLES) <= set(TENANT_TABLES)
    assert rls.SHARED_READ_TABLES <= set(rls.RLS_TABLES)
    assert rls.NULL_OWN_TABLES <= set(rls.RLS_TABLES)


def test_every_rls_table_has_rls_on_and_the_policy():
    tables = _existing_rls_tables()
    # item_lists / list_items have no test mirror; everything else must be here.
    assert len(tables) >= len(rls.RLS_TABLES) - 2, tables
    with connection.cursor() as cursor:
        for table in tables:
            cursor.execute(
                "select c.relrowsecurity, c.relforcerowsecurity from pg_class c "
                "join pg_namespace n on n.oid = c.relnamespace "
                "where c.relname = %s and n.nspname = %s",
                [table, rls._schema_of(cursor, table)],
            )
            enabled, forced = cursor.fetchone()
            assert enabled, f"{table}: row level security is off"
            # Not FORCE: the owner (the job / management-command path) bypasses on purpose.
            assert not forced, f"{table}: FORCE would bind the owner and break every job"
            cursor.execute(
                "select policyname from pg_policies where tablename = %s", [table]
            )
            assert rls.POLICY in {r[0] for r in cursor.fetchall()}, table


def test_the_role_cannot_bypass():
    with connection.cursor() as cursor:
        cursor.execute(
            "select rolsuper, rolbypassrls, rolcanlogin from pg_roles where rolname = %s",
            [rls.RLS_ROLE],
        )
        assert cursor.fetchone() == (False, False, False)


def _as_app(cursor, household_id, user_id=None) -> None:
    cursor.execute(f"set local role {rls.RLS_ROLE}")
    cursor.execute(
        "select set_config(%s, %s, true), set_config(%s, %s, true)",
        [rls.HOUSEHOLD_SETTING, str(household_id or ""), rls.USER_SETTING, str(user_id or "")],
    )


def _reset(cursor) -> None:
    cursor.execute("set local role none")
    cursor.execute(
        "select set_config(%s, '', true), set_config(%s, '', true)",
        [rls.HOUSEHOLD_SETTING, rls.USER_SETTING],
    )


def _seed_a(token_a) -> None:
    """Rows in household A on a spread of RLS tables, through the real API."""
    assert token_a.post(
        "/api/events", {"title": "Dentist", "starts_at": "2026-12-01T09:00:00Z"}, format="json"
    ).status_code == 201
    assert token_a.post("/api/checklists/", {"name": "Hardware"}, format="json").status_code == 201


def test_household_b_sees_writes_and_inserts_nothing_of_household_a(
    token_a, household_a, household_b
):
    _seed_a(token_a)
    tables = _existing_rls_tables()
    with transaction.atomic(), connection.cursor() as cursor:
        seeded = []
        for table in tables:
            cursor.execute(
                f'select count(*) from "{table}" where household_id = %s', [household_a.id]
            )
            if cursor.fetchone()[0]:
                seeded.append(table)
        assert {"events", "checklists"} <= set(seeded), seeded

        _as_app(cursor, household_b.id)
        for table in tables:
            cursor.execute(
                f'select count(*) from "{table}" where household_id = %s', [household_a.id]
            )
            assert cursor.fetchone()[0] == 0, f"{table}: B read A's rows"
            cursor.execute(
                f'update "{table}" set household_id = household_id where household_id = %s',
                [household_a.id],
            )
            assert cursor.rowcount == 0, f"{table}: B updated A's rows"
            cursor.execute(f'delete from "{table}" where household_id = %s', [household_a.id])
            assert cursor.rowcount == 0, f"{table}: B deleted A's rows"

        # A row stamped with A's household is refused outright, not silently hidden.
        with pytest.raises(DatabaseError, match="row-level security"):
            with transaction.atomic():
                cursor.execute(
                    "insert into checklists "
                    "(id, household_id, name, sync_id, created_at, updated_at) "
                    "values (%s, %s, 'planted', %s, now(), now())",
                    [uuid.uuid4(), household_a.id, uuid.uuid4().hex],
                )

        # No household bound: nothing at all (fail closed).
        _as_app(cursor, None)
        for table in seeded:
            cursor.execute(f'select count(*) from "{table}"')
            assert cursor.fetchone()[0] == 0, f"{table}: visible with no household bound"
        _reset(cursor)

        # The owner (jobs, management commands) still sees everything.
        cursor.execute("select count(*) from events where household_id = %s", [household_a.id])
        assert cursor.fetchone()[0] >= 1


def test_shared_builtin_procedures_are_readable_but_not_writable(household_b):
    from procedures.models import Procedure

    Procedure.objects.create(household=None, name=f"builtin_{uuid.uuid4().hex[:6]}")
    with transaction.atomic(), connection.cursor() as cursor:
        _as_app(cursor, household_b.id)
        cursor.execute("select count(*) from procedures where household_id is null")
        assert cursor.fetchone()[0] >= 1
        cursor.execute("update procedures set name = name where household_id is null")
        assert cursor.rowcount == 0
        _reset(cursor)


def test_a_null_household_device_log_is_its_own_users_only(household_b):
    user_one, user_two = uuid.uuid4(), uuid.uuid4()
    with transaction.atomic(), connection.cursor() as cursor:
        _as_app(cursor, household_b.id, user_one)
        cursor.execute(
            "select count(*) from device_logs where household_id is null and user_id <> %s",
            [user_one],
        )
        assert cursor.fetchone()[0] == 0
        _as_app(cursor, household_b.id, user_two)
        cursor.execute("select count(*) from device_logs where user_id = %s", [user_one])
        assert cursor.fetchone()[0] == 0
        _reset(cursor)


def _break_the_choke_point(monkeypatch):
    """Replace the checklists views' scoped() with an UNSCOPED query: the bug RLS is for."""
    import checklists.views as views

    monkeypatch.setattr(views, "scoped", lambda model, request: model.objects.all())


def test_rls_holds_when_the_python_filter_is_broken(token_a, token_b, monkeypatch, settings):
    assert settings.LEGION_RLS is True  # conftest's _rls_on
    made = token_a.post("/api/checklists/", {"name": "Only A's"}, format="json")
    assert made.status_code == 201
    _break_the_choke_point(monkeypatch)
    names = {row["name"] for row in token_b.get("/api/checklists/").data["results"]}
    assert "Only A's" not in names


def test_the_broken_filter_does_leak_without_rls(token_a, token_b, monkeypatch, settings):
    """The control: the same broken view with RLS off shows A's row to B. If this ever stops
    leaking, the test above is no longer testing RLS."""
    made = token_a.post("/api/checklists/", {"name": "Only A's"}, format="json")
    assert made.status_code == 201
    _break_the_choke_point(monkeypatch)
    settings.LEGION_RLS = False
    names = {row["name"] for row in token_b.get("/api/checklists/").data["results"]}
    assert "Only A's" in names


def test_role_and_settings_are_reset_after_a_request(token_a):
    token_a.get("/api/checklists/")
    with connection.cursor() as cursor:
        cursor.execute(
            "select current_user, current_setting(%s, true)", [rls.HOUSEHOLD_SETTING]
        )
        user, household = cursor.fetchone()
    assert user != rls.RLS_ROLE
    assert not household


def test_rls_off_leaves_the_request_alone(token_a, settings):
    settings.LEGION_RLS = False
    token_a.get("/api/checklists/")
    assert not rls.is_active()
