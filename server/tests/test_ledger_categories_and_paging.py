"""Ledger rows reach the phone categorised and complete.

Two server halves of `.scratch/backend-etl/issues/14-ledger-rows-reach-the-phone.md`:

- **Categorised at the INSERT.** `ingest/category_rules.py` applies the
  household's `category_rules` when the gate commits and when the rule-7
  provisional writer commits, with the phone's own match semantics
  (`LedgerController.applyCategoryRules`). A later UPDATE is impossible -
  `forbid_mutation_of_facts` refuses it - and one test here proves that is
  still true rather than assuming it.
- **The full list is complete.** The phone replaces its set of server rows
  with what the list returns, so a list that stops early reads as deletion.
  A statement's lines share one `created_at`; the keyset tiebreak
  (`next_after` / `?after=`) is what lets a client page past them.
"""
from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from django.db import DatabaseError, transaction
from django.utils import timezone

from api.sync import PAGE_SIZE
from ingest import gate
from ingest.category_rules import Rule, category_for_insert, first_match, household_rules
from ingest.provisional import commit_provisional
from ingest.views import commit_statement
from legacy.enums import Provenance
from legacy.models.ledger import CategoryRule, LedgerTransaction
from tests.test_ledger_pantry_api import EPOCH, TRANSACTIONS, commit_a_statement

pytestmark = pytest.mark.django_db

T0 = datetime(2026, 9, 1, 12, 0, tzinfo=UTC)


def a_rule(household, substring: str, category: str, *, minutes: int = 0, deleted: bool = False):
    return CategoryRule.objects.create(
        id=uuid.uuid4(),
        household=household,
        category=category,
        substring=substring,
        created_at_client=T0 + timedelta(minutes=minutes),
        provenance=Provenance.USER,
        created_at=timezone.now(),
        updated_at=timezone.now(),
        deleted_at=timezone.now() if deleted else None,
        origin_guid=str(uuid.uuid4()),
    )


def a_statement(**overrides) -> dict:
    """Ties out: 10000 - 450 - 6000 = 3550."""
    payload = {
        "content_sha256": f"sha-{uuid.uuid4()}",
        "provenance": gate.DETERMINISTIC,
        "account_last4": "3119",
        "account_nickname": "BofA checking",
        "currency": "USD",
        "stated_total_cents": None,
        "opening_balance_cents": 10000,
        "closing_balance_cents": 3550,
        "period_start": "2026-09-01",
        "period_end": "2026-09-05",
        "lines": [
            {"txn_date": "2026-09-01", "description": "Starbucks #123 Houston",
             "amount_cents": -450, "line_ref": "s:1"},
            {"txn_date": "2026-09-05", "description": "HEB GROCERY 042",
             "amount_cents": -6000, "line_ref": "s:2"},
        ],
    }
    payload.update(overrides)
    return payload


def a_provisional(**overrides) -> dict:
    payload = {
        "content_sha256": f"sha-{uuid.uuid4()}",
        "provenance": Provenance.UNRECONCILED,
        "account_last4": "4146",
        "account_nickname": "BofA card",
        "currency": "USD",
        "lines": [
            {"txn_date": "2026-09-10", "description": "UBER *TRIP", "amount_cents": -1200,
             "line_ref": "a:1"},
            {"txn_date": "2026-09-11", "description": "MYSTERY MERCHANT", "amount_cents": -300,
             "line_ref": "a:2"},
        ],
    }
    payload.update(overrides)
    return payload


def by_description(household) -> dict[str, LedgerTransaction]:
    return {r.description: r for r in LedgerTransaction.objects.filter(household=household)}


# =============================================================================
# The matcher: the phone's semantics, one rule at a time
# =============================================================================


def test_the_match_is_a_case_insensitive_substring():
    rules = [Rule("STARBUCKS", "Dining")]
    assert first_match("starbucks #123", rules) == "Dining"
    assert first_match("Paid at StarBucks", rules) == "Dining"
    assert first_match("STAR BUCKS", rules) is None


def test_the_oldest_matching_rule_wins():
    """The phone applies rules oldest first and each claims only rows still
    uncategorised, so a later rule never overrides an earlier one."""
    rules = [Rule("UBER", "Transport"), Rule("UBER EATS", "Dining")]
    assert first_match("UBER EATS 555", rules) == "Transport"


def test_uppercasing_agrees_with_kotlin_on_sharp_s():
    """Kotlin's `"ß".uppercase()` is `"SS"`, and so is Python's."""
    rules = [Rule("STRASSE".upper(), "Travel")]
    assert first_match("Hauptstraße 5", rules) == "Travel"


def test_a_stated_category_is_never_overridden():
    rules = [Rule("HEB", "Groceries")]
    assert category_for_insert("Gifts", "HEB GROCERY", rules) == ("Gifts", False)
    assert category_for_insert(None, "HEB GROCERY", rules) == ("Groceries", False)
    assert category_for_insert(None, "NOTHING MATCHES", rules) == (None, True)


def test_rules_come_oldest_first_by_the_phone_clock_and_skip_deleted(household_a):
    # Inserted newest first, so an insert-order read would get this wrong.
    a_rule(household_a, "uber", "Dining", minutes=5)
    a_rule(household_a, "uber", "Transport", minutes=1)
    a_rule(household_a, "uber", "Shopping", minutes=0, deleted=True)
    rules = household_rules(household_a)
    assert [r.category for r in rules] == ["Transport", "Dining"]
    assert rules[0].substring_upper == "UBER"


def test_rules_are_scoped_to_the_household(household_a, household_b):
    a_rule(household_b, "HEB", "Groceries")
    assert household_rules(household_a) == []


