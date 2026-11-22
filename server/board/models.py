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
    # Both keys delete with their parent AT THE DATABASE (migration 0001 adds them, ON DELETE
    # CASCADE): Django's CASCADE is emulated in Python, and code that predates this table (a
    # rollback) would not know to delete its row, so a NO ACTION key would block deleting that
    # member or household. `db_constraint=False`: Django makes no constraint of its own.
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.OneToOneField(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    # The cleaned cards (`board.cards.clean_board`), never the raw upload.
    cards = models.JSONField(default=list, blank=True)
    build_slot = models.TextField(default="", blank=True)
    generated_at = models.DateTimeField(null=True, blank=True)
    received_at = models.DateTimeField(null=True, blank=True)
    size_bytes = models.IntegerField(default=0)
    # The whole backlog, compact (`board.cards.clean_backlog`; control room C2): the cards list
    # shows only the best few ideas, the phone shows all of them. `backlog_total` counts what the
    # laptop had, so a cut list says how many more there are.
    # Nullable: the engine revision still serving during a rollout inserts rows without them.
    backlog = models.JSONField(null=True, blank=True, default=list)
    backlog_total = models.IntegerField(null=True, blank=True, default=0)
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


IDEA_DRAFT = "draft"
IDEA_PENDING = "pending"
IDEA_DELIVERED = "delivered"
IDEA_DROPPED = "dropped"
IDEA_STATUSES = (IDEA_DRAFT, IDEA_PENDING, IDEA_DELIVERED, IDEA_DROPPED)


def new_card_id() -> str:
    """The laptop card id an idea becomes: made by the engine, never from the idea's text."""
    return "idea-" + secrets.token_hex(5)


class BoardIdea(models.Model):
    """A backlog idea the member said to Alfred, waiting for the laptop (`board.ideas`).

    `public.board_ideas`, born tenanted and private to its member like `board_snapshots`.

    **Life.** `draft` when Alfred has scored it and read it back; `pending` only after the member's
    yes (`board.ideas.confirm`); `delivered` once the laptop's writer has turned it into a queued
    card; `dropped` on a no or an expired draft. Only `pending` rows are ever handed to the laptop,
    so a draft nobody said yes to never reaches the board. Nothing here starts any work: the laptop
    makes a `queued` card and the orchestrator promotes it with the member in chat.

    `text` is the member's own words, cleaned (`board.ideas.clean_text`); `scores` holds the
    rubric's enums and what code computed from them (`board.rubric`), never free model text.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.ForeignKey(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    card_id = models.CharField(max_length=60, unique=True, default=new_card_id)
    title = models.TextField()
    text = models.TextField()
    scores = models.JSONField(default=dict, blank=True)
    status = models.CharField(max_length=12, default=IDEA_DRAFT)
    created_at = models.DateTimeField(default=timezone.now)
    confirmed_at = models.DateTimeField(null=True, blank=True)
    delivered_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "board_ideas"
        indexes = [models.Index(fields=["owner_user", "status"], name="board_ideas_owner_status")]

    def __str__(self) -> str:
        # Ids only, never the idea's words.
        return f"idea {self.card_id}"


ANSWER_PENDING = "pending"
ANSWER_APPLIED = "applied"
ANSWER_REFUSED = "refused"
ANSWER_OUTCOMES = (ANSWER_APPLIED, ANSWER_REFUSED)


class AppendOnly(models.Model):
    """A row that is written once. Only the laptop's delivery stamp may change later
    (`DELIVERY_FIELDS`); any other update, and every delete through the ORM, raises. The tap log
    and his answers are a record of what he did, so nothing rewrites them."""

    DELIVERY_FIELDS: tuple[str, ...] = ()

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        if not self._state.adding:
            fields = set(kwargs.get("update_fields") or ())
            if not fields or not fields <= set(self.DELIVERY_FIELDS):
                raise ValueError(f"{type(self).__name__} is append-only")
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise ValueError(f"{type(self).__name__} is append-only")


class BoardAnswer(AppendOnly):
    """His answer to one board question, tapped on the phone (control room C2, `board.asks`).

    `public.board_answers`, tenanted and private to its member like `board_snapshots`. APPEND-ONLY:
    one row per answer, never overwritten (the 11-20 design kept one answer per card and a second
    tap replaced the first). `ask_sha256` is the hash of the question as the phone showed it
    (`board.asks.ask_hash`); the engine took the answer only while the stored card still had that
    hash, and the laptop applies it only while ITS card still has it. `outcome` is the laptop's:
    `applied` (recorded on the card) or `refused` (the card changed, or was answered in chat).

    An answer is never authority for a deploy, a publish, a flag or a spend: options with those
    words are refused (`board.asks.action_word`), and only an approved `BoardAction` is."""

    DELIVERY_FIELDS = ("delivered_at", "outcome")

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.ForeignKey(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    card_id = models.CharField(max_length=60)
    ask_sha256 = models.CharField(max_length=64)
    choice = models.TextField(blank=True, default="")
    text = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(default=timezone.now)
    delivered_at = models.DateTimeField(null=True, blank=True)
    outcome = models.CharField(max_length=12, default=ANSWER_PENDING)

    class Meta:
        db_table = "board_answers"
        indexes = [
            models.Index(fields=["owner_user", "card_id"], name="board_answers_owner_card"),
            models.Index(fields=["owner_user", "delivered_at"], name="board_answers_owner_due"),
        ]

    def __str__(self) -> str:
        return f"answer {self.pk}"


VOTES: tuple[str, ...] = ("p1", "p2", "p3", "up", "down", "clear")


class BoardVote(AppendOnly):
    """His vote on one backlog idea (`board.asks.vote`): P1 to P3, up, down, or clear. Append-only;
    the newest row per card is his vote (`kevin_vote`). A vote only sorts the backlog (his
    priority above RICE); it never promotes an idea or starts work."""

    DELIVERY_FIELDS = ("delivered_at",)

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.ForeignKey(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    card_id = models.CharField(max_length=60)
    vote = models.CharField(max_length=8)
    created_at = models.DateTimeField(default=timezone.now)
    delivered_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "board_votes"
        indexes = [models.Index(fields=["owner_user", "card_id"], name="board_votes_owner_card")]

    def __str__(self) -> str:
        return f"vote {self.pk}"


ACTION_PENDING = "pending"
ACTION_APPROVED = "approved"
ACTION_DECLINED = "declined"
ACTION_PULLED = "pulled"
ACTION_STATUSES = (ACTION_PENDING, ACTION_APPROVED, ACTION_DECLINED, ACTION_PULLED)


class BoardAction(models.Model):
    """One thing only Kevin may allow, asked by the laptop and allowed by his tap (control room
    C3, `board.actions`, DARK behind `LEGION_BOARD_ACTIONS`).

    `public.board_actions`, tenanted and private to its member. Made ONLY by the laptop's writer
    token, from TYPED fields (`kind` and the scope columns): no free text decides anything, and
    `payee`/`purpose` are shown as quoted data. `record_sha256` is the engine's hash of exactly
    those fields (`board.actions.record_hash`); the phone sends it back with the tap, so a record
    that changed after he saw it is refused. `expires_at` is 30 minutes on the ENGINE's clock.

    Life: `pending`, then `approved` (his tap, once) or `declined`; `pulled` once the laptop has
    taken an approved record. Nothing runs from an approval by itself: the orchestrator re-checks
    the hash and the head, then runs the pinned ship script."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.ForeignKey(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    kind = models.CharField(max_length=20)
    sha = models.CharField(max_length=40, blank=True, default="")
    tier = models.CharField(max_length=8, blank=True, default="")
    review_ok_sha = models.CharField(max_length=40, blank=True, default="")
    ci_green = models.BooleanField(default=False)
    flags = models.JSONField(default=list, blank=True)
    amount_cents = models.IntegerField(null=True, blank=True)
    payee = models.CharField(max_length=60, blank=True, default="")
    purpose = models.CharField(max_length=120, blank=True, default="")
    record_sha256 = models.CharField(max_length=64)
    status = models.CharField(max_length=12, default=ACTION_PENDING)
    created_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    decided_at = models.DateTimeField(null=True, blank=True)
    pulled_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "board_actions"
        indexes = [models.Index(fields=["owner_user", "status"], name="board_actions_owner_status")]

    def __str__(self) -> str:
        return f"action {self.pk}"


class BoardActionTap(AppendOnly):
    """The append-only tap log: every Allow or Decline he sent, with what it came to (`applied`,
    `stale`, `expired`, `replay`, `changed`). Never updated, never deleted."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    owner_user = models.ForeignKey(
        "household.User", on_delete=models.CASCADE, related_name="+", db_constraint=False
    )
    action_id = models.UUIDField()
    decision = models.CharField(max_length=10)
    record_sha256 = models.CharField(max_length=64, blank=True, default="")
    result = models.CharField(max_length=10)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        db_table = "board_action_taps"
        indexes = [models.Index(fields=["owner_user", "action_id"], name="board_taps_owner_action")]

    def __str__(self) -> str:
        return f"tap {self.pk}"
