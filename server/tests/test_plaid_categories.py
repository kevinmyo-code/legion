"""Plaid's own category on a bank row (Kevin, 2026-11-23: *"accept plaid's
category"*), the evidence it is derived from, and the rows it deliberately
leaves alone.

The bank feed is fact with no gate (ADR 0057), and `personal_finance_category`
rides in on that same feed, so a category taken from it is stored and rendered
plain. His own `category_rules` still win over it, every time.

The fakes are `tests/test_plaid.py`'s; nothing here reaches the network.
"""
from __future__ import annotations

import uuid

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone

from ingest import plaid_categories
from ingest.category_overrides import backfill_household
from legacy.enums import Provenance
from legacy.models.ledger import (
    Category,
    CategoryRule,
    LedgerTransaction,
    LedgerTransactionCategory,
)
from tests import test_plaid_balances
from tests.test_plaid import CHECKING, bank_rows, sync, txn

pytestmark = pytest.mark.django_db

# The gateway and the linked Item, from the module next door.
fake = test_plaid_balances.fake
item = test_plaid_balances.item

HIS_CATEGORIES = (
    "Groceries",
    "Dining Out",
    "Coffee & Snacks",
    "Transport",
    "Housing",
    "Shopping",
    "Travel",
    "Income",
    "Fees",
    "Insurance",
    "Pets",
    "Transfers",
)


def pfc(primary, detailed, confidence="VERY_HIGH"):
    return {
        "primary": primary,
        "detailed": detailed,
        "confidence_level": confidence,
        "version": "v2",
    }


@pytest.fixture
def categories(household_a):
    now = timezone.now()
    for name in HIS_CATEGORIES:
        Category.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            name=name,
            is_food_category=False,
            provenance=Provenance.USER,
            created_at=now,
            updated_at=now,
            origin_guid=f"cat-{name}",
        )


def a_rule(household, substring, category):
    now = timezone.now()
    return CategoryRule.objects.create(
        id=uuid.uuid4(),
        household=household,
        substring=substring,
        category=category,
        provenance=Provenance.USER,
        created_at=now,
        created_at_client=now,
        updated_at=now,
        origin_guid=f"rule-{substring}",
    )


def one_row(fake, household, item, **extra):
    fake.page(None, "c1", added=[txn("t1", **extra)], accounts=[CHECKING])
    fake.accounts = [CHECKING]
    run = sync(household)
    assert run.outcome == "ok", run.error
    rows = bank_rows(household)
    assert len(rows) == 1
    return rows[0], run


# =============================================================================
# The category Plaid sent
# =============================================================================


def test_plaids_category_fills_a_row_no_rule_matched(fake, item, household_a, categories):
    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        name="SQ *SOME CAFE",
        personal_finance_category=pfc("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"),
    )

    assert (row.category, row.category_pending) == ("Coffee & Snacks", False)
    assert row.category_origin == "plaid"


def test_a_rule_of_his_beats_plaids_category(fake, item, household_a, categories):
    a_rule(household_a, "SOME CAFE", "Dining Out")

    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        name="SQ *SOME CAFE",
        personal_finance_category=pfc("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"),
    )

    assert row.category == "Dining Out"
    assert row.category_origin == "rule"
    # And the evidence is kept anyway: section 4 rule 8.
    assert row.plaid_category["detailed"] == "FOOD_AND_DRINK_COFFEE"


def test_the_raw_object_is_stored_whole(fake, item, household_a, categories):
    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc("TRANSPORTATION", "TRANSPORTATION_GAS", "LOW"),
    )

    assert row.plaid_category == {
        "primary": "TRANSPORTATION",
        "detailed": "TRANSPORTATION_GAS",
        "confidence_level": "LOW",
        "version": "v2",
    }
    assert row.category == "Transport"


def test_a_detailed_value_this_map_has_never_seen_falls_back_to_its_primary(
    fake, item, household_a, categories
):
    """Plaid's v2 taxonomy adds detailed values under the same primaries."""
    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc("TRAVEL", "TRAVEL_SOMETHING_PLAID_ADDED_LATER"),
    )

    assert row.category == "Travel"


# =============================================================================
# What it refuses to guess
# =============================================================================


