"""Board questions answered from the phone, and votes on backlog ideas (control room C2), DARK
with the rest of the board (`board.flags`).

**The question he answered is the question that is applied.** Each card carries `ask_sha256`, the
hash of its question as stored (`ask_hash`). The phone sends it back with his tap; the engine takes
the answer only while the stored card still has that hash (else 409: the question changed), and the
laptop applies it only while ITS card still has it (`.legion/board/controlroom_sync.py` computes the
same hash from the same canonical form; a fixed test vector pins both). A card that left the board,
or stopped asking, is 410.

**An answer is never authority for a deploy, a publish, a flag or a spend.** A question whose
options name one of those (`action_word`), or that the laptop marked `approval` (answered in chat
only), is not answerable here; a typed answer naming one is refused too. Those go through an
approved action record (`board.actions`) or through chat.

**Append-only.** Each answer is a new `BoardAnswer`; one question takes one answer. A second tap on
the same question, before or after the laptop picked the first up, is 409.

**Votes only sort.** P1 to P3, up, down or clear on a backlog idea; the newest per card is his vote.
The backlog sorts by his priority first, then RICE (Kevin, 2026-11-22). A vote never promotes.
"""

from __future__ import annotations

import hashlib
import json
import re

from django.db import transaction
from django.utils import timezone

from board import cards as board_cards
from board.models import (
    ANSWER_OUTCOMES,
    ANSWER_PENDING,
    ANSWER_REFUSED,
    VOTES,
    BoardAnswer,
    BoardSnapshot,
    BoardVote,
)

TEXT_MAX = 500
INBOX_MAX = 50
HEX64 = re.compile(r"^[0-9a-f]{64}$")

# Words that make an answer look like permission to change something outside the board. Whole
# words, any case. Broad on purpose: a refused harmless answer costs one chat message.
_ACTION_WORDS = re.compile(
    r"\b(deploy\w*|ship|ships|shipped|shipping|publish\w*|releas\w*|merg\w*|ota|install\w*|"
    r"roll\s*out|rollout|go\s+live|spend\w*|spent|pay|pays|paid|payment\w*|buy\w*|bought|"
    r"purchas\w*|charge\w*|flag\w*|migrat\w*|force[-\s]?push\w*)\b",
    re.I,
)

GONE = "Nothing was sent. That question is no longer on the board."
NOT_ASKING = "Nothing was sent. That card is not asking you anything now."
CHANGED = (
    "Nothing was sent. The question changed after you saw it; read it again and answer the new one."
)
ANSWERED = "Nothing was sent. That question already has your answer."
IN_CHAT = (
    "Nothing was sent. A board answer is never permission to deploy, publish, flip a flag or "
    "spend; answer that one in chat."
)
NO_CHOICE = "Nothing was sent. Pick one of the card's choices or type an answer."
NOT_A_CHOICE = "Nothing was sent. That is not one of the card's choices."
NO_TYPING = "Nothing was sent. This question takes one of its choices only."
NO_BOARD = "Nothing was sent. The laptop has not sent its board yet."
NOT_AN_IDEA = "Nothing was saved. That is not a backlog idea on the board."
BAD_VOTE = "Nothing was saved. A vote is p1, p2, p3, up, down or clear."

# His priority sorts above RICE. Unvoted ideas sit between "up" and "down".
VOTE_RANK = {"p1": 0, "p2": 1, "p3": 2, "up": 3, None: 4, "clear": 4, "down": 5}


