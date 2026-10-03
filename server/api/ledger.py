"""`/api/ledger/<table>` - five tables, and they are NOT all the same shape.

This is the first aspect where ticket 04's uniform shape and its own
exceptions table meet in one module, so the split is stated here rather than
left to be inferred from which base class each viewset happens to extend:

| Table | Route | Why |
|---|---|---|
| `categories` | full CRUD | authored config, `LedgerConfigBackend` already writes it |
| `category_rules` | full CRUD | same |
| `budget_targets` | full CRUD | same |
| `ledger_transaction_categories` | full CRUD, by transaction | a category laid OVER a gated row |
| `statements` | **GET only** | the section 4 gate's own output |
| `ledger_transactions` | **GET only** | the section 4 gate's own output |

## A transaction's category is the EFFECTIVE one

backend-etl ticket 14, option 2 (Kevin, 2026-09-28: "yes 2"). The gate's
trigger refuses every UPDATE on `ledger_transactions`, so a category that
arrives after the row - a person's, or a rule's via
`manage.py apply_category_rules` - is a row in `ledger_transaction_categories`
instead. On `GET /api/ledger/transactions/` (and in `/api/changes`):

- `category` is the **effective** category: a live override's if there is
  one, else the row's own. Every reader, including an installed phone that
  has never heard of overrides, sees the right one without asking.
- `category_pending` is False under a live override.
- `stored_category` is the row's own column, exactly as the gate wrote it.
- `category_source` says which one `category` is: `person`, `rule`,
  `stored`, or null when there is none.

The precedence is written once, in `ingest/category_overrides.py`.

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

from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from api.synced import (
    GatedReadSerializer,
    GatedReadViewSet,
    HouseholdScopedPrimaryKeyRelatedField,
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    choice_error,
)
from ingest.category_overrides import STORED, with_effective_category
from ingest.provisional import ROW_NOTE
from legacy.enums import Provenance
from legacy.models.ledger import (
    BudgetTarget,
    Category,
    CategoryRule,
    LedgerTransaction,
    LedgerTransactionCategory,
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
            "excluded_from_spend",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "excluded_from_spend": {
                "help_text": (
                    "True for a category that is not spending (money moving between the "
                    "household's own places, e.g. Transfers). Every spend total leaves its rows "
                    "out AND says so in words. Defaults false; omitted on a PUT, it is unchanged."
                )
            }
        }

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


SOURCE_CHOICES = (LedgerTransactionCategory.SOURCE_PERSON, LedgerTransactionCategory.SOURCE_RULE)


class LedgerTransactionCategorySerializer(SyncedSerializer):
    """One `ledger_transaction_categories` row: a category shown in place of a
    gated transaction's own.

    `transaction_id` can only name a transaction in the caller's household
    (`HouseholdScopedPrimaryKeyRelatedField`); one in another household reads
    as one that does not exist, which from here it does not.

    **`source` defaults to `person` on every write**, not only on insert. A
    PUT says "the row in this state", and a client that left `source` out of
    a PUT over a `rule` row is a person choosing a category; keeping `rule`
    would let the backfill's word stand over theirs.

    **A `rule` never replaces a live `person` row.** Refused here in words; a
    trigger refuses it again in SQL (`ingest/category_overrides.py`).
    """

    transaction_id = HouseholdScopedPrimaryKeyRelatedField(
        source="transaction", queryset=LedgerTransaction.objects.all()
    )

    class Meta:
        model = LedgerTransactionCategory
        fields = [
            "id",
            "transaction_id",
            "category",
            "source",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            "source": {
                "required": False,
                "help_text": (
                    "`person` (the default: someone chose it) or `rule` (written by "
                    "`manage.py apply_category_rules` from the household's rules). A rule "
                    "never replaces a live person row."
                ),
            },
            "origin_guid": {"required": False, "allow_null": True},
        }

    def validate_category(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("category")
        return value

    def validate_source(self, value: str) -> str:
        if value not in SOURCE_CHOICES:
            raise choice_error("source", value, SOURCE_CHOICES)
        return value

    def validate(self, attrs: dict) -> dict:
        attrs.setdefault("source", LedgerTransactionCategory.SOURCE_PERSON)
        existing = self.instance
        if (
            existing is not None
            and existing.deleted_at is None
            and existing.source == LedgerTransactionCategory.SOURCE_PERSON
            and attrs["source"] == LedgerTransactionCategory.SOURCE_RULE
        ):
            raise serializers.ValidationError(
                {
                    "source": (
                        "Nothing was changed. A person set this transaction's category, "
                        "and a rule never replaces it."
                    )
                }
            )
        return attrs


class LedgerTransactionCategoryViewSet(_LedgerViewSet):
    """`/api/ledger/transaction_categories/<transaction_id>/`.

    **Keyed by the transaction**, not by an `origin_guid`: there is one row
    per transaction, so the transaction's own id is the natural identity and
    a PUT to it is idempotent by construction. `origin_guid` is on the row for
    a client that mints its own identity, and is not used for routing.

    DELETE tombstones the override, and the stored category comes back into
    view. A later PUT revives it (`put_revives_tombstone`), because setting a
    category again after clearing it is the same intention as the first time.
    """

    table = "ledger_transaction_categories"
    url_segment = "transaction_categories"
    serializer_class = LedgerTransactionCategorySerializer
    identity_field = "transaction_id"
    identity_url_converter = "uuid"
    put_revives_tombstone = True


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

    # Section 4 rule 7's third condition on the wire: "every surface that
    # renders one says so in words". `provenance` is an enum a client has to
    # know to translate; this is the sentence itself, starting with the word
    # "Unverified", on every UNRECONCILED row and null on every other, so a
    # client that renders it verbatim cannot get it wrong.
    verification_note = serializers.SerializerMethodField(
        help_text=(
            "A sentence beginning 'Unverified' on every UNRECONCILED row (section 4 rule 7: "
            "the source stated no anchor, so the row is provisional). Null on a row that "
            "passed the gate. A surface showing the row, or a total containing it, shows this."
        )
    )

    @extend_schema_field(serializers.CharField(allow_null=True))
    def get_verification_note(self, row) -> str | None:
        return ROW_NOTE if row.provenance == Provenance.UNRECONCILED else None

    # backend-etl ticket 14 option 2: the category every reader shows. These
    # read annotations `with_effective_category` puts on the queryset
    # (`LedgerTransactionViewSet.decorate`), and a queryset without them fails
    # loudly here rather than quietly serving the stored column as if it were
    # the effective one.
    category = serializers.CharField(
        source="effective_category",
        allow_null=True,
        help_text=(
            "The EFFECTIVE category: a live `ledger_transaction_categories` override's if "
            "there is one, else the row's own (`stored_category`). What every surface shows."
        ),
    )
    category_pending = serializers.BooleanField(
        source="effective_category_pending",
        help_text="False under a live override; otherwise the row's own flag.",
    )
    stored_category = serializers.CharField(
        source="category",
        allow_null=True,
        help_text=(
            "The row's own `category` column, exactly as the gate wrote it. Never changes: "
            "`forbid_mutation_of_facts` refuses every UPDATE."
        ),
    )
    category_source = serializers.ChoiceField(
        choices=[
            LedgerTransactionCategory.SOURCE_PERSON,
            LedgerTransactionCategory.SOURCE_RULE,
            STORED,
        ],
        allow_null=True,
        help_text=(
            "Which one `category` is: `person` or `rule` (a live override), `stored` (the "
            "row's own column), or null when the row has no category."
        ),
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
            "stored_category",
            "category_source",
            "pending_logged_at",
            "reversal_of",
            "provenance",
            "verification_note",
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
    """**How a client learns that a row is gone: the full list is the set.**

    A row leaves this table in exactly one way - a rule-7 supersession
    physically DELETEs an UNRECONCILED row when a verified statement, or a
    newer provisional export, covers its dates - and there is no tombstone
    to observe (`has_tombstones = False`; the table has no `deleted_at`). So
    the contract is the simplest one that is correct: **a client that wants
    to mirror this table fetches the whole list (no `since`), and any row it
    holds from this server that the list does not contain has been
    deleted.** At household scale (about a thousand rows) that is one to
    three pages. No tombstone table, no deleted-ids feed.

    It is only correct if the list is COMPLETE, which is why the list pages
    with a keyset (`next` plus `next_after`, `api/sync.paginate_keyset`): a
    statement's lines share one `created_at`, and without the tiebreak a
    page of them re-served itself and a client stopped early, which under
    this contract would have read as mass deletion. A client must treat any
    fetch that did not reach a page with `next: null` as incomplete and
    delete nothing. `backend/LedgerTransactionsSync.kt` on the phone is the
    first client that does this.
    """

    table = "ledger_transactions"
    # `/api/ledger/transactions/`, this ticket's own path. `ledger/ledger_transactions`
    # would say the word twice; the changes-feed key stays `ledger_transactions`.
    url_segment = "transactions"
    serializer_class = LedgerTransactionSerializer
    gate_endpoint = STATEMENT_ENDPOINT

    @classmethod
    def decorate(cls, queryset):
        """The effective category (`ingest/category_overrides.py`)."""
        return with_effective_category(queryset)


# Registry order is the order routes are declared and the order the changes
# feed's keys are built: config first, then the gated pair, which is the order
# a reader of `20260902000400_aspect_ledger_config.sql` and
# `20260825000300_aspect_ledger_pantry.sql` would meet them.
LEDGER_VIEWSETS = [
    CategoryViewSet,
    CategoryRuleViewSet,
    BudgetTargetViewSet,
    LedgerTransactionCategoryViewSet,
    StatementViewSet,
    LedgerTransactionViewSet,
]
