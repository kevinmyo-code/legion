"""`ledger_transaction_categories`: backend-etl ticket 14, option 2.

A category laid over a gated `ledger_transactions` row, because the row itself
may never be updated. Four things are proved here:

- **the SQL** holds its rules on its own (a bug in Django cannot lay one
  household's category over another's, a rule cannot replace a person);
- **the API** writes overrides in the house style, keyed by transaction;
- **the read** serves the EFFECTIVE category on the transaction list and in
  the changes feed, with the stored one beside it;
- **the backfill** (`manage.py apply_category_rules`) fills what the rules
  can, is idempotent, and never touches a person's choice.
"""
from __future__ import annotations

import uuid
from io import StringIO

import pytest
from django.core.management import call_command
from django.db import DatabaseError, connection, transaction
from django.utils import timezone

from ingest.category_overrides import DROP_SQL, TABLE, backfill_household, create_table
from ingest.provisional import commit_provisional
from ingest.views import commit_statement
from legacy.enums import Provenance
from legacy.models.ledger import LedgerTransaction, LedgerTransactionCategory
from tests.test_ledger_categories_and_paging import (
    a_provisional,
    a_rule,
    a_statement,
    by_description,
)

pytestmark = pytest.mark.django_db

TXNS = "/api/ledger/transactions/"
OVERRIDES = "/api/ledger/transaction_categories/"


def an_override(household, txn, category: str, *, source: str = "person", deleted: bool = False):
    return LedgerTransactionCategory.objects.create(
        id=uuid.uuid4(),
        household=household,
        transaction=txn,
        category=category,
        source=source,
        created_at=timezone.now(),
        updated_at=timezone.now(),
        deleted_at=timezone.now() if deleted else None,
    )


def refused(callable_) -> str:
    """Runs a write that the database must refuse, inside a savepoint so the
    test's own transaction survives it, and returns what Postgres said."""
    with pytest.raises(DatabaseError) as caught:
        with transaction.atomic():
            callable_()
    return str(caught.value)


def listed(client) -> dict[str, dict]:
    rows = client.get(TXNS).data["results"]
    return {row["description"]: row for row in rows}


@pytest.fixture
def rows(household_a):
    """Two uncategorised provisional card rows, stored with no rules in play."""
    commit_provisional(a_provisional(), household_a)
    return by_description(household_a)


# =============================================================================
# The SQL
# =============================================================================


def test_the_migration_function_is_idempotent_and_rebuilds_from_nothing():
    with connection.cursor() as cursor:
        assert create_table(cursor) is None
        assert create_table(cursor) is None
        cursor.execute(DROP_SQL)
        cursor.execute("select to_regclass(%s)", [f"public.{TABLE}"])
        assert cursor.fetchone()[0] is None
        assert create_table(cursor) is None
        cursor.execute("select to_regclass(%s)", [f"public.{TABLE}"])
        assert cursor.fetchone()[0] is not None


def test_one_override_per_transaction(household_a, rows):
    txn = rows["UBER *TRIP"]
    an_override(household_a, txn, "Transport")
    message = refused(lambda: an_override(household_a, txn, "Dining"))
    assert "one_per_transaction" in message


def test_a_blank_category_and_an_unknown_source_are_refused_by_sql(household_a, rows):
    txn = rows["UBER *TRIP"]
    assert "category_not_blank" in refused(lambda: an_override(household_a, txn, "  "))
    assert "source_valid" in refused(lambda: an_override(household_a, txn, "Transport", source="ai"))


def test_an_override_cannot_sit_in_another_household_than_its_transaction(
    household_a, household_b, rows
):
    message = refused(lambda: an_override(household_b, rows["UBER *TRIP"], "Transport"))
    assert "same_household_as_transaction" in message


def test_sql_refuses_a_rule_over_a_live_person_override(household_a, rows):
    row = an_override(household_a, rows["UBER *TRIP"], "Travel")
    message = refused(
        lambda: LedgerTransactionCategory.objects.filter(pk=row.pk).update(source="rule")
    )
    assert "a rule never replaces it" in message


def test_sql_allows_a_rule_over_a_tombstoned_person_override(household_a, rows):
    row = an_override(household_a, rows["UBER *TRIP"], "Travel", deleted=True)
    LedgerTransactionCategory.objects.filter(pk=row.pk).update(source="rule", deleted_at=None)
    row.refresh_from_db()
    assert row.source == "rule"


