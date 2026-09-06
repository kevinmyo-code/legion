"""`/api/memory/<table>` - the companion's memory: `memories`,
`companion_memories`, `memory_audit`.

`MemoryBackend.kt` is the phone-side contract, and it names both departures
this module honours:

1. **`memory_audit` has no delete route.** That interface deliberately has
   no `softDeleteMemoryAudit` ("No delete function for `RemoteMemoryAudit`
   at all"), because nothing on the phone ever soft-deletes an individual
   audit row - the table is pushed by backfill and read as a trail. A
   DELETE here answers 405 saying why, rather than quietly succeeding or
   quoting DRF's bare default. `deleted_at` still exists on the table (the
   phone runs one generic merge over all three), and this API simply gives
   nobody a way to set it.
2. **`companion_memories` carries no embedding.** `RemoteCompanionMemory`
   lists exactly the fields that cross - id, vehicle_id, text, category,
   source, importance, logged_at, last_accessed_at, updated_at, deleted_at,
   origin_guid - and says in its own doc comment that `embeddingVector` /
   `embeddingModel` are absent on purpose: the embedding never leaves the
   device. `public.companion_memories` has no such column either, confirmed
   against the live schema on 2026-09-06, so there is nothing here to
   filter out. That is the stronger guarantee, and it is the same shape
   CLAUDE.md section 7 asks for elsewhere: the guarantee is that it was
   never stored, not that something remembered to exclude it.

`vehicle_id` on `companion_memories` and `memory_audit` is a plain text
label - the phone's local ActiveVehicle key - and NOT a foreign key into
any server vehicle table. `legacy/models/memory.py` confirmed that against
`pg_constraint`; the fleet aspect is not routed by this ticket, and this
column would not connect to it if it were.
"""
from __future__ import annotations

from api.synced import (
    SyncedModelViewSet,
    SyncedSerializer,
    blank_error,
    choice_error,
    range_error,
)
from legacy.models.memory import CompanionMemory, Memory, MemoryAudit

# `legacy/CONSTRAINTS.md`, read from the live schema.
CATEGORY_CHOICES = ("car_anchored", "driver", "relationship")
SOURCE_CHOICES = ("consolidated", "reflection", "stated")
IMPORTANCE_BOUNDS = (1, 10)
AUDIT_EVENT_CHOICES = ("written", "deleted", "recall", "recalled", "spoken")
AUDIT_STORE_CHOICES = ("memories", "companion_memories", "speech")


class MemorySerializer(SyncedSerializer):
    class Meta:
        model = Memory
        fields = [
            "id",
            "text",
            "logged_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_text(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("text")
        return value


class CompanionMemorySerializer(SyncedSerializer):
    """Field-for-field `RemoteCompanionMemory`. See this module's own doc
    comment for the embedding columns that exist on neither side."""

    class Meta:
        model = CompanionMemory
        fields = [
            "id",
            "vehicle_id",
            "text",
            "category",
            "source",
            "importance",
            "logged_at",
            "last_accessed_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            # DB default 5, and a caller that omits it means "no opinion".
            "importance": {"required": False, "default": 5},
        }

    def validate_text(self, value: str) -> str:
        if not value or not value.strip():
            raise blank_error("text")
        return value

    def validate_category(self, value: str) -> str:
        if value not in CATEGORY_CHOICES:
            raise choice_error("category", value, CATEGORY_CHOICES)
        return value

    def validate_source(self, value: str) -> str:
        if value not in SOURCE_CHOICES:
            raise choice_error("source", value, SOURCE_CHOICES)
        return value

    def validate_importance(self, value: int) -> int:
        low, high = IMPORTANCE_BOUNDS
        if not low <= value <= high:
            raise range_error("importance", value, low, high)
        return value


class MemoryAuditSerializer(SyncedSerializer):
    class Meta:
        model = MemoryAudit
        fields = [
            "id",
            "event",
            "store",
            "detail",
            "ref_id",
            "vehicle_id",
            "logged_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_event(self, value: str) -> str:
        if value not in AUDIT_EVENT_CHOICES:
            raise choice_error("event", value, AUDIT_EVENT_CHOICES)
        return value

    def validate_store(self, value: str) -> str:
        if value not in AUDIT_STORE_CHOICES:
            raise choice_error("store", value, AUDIT_STORE_CHOICES)
        return value


class _MemoryViewSet(SyncedModelViewSet):
    aspect = "memory"


class MemoryViewSet(_MemoryViewSet):
    table = "memories"
    serializer_class = MemorySerializer


class CompanionMemoryViewSet(_MemoryViewSet):
    table = "companion_memories"
    serializer_class = CompanionMemorySerializer


class MemoryAuditViewSet(_MemoryViewSet):
    table = "memory_audit"
    serializer_class = MemoryAuditSerializer
    # Append-only: no DELETE in the URL map at all, so the refusal comes
    # from routing rather than from a guard inside a view that a later edit
    # could drop. See this module's doc comment for the phone-side rule it
    # mirrors.
    allow_delete = False


MEMORY_VIEWSETS = [MemoryViewSet, CompanionMemoryViewSet, MemoryAuditViewSet]
