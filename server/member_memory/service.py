"""Memory v2's operations, once, for every door (ADR 0075).

REST (`member_memory/views.py`), the engine tools (`engine_mcp/tools.py`: `recall_memory`,
`list_memories`, `remember`, `forget_memory`), the one-time import and the consolidation job all
call these functions, so the privacy rule, the vault filter and the hard delete live in one place.

Every read starts from `visible()` (ADR 0052): the caller's own private memories plus the
household's shared ones. Another member's private memory does not exist for this caller, so a
lookup by id answers "no memory with that id" exactly as for another household's.

`refusal(request)` is the door check every caller runs first: a recruiter is refused (ADR 0068),
and so is the work laptop's token (map decision G, `claude_session.tokens`).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from django.db import transaction
from django.db.models import F, Q
from django.db.models.functions import Now
from django.utils import timezone

from claude_session.tokens import FENCE_LISTED, FENCE_UNBOUND, worker_fence
from household.models import is_recruiter
from household.tenancy import household_of, visible
from member_memory import scoring
from member_memory.models import (
    DECAY_CLASSES,
    DECAY_DURABLE,
    IMPORTANCE_DEFAULT,
    IMPORTANCE_MAX,
    IMPORTANCE_MIN,
    KIND_FACT,
    KINDS,
    SOURCES,
    TEXT_MAX_CHARS,
    MemberMemory,
)
from member_memory.sensitive import REFUSAL as SENSITIVE_REFUSAL
from member_memory.sensitive import looks_sensitive

RECRUITER_REFUSAL = (
    "Nothing was read or written. This is a recruiter's demo visit, and the assistant's memory "
    "of a household member is not part of the demo."
)
WORK_LAPTOP_REFUSAL = (
    "Nothing was read or written. This token belongs to a Claude machine with listed scope (the "
    "work laptop), and the work laptop never reads or writes memory (ADR 0067, ADR 0075)."
)
UNBOUND_WORKER_REFUSAL = (
    "Nothing was read or written. This is a Claude worker's token that has not yet reported "
    "which machine it runs on, so the engine cannot tell the Alienware from the work laptop. "
    "Start the worker so its heartbeat names the machine, then try again."
)
NOT_FOUND = "Nothing was changed. There is no memory with that id that you can see."
MAKE_PRIVATE_REFUSAL = (
    "Nothing was changed. Only the person a memory is about can make it private."
)
SHARE_REFUSAL = "Nothing was changed. Only the person a memory is about can share it."


class MemoryRefused(Exception):
    """Nothing was read or written. The message says why, in words."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


def refusal(request) -> str | None:
    """Why this caller may not use memory at all, or None."""
    if is_recruiter(getattr(request, "user", None)):
        return RECRUITER_REFUSAL
    fence = worker_fence(request)
    if fence == FENCE_LISTED:
        return WORK_LAPTOP_REFUSAL
    if fence == FENCE_UNBOUND:
        return UNBOUND_WORKER_REFUSAL
    return None


def readable(request):
    """The caller's memories: their own private ones and every shared one in the household."""
    return visible(MemberMemory, request)


def _clean_text(text) -> str:
    if not isinstance(text, str) or not text.strip():
        raise MemoryRefused("Nothing was saved. A memory needs some text.")
    text = " ".join(text.split())
    if len(text) > TEXT_MAX_CHARS:
        raise MemoryRefused(
            f"Nothing was saved. A memory is at most {TEXT_MAX_CHARS} characters; this one is "
            f"{len(text)}. Keep the gist, not a transcript."
        )
    if looks_sensitive(text):
        raise MemoryRefused(SENSITIVE_REFUSAL)
    return text


def _check_choice(name: str, value, choices) -> None:
    if value not in choices:
        raise MemoryRefused(
            f"Nothing was saved. {name} {value!r} is not one of {', '.join(choices)}."
        )


def _check_importance(value) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or not (
        IMPORTANCE_MIN <= value <= IMPORTANCE_MAX
    ):
        raise MemoryRefused(
            f"Nothing was saved. importance must be a whole number from {IMPORTANCE_MIN} to "
            f"{IMPORTANCE_MAX}."
        )
    return value


def _parse_id(raw) -> uuid.UUID | None:
    try:
        return uuid.UUID(str(raw))
    except (TypeError, ValueError, AttributeError):
        return None


def find(request, memory_id) -> MemberMemory | None:
    parsed = _parse_id(memory_id)
    if parsed is None:
        return None
    return readable(request).filter(pk=parsed).first()


