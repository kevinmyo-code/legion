"""`public.vault_items` and `public.vault_reveals`: the ID vault (ADR 0076).

Kevin, 2026-11-05: *"centralized memory. and the id vault"*. The vault holds the identifiers that
must never reach a model: passport, NRIC, SSN, A-number, bank account numbers. **Phone only,
behind fingerprint. Never on the web, never readable by Claude, never spoken aloud, never sent to
the AI model, never logged.** Plates, VINs and insurance policy numbers are ordinary data and do
not belong here.

- **Each item is one member's, always.** `owner_user` is ADR 0052's column and is NOT NULL here:
  there is no shared vault item, so `household.tenancy.visible()` (`OWNER_PATHS`) gives a member
  their own items and nothing else, and the owner guard trigger holds that the owner is a member
  of the row's household.
- **The value is sealed** with Fernet under `LEGION_VAULT_KEY` (`ingest/vault.py`, the helper
  that already seals Plaid access tokens) before it touches the database. The key lives in the
  environment, never in the database; a database dump or the nightly backup holds ciphertext.
- **`vault_reveals` is the audit trail**: one row per reveal, the label and the time and the
  device token's name. Never the value. It survives the item (SET_NULL) so a deleted item's
  reveals still read.

`__str__` and every message name the row, never the value.
"""

from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Lower, Now

KIND_PASSPORT = "passport"
KIND_NRIC = "nric"
KIND_SSN = "ssn"
KIND_A_NUMBER = "a_number"
KIND_BANK_ACCOUNT = "bank_account"
KIND_OTHER = "other"
KINDS: tuple[str, ...] = (
    KIND_PASSPORT,
    KIND_NRIC,
    KIND_SSN,
    KIND_A_NUMBER,
    KIND_BANK_ACCOUNT,
    KIND_OTHER,
)

LABEL_MAX_CHARS = 60
VALUE_MAX_CHARS = 128


class VaultItem(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    # Never null: every vault item is private to the member who keeps it.
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    label = models.TextField()
    kind = models.TextField(default=KIND_OTHER)
    ciphertext = models.BinaryField()
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())
    last_revealed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "vault_items"
        constraints = [
            models.UniqueConstraint(
                "household", "owner_user", Lower("label"), name="vault_items_owner_label_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=KINDS), name="vault_items_kind_valid"
            ),
        ]

    def __str__(self) -> str:
        return f"vault item {self.id}"


class VaultReveal(models.Model):
    id = models.BigAutoField(primary_key=True)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    item = models.ForeignKey(
        VaultItem, null=True, blank=True, on_delete=models.SET_NULL, related_name="reveals"
    )
    # Copied, so the trail reads after the item is gone. The label only, never the value.
    label = models.TextField()
    device_name = models.TextField(blank=True, default="")
    revealed_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "vault_reveals"
        indexes = [
            models.Index(fields=["owner_user", "-revealed_at"], name="vault_reveals_owner_idx"),
        ]

    def __str__(self) -> str:
        return f"vault reveal {self.id}"
