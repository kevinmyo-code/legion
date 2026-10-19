"""`public.game_matches`: a Tetris score race while it lives (retro-games
ticket 05, ADR 0060).

**Transient by ruling.** A row holds an invite, a seed, a clock and both
players' live progress, and is deleted ten minutes after the match closes
(`games/matches.py:sweep`, run lazily on every games request and by
`manage.py push_dispatch`). No scores or history are kept anywhere else.

Born tenanted, in `public`, like `device_logs` and `push_*`: `household` is an
ordinary foreign key, the table is in `household.tenancy.TENANT_TABLES`, and
every read goes through the request's household (`household_of`). Both
players are members of that household; the invite view refuses anyone else.

Progress is two flat column sets rather than a child table so that each
player's write is one conditional UPDATE of their own columns: two players
posting in the same instant can never overwrite each other.
"""

from __future__ import annotations

import uuid

from django.db import models

INVITED = "invited"
PLAYING = "playing"
ENDED = "ended"
DECLINED = "declined"
CANCELLED = "cancelled"
EXPIRED = "expired"
STATUSES: tuple[str, ...] = (INVITED, PLAYING, ENDED, DECLINED, CANCELLED, EXPIRED)
CLOSED: frozenset[str] = frozenset({ENDED, DECLINED, CANCELLED, EXPIRED})

SEED_MAX = 4294967295  # a uint32: mulberry32's seed in ticket 04


class GameMatch(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey("household.Household", on_delete=models.PROTECT, related_name="+")
    inviter = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    invitee = models.ForeignKey("household.User", on_delete=models.CASCADE, related_name="+")
    # bigint, because a uint32 does not fit Postgres's signed int4.
    seed = models.BigIntegerField()
    status = models.TextField(default=INVITED)
    created_at = models.DateTimeField()
    expires_at = models.DateTimeField()
    start_at = models.DateTimeField(null=True, blank=True)
    ends_at = models.DateTimeField(null=True, blank=True)
    # When the match stopped being live, by any route; the purge counts from it.
    closed_at = models.DateTimeField(null=True, blank=True)

    inviter_score = models.IntegerField(default=0)
    inviter_lines = models.IntegerField(default=0)
    inviter_level = models.IntegerField(default=1)
    inviter_topped_out = models.BooleanField(default=False)
    inviter_finished = models.BooleanField(default=False)
    inviter_updated_at = models.DateTimeField(null=True, blank=True)

    invitee_score = models.IntegerField(default=0)
    invitee_lines = models.IntegerField(default=0)
    invitee_level = models.IntegerField(default=1)
    invitee_topped_out = models.BooleanField(default=False)
    invitee_finished = models.BooleanField(default=False)
    invitee_updated_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "game_matches"
        indexes = [
            models.Index(
                fields=["household", "invitee", "status"], name="game_matches_invitee_status"
            ),
        ]
        constraints = [
            models.CheckConstraint(
                condition=models.Q(status__in=STATUSES), name="game_matches_status_valid"
            ),
            models.CheckConstraint(
                condition=models.Q(seed__gte=0, seed__lte=SEED_MAX), name="game_matches_seed_uint32"
            ),
            models.CheckConstraint(
                condition=~models.Q(inviter=models.F("invitee")), name="game_matches_two_players"
            ),
        ]