def create(
    request,
    *,
    text,
    source: str,
    kind: str = KIND_FACT,
    importance: int = IMPORTANCE_DEFAULT,
    decay_class: str = DECAY_DURABLE,
    shared: bool = False,
    supersedes=None,
) -> MemberMemory:
    """A new memory about the caller. `supersedes`, when given, must be a memory the caller can
    see; it is marked superseded in the same transaction."""
    text = _clean_text(text)
    _check_choice("kind", kind, KINDS)
    _check_choice("decay_class", decay_class, DECAY_CLASSES)
    _check_choice("source", source, SOURCES)
    importance = _check_importance(importance)
    older = None
    if supersedes not in (None, ""):
        older = find(request, supersedes)
        if older is None:
            raise MemoryRefused(
                "Nothing was saved. The memory it supersedes is not one you can see; recall or "
                "list memories for its id."
            )
        # Only your own memory can be replaced: superseding a shared fact about someone else
        # would take it out of their recall in favour of a row they may not be able to see.
        if older.member_id != request.user.pk:
            raise MemoryRefused(
                "Nothing was saved. Only the person a memory is about can replace it with a newer "
                "one.",
                status=403,
            )
    user = request.user
    with transaction.atomic():
        row = MemberMemory.objects.create(
            household=household_of(request),
            member=user,
            owner_user=None if shared else user,
            text=text,
            kind=kind,
            importance=importance,
            decay_class=decay_class,
            source=source,
            created_by=user,
            supersedes=older,
        )
        if older is not None:
            MemberMemory.objects.filter(pk=older.pk).update(
                superseded_at=Now(), updated_at=Now()
            )
    row.refresh_from_db()
    return row


def update(request, memory_id, changes: dict) -> MemberMemory:
    row = find(request, memory_id)
    if row is None:
        raise MemoryRefused(NOT_FOUND, status=404)
    fields = []
    if "text" in changes:
        row.text = _clean_text(changes["text"])
        fields.append("text")
    if "kind" in changes:
        _check_choice("kind", changes["kind"], KINDS)
        row.kind = changes["kind"]
        fields.append("kind")
    if "decay_class" in changes:
        _check_choice("decay_class", changes["decay_class"], DECAY_CLASSES)
        row.decay_class = changes["decay_class"]
        fields.append("decay_class")
    if "importance" in changes:
        row.importance = _check_importance(changes["importance"])
        fields.append("importance")
    if "shared" in changes:
        wanted = changes["shared"]
        if not isinstance(wanted, bool):
            raise MemoryRefused("Nothing was changed. shared must be true or false.")
        if wanted != row.shared:
            if row.member_id != request.user.pk:
                raise MemoryRefused(
                    SHARE_REFUSAL if wanted else MAKE_PRIVATE_REFUSAL, status=403
                )
            row.owner_user_id = None if wanted else row.member_id
            fields.append("owner_user")
    if fields:
        row.updated_at = timezone.now()
        row.save(update_fields=[*fields, "updated_at"])
    return row


@dataclass(frozen=True)
class Forgotten:
    text: str
    deleted: int


def _chain(row: MemberMemory) -> list:
    """`row` and every older version it superseded, newest first. Cycle-safe."""
    ids, seen, current = [], set(), row
    while current is not None and current.pk not in seen:
        seen.add(current.pk)
        ids.append(current.pk)
        current = current.supersedes
    return ids


def forget(request, memory_id) -> Forgotten | None:
    """Hard-deletes the memory and the versions it superseded (map decision C). None when there
    is no such memory for this caller; nothing is deleted then. Only versions this caller can
    see go with it: an older version private to someone else is never touched from here."""
    row = find(request, memory_id)
    if row is None:
        return None
    ids = _chain(row)
    with transaction.atomic():
        deleted, _ = readable(request).filter(pk__in=ids).delete()
    return Forgotten(text=row.text, deleted=deleted)


def listing(request, *, kind=None, query=None, include_superseded=False, limit=100):
    rows = readable(request)
    if not include_superseded:
        rows = rows.filter(superseded_at__isnull=True)
    if kind:
        rows = rows.filter(kind=kind)
    if query:
        rows = rows.filter(Q(text__icontains=query))
    return list(rows.order_by("-created_at", "-pk")[:limit])


def recall(request, query: str = "", limit: int = scoring.RECALL_LIMIT_DEFAULT):
    """The best `limit` live memories for `query`, rehearsed (map decision A)."""
    limit = max(1, min(int(limit), scoring.RECALL_LIMIT_MAX))
    rows = list(
        readable(request)
        .filter(superseded_at__isnull=True)
        .order_by("-created_at")[: scoring.SCAN_MAX]
    )
    now = timezone.now()
    best = scoring.score_rows(rows, query or "", now)[:limit]
    if best:
        MemberMemory.objects.filter(pk__in=[s.memory.pk for s in best]).update(
            last_recalled_at=now, recall_count=F("recall_count") + 1
        )
    return best


def as_dict(row: MemberMemory, request) -> dict:
    """The one wire shape, for REST and tool results alike."""
    from rest_framework.fields import DateTimeField

    stamp = DateTimeField().to_representation
    return {
        "id": str(row.id),
        "text": row.text,
        "kind": row.kind,
        "importance": row.importance,
        "decay_class": row.decay_class,
        "source": row.source,
        "shared": row.shared,
        "about_you": row.member_id == request.user.pk,
        "supersedes": str(row.supersedes_id) if row.supersedes_id else None,
        "superseded_at": stamp(row.superseded_at) if row.superseded_at else None,
        "last_recalled_at": stamp(row.last_recalled_at) if row.last_recalled_at else None,
        "recall_count": row.recall_count,
        "from_conversation": str(row.source_log_id) if row.source_log_id else None,
        "created_at": stamp(row.created_at),
        "updated_at": stamp(row.updated_at),
    }
