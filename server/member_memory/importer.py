"""The one-time import of the phone's memories into memory v2 (memory-and-vault ticket 04).

Two doors, one function:

- `POST /api/member-memories/import`, the phone uploading its own Room rows (phone ticket 10).
- `manage.py import_legacy_memories --member <email>`, reading the same rows from the engine's
  legacy replica (`companion_memories` and `memories`, `legacy/models/memory.py`), which the
  phone has been pushing all along. Found while charting: this needs no phone at all.

**Every imported row is private to the member doing the import** (Kevin's ruling 2): the legacy
tables carry no member, and the phone that made them is one person's. Sharing is a later,
explicit act (map decision B).

The mapping, because the legacy categories do not line up with v2's:

| Legacy row | kind | decay_class |
|---|---|---|
| `memories` (what the phone's `remember` saved) | fact | durable, importance 5 |
| `companion_memories` with source `stated`, or category `car_anchored` | fact | durable |
| `companion_memories` otherwise (`consolidated`, `reflection`) | event | episodic |

Idempotent by `origin_key` = `phone:<store>:<origin_guid>`, unique per household. A row whose
text looks like a vault identifier is counted and never stored.
"""

from __future__ import annotations

from django.db import IntegrityError, transaction

from member_memory.models import (
    DECAY_DURABLE,
    DECAY_EPISODIC,
    IMPORTANCE_DEFAULT,
    IMPORTANCE_MAX,
    IMPORTANCE_MIN,
    KIND_EVENT,
    KIND_FACT,
    SOURCE_PHONE,
    TEXT_MAX_CHARS,
    MemberMemory,
)
from member_memory.sensitive import looks_sensitive

STORE_COMPANION = "companion_memories"
STORE_FLAT = "memories"
STORES: tuple[str, ...] = (STORE_COMPANION, STORE_FLAT)
BATCH_MAX = 500


def classify(store: str, category: str, source: str) -> tuple[str, str]:
    """(kind, decay_class) for one legacy row."""
    if store == STORE_FLAT or source == "stated" or category == "car_anchored":
        return KIND_FACT, DECAY_DURABLE
    return KIND_EVENT, DECAY_EPISODIC


def _importance(store: str, raw) -> int:
    if store == STORE_FLAT or not isinstance(raw, int) or isinstance(raw, bool):
        return IMPORTANCE_DEFAULT
    return max(IMPORTANCE_MIN, min(IMPORTANCE_MAX, raw))


def import_for(household, member, rows) -> dict:
    """Imports `rows` (dicts: store, origin_guid, text, category, source, importance, logged_at,
    last_accessed_at) as `member`'s private memories in `household`."""
    counts = {"imported": 0, "already_here": 0, "refused_sensitive": 0, "refused_invalid": 0}
    for row in rows:
        store = row.get("store")
        guid = str(row.get("origin_guid") or "").strip()
        text = " ".join(str(row.get("text") or "").split())
        if store not in STORES or not guid or not text or len(text) > TEXT_MAX_CHARS:
            counts["refused_invalid"] += 1
            continue
        if looks_sensitive(text):
            counts["refused_sensitive"] += 1
            continue
        key = f"phone:{store}:{guid}"
        if MemberMemory.objects.filter(household=household, origin_key=key).exists():
            counts["already_here"] += 1
            continue
        kind, decay = classify(store, row.get("category") or "", row.get("source") or "")
        logged = row.get("logged_at")
        try:
            with transaction.atomic():
                memory = MemberMemory.objects.create(
                    household=household,
                    member=member,
                    owner_user=member,
                    text=text,
                    kind=kind,
                    importance=_importance(store, row.get("importance")),
                    decay_class=decay,
                    source=SOURCE_PHONE,
                    created_by=member,
                    origin_key=key,
                    last_recalled_at=row.get("last_accessed_at"),
                )
                if logged is not None:
                    MemberMemory.objects.filter(pk=memory.pk).update(
                        created_at=logged, updated_at=logged
                    )
        except IntegrityError:
            # Another import of the same row won the race.
            counts["already_here"] += 1
            continue
        counts["imported"] += 1
    return counts


def import_rows(request, rows) -> dict:
    from household.tenancy import household_of

    return import_for(household_of(request), request.user, rows)


def legacy_rows(household) -> list[dict]:
    """The engine's own replica of the phone's live memory rows for `household`."""
    from legacy.models.memory import CompanionMemory, Memory

    rows = [
        {
            "store": STORE_COMPANION,
            "origin_guid": row.origin_guid,
            "text": row.text,
            "category": row.category,
            "source": row.source,
            "importance": row.importance,
            "logged_at": row.logged_at,
            "last_accessed_at": row.last_accessed_at,
        }
        for row in CompanionMemory.objects.filter(household=household, deleted_at__isnull=True)
    ]
    rows += [
        {
            "store": STORE_FLAT,
            "origin_guid": row.origin_guid,
            "text": row.text,
            "logged_at": row.logged_at,
        }
        for row in Memory.objects.filter(household=household, deleted_at__isnull=True)
    ]
    return rows
