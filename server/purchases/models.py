"""`public.purchases`: the household bought log (purchase-log ticket 01, ADR 0055).

A bought entry is its own record, not a note and not a tick (Kevin,
2026-10-04). It says that someone in the household bought `item` on the local
day `bought_on`. Three ways one is made, named by `source`:

- `MANUAL`: typed or said by a member (REST, the web form, `/mcp`).
- `GROCERIES_TICK`: a tick on the household's Groceries list
  (`purchases/groceries.py`, ADR 0055), one per tick, linked by `tick`.
- `GROCERIES_BACKFILL`: a Groceries tick made before the hook existed,
  imported once by `migrations/0002_backfill_groceries_ticks.py`. Who ticked
  it was never recorded, so `created_by` is null and every surface says
  "not recorded".

Born tenanted, in `public`, like `ingest_runs` and `push_*`: `household` is an
ordinary foreign key, the table is in `household.tenancy.TENANT_TABLES`, and
every read goes through `household.tenancy.visible()` (ADR 0052's owner rule,
`OWNER_PATHS["purchases"]`). A private entry is invisible to the other member
on every surface, `/mcp` included.

**Price is what someone typed** (map notes). `price_cents` is integer cents
(CLAUDE.md section 4 rule 3), never reconciled against the bank, never summed
into a ledger figure, and every surface that shows it says it was entered by
hand.

The phone reaches this table online only (ticket 04, Kevin): there is no Room
replica and no `/api/changes` key for it, so it carries no `updated_at`.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Lower, Now

from household.tenancy_sql import household_unique_name

SOURCE_MANUAL = "MANUAL"
SOURCE_GROCERIES_TICK = "GROCERIES_TICK"
SOURCE_GROCERIES_BACKFILL = "GROCERIES_BACKFILL"
SOURCES: tuple[str, ...] = (SOURCE_MANUAL, SOURCE_GROCERIES_TICK, SOURCE_GROCERIES_BACKFILL)


class Purchase(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # The text as written ("Head & Shoulders shampoo"). For a Groceries tick,
    # the item's text at the moment of the tick; a later edit of the list item
    # does not rewrite the purchase.
    item = models.TextField()
    # Local epoch day (`LocalDate.toEpochDay()`), the same unit as
    # `checklist_ticks.day`, so a timezone change cannot move a purchase.
    bought_on = models.IntegerField()
    # The instant the entry was written, on the database clock.
    logged_at = models.DateTimeField(db_default=Now())
    # Who logged it. Null only for `GROCERIES_BACKFILL` ("not recorded"), or
    # after that member's account was hard-deleted. Never on the wire as an id.
    created_by = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    store = models.TextField(null=True, blank=True)
    # Integer cents, entered by hand. Null when nobody said.
    price_cents = models.BigIntegerField(null=True, blank=True)
    quantity_note = models.TextField(null=True, blank=True)
    # ADR 0052. Null is shared, set is private to that member. RESTRICT for the
    # reason `checklists.owner_user` gives: SET NULL would make private rows
    # shared in the same statement that deleted their owner.
    owner_user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.RESTRICT, related_name="+"
    )
    source = models.TextField(default=SOURCE_MANUAL)
    # The Groceries tick that made this entry. SET_NULL, not CASCADE: a
    # purchase is a fact about the world and outlives the row that recorded
    # it. Ticks are only ever soft-deleted through the API anyway.
    tick = models.OneToOneField(
        "checklists.ChecklistTick",
        null=True,
        blank=True,
        on_delete=models.SET_NULL,
        related_name="purchase",
    )
    deleted_at = models.DateTimeField(null=True, blank=True)
    sync_id = models.CharField(max_length=64, null=True, blank=True)

    class Meta:
        db_table = "purchases"
        indexes = [
            models.Index(fields=["household", "bought_on"], name="purchases_household_day_idx"),
            models.Index("household", Lower("item"), name="purchases_household_item_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "sync_id"],
                name=household_unique_name("purchases", ["sync_id"]),
            ),
            models.CheckConstraint(
                condition=models.Q(source__in=SOURCES), name="purchases_source_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(price_cents__isnull=True) | models.Q(price_cents__gte=0),
                name="purchases_price_not_negative",
            ),
            # A hand-logged entry never claims to come from a tick.
            models.CheckConstraint(
                condition=~models.Q(source=SOURCE_MANUAL) | models.Q(tick__isnull=True),
                name="purchases_manual_has_no_tick",
            ),
            models.CheckConstraint(
                condition=~models.Q(item__regex=r"^\s*$"), name="purchases_item_not_blank"
            ),
        ]

    def __str__(self) -> str:
        return f"{self.item} on day {self.bought_on}"
