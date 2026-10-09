"""Ledger: statements, their transactions, and categorization. Money is
`BigIntegerField` throughout (cents) - CLAUDE.md section 4 rule 3, never
`DecimalField`, because the reconciliation gate depends on exact-integer
equality and a `Decimal` invites the illusion of exactness `float` already
correctly refuses.

`statements` and `ledger_transactions` have no `updated_at` and no
`deleted_at` - confirmed against the live schema, not an omission here.
Both are the section 4 gate's own output: a reconciled financial fact that
a later write is never supposed to silently touch. `receipts` in
`legacy/models/pantry.py` shares the same shape for the same reason.
"""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.ingest import IngestedFile
from legacy.models.tenancy import household_field, household_unique


class Category(models.Model):
    id = models.UUIDField(primary_key=True)
    name = models.TextField()
    is_food_category = models.BooleanField()
    # Not spending (money moving between the household's own places). The one
    # definition every spend total reads: `ingest/category_flags.py`.
    excluded_from_spend = models.BooleanField(default=False)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "categories"
        constraints = [
            household_unique("categories", "name"),
            household_unique("categories", "origin_guid"),
        ]


class CategoryRule(models.Model):
    id = models.UUIDField(primary_key=True)
    category = models.TextField()
    substring = models.TextField()
    created_at_client = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "category_rules"
        constraints = [
            household_unique("category_rules", "origin_guid"),
        ]


class MerchantAlias(models.Model):
    """A merchant name a person chose, shown IN PLACE OF a gated row's bank
    text and never written onto it (Kevin, 2026-10-07).

    `ledger_transactions.description` is the bank's own text and is never
    changed (`private.forbid_mutation_of_facts`). An alias is a substring ->
    display name rule; every read of a transaction carries the name of the
    oldest live alias that matches as `display_description`
    (`ingest/merchant_aliases.with_display_description`). Display only: no
    categorisation, dedup, transfer detection or gate logic reads this table.

    AUTHORED, not gated: `updated_at`, a `deleted_at` tombstone and an
    `origin_guid` identity like `CategoryRule`. `managed = False`, its DDL
    shipped by `ingest/migrations/0016_merchant_aliases.py` (ADR 0044). Unlike
    every older synced table, `origin_guid` is a `uuid` column.
    """

    id = models.UUIDField(primary_key=True)
    substring = models.TextField()
    display_name = models.TextField()
    created_at_client = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.UUIDField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "merchant_aliases"
        constraints = [
            household_unique("merchant_aliases", "origin_guid"),
        ]


class BudgetTarget(models.Model):
    id = models.UUIDField(primary_key=True)
    category = models.TextField()
    currency = models.TextField()  # CHECK: SGD | USD
    amount_cents = models.BigIntegerField()
    effective_from_month = models.DateField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "budget_targets"
        constraints = [
            household_unique("budget_targets", "category", "currency", "effective_from_month"),
            household_unique("budget_targets", "origin_guid"),
        ]


class Statement(models.Model):
    id = models.UUIDField(primary_key=True)
    ingested_file = models.ForeignKey(
        IngestedFile,
        db_column="ingested_file_id",
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    account_last4 = models.TextField()  # CHECK: exactly 4 digits
    account_nickname = models.TextField()
    currency = models.TextField()  # CHECK: SGD | USD
    period_start = models.DateField()
    period_end = models.DateField()
    # Null only when `provenance = DETERMINISTIC` (CONSTRAINTS.md); every
    # other provenance must state a printed total per section 4 rule 2.
    stated_total_cents = models.BigIntegerField(null=True)
    opening_balance_cents = models.BigIntegerField()
    closing_balance_cents = models.BigIntegerField()
    # No DB default (unlike most `provenance` columns elsewhere) - a
    # statement must always say which of the four it is.
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()

    household = household_field()

    class Meta:
        managed = False
        db_table = "statements"
        unique_together = (("ingested_file", "account_last4"),)


class LedgerTransaction(models.Model):
    id = models.UUIDField(primary_key=True)
    statement = models.ForeignKey(
        Statement,
        db_column="statement_id",
        null=True,
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    account_last4 = models.TextField()
    account_nickname = models.TextField()
    currency = models.TextField()
    txn_date = models.DateField()
    description = models.TextField()
    amount_cents = models.BigIntegerField()
    balance_cents = models.BigIntegerField(null=True)
    line_ref = models.TextField()
    category = models.TextField(null=True)
    category_pending = models.BooleanField()
    pending_logged_at = models.DateTimeField(null=True)
    # The physical column is `reversal_of`, not Django's default
    # `reversal_of_id` for a field of this name - `db_column` has to be
    # explicit here (unlike `vehicle_id`-shaped columns elsewhere in this
    # app, where the field is named without its `_id` suffix and Django's
    # own default happens to reconstruct the real column name).
    reversal_of = models.ForeignKey(
        "self", db_column="reversal_of", null=True, on_delete=models.DO_NOTHING, related_name="+"
    )
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    origin_guid = models.TextField(null=True)
    # ADR 0057: the bank feed's own transaction id and whether the bank has
    # posted it yet. Set only on a `BANK_API` row, by check constraint
    # (`ingest/bank_api_sql.py`, migration 0020).
    bank_transaction_id = models.TextField(null=True)
    bank_pending = models.BooleanField(default=False)

    household = household_field()

    class Meta:
        managed = False
        db_table = "ledger_transactions"
        constraints = [
            household_unique("ledger_transactions", "origin_guid"),
        ]


class LedgerTransactionCategory(models.Model):
    """A category set OVER a gated `ledger_transactions` row, never on it.

    backend-etl ticket 14, option 2 (Kevin, 2026-09-28: "yes 2").
    `private.forbid_mutation_of_facts` refuses every UPDATE on
    `ledger_transactions`, category included, and that rule stays. So a
    category that arrives after the row - a person's choice, or a rule the
    household wrote after the row was stored - lives here, one row per
    transaction, and every read of a transaction's category reads this row IN
    PLACE OF the stored one while it is live (`deleted_at IS NULL`).
    `ingest/category_overrides.py` holds the read, the DDL and the backfill.

    AUTHORED, not gated: it has `updated_at` and a `deleted_at` tombstone like
    `categories`, and deleting it puts the stored category back in view.

    `managed = False` like every other ledger table, but its DDL ships in a
    Django migration (`ingest/migrations/0007_ledger_transaction_categories.py`),
    not a Supabase one: ADR 0044 makes Django the engine, and an integrity rule
    is SQL shipped by a Django migration.
    """

    SOURCE_RULE = "rule"
    SOURCE_PERSON = "person"

    id = models.UUIDField(primary_key=True)
    transaction = models.ForeignKey(
        LedgerTransaction,
        db_column="transaction_id",
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    category = models.TextField()
    # CHECK: rule | person. No `choices=` here on purpose: DRF would build its
    # own "is not a valid choice" refusal ahead of `choice_error`, which names
    # the allowed set (ticket 04's rule).
    source = models.TextField(default=SOURCE_PERSON)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(null=True)

    household = household_field()

    class Meta:
        managed = False
        db_table = "ledger_transaction_categories"
