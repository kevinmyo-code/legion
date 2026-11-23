"""Backlog intake: an idea said to Alfred becomes a queued card on the laptop's board, after a yes.

Kevin's rulings, 2026-11-22: "yes score each idea" (the engine scores at intake, `board.rubric`)
and "read it back first" (Alfred reads the idea back, its title and the score in a short phrase,
and saves only on his explicit yes in his NEXT turn; no save on silence or a no).

**Two steps, two rows of state.**
1. `draft`: the text is cleaned, scored (one model call) and stored as a `draft` row. The caller
   reads `readback(idea)` aloud. Nothing reaches the laptop from a draft.
2. `confirm`: on his yes, the SAME row (the score he heard, never re-scored) becomes `pending`.
   Only the newest unanswered draft can be confirmed. A draft confirmed sooner than
   `MIN_CONFIRM_SECONDS` after it was made is refused: a reply was not read back and answered in
   that time. A draft not confirmed within `DRAFT_TTL` is dead (`confirm` refuses, `pending_for`
   never sees it).

**Who enforces "his next turn".** A voice door's confirm (`confirm(..., turn=...)`, from the
`add_backlog_idea` tool) must come from a LATER turn of the SAME conversation than the draft, and
that turn's own words must be a yes (`agents.legion.guards.is_yes`): code decides, not the model,
and one yes is enough. The draft stores its conversation and turn (`draft_conversation`,
`draft_turn`). The turn comes from `turn_of(request)`: the engine brain sets it (`set_turn`, in
`agents.legion.turn.run_turn`), the web cascade's own turn numbers are used (its
`read_through.set_turn(..., carry=True)`). A door that cannot tell his turns apart (Live, the web
tool door, whose turn is the model's count) has no turn here, so its confirm is refused in words:
say yes on the phone or in the web chat. The phone confirms through `POST
/api/board/ideas/<id>/confirm` (no turn here) after its own word check on a later turn
(`IdeaSaveGate`). `/mcp` is never offered the tool (`engine_mcp.server.LATER_YES_ONLY_TOOLS`). The
time floor is the engine's last line on every door.

**Safety.** The text is the member's own words, but it is still data: hidden characters are
stripped, it is capped at `board.cards.FIELD_MAX`, a key-shaped text is refused, and the card id is
the engine's (`models.new_card_id`), never derived from the text. Nothing here, or on the laptop,
starts work from an idea: it becomes a `queued` card and the orchestrator promotes it with him.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import timedelta

from django.core.exceptions import ValidationError
from django.db import transaction
from django.utils import timezone

from board import cards as board_cards
from board import rubric
from board.models import (
    IDEA_DELIVERED,
    IDEA_DRAFT,
    IDEA_DROPPED,
    IDEA_PENDING,
    BoardIdea,
)

MIN_CONFIRM_SECONDS = 4
DRAFT_TTL = timedelta(minutes=15)
DRAFTS_PER_DAY = 20
TITLE_MAX = 80
PENDING_MAX = 40

EMPTY = "Nothing was added: the idea had no words."
SECRET = (
    "Nothing was added: the idea looks like it holds a key, a token or an email address, and "
    "those never go on the board."
)
TOO_MANY = f"Nothing was added: {DRAFTS_PER_DAY} ideas a day is the limit. Try again tomorrow."
NO_DRAFT = "Nothing was saved: there is no idea waiting for a yes with that id."
EXPIRED = "Nothing was saved: that idea was read back too long ago. Say it again to add it."
TOO_SOON = (
    "Nothing was saved yet: the idea has to be read back and answered first. Read it back and "
    "ask; save it only on a yes in the next message."
)
NO_TURN_DOOR = (
    "Nothing was saved. This voice cannot tell your answer from the read-back, so say yes on the "
    "phone or in the web chat."
)
SAME_TURN = (
    "Nothing was saved yet: the idea was only just read back. Wait for his answer; save it only "
    "on his yes in his next message."
)
OTHER_CONVERSATION = (
    "Nothing was saved: that idea was read back in another conversation. Say yes where you heard "
    "it, or say the idea again."
)
NOT_A_YES = (
    "Nothing was saved: his answer was not a clear yes. Ask whether to add it, and save it only "
    "on a yes."
)
SUPERSEDED = "Nothing was saved: a newer idea was read back since. Answer that one."
SAVED = "Added to the backlog as queued. Nothing starts until you pick it."
DROPPED = "Dropped. Nothing was added to the backlog."


class Refused(ValueError):
    """Nothing was stored or changed; `str()` says what did not happen."""


@dataclass(frozen=True)
class Turn:
    """One of his turns on a voice door: its conversation, its number and his own words."""

    conversation: str
    number: int
    words: str


_TURN_ATTR = "legion_idea_turn"


def set_turn(request, conversation, number: int, words: str) -> None:
    """The engine brain's turn, for `turn_of` (its turn numbers are his own turns)."""
    setattr(request, _TURN_ATTR, Turn(str(conversation), int(number), words or ""))


