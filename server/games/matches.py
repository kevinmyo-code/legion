"""A Tetris race's life, as functions of the row and the clock (retro-games
ticket 05, ADR 0060).

    invited --accept--> playing --both finished, or ends_at + 15 s--> ended
       |--decline--> declined
       |--cancel---> cancelled
       '--120 s----> expired

Every transition is a conditional UPDATE (`filter(status=...)`), so a double
tap, two devices or a request racing the sweep can only ever move a match
once. A closed match is deleted `KEEP` after it closed: long enough for both
sides' screens to read the outcome, and not a history.

Every word either side is shown lives here, so the phone and the PWA say the
same sentence for the same outcome.
"""

from __future__ import annotations

import datetime as dt
import secrets

from django.db.models import F

from games.models import (
    CANCELLED,
    CLOSED,
    DECLINED,
    ENDED,
    EXPIRED,
    INVITED,
    PLAYING,
    SEED_MAX,
    GameMatch,
)

INVITE_TTL = dt.timedelta(seconds=120)
COUNTDOWN = dt.timedelta(seconds=5)
RACE = dt.timedelta(seconds=180)
GRACE = dt.timedelta(seconds=15)
KEEP = dt.timedelta(minutes=10)

WON = "won"
LOST = "lost"
DRAW = "draw"

INVITER = "inviter"
INVITEE = "invitee"

PROGRESS_FIELDS = ("score", "lines", "level", "topped_out", "finished")


def display_name(user) -> str:
    return (user.first_name or "").strip() or user.email.split("@")[0]


def new_seed() -> int:
    return secrets.randbelow(SEED_MAX + 1)


def sweep(now: dt.datetime, household=None) -> int:
    """Close what the clock has closed, then delete what closed more than
    `KEEP` ago, with the invite's push dedupe rows (no trace is kept).
    Scoped to one household when given; the cron passes none. Returns the
    number of matches deleted."""
    from push.models import GAME_INVITE, PushSent

    rows = GameMatch.objects.all()
    if household is not None:
        rows = rows.filter(household=household)
    rows.filter(status=INVITED, expires_at__lte=now).update(
        status=EXPIRED, closed_at=F("expires_at")
    )
    rows.filter(status=PLAYING, ends_at__lte=now - GRACE).update(
        status=ENDED, closed_at=F("ends_at") + GRACE
    )
    rows.filter(status=PLAYING, inviter_finished=True, invitee_finished=True).update(
        status=ENDED, closed_at=now
    )
    stale = rows.filter(status__in=CLOSED, closed_at__lte=now - KEEP)
    ids = [str(pk) for pk in stale.values_list("pk", flat=True)]
    if not ids:
        return 0
    PushSent.objects.filter(kind=GAME_INVITE, key__in=[invite_key(i) for i in ids]).delete()
    deleted, _ = GameMatch.objects.filter(pk__in=ids).delete()
    return deleted


def invite_key(match_id) -> str:
    return f"game:{match_id}"


def match_url(match_id) -> str:
    return f"/games/match/{match_id}"


def role_of(match: GameMatch, user) -> str | None:
    if match.inviter_id == user.pk:
        return INVITER
    if match.invitee_id == user.pk:
        return INVITEE
    return None


def _other(role: str) -> str:
    return INVITEE if role == INVITER else INVITER


def progress(match: GameMatch, role: str, user) -> dict:
    return {
        "user_id": user.pk,
        "name": display_name(user),
        **{name: getattr(match, f"{role}_{name}") for name in PROGRESS_FIELDS},
        "updated_at": getattr(match, f"{role}_updated_at"),
    }


def result_for(match: GameMatch, role: str) -> str | None:
    if match.status != ENDED:
        return None
    mine = getattr(match, f"{role}_score")
    theirs = getattr(match, f"{_other(role)}_score")
    if mine == theirs:
        return DRAW
    return WON if mine > theirs else LOST


def sentence(match: GameMatch, role: str, opponent_name: str) -> str:
    """What this match is, in words, for the player asking."""
    mine = getattr(match, f"{role}_score")
    theirs = getattr(match, f"{_other(role)}_score")
    if match.status == INVITED:
        if role == INVITER:
            return f"Waiting for {opponent_name} to accept."
        return f"{opponent_name} invited you to a Tetris race."
    if match.status == PLAYING:
        return f"Racing {opponent_name}."
    if match.status == DECLINED:
        return (
            "You declined. Nothing was played."
            if role == INVITEE
            else (f"{opponent_name} declined. Nothing was played.")
        )
    if match.status == CANCELLED:
        return (
            "You cancelled the invite. Nothing was played."
            if role == INVITER
            else (f"{opponent_name} cancelled the invite. Nothing was played.")
        )
    if match.status == EXPIRED:
        return "The invite expired: it was not accepted within 2 minutes. Nothing was played."
    outcome = result_for(match, role)
    if outcome == DRAW:
        return f"A draw, {mine:,} each."
    if outcome == WON:
        return f"You won, {mine:,} to {theirs:,}."
    return f"{opponent_name} won, {theirs:,} to {mine:,}."


def push_sentence(outcome: str, name: str) -> str:
    """What the inviter is told about the notification, never more than happened."""
    from push import dispatch

    still = f"The invite still shows in {name}'s Games for 2 minutes."
    if outcome == dispatch.SENT:
        return f"Invited {name}. A notification was sent to their device."
    if outcome == dispatch.NO_DEVICE:
        return (
            f"Invited {name}. No notification was sent: they have no device signed up for them. "
            f"{still}"
        )
    if outcome == dispatch.TURNED_OFF:
        return (
            f"Invited {name}. No notification was sent: they have Tetris invites turned off. "
            f"{still}"
        )
    if outcome == dispatch.FAILED:
        return f"Invited {name}. The notification did not reach any of their devices. {still}"
    return (
        f"Invited {name}. No notification was sent: notifications are not set up on this server. "
        f"{still}"
    )
