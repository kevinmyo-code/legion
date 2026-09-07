"""`/api/ledger/<table>` - five tables, and they are NOT all the same shape.

This is the first aspect where ticket 04's uniform shape and its own
exceptions table meet in one module, so the split is stated here rather than
left to be inferred from which base class each viewset happens to extend:

| Table | Route | Why |
|---|---|---|
| `categories` | full CRUD | authored config, `LedgerConfigBackend` already writes it |
| `category_rules` | full CRUD | same |
| `budget_targets` | full CRUD | same |
| `statements` | **GET only** | the section 4 gate's own output |
| `ledger_transactions` | **GET only** | the section 4 gate's own output |

Ticket 04 spells the second half out: "`statements`, `receipts`,
`ledger_transactions`, `receipt_line_items` - **no PUT, no DELETE.** Written
only by ticket 03's gate. GET only." That is CLAUDE.md section 4 rule 2 as a
routing decision. Rule 2's promise is that nothing partial is ever written and
that every stored row reconciled against the document's own printed total; a
PUT that let a client set `amount_cents` on a committed transaction would make
that promise unfalsifiable, because the row would no longer be evidence of
anything but the last person to touch it. `private.forbid_mutation_of_facts`
already refuses the UPDATE at the database, so a write route here would answer
400 rather than corrupt anything - but a 400 from a trigger is an accident
that happens to hold, and a route that does not exist is a design.

## The config tables are genuinely different, and the difference is provenance

`20260902000400_aspect_ledger_config.sql`'s own header: "All three are
AUTHORED, not gated (a hand-typed category or a confirmed categorisation rule
is a thing the app recorded, never a document that came through the
reconciliation gate), so every table gets `updated_at` and stays freely
editable/deletable - no `forbid_mutation_of_facts` trigger anywhere in this
file." They are the uniform shape with nothing to say about it:
`origin_guid` identity, `updated_at` feed, `deleted_at` tombstone.
`LedgerConfigBackend.kt` is the phone-side contract (nine functions over the
three tables) and each serializer below is field-for-field its matching
`Remote*` shape.

## Money is integer cents, checked against the live schema

CLAUDE.md section 4 rule 3. Every `*_cents` column on both halves of this
module - `budget_targets.amount_cents`, `statements.stated_total_cents` /
`opening_balance_cents` / `closing_balance_cents`,
`ledger_transactions.amount_cents` / `balance_cents` - is `bigint` in
Postgres, read from `information_schema.columns` on 2026-09-07 rather than
trusted from the model file, and `BigIntegerField` in `legacy/models/ledger.py`,
which DRF renders as a JSON integer. There is no `DecimalField` and no
`FloatField` on a cents column anywhere in this app; the gate depends on exact
integer equality and a `Decimal` would invite the illusion of exactness that
`float` at least has the honesty to refuse.

## `statements` and `ledger_transactions` carry the gate's own anchors

Section 4 rule 8: "every ingestion path stores the numbers the gate checked
against, in their own columns, alongside the rows they gated". `statements`
does - `stated_total_cents`, `opening_balance_cents`, `closing_balance_cents` -
and all three are on the wire here, because an anchor nobody can retrieve is
the exact failure rule 8 was written after. `stated_total_cents` is nullable
and its null is meaningful, not missing: the statement printed no total, which
`statements_total_only_null_if_deterministic` permits only for a
deterministically parsed one.
"""
from __future__ import annotations

from rest_framework import serializers

from api.synced import (
    GatedReadSerializer,
    GatedReadViewSet,
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    choice_error,
)
from legacy.models.ledger import (
    BudgetTarget,
    Category,
    CategoryRule,
    LedgerTransaction,
    Statement,
)

# `legacy/CONSTRAINTS.md`'s `## budget_targets`, `## statements` and
# `## ledger_transactions` sections, all read from the live schema. A caller
# outside the set is told the set (ticket 04: "a 400 naming the allowed set"),
# never handed Postgres's own constraint name.
CURRENCY_CHOICES = ("SGD", "USD")

STATEMENT_ENDPOINT = "POST /api/ingest/statement"