# =============================================================================
# Both insert paths apply them
# =============================================================================


def test_the_gate_commit_categorises_by_the_households_rules(household_a):
    a_rule(household_a, "starbucks", "Dining")
    a_rule(household_a, "HEB", "Groceries", minutes=1)

    result = commit_statement(a_statement(), household_a)
    assert result.outcome == gate.COMMITTED

    rows = by_description(household_a)
    assert rows["Starbucks #123 Houston"].category == "Dining"
    assert rows["Starbucks #123 Houston"].category_pending is False
    assert rows["HEB GROCERY 042"].category == "Groceries"


def test_the_gate_commit_keeps_a_stated_category_and_leaves_misses_pending(household_a):
    a_rule(household_a, "starbucks", "Dining")
    payload = a_statement()
    payload["lines"][0]["category"] = "Gifts"

    commit_statement(payload, household_a)

    rows = by_description(household_a)
    assert rows["Starbucks #123 Houston"].category == "Gifts"
    assert rows["HEB GROCERY 042"].category is None
    assert rows["HEB GROCERY 042"].category_pending is True


def test_the_provisional_writer_categorises_and_stays_unreconciled(household_a):
    a_rule(household_a, "UBER", "Transport")

    result = commit_provisional(a_provisional(), household_a)
    assert result.outcome == gate.COMMITTED

    rows = by_description(household_a)
    assert rows["UBER *TRIP"].category == "Transport"
    assert rows["UBER *TRIP"].category_pending is False
    # A category does not make a provisional row verified.
    assert rows["UBER *TRIP"].provenance == Provenance.UNRECONCILED
    assert rows["MYSTERY MERCHANT"].category is None
    assert rows["MYSTERY MERCHANT"].category_pending is True


def test_another_households_rules_do_not_categorise_this_households_rows(household_a, household_b):
    a_rule(household_b, "UBER", "Transport")
    commit_provisional(a_provisional(), household_a)
    assert by_description(household_a)["UBER *TRIP"].category is None


def test_a_stored_row_still_cannot_be_recategorised(household_a):
    """Why categorisation happens at the INSERT and nowhere else: the trigger
    refuses the UPDATE a backfill would need. If this ever starts passing
    silently, the trigger has been dropped, and that is a decision somebody
    has to have made on purpose."""
    commit_statement(a_statement(), household_a)
    row = LedgerTransaction.objects.filter(household=household_a).first()
    with pytest.raises(DatabaseError, match="immutable"), transaction.atomic():
        LedgerTransaction.objects.filter(id=row.id).update(category="Dining")


# =============================================================================
# The full list is complete: the keyset tiebreak
# =============================================================================


def _page(client, since: str, after: str | None = None) -> dict:
    url = f"{TRANSACTIONS}?since={since}"
    if after is not None:
        url += f"&after={after}"
    return json.loads(client.get(url).content)


def _one_statement_of(client, line_count: int) -> None:
    lines = [
        {"txn_date": "2026-07-03", "description": f"LINE {n}", "amount_cents": -100,
         "line_ref": f"k-{n}"}
        for n in range(line_count)
    ]
    total = -100 * line_count
    commit_a_statement(
        client,
        content_sha256=f"sha-keyset-{line_count}",
        stated_total_cents=total,
        opening_balance_cents=100000,
        closing_balance_cents=100000 + total,
        lines=lines,
    )


def test_one_statement_bigger_than_a_page_shares_one_timestamp(auth_client):
    """The shape that made the tiebreak necessary, asserted rather than
    assumed: every line of one gate commit has the same `created_at`."""
    _one_statement_of(auth_client, PAGE_SIZE + 1)
    stamps = set(LedgerTransaction.objects.values_list("created_at", flat=True))
    assert len(stamps) == 1


def test_since_alone_re_serves_a_one_timestamp_page(auth_client):
    """The old behaviour, still what an installed client without `after`
    gets: the second request returns the first page again. Kept as a test so
    nobody mistakes it for fixed on the since-only path."""
    _one_statement_of(auth_client, PAGE_SIZE + 1)
    first = _page(auth_client, EPOCH)
    again = _page(auth_client, first["next"])
    assert [r["id"] for r in again["results"]] == [r["id"] for r in first["results"]]


def test_following_next_and_next_after_reaches_every_row_exactly_once(auth_client):
    line_count = 2 * PAGE_SIZE + 7
    _one_statement_of(auth_client, line_count)

    seen: list[str] = []
    since, after = EPOCH, None
    for _ in range(10):
        page = _page(auth_client, since, after)
        seen.extend(r["id"] for r in page["results"])
        if page["next"] is None:
            assert page["next_after"] is None
            break
        assert page["next_after"] is not None
        since, after = page["next"], page["next_after"]
    else:
        pytest.fail("never reached a last page")

    assert len(seen) == line_count
    assert len(set(seen)) == line_count


def test_a_short_list_says_last_page_on_both_cursors(auth_client):
    commit_a_statement(auth_client, content_sha256="sha-short")
    page = _page(auth_client, EPOCH)
    assert page["next"] is None
    assert page["next_after"] is None


def test_an_unparsable_after_falls_back_to_the_inclusive_read(auth_client):
    """Too much, never silently nothing - `parse_since`'s posture."""
    commit_a_statement(auth_client, content_sha256="sha-garbled-after")
    everything = _page(auth_client, EPOCH)["results"]
    garbled = _page(auth_client, EPOCH, "not-a-uuid")["results"]
    assert garbled == everything
    assert everything
