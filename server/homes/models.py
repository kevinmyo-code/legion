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


# -- user-aspect records (engine-brain ticket 15) --------------------------------------------------
#
# The phone's aspect engine (`data/local/Aspect.kt`, `RecordType.kt`, `FieldDef.kt`,
# `EngineRecord.kt`): fixed metadata tables plus one generic record table with a JSON payload,
# never runtime DDL (CLAUDE.md section 1). Only USER-authored aspects come here: an aspect a
# capability plugin seeded on the phone (fleet, ledger, pantry, notes, dates, places; its fields
# carry `ownerPluginId`) holds replica copies of rows that already have typed homes on the engine,
# and is never sent.
#
# **Identity is the phone's.** A record's `origin_guid` is its Room `guid`. The three schema tables
# have no guid column in Room (frozen at v80), so the phone derives one per row (a name-based uuid
# of kind, local id and creation instant) and remembers the engine's guid for a row the engine
# wrote (`HomesRecordIds` on the phone). A rename keeps the guid.
#
# **No local id travels.** Payload keys are field `origin_guid`s, a REFERENCE value is the target
# record's `origin_guid`, and a field's `config` names record types and fields by guid
# (`targetRecordType`, `childRecordType`, `viaField`, `sourceField`, `leftField`, `rightField`).
#
# **Shared in the household**, like every hand-made row (ADR 0052: "hand-made rows default to
# shared"); no owner column, so no `OWNER_PATHS` entry. Ownership is the FK chain aspect ->
# record type -> field and record; a record type's two "primary field" pointers are soft guid
# text, because field_defs already point at record_types and a second FK back would be a cycle.

FIELD_TYPES: tuple[str, ...] = (
    "TEXT",
    "NUMBER",
    "MONEY_CENTS",
    "DATE",
    "DATETIME",
    "BOOLEAN",
    "CHOICE",
    "MULTI_SELECT_CHOICE",
    "REFERENCE",
    "PHOTO",
    "LOCATION",
    "RATING",
    "COMPUTED",
)
RECORD_PROVENANCES: tuple[str, ...] = (
    "DETERMINISTIC",
    "LLM_RECONCILED",
    "UNRECONCILED",
    "USER",
    "BANK_API",
)


class UserAspect(models.Model):
    """One user-authored aspect (a tracker): "Workouts", "Reading". Archive is its only delete."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    origin_guid = models.TextField()
    name = models.TextField()
    icon = models.TextField(default="", blank=True)
    color = models.TextField(default="", blank=True)
    position = models.IntegerField(default=0)
    archived = models.BooleanField(default=False)
    archived_at = models.DateTimeField(null=True, blank=True)
    # The phone's own clocks (`createdAt` / `updatedAt`): what a pull compares, never `updated_at`.
    created_at_client = models.DateTimeField()
    updated_at_client = models.DateTimeField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "user_aspects"
        indexes = [
            models.Index(fields=["household", "updated_at"], name="user_aspects_updated_idx")
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("user_aspects", ["origin_guid"]),
            ),
        ]


class UserRecordType(models.Model):
    """One record shape inside a user aspect ("Session" in "Workouts")."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    origin_guid = models.TextField()
    aspect = models.ForeignKey(UserAspect, on_delete=models.PROTECT, related_name="record_types")
    name = models.TextField()
    # Which field mirrors into a record's promoted `amount_cents` / `due_at`: a field `origin_guid`.
    primary_amount_field = models.TextField(null=True, blank=True)
    primary_due_date_field = models.TextField(null=True, blank=True)
    created_at_client = models.DateTimeField()
    updated_at_client = models.DateTimeField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "user_record_types"
        indexes = [
            models.Index(fields=["household", "updated_at"], name="user_record_types_updated_idx")
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("user_record_types", ["origin_guid"]),
            ),
        ]


class UserFieldDef(models.Model):
    """One field of a user record type. `type` has no CHECK: the phone widens its enum without a
    migration (`FieldType`, CLAUDE.md section 5), and a refused value would lose the row."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    origin_guid = models.TextField()
    record_type = models.ForeignKey(
        UserRecordType, on_delete=models.PROTECT, related_name="field_defs"
    )
    name = models.TextField()
    type = models.TextField()
    required = models.BooleanField(default=False)
    position = models.IntegerField(default=0)
    # JSON text, guid-keyed (see the section comment above); null for a plain field.
    config = models.TextField(null=True, blank=True)
    created_at_client = models.DateTimeField()
    updated_at_client = models.DateTimeField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "user_field_defs"
        indexes = [
            models.Index(fields=["household", "updated_at"], name="user_field_defs_updated_idx")
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("user_field_defs", ["origin_guid"]),
            ),
        ]


class UserRecord(models.Model):
    """One entry of a user record type. `payload` is keyed by field `origin_guid`; values are the
    phone's own shapes (epoch-ms dates, integer cents, a list for multi-select)."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    origin_guid = models.TextField()
    record_type = models.ForeignKey(
        UserRecordType, on_delete=models.PROTECT, related_name="records"
    )
    due_at = models.DateTimeField(null=True, blank=True)
    amount_cents = models.BigIntegerField(null=True, blank=True)
    search_text = models.TextField(default="", blank=True)
    provenance = models.TextField(default="USER")
    payload = models.JSONField(default=dict)
    created_at_client = models.DateTimeField()
    updated_at_client = models.DateTimeField()
    # The phone's 30-day trash (`EngineRecord.deletedAt`): restorable, so it travels as a value.
    trashed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "user_records"
        indexes = [
            models.Index(fields=["household", "updated_at"], name="user_records_updated_idx"),
            models.Index(fields=["household", "record_type"], name="user_records_type_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["household", "origin_guid"],
                name=household_unique_name("user_records", ["origin_guid"]),
            ),
        ]
