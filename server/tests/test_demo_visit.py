"""recruiter-demo ticket 13 (ADR 0069): the per-visit household and the one
exemption the gated-row triggers make for it.

The database refuses to delete a verified money row (CLAUDE.md section 4:
post a reversal). The exemption lets DELETE through only when the row's
household carries `is_demo_visit`, which `POST /api/demo/enter` sets at
creation and nothing else can set. What this file pins:

1. DELETE of every kind of gated row is still refused in a normal household,
   and allowed in a demo-visit one; UPDATE stays refused in both.
2. Nothing on the API, the admin or a serializer can set or flip the flag.
3. One visit's seed: how long it takes, and that it is complete in each copy.
"""

from __future__ import annotations

import time

import pytest
from django.db import DatabaseError, connection, transaction

from household.demo_seed import seed_household
from household.models import Household

pytestmark = pytest.mark.django_db

GATED = ("statements", "ledger_transactions", "receipts", "receipt_line_items")


def _seeded(*, demo_visit: bool) -> Household:
    household = Household.objects.create(
        name="Demo visit test" if demo_visit else "Kevin-like", is_demo_visit=demo_visit
    )
    seed_household(household, created=True)
    return household


def _gated_ids(household: Household, table: str) -> list[str]:
    """Non-provisional rows only: an UNRECONCILED row was always deletable."""
    with connection.cursor() as cursor:
        cursor.execute(
            f"select id::text from public.{table} where household_id = %s "
            f"and provenance::text <> 'UNRECONCILED'",
            [household.id],
        )
        return [row[0] for row in cursor.fetchall()]


def _delete(table: str, row_id: str) -> None:
    with transaction.atomic(), connection.cursor() as cursor:
        cursor.execute(f"delete from public.{table} where id = %s", [row_id])


def _count(table: str, household: Household) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            f"select count(*) from public.{table} where household_id = %s", [household.id]
        )
        return cursor.fetchone()[0]


# =============================================================================
# 1. The trigger
# =============================================================================


@pytest.mark.parametrize("table", GATED)
def test_a_gated_row_still_cannot_be_deleted_in_a_normal_household(table):
    household = _seeded(demo_visit=False)
    ids = _gated_ids(household, table)
    assert ids, table
    with pytest.raises(DatabaseError, match="immutable"):
        _delete(table, ids[0])
    assert _gated_ids(household, table) == ids


def test_a_normal_households_whole_gated_history_cannot_be_deleted():
    household = _seeded(demo_visit=False)
    for table in ("receipt_line_items", "ledger_transactions"):
        with pytest.raises(DatabaseError, match="immutable"):
            with transaction.atomic(), connection.cursor() as cursor:
                cursor.execute(
                    f"delete from public.{table} where household_id = %s", [household.id]
                )
        assert _count(table, household) > 0


def test_every_gated_row_of_a_demo_visit_can_be_deleted_in_order():
    """Leaf tables first: the exemption lifts the trigger, never a foreign key."""
    household = _seeded(demo_visit=True)
    for table in ("receipt_line_items", "receipts", "ledger_transactions", "statements"):
        assert _count(table, household) > 0, table
        with transaction.atomic(), connection.cursor() as cursor:
            cursor.execute(f"delete from public.{table} where household_id = %s", [household.id])
        assert _count(table, household) == 0, table


def test_the_exemption_reaches_only_the_visits_own_rows():
    kevin = _seeded(demo_visit=False)
    visit = _seeded(demo_visit=True)
    before = {t: _count(t, kevin) for t in GATED}
    with transaction.atomic(), connection.cursor() as cursor:
        cursor.execute(
            "delete from public.receipt_line_items where household_id = %s", [visit.id]
        )
    assert _count("receipt_line_items", visit) == 0
    assert {t: _count(t, kevin) for t in GATED} == before