def test_an_unmapped_category_leaves_the_row_alone_and_says_so(
    fake, item, household_a, categories
):
    row, run = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc("LOAN_PAYMENTS", "LOAN_PAYMENTS_CAR_PAYMENT"),
    )

    assert (row.category, row.category_pending, row.category_origin) == (None, True, None)
    assert row.plaid_category["detailed"] == "LOAN_PAYMENTS_CAR_PAYMENT"
    assert any("LOAN_PAYMENTS_CAR_PAYMENT" in note for note in run.last_report.notes)


def test_a_category_the_household_does_not_have_is_never_written(fake, item, household_a):
    """No `categories` rows at all here: every mapped name is one he lacks."""
    row, run = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc("TRAVEL", "TRAVEL_FLIGHTS"),
    )

    assert row.category is None and row.category_origin is None
    assert any(
        "'Travel', which this household does not have" in note
        for note in run.last_report.notes
    )


def _exclude_from_spend(household, name):
    """Flag one of the household's categories as not spending
    (`ingest/category_flags.py`), the way the phone's sync does."""
    updated = Category.objects.filter(household=household, name=name).update(
        excluded_from_spend=True
    )
    assert updated == 1
    return name


def _overdraft_fee(fake, household, item, *, with_plaids_category: bool):
    """One $35 bank fee in September, with or without Plaid's own category."""
    extra = {}
    if with_plaids_category:
        extra["personal_finance_category"] = pfc("BANK_FEES", "BANK_FEES_OVERDRAFT_FEES")
    return one_row(
        fake,
        household,
        item,
        account="acc-chk",
        amount=35.0,
        date="2026-09-10",
        name="SOME OVERDRAFT CHARGE",
        **extra,
    )


def _spend_total(auth_client):
    body = auth_client.get("/api/ledger/spend", {"month": "2026-09", "currency": "USD"}).data
    return sum(a["spend_cents"] for a in body["accounts"]) + body["uncategorised_cents"]


def test_a_category_excluded_from_spending_is_never_written_from_plaids_word(
    fake, item, household_a, categories
):
    """The flag, not the name (`ingest/category_flags.py`). Plaid's taxonomy
    does not know which of his categories are money moving between his own
    places, and filing a row in one does not mislabel it, it takes the whole
    row out of every spend total. So his word is required, and the report
    names the category it refused."""
    _exclude_from_spend(household_a, "Fees")

    row, run = _overdraft_fee(fake, household_a, item, with_plaids_category=True)

    assert (row.category, row.category_pending, row.category_origin) == (None, True, None)
    # The evidence is still kept, used or not (section 4 rule 8).
    assert row.plaid_category["detailed"] == "BANK_FEES_OVERDRAFT_FEES"
    assert any(
        "BANK_FEES_OVERDRAFT_FEES" in note and "'Fees', which is excluded from spending" in note
        for note in run.last_report.notes
    ), run.last_report.notes


def test_an_excluded_category_cannot_quietly_remove_a_row_from_the_spend_total(
    fake, item, household_a, categories, auth_client
):
    """What the review proved was wrong: the row left the total entirely,
    because a dropped row is in neither the category buckets nor
    `uncategorised_cents` (`finance/spending.py:46`). It now reads exactly as
    the same row with no category from Plaid at all."""
    _exclude_from_spend(household_a, "Fees")

    _overdraft_fee(fake, household_a, item, with_plaids_category=True)

    assert _spend_total(auth_client) == 3500


def test_the_control_the_excluded_case_has_to_match(
    fake, item, household_a, categories, auth_client
):
    """The same row with no `personal_finance_category`: uncategorised, and
    its money still counted."""
    _overdraft_fee(fake, household_a, item, with_plaids_category=False)

    assert _spend_total(auth_client) == 3500


def test_a_rule_of_his_still_files_a_row_in_an_excluded_category(
    fake, item, household_a, categories
):
    """Only PLAID's suggestion is refused. A rule he wrote is his own ruling
    about his own money and wins over everything, excluded or not."""
    _exclude_from_spend(household_a, "Fees")
    a_rule(household_a, "OVERDRAFT", "Fees")

    row, _ = _overdraft_fee(fake, household_a, item, with_plaids_category=True)

    assert (row.category, row.category_origin) == ("Fees", "rule")


