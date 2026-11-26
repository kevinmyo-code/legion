"""Saving a conversation turn as it happens (memory chain ticket 450, 2026-11-26).

The phone posts each finished turn (`POST /api/member-memories/turns`); this module is the one
place that decides what of it may be kept. It is a gate in the section 4 sense: the phone's own
redaction (`LiveAudit`) is honoured, never relied on.

## What is kept, and what never is (CLAUDE.md section 7)

- **The person's words**, always, unless they look like an identity or bank number
  (`member_memory.sensitive`): those belong in the ID vault and are never stored here.
- **The assistant's spoken reply**, tool turns included. The consolidation job drops the reply of
  every turn that ran a tool because its model reads the transcript as a source of facts; a raw
  record of what was said has no such reason to.
- **A tool's name and a clipped one-line result**, never a payload: `RESULT_MAX_CHARS`.
- **Nothing read through.** A turn is withheld when the phone flagged it, when a tool in
  `consolidate.READ_THROUGH_TOOLS` ran (the same list the job uses, mail and the web), or when any
  field carries the redaction mark. A withheld turn keeps the person's words, drops the reply and
  every tool result. The rest of the chat is withheld too: an asynchronous result can be spoken
  turns after the call, which is also the job's rule.

Idempotent on `(household, chat_id, seq)`: the phone retries a send whose answer it lost.

Nothing here logs a turn's text.
"""

from __future__ import annotations

from datetime import timedelta

from django.db import IntegrityError, transaction
from django.utils import timezone

from household.tenancy import household_of
from member_memory.consolidate import READ_THROUGH_TOOLS, REDACTION_MARK
from member_memory.models import SURFACES, ConversationTurn
from member_memory.sensitive import looks_sensitive

TEXT_MAX_CHARS = 4_000
RESULT_MAX_CHARS = 200
MAX_TOOL_CALLS = 20
NAME_MAX_CHARS = 80
# Matches `devicelogs.models.RETENTION_DAYS`: the raw conversation record is kept as long as the
# batch it duplicates. Distilled memory (`member_memories`) is what outlives it.
RETENTION_DAYS = 60
LIST_LIMIT_DEFAULT = 50
LIST_LIMIT_MAX = 200


def _marked(value) -> bool:
    return isinstance(value, str) and (
        REDACTION_MARK in value or "[redacted: third-party content]" in value
    )


def _words(value) -> str | None:
    """A kept line of speech: whitespace folded, None when empty or an identity number."""
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    if not text or looks_sensitive(text):
        return None
    return text


def _clip(value) -> str | None:
    text = _words(value)
    return None if text is None else text[:RESULT_MAX_CHARS]


def _calls(raw: list[dict], *, withheld: bool) -> list[dict]:
    calls = []
    for call in raw[:MAX_TOOL_CALLS]:
        name = str(call.get("name") or "")[:NAME_MAX_CHARS]
        if not name:
            continue
        result = None if withheld or name in READ_THROUGH_TOOLS else _clip(call.get("result"))
        calls.append({"name": name, "ok": bool(call.get("ok", True)), "result": result})
    return calls


def _read_through(data: dict) -> bool:
    if data.get("read_through"):
        return True
    calls = data.get("tool_calls") or []
    if any(call.get("name") in READ_THROUGH_TOOLS for call in calls):
        return True
    fields = [data.get("user_text"), data.get("assistant_text")]
    fields += [call.get("result") for call in calls]
    return any(_marked(value) for value in fields)


def save(request, data: dict) -> tuple[ConversationTurn, bool]:
    """Stores one validated turn for the calling member. Returns `(row, created)`; a repeat of an
    already stored `(chat_id, seq)` returns the stored row untouched."""
    household = household_of(request)
    member = request.user
    chat_id, seq = data["chat_id"], data["seq"]
    stored = ConversationTurn.objects.filter(
        household=household, chat_id=chat_id, seq=seq, member=member
    ).first()
    if stored is not None:
        return stored, False

    tainted = ConversationTurn.objects.filter(
        household=household, chat_id=chat_id, member=member, withheld=True
    ).exists()
    withheld = tainted or _read_through(data)
    user_text = None if _marked(data.get("user_text")) else _words(data.get("user_text"))
    try:
        with transaction.atomic():
            row = ConversationTurn.objects.create(
                household=household,
                member=member,
                owner_user=member,
                chat_id=chat_id,
                seq=seq,
                surface=data.get("surface") if data.get("surface") in SURFACES else "voice",
                user_text=user_text,
                assistant_text=None if withheld else _words(data.get("assistant_text")),
                tool_calls=_calls(data.get("tool_calls") or [], withheld=withheld),
                withheld=withheld,
                spoken_at=data.get("spoken_at"),
            )
    except IntegrityError:
        # A concurrent retry of the same turn won the insert.
        return (
            ConversationTurn.objects.get(
                household=household, chat_id=chat_id, seq=seq, member=member
            ),
            False,
        )
    prune(household, member)
    return row, True


def prune(household, member) -> int:
    """Deletes this member's turns past the retention window. Runs on every save, so the table
    never needs a job of its own."""
    cutoff = timezone.now() - timedelta(days=RETENTION_DAYS)
    deleted, _ = ConversationTurn.objects.filter(
        household=household, member=member, received_at__lt=cutoff
    ).delete()
    return deleted