def turn_of(request) -> Turn | None:
    """His turn on this request's door, or None on a door that cannot tell his turns apart."""
    found = getattr(request, _TURN_ATTR, None)
    if found is not None:
        return found
    from assistant import read_through

    ref = read_through.turn_of(request)
    # `carry` is set only by a door whose turn numbers are the person's own (the web cascade);
    # the Live tool door's number is the model's count, so it gives no turn here.
    if ref is None or not ref.carry or ref.turn is None or ref.conversation_id is None:
        return None
    return Turn(str(ref.conversation_id), ref.turn, read_through.heard_words(request))


def clean_text(raw) -> str:
    text = raw if isinstance(raw, str) else ""
    text = re.sub(r"\s+", " ", board_cards._INVISIBLE.sub("", text)).strip()
    return text[: board_cards.FIELD_MAX]


def title_of(text: str) -> str:
    """The card title: the idea's own first words, cut at a word, by code."""
    if len(text) <= TITLE_MAX:
        return text.rstrip(" .")
    cut = text[:TITLE_MAX].rsplit(" ", 1)[0]
    return cut.rstrip(" ,.;:") + "..."


def readback(idea: BoardIdea) -> str:
    """What a voice says before the yes: the title, the score in a phrase, and the question."""
    return (
        f"Backlog idea: {idea.title}. {rubric.phrase(idea.scores)}. Shall I add it to the backlog?"
    )


def _today_start(now):
    local = timezone.localtime(now)
    return local.replace(hour=0, minute=0, second=0, microsecond=0)


def draft(*, household, owner_user, text, now=None, transport=None, turn=None) -> BoardIdea:
    now = now or timezone.now()
    clean = clean_text(text)
    if not clean:
        raise Refused(EMPTY)
    if board_cards.looks_secret(clean):
        raise Refused(SECRET)
    made_today = BoardIdea.objects.filter(
        owner_user=owner_user, created_at__gte=_today_start(now)
    ).count()
    if made_today >= DRAFTS_PER_DAY:
        raise Refused(TOO_MANY)
    scores = rubric.score_or_unscored(clean, transport=transport)
    with transaction.atomic():
        # Older drafts he never answered are dropped: only the newest read-back can be said yes
        # to. Two drafts at once: `confirm` still takes only the newest (`SUPERSEDED`).
        BoardIdea.objects.filter(owner_user=owner_user, status=IDEA_DRAFT).update(
            status=IDEA_DROPPED
        )
        return BoardIdea.objects.create(
            household=household,
            owner_user=owner_user,
            title=title_of(clean),
            text=clean,
            scores=scores,
            status=IDEA_DRAFT,
            created_at=now,
            draft_conversation=turn.conversation if turn else "",
            draft_turn=turn.number if turn else None,
        )


def _own(owner_user, idea_id):
    try:
        return BoardIdea.objects.select_for_update().get(pk=idea_id, owner_user=owner_user)
    except (BoardIdea.DoesNotExist, ValidationError, ValueError, TypeError):
        return None