def test_an_override_never_moves_to_another_transaction(household_a, rows):
    row = an_override(household_a, rows["UBER *TRIP"], "Travel")
    other = rows["MYSTERY MERCHANT"]
    message = refused(
        lambda: LedgerTransactionCategory.objects.filter(pk=row.pk).update(transaction=other)
    )
    assert "never moves" in message


def test_an_update_moves_updated_at(household_a, rows):
    row = an_override(household_a, rows["UBER *TRIP"], "Travel")
    before = row.updated_at
    with connection.cursor() as cursor:
        cursor.execute(
            f"update public.{TABLE} set category = 'Transport', updated_at = %s where id = %s",
            [before.replace(year=2000), row.pk],
        )
    row.refresh_from_db()
    assert row.updated_at.year != 2000


def test_a_superseded_provisional_row_takes_its_override_with_it(household_a, rows):
    """Rule 7: a verified statement over the same dates deletes the provisional
    rows. The override has nothing left to describe and goes too."""
    an_override(household_a, rows["UBER *TRIP"], "Travel")
    LedgerTransaction.objects.filter(pk=rows["UBER *TRIP"].pk).delete()
    assert not LedgerTransactionCategory.objects.filter(household=household_a).exists()


def test_the_gated_row_is_still_immutable(household_a, rows):
    """Option 2 exists because this stays true."""
    txn = rows["UBER *TRIP"]
    refused(lambda: LedgerTransaction.objects.filter(pk=txn.pk).update(category="Travel"))


# =============================================================================
# The API
# =============================================================================


def test_a_person_sets_a_category_and_the_list_shows_it_in_place(auth_client, rows):
    txn = rows["UBER *TRIP"]
    response = auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["source"] == "person"
    assert str(response.data["transaction_id"]) == str(txn.id)

    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["category"] == "Travel"
    assert shown["stored_category"] is None
    assert shown["category_source"] == "person"
    assert shown["category_pending"] is False

    untouched = listed(auth_client)["MYSTERY MERCHANT"]
    assert untouched["category"] is None
    assert untouched["category_source"] is None
    assert untouched["category_pending"] is True


def test_a_stored_category_reads_as_stored(auth_client, household_a):
    a_rule(household_a, "UBER", "Transport")
    commit_provisional(a_provisional(), household_a)
    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["category"] == "Transport"
    assert shown["stored_category"] == "Transport"
    assert shown["category_source"] == "stored"


def test_an_override_wins_over_a_stored_category(auth_client, household_a):
    a_rule(household_a, "UBER", "Transport")
    commit_provisional(a_provisional(), household_a)
    txn = by_description(household_a)["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Work travel"}, format="json")
    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["category"] == "Work travel"
    assert shown["stored_category"] == "Transport"
    assert shown["category_source"] == "person"


def test_a_put_without_source_over_a_rule_row_makes_it_a_persons(auth_client, household_a, rows):
    txn = rows["UBER *TRIP"]
    an_override(household_a, txn, "Transport", source="rule")
    response = auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    assert response.status_code == 200, response.data
    assert response.data["source"] == "person"


def test_the_api_refuses_a_rule_over_a_live_person_row_in_words(auth_client, household_a, rows):
    txn = rows["UBER *TRIP"]
    an_override(household_a, txn, "Travel")
    response = auth_client.put(
        f"{OVERRIDES}{txn.id}/", {"category": "Transport", "source": "rule"}, format="json"
    )
    assert response.status_code == 400
    assert "a rule never replaces it" in str(response.data["source"])
    assert LedgerTransactionCategory.objects.get(transaction=txn).category == "Travel"


def test_blank_category_and_unknown_source_are_400s_naming_the_rule(auth_client, rows):
    txn = rows["UBER *TRIP"]
    blank = auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": " "}, format="json")
    assert blank.status_code == 400 and "blank" in str(blank.data["category"])
    bad = auth_client.put(
        f"{OVERRIDES}{txn.id}/", {"category": "Travel", "source": "ai"}, format="json"
    )
    assert bad.status_code == 400 and "person, rule" in str(bad.data)