def test_a_deliberate_none_in_the_map_is_not_guessed_from_its_primary(
    fake, item, household_a, categories
):
    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc(
            "FOOD_AND_DRINK", "FOOD_AND_DRINK_BEER_WINE_AND_LIQUOR"
        ),
    )

    assert row.category is None


def test_no_category_from_plaid_at_all_is_the_old_behaviour(fake, item, household_a, categories):
    row, _ = one_row(fake, household_a, item, account="acc-chk")

    assert (row.category, row.category_pending, row.plaid_category) == (None, True, None)


@pytest.mark.parametrize("raw", ["FOOD_AND_DRINK", [], 7, {"primary": None}])
def test_a_shape_this_code_does_not_know_stores_nothing_and_loses_no_row(
    fake, item, household_a, categories, raw
):
    """A value that cannot be serialised would not spoil one category, it
    would fail the whole `bulk_create`."""
    row, _ = one_row(fake, household_a, item, account="acc-chk", personal_finance_category=raw)

    assert row.plaid_category is None
    assert row.category is None


def test_an_enum_shaped_value_is_stored_as_its_string(fake, item, household_a, categories):
    """`plaid-python` hands enums over as objects carrying a `value`."""

    class Enumish:
        value = "FOOD_AND_DRINK_GROCERIES"

    row, _ = one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category={
            "primary": "FOOD_AND_DRINK",
            "detailed": Enumish(),
            "confidence_level": object(),
        },
    )

    assert row.plaid_category == {
        "primary": "FOOD_AND_DRINK",
        "detailed": "FOOD_AND_DRINK_GROCERIES",
    }
    assert row.category == "Groceries"


# =============================================================================
# The columns belong to the bank feed alone
# =============================================================================


def test_a_file_derived_row_may_not_carry_the_plaid_fields(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            statement=None,
            account_last4="1000",
            account_nickname="Checking",
            currency="USD",
            txn_date=timezone.now().date(),
            description="A ROW FROM A FILE",
            amount_cents=-100,
            line_ref="file:1",
            category=None,
            category_pending=True,
            provenance=Provenance.UNRECONCILED,
            created_at=timezone.now(),
            plaid_category={"primary": "TRAVEL"},
        )


def test_an_origin_without_a_category_is_refused(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            statement=None,
            account_last4="1000",
            account_nickname="Checking",
            currency="USD",
            txn_date=timezone.now().date(),
            description="A BANK ROW",
            amount_cents=-100,
            line_ref="plaid:x",
            category=None,
            category_pending=True,
            provenance=Provenance.BANK_API,
            created_at=timezone.now(),
            bank_transaction_id="x",
            category_origin="plaid",
        )


# =============================================================================
# How it renders
# =============================================================================


def test_a_plaid_category_renders_plain_like_any_bank_row(
    fake, item, household_a, categories, auth_client
):
    """ADR 0057: the feed is fact, so no tag and no estimate wording."""
    one_row(
        fake,
        household_a,
        item,
        account="acc-chk",
        personal_finance_category=pfc("FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES"),
    )

    body = auth_client.get("/api/ledger/transactions/").data
    row = body["results"][0] if "results" in body else body[0]
    assert row["category"] == "Groceries"
    assert row["category_source"] == "stored"
    assert row["verification_note"] is None
    # The evidence stays on the engine: the wire's field list is unchanged, so
    # no client has to learn anything to render this row.
    assert "plaid_category" not in row and "category_origin" not in row


# =============================================================================
# A rule written LATER still wins (Kevin, 2026-11-23)
# =============================================================================


def _a_cafe_row(fake, household, item, **extra):
    return one_row(
        fake,
        household,
        item,
        account="acc-chk",
        name="SQ *SOME CAFE",
        personal_finance_category=pfc("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"),
        **extra,
    )


