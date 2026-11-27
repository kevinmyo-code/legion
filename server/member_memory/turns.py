"""Saving a conversation turn as it happens (memory chain ticket 450, 2026-11-26).

The phone posts each finished turn (`POST /api/member-memories/turns`); this module is the one
place that decides what of it may be kept. It is a gate in the section 4 sense: the phone's own
redaction (`LiveAudit`) is honoured, and the engine runs its own check on what it is told.

## What is kept, and what never is (CLAUDE.md section 7)

- **The person's words**, always, unless they look like an identity or bank number
  (`member_memory.sensitive`): those belong in the ID vault and are never stored here.
- **The assistant's spoken reply**, tool turns included. The consolidation job drops the reply of
  every turn that ran a tool because its model reads the transcript as a source of facts; a raw
  record of what was said has no such reason to.
- **A tool's name and a clipped one-line result**, never a payload: `RESULT_MAX_CHARS`.
- **Nothing read through.** A turn is withheld when the phone flagged it, when a tool in
  `consolidate.READ_THROUGH_TOOLS` ran (the same list the job uses, mail and the web), or when any
  field carries the redaction mark. The phone sends each call's wire name AND the tool it resolved
  to (`action`: a folded `look_up` or `day` stands for `ask_mail` or `get_sitrep`), and either name
  in the list withholds. The engine cannot see a call's arguments, so a client that sends neither
  the flag, a mark nor an honest `action` for a folded call defeats the check: it is the member's
  own token writing the member's own row, so the check guards against the phone's mistakes, not
  against the member. A withheld turn keeps the person's words, drops the reply and
  every tool result. The rest of the chat is withheld too: an asynchronous result can be spoken
  turns after the call, which is also the job's rule.

Too much is trimmed, not refused: a reply past `TEXT_MAX_CHARS` is cut and a turn with more than
`MAX_TOOL_CALLS` calls keeps the first ones, both marked `trimmed`, so a long answer never costs
the person's words. Only a payload past `TEXT_ACCEPT_CHARS` is refused.

Idempotent on `(household, chat_id, seq)`: the phone retries a send whose answer it lost. A second
MEMBER sending the same `(chat_id, seq)` is a conflict (`TurnConflict`, a 409), never another
member's row.

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
# What the endpoint accepts before it refuses: far past any spoken or typed reply, so only a bug
# or abuse reaches it. Between TEXT_MAX_CHARS and this the text is trimmed.
TEXT_ACCEPT_CHARS = 100_000
RESULT_MAX_CHARS = 200
MAX_TOOL_CALLS = 20
MAX_TOOL_CALLS_ACCEPTED = 200
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
    """A kept line of speech: whitespace folded, None when empty or an identity number. The
    identity check sees the whole line, before any trimming."""
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    if not text or looks_sensitive(text):
        return None
    return text


def _trimmed_words(value) -> tuple[str | None, bool]:
    text = _words(value)
    if text is None or len(text) <= TEXT_MAX_CHARS:
        return text, False
    return text[:TEXT_MAX_CHARS], True


def _clip(value) -> str | None:
    text = _words(value)
    return None if text is None else text[:RESULT_MAX_CHARS]


def _reads_through(call: dict) -> bool:
    """A call is read-through by its wire name or by the tool a folded wrapper resolved to."""
    return call.get("name") in READ_THROUGH_TOOLS or call.get("action") in READ_THROUGH_TOOLS


def _calls(raw: list[dict], *, withheld: bool) -> list[dict]:
    calls = []
    for call in raw[:MAX_TOOL_CALLS]:
        name = str(call.get("name") or "")[:NAME_MAX_CHARS]
        if not name:
            continue
        result = None if withheld or _reads_through(call) else _clip(call.get("result"))
        entry = {"name": name, "ok": bool(call.get("ok", True)), "result": result}
        action = str(call.get("action") or "")[:NAME_MAX_CHARS]
        if action and action != name:
            entry["action"] = action
        calls.append(entry)
    return calls


def _read_through(data: dict) -> bool:
    if data.get("read_through"):
        return True
    # Every call sent, not only the ones kept: a mail call past the cap still taints the chat.
    calls = data.get("tool_calls") or []
    if any(_reads_through(call) for call in calls):
        return True
    fields = [data.get("user_text"), data.get("assistant_text")]
    fields += [call.get("result") for call in calls]
    return any(_marked(value) for value in fields)


class TurnConflict(Exception):
    """`(chat_id, seq)` already belongs to another member of the household."""


def _stored(household, chat_id, seq, member) -> ConversationTurn | None:
    row = ConversationTurn.objects.filter(household=household, chat_id=chat_id, seq=seq).first()
    if row is None:
        return None
    if row.member_id != member.pk:
        raise TurnConflict
    return row


def save(request, data: dict) -> tuple[ConversationTurn, bool]:
    """Stores one validated turn for the calling member. Returns `(row, created)`; a repeat of an
    already stored `(chat_id, seq)` returns the stored row untouched. Raises `TurnConflict` when
    another member holds that `(chat_id, seq)`."""
    household = household_of(request)
    member = request.user
    chat_id, seq = data["chat_id"], data["seq"]
    stored = _stored(household, chat_id, seq, member)
    if stored is not None:
        return stored, False

    tainted = ConversationTurn.objects.filter(
        household=household, chat_id=chat_id, member=member, withheld=True
    ).exists()
    withheld = tainted or _read_through(data)
    user_text, user_trimmed = (
        (None, False) if _marked(data.get("user_text")) else _trimmed_words(data.get("user_text"))
    )
    reply, reply_trimmed = (None, False) if withheld else _trimmed_words(data.get("assistant_text"))
    raw_calls = data.get("tool_calls") or []
    trimmed = user_trimmed or reply_trimmed or len(raw_calls) > MAX_TOOL_CALLS
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
                assistant_text=reply,
                tool_calls=_calls(raw_calls, withheld=withheld),
                withheld=withheld,
                trimmed=trimmed,
                spoken_at=data.get("spoken_at"),
            )
    except IntegrityError:
        # A concurrent retry of the same turn won the insert (or another member's did: a conflict).
        row = _stored(household, chat_id, seq, member)
        if row is None:
            raise
        return row, False
    return row, True


def prune_all() -> int:
    """Deletes every member's turns past the retention window. Run by the memory job
    (`consolidate_memories`) whether or not it has a key, so a member who stopped chatting, or
    switched the log off, still loses their turns on time. Server-wide, by age."""
    cutoff = timezone.now() - timedelta(days=RETENTION_DAYS)
    deleted, _ = ConversationTurn.objects.filter(received_at__lt=cutoff).delete()
    return deleted