# =============================================================================
# The three CONFIG tables. Authored, uniform, full CRUD.
# =============================================================================


class CategorySerializer(SyncedSerializer):
    """Field-for-field `RemoteCategory` / `CategoryFields`."""

    class Meta:
        model = Category
        fields = [
            "id",
            "name",
            "is_food_category",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_name(self, value: str) -> str:
        # `categories` carries no CHECK at all (CONSTRAINTS.md's own
        # no-constraints list names it), only `categories_name_unique`. The
        # blank refusal is this API's, not Postgres's: a category named " "
        # would be storable and useless, and it would occupy the unique name
        # slot that the real one wants.
        if not value or not value.strip():
            raise blank_error("name")
        return value


class CategoryRuleSerializer(SyncedSerializer):
    """Field-for-field `RemoteCategoryRule` / `CategoryRuleFields`.

    `created_at_client` is writable and `created_at` is not, and the pair is
    deliberate rather than an oversight - the migration's own header explains
    it: `created_at` is Postgres's insert stamp, `created_at_client` is the
    instant the rule was written on the phone, and `LedgerController.applyCategoryRules`
    orders rules by the second one, oldest first, because the earliest rule
    written for a merchant is the one that governs it. Letting the server's
    clock stand in for the phone's would silently reorder every rule around
    whenever it happened to reach the server.
    """

    class Meta:
        model = CategoryRule
        fields = [
            "id",
            "category",
            "substring",
            "created_at_client",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_category(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("category")
        return value

    def validate_substring(self, value: str) -> str:
        # A rule whose substring is blank matches every description, so it
        # would categorise the whole ledger as one thing. Postgres does not
        # refuse it; this does.
        if not value or not value.strip():
            raise blank_error("substring")
        return value


class BudgetTargetSerializer(SyncedSerializer):
    """Field-for-field `RemoteBudgetTarget` / `BudgetTargetFields`.

    `amount_cents` is an integer. `effective_from_month` is a bare `date`, not
    a timestamp, matching `meal_targets.effective_from_date`'s precedent - the
    phone's `effectiveFromMonthEpoch` is always a UTC month-start instant by
    convention, so a date round-trips it with no timezone ambiguity.
    """

    class Meta:
        model = BudgetTarget
        fields = [
            "id",
            "category",
            "currency",
            "amount_cents",
            "effective_from_month",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_category(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("category")
        return value

    def validate_currency(self, value: str) -> str:
        if value not in CURRENCY_CHOICES:
            raise choice_error("currency", value, CURRENCY_CHOICES)
        return value


class _LedgerViewSet(SyncedModelViewSet):
    aspect = "ledger"


class CategoryViewSet(_LedgerViewSet):
    table = "categories"
    serializer_class = CategorySerializer


class CategoryRuleViewSet(_LedgerViewSet):
    table = "category_rules"
    serializer_class = CategoryRuleSerializer


class BudgetTargetViewSet(_LedgerViewSet):
    table = "budget_targets"
    serializer_class = BudgetTargetSerializer


# =============================================================================
# The two GATED tables. GET only - see this module's own doc comment.
# =============================================================================


class StatementSerializer(GatedReadSerializer):
    """One `public.statements` row: a bank statement's header and, crucially,
    the three anchors the gate checked it against (section 4 rule 8).

    `ingested_file_id` rather than DRF's default `ingested_file` because that
    is the column's real name, and the name every existing client already
    reads - these rows reached the phone through PostgREST, which renders the
    physical column. Renaming it here would have been a gratuitous break for
    a tidier-looking field.
    """

    ingested_file_id = serializers.PrimaryKeyRelatedField(source="ingested_file", read_only=True)

    class Meta:
        model = Statement
        fields = [
            "id",
            "ingested_file_id",
            "account_last4",
            "account_nickname",
            "currency",
            "period_start",
            "period_end",
            "stated_total_cents",
            "opening_balance_cents",
            "closing_balance_cents",
            "provenance",
            "created_at",
        ]
        extra_kwargs = {
            "stated_total_cents": {
                "help_text": (
                    "The total the statement itself printed, in cents. NULL means the "
                    "document printed none, which `statements_total_only_null_if_deterministic` "
                    "permits only for a deterministically parsed statement. Never synthesised "
                    "from the sum of the lines - that would make the check an identity "
                    "(CLAUDE.md section 4 rule 6)."
                )
            },
            "opening_balance_cents": {
                "help_text": "An anchor the gate checked against, in cents (section 4 rule 8)."
            },
            "closing_balance_cents": {
                "help_text": "An anchor the gate checked against, in cents (section 4 rule 8)."
            },
        }


class LedgerTransactionSerializer(GatedReadSerializer):
    """One `public.ledger_transactions` row.

    `provenance` is the load-bearing field on the wire, not decoration.
    `UNRECONCILED` means section 4 rule 7: the source stated no anchor, the
    row is provisional, it is transient, and **every surface that renders one
    must say so in words** - which for this API means a client cannot render
    this row without reading this field. `statement_id` is null on exactly
    those rows and non-null on every other, by
    `ledger_txn_header_matches_provenance`, so the two always agree.
    """

    # `allow_null` is not inherited from the model by a hand-declared relation
    # field, and it is load-bearing here rather than pedantry: `statement_id`
    # is NULL on exactly the UNRECONCILED rows (by
    # `ledger_txn_header_matches_provenance`), which is rule 7's whole shape. A
    # schema that called it non-nullable would hand a generated client a
    # required field that is absent on the rows it most needs to handle
    # carefully.
    statement_id = serializers.PrimaryKeyRelatedField(
        source="statement", read_only=True, allow_null=True
    )

    class Meta:
        model = LedgerTransaction
        fields = [
            "id",
            "statement_id",
            "account_last4",
            "account_nickname",
            "currency",
            "txn_date",
            "description",
            "amount_cents",
            "balance_cents",
            "line_ref",
            "category",
            "category_pending",
            "pending_logged_at",
            "reversal_of",
            "provenance",
            "created_at",
            "origin_guid",
        ]
        extra_kwargs = {
            "provenance": {
                "help_text": (
                    "DETERMINISTIC / LLM_RECONCILED / UNRECONCILED / USER. **UNRECONCILED is "
                    "provisional** (CLAUDE.md section 4 rule 7): the source stated no anchor, "
                    "the figure is unverified, and any surface showing it - or any total "
                    "containing it - must say so in words, never by colour or a glyph alone."
                )
            },
            "reversal_of": {
                "help_text": (
                    "The id of the row this one reverses, or null. A gated row is never "
                    "edited; it is corrected by posting a reversal and a replacement."
                )
            },
        }


class _GatedLedgerViewSet(GatedReadViewSet):
    """Written as its own base rather than as `(_LedgerViewSet, GatedReadViewSet)`
    on purpose: that pair resolves correctly today only because `_LedgerViewSet`
    happens to set nothing but `aspect`, and the day it sets a second attribute
    the MRO would silently hand a gated table a writable default. One base, one
    place to read."""

    aspect = "ledger"


class StatementViewSet(_GatedLedgerViewSet):
    table = "statements"
    serializer_class = StatementSerializer
    gate_endpoint = STATEMENT_ENDPOINT


class LedgerTransactionViewSet(_GatedLedgerViewSet):
    table = "ledger_transactions"
    # `/api/ledger/transactions/`, this ticket's own path. `ledger/ledger_transactions`
    # would say the word twice; the changes-feed key stays `ledger_transactions`.
    url_segment = "transactions"
    serializer_class = LedgerTransactionSerializer
    gate_endpoint = STATEMENT_ENDPOINT


# Registry order is the order routes are declared and the order the changes
# feed's keys are built: config first, then the gated pair, which is the order
# a reader of `20260902000400_aspect_ledger_config.sql` and
# `20260825000300_aspect_ledger_pantry.sql` would meet them.
LEDGER_VIEWSETS = [
    CategoryViewSet,
    CategoryRuleViewSet,
    BudgetTargetViewSet,
    StatementViewSet,
    LedgerTransactionViewSet,
]