@pytest.mark.parametrize(
    ("table", "column"),
    [
        ("statements", "account_nickname"),
        ("ledger_transactions", "description"),
        ("receipts", "store"),
        ("receipt_line_items", "name"),
    ],
)
def test_update_stays_refused_in_a_demo_visit(table, column):
    household = _seeded(demo_visit=True)
    [row_id, *_] = _gated_ids(household, table)
    with pytest.raises(DatabaseError, match="immutable"):
        with transaction.atomic(), connection.cursor() as cursor:
            cursor.execute(f"update public.{table} set {column} = 'x' where id = %s", [row_id])


def test_the_flag_is_read_live_not_cached():
    """Flip the flag OFF on a visit (as a bug might) and its rows are
    protected again: the trigger reads the household row at delete time."""
    household = _seeded(demo_visit=True)
    Household.objects.filter(pk=household.pk).update(is_demo_visit=False)
    [row_id, *_] = _gated_ids(household, "receipt_line_items")
    with pytest.raises(DatabaseError, match="immutable"):
        _delete("receipt_line_items", row_id)
    assert row_id in _gated_ids(household, "receipt_line_items")


def test_no_trigger_function_names_private():
    """The live role has no USAGE on `private` (ingest/bank_api_sql.py)."""
    with connection.cursor() as cursor:
        cursor.execute(
            "select c.relname, p.proname, n.nspname from pg_trigger t "
            "join pg_class c on c.oid = t.tgrelid "
            "join pg_proc p on p.oid = t.tgfoid join pg_namespace n on n.oid = p.pronamespace "
            "where t.tgname = 'forbid_mutation' and not t.tgisinternal"
        )
        rows = {r[0]: f"{r[2]}.{r[1]}" for r in cursor.fetchall()}
    assert rows == {
        "statements": "public.gated_row_guard",
        "receipts": "public.gated_row_guard",
        "receipt_line_items": "public.gated_row_guard",
        "ledger_transactions": "public.ledger_transactions_guard",
    }


# =============================================================================
# 2. Nothing can set the flag
# =============================================================================


def test_the_flag_is_not_editable_by_any_form():
    field = Household._meta.get_field("is_demo_visit")
    assert field.editable is False
    assert field.default is False


def test_patching_the_household_cannot_set_the_flag(auth_client, household_user):
    household = household_user.household
    response = auth_client.patch(
        "/api/households/me", {"name": "Renamed", "is_demo_visit": True}, format="json"
    )
    assert response.status_code in (200, 400), response.data
    household.refresh_from_db()
    assert household.is_demo_visit is False
    assert "is_demo_visit" not in auth_client.get("/api/households/me").data


def test_signup_founding_a_household_never_sets_the_flag(db):
    assert not Household.objects.filter(is_demo_visit=True).exists()
    Household.objects.create(name="Founded")
    assert not Household.objects.filter(is_demo_visit=True).exists()


def test_the_admin_form_has_no_flag(rf, household_user):
    from django.contrib.admin.sites import site

    request = rf.get("/admin/")
    request.user = household_user
    admin = site._registry[Household]
    form = admin.get_form(request)
    assert "is_demo_visit" not in form.base_fields


# =============================================================================
# 3. One visit's seed
# =============================================================================


def test_seed_time_per_visit_and_every_copy_is_whole(capsys):
    """Measured on the test database; the number is printed for the report.
    Two copies, so a dedup keyed per server rather than per household would
    show here as the second copy missing its statements."""
    timings = []
    households = []
    for _ in range(2):
        household = Household.objects.create(name="Demo visit timing", is_demo_visit=True)
        started = time.perf_counter()
        report = seed_household(household, created=True)
        timings.append(time.perf_counter() - started)
        households.append(household)
        assert report.statements == {"COMMITTED": 3}, report.statements
    first, second = households
    for table in (*GATED, "places", "voice_notes", "events", "vehicles", "purchases"):
        assert _count(table, first) == _count(table, second) > 0, table
    with capsys.disabled():
        print(f"\nseed time per visit: {', '.join(f'{t:.2f} s' for t in timings)}")
    assert max(timings) < 30
