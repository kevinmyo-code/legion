"""Each conversation turn, saved the moment it ends (issue #450).

`POST /api/member-memories/turns` takes one finished turn from a client (the phone today): the
person's words, the reply that was spoken, and the names of the tools the turn called. It is the
raw recent record a new chat can read straight away, where the conversation record reaches the
engine every three minutes and memory only after the hourly job.

## What is kept, and what never is (CLAUDE.md section 7)

The client decides first and this module decides again, so a client that forgot a rule still
stores nothing it should not. A turn is **not kept** when:

- the client marked it read-through (`read_through`), it named a read-through tool
  (`consolidate.READ_THROUGH_TOOLS`), or either text carries the redaction marker: dropped whole,
  his words included, exactly as consolidation drops it;
- either text has a crisis phrase (`agents.voice.crisis.detect`): nothing of that conversation is
  kept, so every turn already saved under the same session is deleted too, the ones said before
  the phrase included (the head unit's `CrisisMemoryGate.forgetsSession`, the same rule);
- either text looks like a vault identifier (`sensitive.looks_sensitive`).

Tool arguments and results never arrive here: the request has no field for them.

A refusal is an ordinary answer (`kept: false` with the reason in words), never an error, so a
client does not retry it. Nothing here logs turn text.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from django.utils import timezone

from agents.voice.crisis import detect as is_crisis
from devicelogs import voice_audit
from household.tenancy import household_of, visible
from member_memory.consolidate import READ_THROUGH_TOOLS, REDACTION_MARK
from member_memory.models import TURN_TEXT_MAX_CHARS, ConversationTurn
from member_memory.sensitive import looks_sensitive

NOT_KEPT_READ_THROUGH = "read_through"
NOT_KEPT_CRISIS = "crisis"
NOT_KEPT_SENSITIVE = "sensitive"
NOT_KEPT_EMPTY = "empty"

REASONS: dict[str, str] = {
    NOT_KEPT_READ_THROUGH: "Not kept: the turn read third-party text, and that is never stored.",
    NOT_KEPT_CRISIS: "Not kept: nothing of a conversation about distress is stored.",
    NOT_KEPT_SENSITIVE: "Not kept: the turn looks like it holds an identity or bank number.",
    NOT_KEPT_EMPTY: "Not kept: the turn had no words.",
}

RECENT_LIMIT_DEFAULT = 50
RECENT_LIMIT_MAX = 200


@dataclass(frozen=True)
class Saved:
    kept: bool
    reason: str | None = None
    row: ConversationTurn | None = None
    created: bool = False

    @property
    def sentence(self) -> str | None:
        return REASONS.get(self.reason) if self.reason else None


def _clean(text: str | None) -> str:
    text = " ".join((text or "").split())
    return text[-TURN_TEXT_MAX_CHARS:] if len(text) > TURN_TEXT_MAX_CHARS else text


def _redacted(text: str) -> bool:
    return voice_audit.REDACTED in text or REDACTION_MARK in text


def own_turns(request):
    """The caller's own turns. Every read goes through `visible()`, so another member's are
    never in it."""
    return visible(ConversationTurn, request).filter(member=request.user)


def forget_session(request, session: str) -> int:
    """Deletes every turn the caller saved under `session`. Returns how many went."""
    deleted, _ = own_turns(request).filter(session=session).delete()
    return deleted


def save_turn(
    request,
    *,
    session: str,
    turn: int,
    source: str,
    user_text: str | None,
    assistant_text: str | None,
    tools: list[str],
    read_through: bool,
    spoken_at: datetime | None = None,
) -> Saved:
    """Saves one finished turn for the caller, or says in `reason` why it was not kept."""
    user_text = _clean(user_text)
    assistant_text = _clean(assistant_text)
    tool_names = sorted({str(name) for name in tools if str(name).strip()})

    if is_crisis(user_text) or is_crisis(assistant_text):
        forget_session(request, session)
        return Saved(kept=False, reason=NOT_KEPT_CRISIS)
    if (
        read_through
        or any(name in READ_THROUGH_TOOLS for name in tool_names)
        or _redacted(user_text)
        or _redacted(assistant_text)
    ):
        return Saved(kept=False, reason=NOT_KEPT_READ_THROUGH)
    if looks_sensitive(user_text) or looks_sensitive(assistant_text):
        return Saved(kept=False, reason=NOT_KEPT_SENSITIVE)
    if not user_text and not assistant_text:
        return Saved(kept=False, reason=NOT_KEPT_EMPTY)

    user = request.user
    row, created = ConversationTurn.objects.update_or_create(
        member=user,
        session=session,
        turn=turn,
        defaults={
            "household": household_of(request),
            "owner_user": user,
            "source": source,
            "user_text": user_text,
            "assistant_text": assistant_text,
            "tools": tool_names,
            "spoken_at": spoken_at or timezone.now(),
        },
    )
    return Saved(kept=True, row=row, created=created)


def recent(request, *, session: str | None = None, limit: int = RECENT_LIMIT_DEFAULT):
    """The caller's newest turns, newest first."""
    rows = own_turns(request)
    if session:
        rows = rows.filter(session=session)
    return list(rows.order_by("-spoken_at", "-turn")[: max(1, min(limit, RECENT_LIMIT_MAX))])


def as_dict(row: ConversationTurn) -> dict:
    return {
        "id": str(row.id),
        "session": row.session,
        "turn": row.turn,
        "source": row.source,
        "user_text": row.user_text,
        "assistant_text": row.assistant_text,
        "tools": list(row.tools or []),
        "spoken_at": row.spoken_at.isoformat(),
    }
