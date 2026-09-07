"""`/api/pantry/<table>` - three tables, split the same way `api/ledger.py` is.

| Table | Route | Why |
|---|---|---|
| `grocery_staples` | full CRUD | authored config, `LastAspectsBackend` already writes it |
| `receipts` | **GET only** | the section 4 gate's own output |
| `receipt_line_items` | **GET only** | the section 4 gate's own output |

Read `api/ledger.py`'s module doc for the reasoning behind the split; it is
the same rule (ticket 04's exceptions table, CLAUDE.md section 4 rule 2 as
routing) and is not restated here.

## What is NOT here, and it is not an omission

- **`grocery_items`.** Nothing syncs it: the trip list retired into
  checklists on 2026-09-05, and `checklists.urls` is where that capability
  lives now. A route over a table no client reads and no backend writes would
  be a route nobody would notice going wrong.
- **`meal_logs` and `meal_targets`**, which `legacy/models/pantry.py` holds
  because ticket 02 split model files by domain and meals read as pantry.
  They are routed under `/api/body/` (`api/body.py`) because `BodyBackend.kt`
  carries them and the phone's body screens are what read them. The split is
  confusing enough that `api/body.py` says so too; the tables are routed once,
  not twice.

## Rule 5 and rule 7 both land on this aspect, and both are wire-visible

**Rule 5, the estimates.** `estimated_calories_kcal`, `estimated_protein_g`,
`estimated_carbs_g` and `estimated_fat_g` on a line item are a model's guess
from the product name. A receipt never prints them, they are excluded from
every reconciliation check, and every surface that renders one must say
"estimate". This API's user-facing surface is its generated schema, so each of
the four carries a `help_text` that says it - the same treatment `api/body.py`
gives `meal_logs`'s macros, for the same reason.

**Rule 7, the unaccounted amount.** `receipts.unaccounted_cents` is non-null
only on a receipt whose captured lines do not explain its total and which
could not be re-verified, and `receipts_unaccounted_requires_unreconciled`
makes it non-null, non-zero and `UNRECONCILED` together or not at all. It is
NEVER summed into an anchor: `tax := total - sum(lines)` would make
`sum(lines) + tax = total` true by construction and quietly absorb a genuinely
missed line, which is rule 6's failure shape. Its `help_text` says both halves,
because a client that folded it into a total would produce a figure that looks
reconciled and is not.

## Money is integer cents, checked against the live schema

`receipts.total_cents` / `subtotal_cents` / `tax_cents` /
`other_charges_cents` / `unaccounted_cents` and
`receipt_line_items.unit_price_cents` / `total_price_cents` are all `bigint`,
read from `information_schema.columns` on 2026-09-07 rather than trusted from
the model file, and `BigIntegerField` in the model, which DRF renders as a
JSON integer.

**`quantity` and the four `estimated_*` columns are NOT cents and are NOT
integers.** Postgres declares them bare `numeric` with no precision or scale,
so the model bounds them generously and this API renders them through DRF's
`DecimalField`, which serialises as a STRING by default
(`COERCE_DECIMAL_TO_STRING`). That is left alone deliberately: a decimal
rendered as a JSON number is a decimal handed to a float parser, and these are
the fields where "2 for the price of 3" quantities live. Section 4 rule 3
governs money, and none of these five is money.
"""
from __future__ import annotations

from rest_framework import serializers

from api.synced import (
    GatedReadSerializer,
    GatedReadViewSet,
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    minimum_error,
)
from legacy.models.pantry import GroceryStaple, Receipt, ReceiptLineItem

RECEIPT_ENDPOINT = "POST /api/ingest/receipt"

ESTIMATE_HELP = (
    "An estimate, not a measurement: the model's guess from the product name. A receipt never "
    "prints this. Excluded from every reconciliation check and never a gated figure (CLAUDE.md "
    "section 4 rule 5); any surface showing it must say it is an estimate."
)


# =============================================================================
# The one CONFIG table. Authored, uniform, full CRUD.
# =============================================================================


