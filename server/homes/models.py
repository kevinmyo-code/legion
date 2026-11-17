"""Engine homes for data that lived only on the phone (engine-brain ticket 03, ADR 0095).

Kevin ruled the phone a thin client (2026-11-17), so what Alfred reads must be on the engine
before the phone's own loop retires. Three tables are new here; the fourth home, `goals`, is the
legacy table `legacy/models/body.py` already maps (routed by `homes/views.py`).

| Table | Phone twin (Room) | Who sees a row |
|---|---|---|
| `advisor_advice` | `advisor_advice` | private to the member it was given to (`OWNER_PATHS`) |
| `pending_transactions` | voice-logged `ledger_transactions` (`pendingLoggedAt`) | the household |
| `proactive_raises` | `proactive_raises` | private to the member it was said to (`OWNER_PATHS`) |

**Identity is the phone's**: `origin_guid` is the Room row's `syncId` (advice, pending), or for a
raise, which has no guid in Room, a name-based uuid of its `(ruleId, spokenAt)` the phone derives
the same way every time. So a push, a retry and the one-time backfill all land on one row.

**A pending transaction is never a ledger row.** `ledger_transactions` is written only by the
section 4 gate; a spoken "I just spent 12 at the cafe" is his word, unverified, and stays in its
own table until the bank's row arrives. Money is `Long` cents (CLAUDE.md section 4 rule 3).

Born tenanted in `public` (the search_path swap `greeting/migrations/0001_initial.py` uses) and
listed in `household.tenancy.TENANT_TABLES`. The two private tables carry the ADR 0052 owner guard.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now

from household.tenancy_sql import household_unique_name

CURRENCIES: tuple[str, ...] = ("SGD", "USD")
DELIVERIES: tuple[str, ...] = ("spoken", "notified", "rung")


class AdvisorAdvice(models.Model):
    """One piece of advice the advisor gave on an aspect, and what became of its proposal."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    origin_guid = models.TextField()
    aspect = models.TextField()
    question_text = models.TextField()
    gist = models.TextField()
    advice_text = models.TextField()
    # The proposal exactly as the advisor wrote it (JSON text), or null when it proposed nothing.
    proposal_json = models.TextField(null=True, blank=True)
    outcome = models.TextField(default="pending")
    given_at = models.DateTimeField()
    resolved_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "advisor_advice"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "updated_at"], name="advisor_advice_owner_idx"
            )
        ]
        constraints = [
            # No CHECK on `outcome`: the phone's own doc makes a new outcome "a code change, not a
            # migration", and a refused value would lose a row in the backfill.
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("advisor_advice", ["origin_guid"]),
            ),
        ]


class PendingTransaction(models.Model):
    """A charge or credit he said out loud, before the bank's own row exists. Unverified."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    origin_guid = models.TextField()
    account_id = models.TextField()
    currency = models.TextField()
    txn_date = models.DateField()
    description = models.TextField()
    # Signed integer cents: negative is money out, as on the ledger.
    amount_cents = models.BigIntegerField()
    logged_at = models.DateTimeField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    # Set when he clears it (the bank's row arrived, or it was a mistake). Never a hard delete.
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "pending_transactions"
        indexes = [models.Index(fields=["household", "updated_at"], name="pending_txn_updated_idx")]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("pending_transactions", ["origin_guid"]),
            ),
            models.CheckConstraint(
                condition=models.Q(currency__in=CURRENCIES), name="pending_txn_currency_valid"
            ),
            models.CheckConstraint(
                condition=~models.Q(amount_cents=0), name="pending_txn_amount_nonzero"
            ),
        ]


class ProactiveRaise(models.Model):
    """One unsolicited line Alfred said, rang or posted, and the rule and fact behind it.

    `reason` is the rule's own sentence about the fact (`ProactiveRaise.reason` on the phone),
    never the text a raise was built from: a mail-reply raise stores a fixed phrase, not the mail.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    origin_guid = models.TextField()
    rule_id = models.TextField()
    category = models.TextField()
    reason = models.TextField()
    spoken_at = models.DateTimeField()
    declined = models.BooleanField(default=False)
    delivery = models.TextField(default="spoken")
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "proactive_raises"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "spoken_at"], name="proactive_raises_owner_idx"
            ),
            models.Index(fields=["household", "updated_at"], name="proactive_raises_updated_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("proactive_raises", ["origin_guid"]),
            ),
            models.CheckConstraint(
                condition=models.Q(delivery__in=DELIVERIES), name="proactive_raises_delivery_valid"
            ),
        ]
