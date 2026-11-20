"""The factory board's latest snapshot, one row per member (DARK behind `LEGION_BOARD`).

The board lives on the member's laptop (agents update one card each); this row is the off-laptop
copy, so the phone, the web and the assistant can read what is in flight when the laptop is off.
LATEST ONLY: each push replaces `cards`; no history is kept.

`public.board_snapshots`, born tenanted (`household`, in `household.tenancy.TENANT_TABLES`) and
private to its member (`owner_user`, never null, `OWNER_PATHS`): another member of the same
household never reads it.

**The writer.** The laptop pushes with its own token, `bw1_` plus 32 random bytes, minted by the
member from a signed-in session and shown once. Only its SHA-256 is kept here (`writer_key_hash`,
null = no writer). One writer per member: minting again replaces the hash, so the old token dies at
once; revoking clears it. It reaches `PUT /api/board` and nothing else (`board.authentication`).
The credential sits on the snapshot row because it writes exactly this row and nothing else; a
table of its own would hold one row per member too.

**Times.** `received_at` is the engine's clock and decides freshness. `generated_at` is the
laptop's own clock, kept for display only: a laptop clock comes back hours wrong after a full
power-off, so nothing is computed from it.
"""

from __future__ import annotations

import hashlib
import secrets
import uuid

from django.db import models
from django.utils import timezone

TOKEN_PREFIX = "bw1_"
TOKEN_BYTES = 32


def new_token() -> str:
    return TOKEN_PREFIX + secrets.token_urlsafe(TOKEN_BYTES)


def hash_token(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class BoardSnapshot(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.CASCADE, related_name="+")
    owner_user = models.OneToOneField("household.User", on_delete=models.CASCADE, related_name="+")
    # The cleaned cards (`board.cards.clean_board`), never the raw upload.
    cards = models.JSONField(default=list, blank=True)
    build_slot = models.TextField(default="", blank=True)
    generated_at = models.DateTimeField(null=True, blank=True)
    received_at = models.DateTimeField(null=True, blank=True)
    size_bytes = models.IntegerField(default=0)
    writer_key_hash = models.CharField(max_length=64, null=True, blank=True, unique=True)
    writer_created_at = models.DateTimeField(null=True, blank=True)
    writer_last_used_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "board_snapshots"

    def __str__(self) -> str:
        # Ids only: admin's LogEntry stores str(obj), and card text never belongs in a log.
        return f"board {self.pk}"

    @classmethod
    def mint_writer(cls, *, household, owner_user, now=None) -> tuple[BoardSnapshot, str]:
        """The member's row (made if missing) with a new writer token. The token is the return
        value, never a field; any earlier token stops working now."""
        now = now or timezone.now()
        raw = new_token()
        row, _ = cls.objects.get_or_create(owner_user=owner_user, defaults={"household": household})
        row.household = household
        row.writer_key_hash = hash_token(raw)
        row.writer_created_at = now
        row.writer_last_used_at = None
        row.save(
            update_fields=[
                "household",
                "writer_key_hash",
                "writer_created_at",
                "writer_last_used_at",
            ]
        )
        return row, raw
