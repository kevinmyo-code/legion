"""Pantry: receipts (gated per section 4), their line items, grocery
staples, and meal logging. Nutrition estimates (`estimated_*` on
`ReceiptLineItem`, `calories_kcal`/`protein_g`/`carbs_g`/`fat_g` on
`MealLog`) are LLM guesses a receipt never prints - section 4 rule 5 says
they must never be gated and must read as estimates everywhere they
surface; nothing in a read-only mirror enforces that, the API layer will.
"""
from __future__ import annotations

from django.db import models

from legacy.enums import Provenance
from legacy.models.ingest import IngestedFile


class GroceryStaple(models.Model):
    id = models.UUIDField(primary_key=True)
    name = models.TextField(unique=True)
    display_name = models.TextField()
    times_bought = models.IntegerField()
    last_bought_at = models.DateTimeField()
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "grocery_staples"


class MealLog(models.Model):
    id = models.UUIDField(primary_key=True)
    description = models.TextField()
    calories_kcal = models.IntegerField(null=True)  # estimate, section 4 rule 5
    protein_g = models.FloatField(null=True)  # estimate
    carbs_g = models.FloatField(null=True)  # estimate
    fat_g = models.FloatField(null=True)  # estimate
    logged_at = models.DateTimeField()
    source_image_path = models.TextField(null=True)
    trust_tier = models.TextField()  # CHECK: PROVEN | REPORTED
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "meal_logs"


class MealTarget(models.Model):
    id = models.UUIDField(primary_key=True)
    calories_kcal = models.IntegerField()
    protein_g = models.FloatField()
    carbs_g = models.FloatField()
    fat_g = models.FloatField()
    effective_from_date = models.DateField(unique=True)
    provenance = models.TextField(choices=Provenance.choices)
    created_at = models.DateTimeField()
    updated_at = models.DateTimeField()
    deleted_at = models.DateTimeField(null=True)
    origin_guid = models.TextField(unique=True)

    class Meta:
        managed = False
        db_table = "meal_targets"


class Receipt(models.Model):
    """No `updated_at`/`deleted_at` - see the note at the top of
    `legacy/models/ledger.py`; a gated receipt is a fact, not a draft.
    `unaccounted_cents` is CLAUDE.md section 4 rule 7's amendment column:
    never summed into an anchor, non-null only when `provenance =
    UNRECONCILED` (CONSTRAINTS.md `receipts_unaccounted_requires_unreconciled`).
    """

    id = models.UUIDField(primary_key=True)
    ingested_file = models.ForeignKey(
        IngestedFile,
        db_column="ingested_file_id",
        null=True,
        on_delete=models.DO_NOTHING,
        related_name="+",
    )
    store = models.TextField()
    purchase_date = models.DateField()
    currency = models.TextField()  # CHECK: SGD | USD
    total_cents = models.BigIntegerField()
    subtotal_cents = models.BigIntegerField(null=True)
    tax_cents = models.BigIntegerField(null=True)
    other_charges_cents = models.BigIntegerField(null=True)
    photo_object_path = models.TextField(null=True)
    provenance = models.TextField(choices=Provenance.choices)  # no DB default
    created_at = models.DateTimeField()
    origin_guid = models.TextField(null=True, unique=True)
    unaccounted_cents = models.BigIntegerField(null=True)

    class Meta:
        managed = False
        db_table = "receipts"


class ReceiptLineItem(models.Model):
    id = models.UUIDField(primary_key=True)
    receipt = models.ForeignKey(
        Receipt, db_column="receipt_id", on_delete=models.DO_NOTHING, related_name="+"
    )
    name = models.TextField()
    # Postgres declares this bare `numeric` - no precision/scale at all, so
    # Postgres itself enforces no bound. Django's `DecimalField` requires
    # one; `max_digits=10, decimal_places=3` is chosen generously against
    # the live data (largest observed value so far is 2.0, all values seen
    # have at most two decimal places) and is NOT a source of truth - the
    # database's bound is "none". Hand-correction, ticket 02.
    quantity = models.DecimalField(max_digits=10, decimal_places=3)
    unit_price_cents = models.BigIntegerField(null=True)
    total_price_cents = models.BigIntegerField()
    # Same `numeric`-with-no-bound situation as `quantity`, and the same
    # section 4 rule 5 estimate status as `MealLog`'s macro columns above.
    estimated_calories_kcal = models.DecimalField(max_digits=10, decimal_places=3, null=True)
    estimated_protein_g = models.DecimalField(max_digits=10, decimal_places=3, null=True)
    estimated_carbs_g = models.DecimalField(max_digits=10, decimal_places=3, null=True)
    estimated_fat_g = models.DecimalField(max_digits=10, decimal_places=3, null=True)
    # Same `db_column` correction as `LedgerTransaction.reversal_of` in
    # `legacy/models/ledger.py` - the physical column is `reversal_of`, not
    # Django's default `reversal_of_id`.
    reversal_of = models.ForeignKey(
        "self", db_column="reversal_of", null=True, on_delete=models.DO_NOTHING, related_name="+"
    )
    provenance = models.TextField(choices=Provenance.choices)  # no DB default
    created_at = models.DateTimeField()
    origin_guid = models.TextField(null=True, unique=True)

    class Meta:
        managed = False
        db_table = "receipt_line_items"