def test_a_rule_written_later_overrides_the_category_plaid_wrote(
    fake, item, household_a, categories, auth_client
):
    """His rules win afterwards, not only at insert. The stored column is
    immutable, so the sweep lays a `rule` override over the feed's category and
    the precedence makes it win."""
    row, _ = _a_cafe_row(fake, household_a, item)
    assert (row.category, row.category_origin) == ("Coffee & Snacks", "plaid")

    a_rule(household_a, "SOME CAFE", "Dining Out")
    report = backfill_household(household_a, dry_run=False)

    assert (report.plaid_categorised, report.uncategorised, report.written) == (1, 0, 1)
    override = LedgerTransactionCategory.objects.get(transaction=row)
    assert (override.category, override.source) == ("Dining Out", "rule")
    # And it is what every reader sees.
    body = auth_client.get("/api/ledger/transactions/").data
    shown = (body["results"] if "results" in body else body)[0]
    assert (shown["category"], shown["category_source"]) == ("Dining Out", "rule")
    # The row's own column is untouched: the feed's word is still on record.
    row.refresh_from_db()
    assert row.category == "Coffee & Snacks"
    # Idempotent.
    assert backfill_household(household_a, dry_run=False).written == 0


def test_a_rule_that_names_the_category_plaid_already_wrote_writes_nothing(
    fake, item, household_a, categories
):
    """An override saying what the row already says is noise."""
    row, _ = _a_cafe_row(fake, household_a, item)
    a_rule(household_a, "SOME CAFE", "Coffee & Snacks")

    report = backfill_household(household_a, dry_run=False)

    assert (report.written, report.matched, report.agreed) == (0, 0, 1)
    assert not LedgerTransactionCategory.objects.filter(transaction=row).exists()


def test_the_sweep_never_re_decides_a_category_one_of_his_rules_wrote(
    fake, item, household_a, categories
):
    """A rule beating a rule is the same decision twice. The row keeps the
    category its own rule gave it, whatever a later rule says."""
    a_rule(household_a, "SOME CAFE", "Dining Out")
    row, _ = _a_cafe_row(fake, household_a, item)
    assert row.category_origin == "rule"

    a_rule(household_a, "SQ *", "Shopping")
    report = backfill_household(household_a, dry_run=False)

    assert (report.written, report.plaid_categorised, report.uncategorised) == (0, 0, 0)
    assert not LedgerTransactionCategory.objects.filter(transaction=row).exists()


def test_the_sweep_never_touches_a_persons_choice_on_a_feed_categorised_row(
    fake, item, household_a, categories
):
    row, _ = _a_cafe_row(fake, household_a, item)
    LedgerTransactionCategory.objects.create(
        id=uuid.uuid4(),
        household=household_a,
        transaction=row,
        category="Travel",
        source="person",
        created_at=timezone.now(),
        updated_at=timezone.now(),
    )
    a_rule(household_a, "SOME CAFE", "Dining Out")

    report = backfill_household(household_a, dry_run=False)

    assert report.written == 0
    kept = LedgerTransactionCategory.objects.get(transaction=row)
    assert (kept.category, kept.source) == ("Travel", "person")


def test_an_override_deleted_on_purpose_stays_deleted_on_a_feed_categorised_row(
    fake, item, household_a, categories
):
    """Someone cleared that category deliberately; a rule does not refill it."""
    row, _ = _a_cafe_row(fake, household_a, item)
    LedgerTransactionCategory.objects.create(
        id=uuid.uuid4(),
        household=household_a,
        transaction=row,
        category="Travel",
        source="person",
        created_at=timezone.now(),
        updated_at=timezone.now(),
        deleted_at=timezone.now(),
    )
    a_rule(household_a, "SOME CAFE", "Dining Out")

    report = backfill_household(household_a, dry_run=False)

    assert (report.written, report.left_alone_removed) == (0, 1)


def test_the_map_only_ever_names_categories_legion_seeds():
    """The map is data, and its right-hand side is LEGION's starter set
    (`app/.../data/local/CategorySeed.kt`). A name not on that list would be a
    category no fresh install has."""
    seeded = {
        "Groceries", "Dining Out", "Coffee & Snacks", "Transport", "Housing", "Utilities",
        "Subscriptions", "Shopping", "Health", "Travel", "Entertainment", "Income", "Fees",
        "Insurance", "Other", "Pets", "Transfers",
    }
    named = set(plaid_categories.BY_PRIMARY.values()) | {
        name for name in plaid_categories.BY_DETAILED.values() if name
    }
    assert named <= seeded
