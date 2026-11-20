"""A head unit's own engine sign-in (T3b): one credential per paired unit, DARK.

Pairing the phone with the car's head unit also signs the unit in: the phone, as a signed-in
member, asks the engine for a token minted for THAT unit and hands it over the sealed car link.
The unit never holds the member's own token.

`public.headunit_credentials`, born tenanted (`household`, listed in
`household.tenancy.TENANT_TABLES`) and private to the member who paired (`owner_user`, never
null, `OWNER_PATHS`), so `visible()` hands a member only their own units and the ADR 0052 owner
guard refuses an owner from outside the row's household.

- **Only a hash is stored.** The token is `hu1_` plus 32 random bytes (256 bits, base64url). It is
  returned once, by `mint` or `rotate`, and never written anywhere: `key_hash` is its SHA-256.
- **Lifetime.** 30 days from mint or refresh, and never past `MAX_AGE` (90 days) after the
  pairing: after that the phone pairs again.
- **Revocation.** `revoked_at` set is revoked (the flag); `revoked_reason` says why. Checked on
  every request by `headunit_signin.authentication`, with expiry and membership.
- What the token may reach is `headunit_signin.scope`, not this file.
"""

from __future__ import annotations

import hashlib
import secrets
import uuid
from datetime import datetime, timedelta

from django.db import models
from django.utils import timezone

TOKEN_PREFIX = "hu1_"
TOKEN_BYTES = 32
LIFETIME = timedelta(days=30)
MAX_AGE = timedelta(days=90)
LABEL_MAX = 64

REASON_FORGOTTEN = "forgotten"
REASON_LOST_OR_SOLD = "lost_or_sold"
REASONS: tuple[str, ...] = (REASON_FORGOTTEN, REASON_LOST_OR_SOLD)


def new_token() -> str:
    return TOKEN_PREFIX + secrets.token_urlsafe(TOKEN_BYTES)


def hash_token(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class HeadUnitCredential(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+"
    )
    owner_user = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    label = models.CharField(max_length=LABEL_MAX)
    key_hash = models.CharField(max_length=64, unique=True)
    created_at = models.DateTimeField(default=timezone.now)
    last_used_at = models.DateTimeField(null=True, blank=True)
    expires_at = models.DateTimeField()
    rotated_at = models.DateTimeField(null=True, blank=True)
    revoked_at = models.DateTimeField(null=True, blank=True)
    revoked_reason = models.TextField(default="", blank=True)

    class Meta:
        db_table = "headunit_credentials"
        indexes = [
            models.Index(
                fields=["household", "owner_user", "created_at"], name="headunit_cred_owner_idx"
            )
        ]
        constraints = [
            # Revoked and its reason come together, and the reason is one of the named ones.
            models.CheckConstraint(
                condition=(
                    models.Q(revoked_at__isnull=True, revoked_reason="")
                    | models.Q(revoked_at__isnull=False, revoked_reason__in=REASONS)
                ),
                name="headunit_cred_revoked_reason_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(expires_at__gt=models.F("created_at")),
                name="headunit_cred_expires_after_created",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.label} ({self.pk})"

    @classmethod
    def mint(cls, *, household, owner_user, label: str, now=None) -> tuple[HeadUnitCredential, str]:
        """A new credential and its raw token. The token is the return value, never a field."""
        now = now or timezone.now()
        raw = new_token()
        row = cls.objects.create(
            household=household,
            owner_user=owner_user,
            label=label,
            key_hash=hash_token(raw),
            created_at=now,
            expires_at=now + LIFETIME,
        )
        return row, raw

    def next_expiry(self, now) -> datetime:
        return min(now + LIFETIME, self.created_at + MAX_AGE)

    def rotate(self, now=None) -> str:
        """A new token for this credential; the old one stops working at once (its hash is gone).
        The caller holds the row locked (`select_for_update`)."""
        now = now or timezone.now()
        raw = new_token()
        self.key_hash = hash_token(raw)
        self.expires_at = self.next_expiry(now)
        self.rotated_at = now
        self.last_used_at = now
        self.save(update_fields=["key_hash", "expires_at", "rotated_at", "last_used_at"])
        return raw

    def is_live(self, now=None) -> bool:
        now = now or timezone.now()
        return self.revoked_at is None and self.expires_at > now