class GroceryStapleSerializer(SyncedSerializer):
    """Field-for-field `RemoteGroceryStaple` / `GroceryStapleFields`
    (`LastAspectsBackend.kt`).

    `grocery_staples` carries no CHECK constraints at all - CONSTRAINTS.md's
    own no-constraints list names it - only `grocery_staples_name_unique` and
    the `origin_guid` unique. The two refusals below are therefore this API's
    own, and each has a reason beyond tidiness: a blank `name` would take the
    unique name slot the real staple wants, and a `times_bought` below 1 would
    contradict the column's own `default 1`, which exists because a staple
    only becomes one by being bought at least once.
    """

    class Meta:
        model = GroceryStaple
        fields = [
            "id",
            "name",
            "display_name",
            "times_bought",
            "last_bought_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            # DB default 1, and a caller that omits it means "bought once".
            "times_bought": {"required": False, "default": 1},
        }

    def validate_name(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("name")
        return value

    def validate_display_name(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("display_name")
        return value

    def validate_times_bought(self, value: int) -> int:
        if value < 1:
            raise minimum_error("times_bought", value, 1, exclusive=False)
        return value


# =============================================================================
# The two GATED tables. GET only - see this module's own doc comment.
# =============================================================================


class ReceiptSerializer(GatedReadSerializer):
    """One `public.receipts` header, with the anchors the gate checked it
    against (section 4 rule 8) and the residual it could not explain (rule 7).

    `ingested_file_id` is nullable here and NOT on `statements`: a receipt can
    reach the gate from a photo the phone took rather than from a file it
    scanned, so there is not always a file row to point at.
    """

    # `allow_null`, unlike `StatementSerializer`'s: `receipts.ingested_file_id`
    # is nullable on the live schema and `statements.ingested_file_id` is not.
    # A relation field declared by hand does not pick that up from the model.
    ingested_file_id = serializers.PrimaryKeyRelatedField(
        source="ingested_file", read_only=True, allow_null=True
    )

    class Meta:
        model = Receipt
        fields = [
            "id",
            "ingested_file_id",
            "store",
            "purchase_date",
            "currency",
            "total_cents",
            "subtotal_cents",
            "tax_cents",
            "other_charges_cents",
            "photo_object_path",
            "provenance",
            "created_at",
            "origin_guid",
            "unaccounted_cents",
        ]
        extra_kwargs = {
            "total_cents": {
                "help_text": (
                    "The total the receipt printed, in cents. The anchor the line items were "
                    "reconciled against (CLAUDE.md section 4 rule 2)."
                )
            },
            "unaccounted_cents": {
                "help_text": (
                    "Null on every healthy receipt. Non-null means the total is MORE than the "
                    "captured lines explain and the difference could not be re-verified "
                    "(CLAUDE.md section 4 rule 7's 2026-08-26 amendment). `provenance` reads "
                    "UNRECONCILED on exactly those rows, by check constraint, so the two "
                    "always agree. **Never add this into a total or any other anchor** - it is "
                    "the residual the arithmetic could not explain, not a figure to reconcile "
                    "against - and any surface showing such a receipt must say in words that "
                    "it is unverified."
                )
            },
            "provenance": {
                "help_text": (
                    "DETERMINISTIC / LLM_RECONCILED / UNRECONCILED / USER. UNRECONCILED is "
                    "provisional and transient: it is superseded, and physically deleted, when "
                    "a document that DID pass the gate covers the same purchase."
                )
            },
        }


class ReceiptLineItemSerializer(GatedReadSerializer):
    """One `public.receipt_line_items` row: what the receipt printed, plus four
    macro estimates it did not. See this module's doc comment on rule 5, and on
    why `quantity` and the estimates render as strings rather than numbers."""

    class Meta:
        model = ReceiptLineItem
        fields = [
            "id",
            "receipt_id",
            "name",
            "quantity",
            "unit_price_cents",
            "total_price_cents",
            "estimated_calories_kcal",
            "estimated_protein_g",
            "estimated_carbs_g",
            "estimated_fat_g",
            "reversal_of",
            "provenance",
            "created_at",
            "origin_guid",
        ]
        extra_kwargs = {
            "total_price_cents": {
                "help_text": (
                    "In cents. Summed across a receipt's lines, this is what the gate checked "
                    "against the printed total (CLAUDE.md section 4 rule 2)."
                )
            },
            "estimated_calories_kcal": {"help_text": ESTIMATE_HELP},
            "estimated_protein_g": {"help_text": ESTIMATE_HELP},
            "estimated_carbs_g": {"help_text": ESTIMATE_HELP},
            "estimated_fat_g": {"help_text": ESTIMATE_HELP},
        }

    # `receipt_id` is the physical column, and `ModelSerializer` would name the
    # field `receipt` after the model attribute. Declared so the wire name is
    # the column name, matching what every existing client reads - the same
    # correction `StatementSerializer.ingested_file_id` makes.
    receipt_id = serializers.PrimaryKeyRelatedField(source="receipt", read_only=True)


class _PantryViewSet(SyncedModelViewSet):
    aspect = "pantry"


class GroceryStapleViewSet(_PantryViewSet):
    table = "grocery_staples"
    serializer_class = GroceryStapleSerializer


class _GatedPantryViewSet(GatedReadViewSet):
    """Its own base rather than a mixin pair - see `api/ledger._GatedLedgerViewSet`
    for why."""

    aspect = "pantry"


class ReceiptViewSet(_GatedPantryViewSet):
    table = "receipts"
    serializer_class = ReceiptSerializer
    gate_endpoint = RECEIPT_ENDPOINT


class ReceiptLineItemViewSet(_GatedPantryViewSet):
    table = "receipt_line_items"
    # `/api/pantry/line-items/`, this ticket's own path. The changes-feed key
    # stays `receipt_line_items`.
    url_segment = "line-items"
    serializer_class = ReceiptLineItemSerializer
    gate_endpoint = RECEIPT_ENDPOINT


PANTRY_VIEWSETS = [
    GroceryStapleViewSet,
    ReceiptViewSet,
    ReceiptLineItemViewSet,
]