class Refused(ValueError):
    """Refused in words; `status` is the HTTP status the view answers with."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def ask_hash(card: dict) -> str:
    """SHA-256 of the question's canonical form: the cleaned card's id, title, ask, options,
    ask_kind and free_text, as compact sorted JSON in UTF-8. The laptop computes the same."""
    canonical = {
        "id": card.get("id", ""),
        "title": card.get("title", ""),
        "ask": card.get("ask", ""),
        "options": list(card.get("options") or []),
        "ask_kind": card.get("ask_kind", ""),
        "free_text": card.get("free_text") is not False,
    }
    raw = json.dumps(canonical, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def action_word(text: str) -> bool:
    return bool(text) and _ACTION_WORDS.search(text) is not None


def answerable(card: dict) -> bool:
    """A choice question the phone may answer: asking, not `approval`, and no option or question
    text names a deploy, publish, flag or spend."""
    if not card.get("ask") or card.get("ask_kind") == "approval":
        return False
    words = [card.get("ask", ""), card.get("title", ""), *card.get("options", [])]
    return not any(action_word(w) for w in words)


def _pending_answer_cards(owner_user) -> set[str]:
    return set(
        BoardAnswer.objects.filter(owner_user=owner_user, outcome=ANSWER_PENDING).values_list(
            "card_id", flat=True
        )
    )


def latest_votes(owner_user) -> dict[str, str]:
    """card id -> his newest vote ("clear" drops it)."""
    out: dict[str, str] = {}
    for card_id, vote in (
        BoardVote.objects.filter(owner_user=owner_user)
        .order_by("created_at")
        .values_list("card_id", "vote")
    ):
        out[card_id] = vote
    return {k: v for k, v in out.items() if v != "clear"}


def decorate(cards: list[dict], owner_user) -> list[dict]:
    """Each card with `ask_sha256`, `answerable` and `answer_pending` (his tap is on its way to
    the laptop), computed now."""
    pending = _pending_answer_cards(owner_user)
    out = []
    for card in cards:
        asking = bool(card.get("ask"))
        out.append(
            {
                **card,
                "ask_sha256": ask_hash(card) if asking else "",
                "answerable": answerable(card) and not card.get("answer"),
                "answer_pending": card["id"] in pending,
            }
        )
    return out


def sorted_backlog(backlog: list[dict], votes: dict[str, str]) -> list[dict]:
    """His priority first, then RICE (best first), unscored last; each with `kevin_vote`."""

    def key(item):
        rice = item.get("rice") or {}
        score = rice.get("score")
        return (VOTE_RANK.get(votes.get(item["id"]), 4), score is None, -(score or 0))

    return [{**i, "kevin_vote": votes.get(i["id"], "")} for i in sorted(backlog, key=key)]


def record_answer(*, household, owner_user, card_id, ask_sha256, choice, text) -> BoardAnswer:
    """His tap, checked against the stored card under the snapshot's row lock."""
    choice = board_cards.clean_plain(choice, board_cards.OPTION_MAX)
    text = board_cards.clean_plain(text, TEXT_MAX)
    if not isinstance(ask_sha256, str) or not HEX64.match(ask_sha256):
        raise Refused(CHANGED, 409)
    with transaction.atomic():
        row = BoardSnapshot.objects.select_for_update().filter(owner_user=owner_user).first()
        if row is None or row.received_at is None:
            raise Refused(NO_BOARD, 410)
        card = next((c for c in row.cards if c.get("id") == card_id), None)
        if card is None:
            raise Refused(GONE, 410)
        if not card.get("ask"):
            raise Refused(NOT_ASKING, 410)
        if ask_hash(card) != ask_sha256:
            raise Refused(CHANGED, 409)
        # One question, one answer; an answer the laptop refused (its card had moved on) no
        # longer holds the question, so he may answer again.
        if (
            card.get("answer")
            or BoardAnswer.objects.filter(
                owner_user=owner_user, card_id=card_id, ask_sha256=ask_sha256
            )
            .exclude(outcome=ANSWER_REFUSED)
            .exists()
        ):
            raise Refused(ANSWERED, 409)
        if not answerable(card) or action_word(choice) or action_word(text):
            raise Refused(IN_CHAT)
        if not choice and not text:
            raise Refused(NO_CHOICE)
        if choice and choice not in card.get("options", []):
            raise Refused(NOT_A_CHOICE)
        if text and card.get("free_text") is False:
            raise Refused(NO_TYPING)
        return BoardAnswer.objects.create(
            household=household,
            owner_user=owner_user,
            card_id=card_id,
            ask_sha256=ask_sha256,
            choice=choice,
            text=text,
        )


def record_vote(*, household, owner_user, card_id, vote) -> BoardVote:
    if vote not in VOTES:
        raise Refused(BAD_VOTE)
    row = BoardSnapshot.objects.filter(owner_user=owner_user).first()
    ideas = {i.get("id") for i in (row.backlog or [])} if row is not None else set()
    if card_id not in ideas:
        raise Refused(NOT_AN_IDEA, 410)
    return BoardVote.objects.create(
        household=household, owner_user=owner_user, card_id=card_id, vote=vote
    )


def inbox(owner_user) -> dict:
    """What the laptop's writer pulls: answers not yet delivered, oldest first, and the newest
    undelivered vote per card."""
    answers = BoardAnswer.objects.filter(owner_user=owner_user, delivered_at__isnull=True).order_by(
        "created_at"
    )[:INBOX_MAX]
    votes: dict[str, BoardVote] = {}
    for v in BoardVote.objects.filter(owner_user=owner_user, delivered_at__isnull=True).order_by(
        "created_at"
    ):
        votes[v.card_id] = v
    return {
        "answers": [
            {
                "id": a.id,
                "card_id": a.card_id,
                "ask_sha256": a.ask_sha256,
                "choice": a.choice,
                "text": a.text,
                "at": a.created_at,
            }
            for a in answers
        ],
        "votes": [
            {"id": v.id, "card_id": v.card_id, "vote": v.vote, "at": v.created_at}
            for v in list(votes.values())[:INBOX_MAX]
        ],
    }


def mark_delivered(owner_user, answers: list[dict], votes: list) -> dict[str, int]:
    """The laptop's report: each answer `applied` or `refused`, and the votes it took. Only the
    delivery stamp changes; a row already stamped keeps its first stamp."""
    now = timezone.now()
    done = {"answers": 0, "votes": 0}
    for item in answers[:INBOX_MAX]:
        outcome = item.get("outcome") if isinstance(item, dict) else None
        if outcome not in ANSWER_OUTCOMES:
            continue
        done["answers"] += BoardAnswer.objects.filter(
            owner_user=owner_user, pk=item.get("id"), delivered_at__isnull=True
        ).update(delivered_at=now, outcome=outcome)
    voted = BoardVote.objects.filter(owner_user=owner_user, delivered_at__isnull=True)
    for vote_id in votes[:INBOX_MAX]:
        hit = voted.filter(pk=vote_id).first()
        if hit is None:
            continue
        # Older undelivered votes on the same card are superseded by the one the laptop took.
        done["votes"] += voted.filter(card_id=hit.card_id, created_at__lte=hit.created_at).update(
            delivered_at=now
        )
    return done