def test_a_transaction_that_does_not_exist_is_refused(auth_client, rows):
    response = auth_client.put(f"{OVERRIDES}{uuid.uuid4()}/", {"category": "Travel"}, format="json")
    assert response.status_code == 400
    assert not LedgerTransactionCategory.objects.exists()


def test_delete_puts_the_stored_category_back_and_a_put_revives_it(auth_client, household_a):
    a_rule(household_a, "UBER", "Transport")
    commit_provisional(a_provisional(), household_a)
    txn = by_description(household_a)["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")

    assert auth_client.delete(f"{OVERRIDES}{txn.id}/").status_code == 204
    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["category"] == "Transport"
    assert shown["category_source"] == "stored"

    revived = auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    assert revived.status_code == 200 and revived.data["deleted_at"] is None
    assert listed(auth_client)["UBER *TRIP"]["category"] == "Travel"


def test_the_override_list_syncs_like_its_siblings(auth_client, rows):
    txn = rows["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    auth_client.delete(f"{OVERRIDES}{txn.id}/")
    everything = auth_client.get(OVERRIDES).data["results"]
    assert len(everything) == 1 and everything[0]["deleted_at"] is not None
    assert auth_client.get(f"{OVERRIDES}?active=1").data["results"] == []


def test_the_changes_feed_serves_the_effective_category_and_the_overrides(auth_client, rows):
    txn = rows["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    body = auth_client.get("/api/changes?since=1970-01-01T00:00:00Z&aspects=ledger").data
    fed = {row["description"]: row for row in body["ledger_transactions"]}
    assert fed["UBER *TRIP"]["category"] == "Travel"
    assert fed["UBER *TRIP"]["category_source"] == "person"
    assert len(body[TABLE]) == 1


def test_the_transaction_detail_route_serves_the_effective_category(auth_client, rows):
    txn = rows["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    detail = auth_client.get(f"{TXNS}{txn.id}/").data
    assert detail["category"] == "Travel"


def test_the_list_still_says_unverified_in_words_under_an_override(auth_client, rows):
    """A category does not make a provisional row verified."""
    txn = rows["UBER *TRIP"]
    auth_client.put(f"{OVERRIDES}{txn.id}/", {"category": "Travel"}, format="json")
    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["provenance"] == Provenance.UNRECONCILED
    assert shown["verification_note"].startswith("Unverified")


# =============================================================================
# The backfill
# =============================================================================


def test_the_dry_run_counts_and_writes_nothing(household_a, rows):
    a_rule(household_a, "UBER", "Transport")
    report = backfill_household(household_a, dry_run=True)
    assert (report.uncategorised, report.matched, report.written, report.unmatched) == (2, 1, 0, 1)
    assert not LedgerTransactionCategory.objects.exists()


def test_the_backfill_writes_rule_overrides_and_is_idempotent(auth_client, household_a, rows):
    a_rule(household_a, "UBER", "Transport")
    first = backfill_household(household_a, dry_run=False)
    assert first.written == 1
    written = LedgerTransactionCategory.objects.get(transaction=rows["UBER *TRIP"])
    assert (written.category, written.source) == ("Transport", "rule")

    second = backfill_household(household_a, dry_run=False)
    assert second.written == 0 and second.matched == 0
    assert LedgerTransactionCategory.objects.count() == 1

    shown = listed(auth_client)["UBER *TRIP"]
    assert shown["category"] == "Transport" and shown["category_source"] == "rule"


def test_the_backfill_uses_the_oldest_matching_rule_like_the_phone(household_a, rows):
    a_rule(household_a, "UBER", "Dining", minutes=5)
    a_rule(household_a, "UBER", "Transport", minutes=1)
    backfill_household(household_a, dry_run=False)
    assert LedgerTransactionCategory.objects.get().category == "Transport"


def test_the_backfill_never_touches_a_persons_override(household_a, rows):
    txn = rows["UBER *TRIP"]
    an_override(household_a, txn, "Travel")
    a_rule(household_a, "UBER", "Transport")
    report = backfill_household(household_a, dry_run=False)
    assert report.written == 0
    kept = LedgerTransactionCategory.objects.get(transaction=txn)
    assert (kept.category, kept.source) == ("Travel", "person")


def test_the_backfill_leaves_a_deliberately_removed_override_alone(household_a, rows):
    txn = rows["UBER *TRIP"]
    an_override(household_a, txn, "Travel", deleted=True)
    a_rule(household_a, "UBER", "Transport")
    report = backfill_household(household_a, dry_run=False)
    assert report.written == 0 and report.left_alone_removed == 1
    assert LedgerTransactionCategory.objects.get(transaction=txn).deleted_at is not None


def test_the_backfill_skips_a_row_with_a_stored_category(household_a):
    a_rule(household_a, "starbucks", "Dining")
    commit_statement(a_statement(), household_a)
    a_rule(household_a, "HEB", "Groceries", minutes=1)
    report = backfill_household(household_a, dry_run=False)
    # Starbucks was categorised at insert, HEB was not (no rule then).
    assert report.uncategorised == 1 and report.written == 1
    row = LedgerTransactionCategory.objects.get()
    assert row.transaction.description == "HEB GROCERY 042"


def test_the_backfill_is_scoped_to_each_households_own_rules(household_a, household_b, rows):
    a_rule(household_b, "UBER", "Transport")
    assert backfill_household(household_a, dry_run=False).written == 0


def test_the_command_dry_run_prints_counts_and_writes_nothing(household_a, rows):
    a_rule(household_a, "UBER", "Transport")
    out = StringIO()
    call_command("apply_category_rules", "--dry-run", stdout=out)
    text = out.getvalue()
    assert "DRY RUN, nothing written." in text
    assert "would write 1" in text
    assert not LedgerTransactionCategory.objects.exists()


def test_the_command_writes_for_one_household(household_a, household_b, rows):
    a_rule(household_a, "UBER", "Transport")
    out = StringIO()
    call_command("apply_category_rules", "--household", str(household_a.id), stdout=out)
    assert "wrote 1" in out.getvalue()
    assert LedgerTransactionCategory.objects.filter(household=household_a).count() == 1


def test_the_command_refuses_an_unknown_household_in_words(db):
    from django.core.management.base import CommandError

    with pytest.raises(CommandError, match="Nothing was written"):
        call_command("apply_category_rules", "--household", str(uuid.uuid4()))
    with pytest.raises(CommandError, match="not a household uuid"):
        call_command("apply_category_rules", "--household", "nope")


# =============================================================================
# categories.excluded_from_spend (Kevin, 2026-09-29: "ignore zelle for spending")
# =============================================================================

CATEGORIES = "/api/ledger/categories/"


def test_a_category_is_spending_unless_flagged(auth_client):
    made = auth_client.put(
        f"{CATEGORIES}guid-shopping/", {"name": "Shopping", "is_food_category": False}, format="json"
    )
    assert made.status_code == 200, made.data
    assert made.data["excluded_from_spend"] is False


def test_transfers_carries_the_flag_and_a_put_without_it_keeps_it(auth_client):
    made = auth_client.put(
        f"{CATEGORIES}guid-transfers/",
        {"name": "Transfers", "is_food_category": False, "excluded_from_spend": True},
        format="json",
    )
    assert made.status_code == 200, made.data
    assert made.data["excluded_from_spend"] is True

    # An older phone that has never heard of the flag clears nothing: an
    # omitted field is unchanged on an update.
    again = auth_client.put(
        f"{CATEGORIES}guid-transfers/", {"name": "Transfers", "is_food_category": False}, format="json"
    )
    assert again.data["excluded_from_spend"] is True


def test_the_flag_column_is_added_idempotently_and_defaults_false():
    from ingest.category_flags import add_column

    with connection.cursor() as cursor:
        assert add_column(cursor) is None
        assert add_column(cursor) is None
        cursor.execute(
            "select column_default, is_nullable from information_schema.columns "
            "where table_schema = 'public' and table_name = 'categories' "
            "and column_name = 'excluded_from_spend'"
        )
        default, nullable = cursor.fetchone()
    assert default == "false" and nullable == "NO"


def test_the_migration_sql_never_touches_the_private_schema():
    """The live engine role (`legion_engine`) has no USAGE on `private`, whose
    functions `postgres` owns. The tests run as a superuser and cannot see
    that, so the first deploy failed with "permission denied for schema
    private" (2026-09-29). This pins the SQL, not the privilege."""
    from ingest.category_overrides import create_sql

    sql = create_sql("django")
    assert "private." not in sql
    assert "create schema" not in sql.lower()
    assert "private." not in DROP_SQL