def _voice_refusal(idea: BoardIdea, turn: Turn | None) -> str | None:
    """Why a voice door's confirm may not save this draft now, or None: a later turn of the same
    conversation, whose own words are a yes."""
    from agents.legion.guards import is_yes

    if turn is None:
        return NO_TURN_DOOR
    if idea.draft_turn is None or idea.draft_conversation != turn.conversation:
        return OTHER_CONVERSATION
    if turn.number <= idea.draft_turn:
        return SAME_TURN
    if not is_yes(turn.words):
        return NOT_A_YES
    return None


def confirm_by_voice(*, owner_user, idea_id, turn: Turn | None, now=None) -> BoardIdea:
    """A voice door's confirm (the `add_backlog_idea` tool): `confirm` plus the turn rules."""
    return confirm(owner_user=owner_user, idea_id=idea_id, now=now, voice=True, turn=turn)


def confirm(*, owner_user, idea_id, now=None, voice=False, turn=None) -> BoardIdea:
    now = now or timezone.now()
    with transaction.atomic():
        idea = _own(owner_user, idea_id)
        if idea is None or idea.status != IDEA_DRAFT:
            raise Refused(NO_DRAFT)
        newer = (
            BoardIdea.objects.filter(owner_user=owner_user, status=IDEA_DRAFT)
            .exclude(pk=idea.pk)
            .filter(created_at__gte=idea.created_at)
        )
        if newer.exists():
            raise Refused(SUPERSEDED)
        if voice:
            refusal = _voice_refusal(idea, turn)
            if refusal is not None:
                raise Refused(refusal)
        age = now - idea.created_at
        if age < timedelta(seconds=MIN_CONFIRM_SECONDS):
            raise Refused(TOO_SOON)
        expired = age > DRAFT_TTL
        # A stale draft is dropped, and that drop is kept: the refusal is raised after the commit.
        idea.status = IDEA_DROPPED if expired else IDEA_PENDING
        idea.confirmed_at = None if expired else now
        idea.save(update_fields=["status", "confirmed_at"])
    if expired:
        raise Refused(EXPIRED)
    return idea


def discard(*, owner_user, idea_id) -> BoardIdea:
    with transaction.atomic():
        idea = _own(owner_user, idea_id)
        if idea is None or idea.status != IDEA_DRAFT:
            raise Refused("Nothing was dropped: there is no idea waiting for a yes with that id.")
        idea.status = IDEA_DROPPED
        idea.save(update_fields=["status"])
    return idea


def pending_for(owner_user) -> list[BoardIdea]:
    """Ideas he said yes to that the laptop has not picked up, oldest first."""
    return list(
        BoardIdea.objects.filter(owner_user=owner_user, status=IDEA_PENDING).order_by(
            "confirmed_at", "created_at"
        )[:PENDING_MAX]
    )


def pending_count(owner_user) -> int:
    return BoardIdea.objects.filter(owner_user=owner_user, status=IDEA_PENDING).count()


def mark_delivered(*, owner_user, idea_id, now=None) -> BoardIdea:
    now = now or timezone.now()
    with transaction.atomic():
        idea = _own(owner_user, idea_id)
        if idea is None or idea.status not in (IDEA_PENDING, IDEA_DELIVERED):
            raise Refused("Nothing was marked: no pending idea has that id.")
        if idea.status == IDEA_PENDING:
            idea.status = IDEA_DELIVERED
            idea.delivered_at = now
            idea.save(update_fields=["status", "delivered_at"])
    return idea


def as_card(idea: BoardIdea) -> dict:
    """What the laptop turns into a card: queued, kind idea, no owner. Text is data."""
    scores = idea.scores or {}
    return {
        "id": str(idea.id),
        "card_id": idea.card_id,
        "title": idea.title,
        "text": idea.text,
        "rice": scores.get("rice"),
        "blast": scores.get("blast") or "",
        "scored_by": scores.get("scored_by") or "",
        "scored": bool(scores.get("scored")),
        "confirmed_at": idea.confirmed_at,
    }
