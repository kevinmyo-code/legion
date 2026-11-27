"""Saving a conversation turn as it happens (memory chain ticket 450, 2026-11-26).

The phone posts each finished turn (`POST /api/member-memories/turns`); this module decides what
of it is kept.

## What is kept: his words, the tool names, nothing else (CLAUDE.md section 7)

A stored turn is the person's own words (`user_text`), the NAMES of the tools called and whether
each worked, the surface, the times, the chat id and the turn's place in it. **No assistant reply
and no tool result is stored, anywhere**: not in the row, not in the request, not on the phone's
queue. A reply or a result can carry text other people wrote (mail, a web page, a school portal),
and tracking which ones do, per chat, leaks by every new path (typed chat restating a spoken mail
answer, a dropped turn that carried the mark). Not storing them cannot leak.

His own words are first-party even when he repeats something he read: provenance, not subject
(the section 7 recording carve-out, ADR 0041). The one thing refused is a line that looks like an
identity or bank number (`member_memory.sensitive`); those belong in the ID vault.

If the assistant's side should be remembered later, that is a new ruling, not a flag here.

Too much is trimmed, not refused: words past `TEXT_MAX_CHARS` are cut and a turn with more than
`MAX_TOOL_CALLS` calls keeps the first ones, both marked `trimmed`. Only a payload past
`TEXT_ACCEPT_CHARS` is refused.

Idempotent on `(household, chat_id, seq)`: the phone retries a send whose answer it lost, and a
repeat with the same content is a 200 with the stored row. The same pair with DIFFERENT content, or
from another member, is a conflict (`TurnConflict`, a 409): the stored turn is never overwritten
and the sender is not told it was saved.

A turn is held in memory on the phone from his first words to its end, a few seconds. An app killed
in that window loses that one turn (accepted, ticket 450); every finished turn is queued on disk.

Nothing here logs a turn's text.
"""

from __future__ import annotations

from datetime import timedelta

from django.db import IntegrityError, transaction
from django.utils import timezone

from household.tenancy import household_of
from member_memory.models import SURFACES, ConversationTurn
from member_memory.sensitive import looks_sensitive

TEXT_MAX_CHARS = 4_000
# What the endpoint accepts before it refuses: far past any spoken or typed line, so only a bug
# or abuse reaches it. Between TEXT_MAX_CHARS and this the text is trimmed.
TEXT_ACCEPT_CHARS = 100_000
MAX_TOOL_CALLS = 20
MAX_TOOL_CALLS_ACCEPTED = 200
NAME_MAX_CHARS = 80
# The column is a 32-bit integer; past it the insert would be a 500, not a worded 400.
SEQ_MAX = 2**31 - 1
# Matches `devicelogs.models.RETENTION_DAYS`: the raw conversation record is kept as long as the
# batch it duplicates. Distilled memory (`member_memories`) is what outlives it.
RETENTION_DAYS = 60
LIST_LIMIT_DEFAULT = 50
LIST_LIMIT_MAX = 200


def _words(value) -> tuple[str | None, bool]:
    """His line: whitespace folded, None when empty or an identity number (checked on the whole
    line before any trimming). Returns the kept text and whether it was cut."""
    if not isinstance(value, str):
        return None, False
    text = " ".join(value.split())
    if not text or looks_sensitive(text):
        return None, False
    if len(text) > TEXT_MAX_CHARS:
        return text[:TEXT_MAX_CHARS], True
    return text, False


def _calls(raw: list[dict]) -> list[dict]:
    calls = []
    for call in raw[:MAX_TOOL_CALLS]:
        name = str(call.get("name") or "")[:NAME_MAX_CHARS]
        if name:
            calls.append({"name": name, "ok": bool(call.get("ok", True))})
    return calls


class TurnConflict(Exception):
    """`(chat_id, seq)` is already held by another member, or by this member with other content."""


def _fields(data: dict) -> dict:
    user_text, cut = _words(data.get("user_text"))
    raw_calls = data.get("tool_calls") or []
    surface = data.get("surface")
    return {
        "surface": surface if surface in SURFACES else "voice",
        "user_text": user_text,
        "tool_calls": _calls(raw_calls),
        "trimmed": cut or len(raw_calls) > MAX_TOOL_CALLS,
        "interrupted": bool(data.get("interrupted")),
    }


def _same(row: ConversationTurn, fields: dict) -> bool:
    return all(getattr(row, key) == value for key, value in fields.items())


def _existing(household, chat_id, seq, member, fields) -> ConversationTurn | None:
    row = ConversationTurn.objects.filter(household=household, chat_id=chat_id, seq=seq).first()
    if row is None:
        return None
    if row.member_id != member.pk or not _same(row, fields):
        raise TurnConflict
    return row


def save(request, data: dict) -> tuple[ConversationTurn, bool]:
    """Stores one validated turn for the calling member. Returns `(row, created)`; an identical
    repeat returns the stored row. Raises `TurnConflict` for the same `(chat_id, seq)` with other
    content or from another member."""
    household = household_of(request)
    member = request.user
    chat_id, seq = data["chat_id"], data["seq"]
    fields = _fields(data)
    row = _existing(household, chat_id, seq, member, fields)
    if row is not None:
        return row, False
    try:
        with transaction.atomic():
            row = ConversationTurn.objects.create(
                household=household,
                member=member,
                owner_user=member,
                chat_id=chat_id,
                seq=seq,
                spoken_at=data.get("spoken_at"),
                **fields,
            )
    except IntegrityError:
        # A concurrent send of the same turn won the insert.
        row = _existing(household, chat_id, seq, member, fields)
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
