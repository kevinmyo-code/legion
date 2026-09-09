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

## `?vehicle=` - the recall rule, moved here from a Room query

django-engine ticket 14. **A `car_anchored` memory is only recalled for the
car it belongs to.** That rule lived in exactly one place until 2026-09-07:
`data/local/CompanionMemoryDao.getRecallScan`'s WHERE clause,
`category != 'car_anchored' OR vehicleId = :vehicleId`. This server served
every row unfiltered, so a second client - the head unit, which is a
separate app (ADR 0044, Kevin 2026-09-07 "django owns everything, the 2
android apps just consume") - would have recalled the Outlander's service
history while parked in the Jeep.

**How the server learns which car, and why it is a parameter rather than
server-held state.** `vehicle/ActiveVehicle.kt` says in its own class doc
that the active vehicle "is per-device state and MUST NOT sync": the phone
can be in the Outlander at the moment the head unit is bolted into the
Cherokee, "they legitimately disagree, and that disagreement is correct."
A household-level active vehicle on the server would make them agree, which
is the bug that ruling exists to prevent; a per-device one would be the same
answer this parameter already carries, plus a table that can lag the dongle.
So the caller states the FACT (which car this device is in - only the device
can know, the dongle is plugged into it) and the server applies the RULE
(what may be recalled given that fact). A client cannot widen the rule; it
can only misreport where it is.

**Omitting it returns everything, and that is not a bypass - it is the other
job this route has.** `GET ?since=` is also the replication feed the phone's
Room replica lives on, and that replica is deliberately whole: the
driver-facing memory screen reads across ALL cars
(`CompanionMemoryDao.allRecent`, "a memory attached to a car he is not
sitting in is exactly the one he would otherwise never see"), and a
tombstone for another car's row still has to arrive. Narrowing the default
would silently drop rows out of a sync feed, which is the failure nobody
greps for. The honest limit, stated rather than papered over: this server
cannot stop a client that has pulled a full replica from ignoring the rule
afterwards. What it can do, and now does, is answer the recall question
correctly for any client that asks it.
"""
from __future__ import annotations

from django.db.models import Q
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework.response import Response

from api.sync import paginate_since, parse_since
from api.synced import (
    TRUTHY,
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

# The one category the recall rule scopes. `driver` and `relationship` are
# about the person and the person does not change car to car - the Kotlin
# DAO's own words, and the bug that produced them: scoping the whole table
# by vehicle stranded 46 memories about Kevin the moment the Jeep became the
# active car.
CAR_ANCHORED = "car_anchored"

VEHICLE_PARAMETER = OpenApiParameter(
    name="vehicle",
    location=OpenApiParameter.QUERY,
    type=OpenApiTypes.STR,
    required=False,
    description=(
        "The vehicle this device is currently in (the phone's `ActiveVehicle.current()` - a "
        "dongle MAC or a `car:<uuid>`). Supplying it applies the recall rule: every `driver` "
        "and `relationship` memory comes back, and `car_anchored` memories come back ONLY for "
        "this vehicle. **Omitting it returns everything**, because this route is also the "
        "replication feed and a replica has to be whole - see `api/memory.py`'s own module doc. "
        "Send it whenever the rows are going into a prompt; omit it when they are going into a "
        "local cache."
    ),
)


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

    # **Why this is a copy of the base `list` rather than a hook in
    # `api/synced.py`.** One filter is the only difference, and the right
    # shape is a `narrow_list_queryset(request, queryset)` override point on
    # the base class. That file was being edited by another effort in the
    # same session this landed (ledger, pantry and the gated read-only
    # tables all land on the same generic shape), and adding a hook to it
    # from here would have been two efforts writing one file.
    #
    # **The duplication has ALREADY drifted once, inside that same session,
    # which is the argument for collapsing it and not a hypothetical.** The
    # base `list` grew `cursor_field` and `has_tombstones` while this copy
    # sat here; the two lines below were re-mirrored by hand. They agree
    # today because `companion_memories` has `updated_at` and `deleted_at`
    # and therefore takes both defaults - so the drift changed no behaviour
    # this time. **Collapse this into the hook the moment `synced.py` is
    # free.**
    #
    # This note is a comment and not part of the docstring on purpose: the
    # docstring becomes the operation `description` in `server/openapi.yaml`,
    # which a generated client reads, and an internal scheduling note has no
    # business in a published contract.
    @extend_schema(parameters=[VEHICLE_PARAMETER])
    def list(self, request):
        """`?since=<iso>` (tombstones included), `?active=1` (live rows
        only) and `?vehicle=<id>` (the recall rule: `car_anchored` memories
        only for that vehicle, `driver` and `relationship` always). All
        three compose.

        A missing `since` means EVERYTHING, and so does a missing
        `vehicle` - this route is the replication feed as well as the recall
        read, and a replica has to be whole. See the `vehicle` parameter's
        own description.
        """
        vehicle = (request.query_params.get("vehicle") or "").strip()

        since = parse_since(request.query_params.get("since"))
        # `self.queryset()`, the inherited household filter - see the note
        # above about this method being a copy of the base `list` that has
        # already drifted once. ADR 0045's scoping is not something a copy is
        # allowed to miss, so it comes from the base class rather than being
        # re-spelled here.
        queryset = self.queryset().filter(**{f"{self.cursor_field}__gte": since})
        if self.has_tombstones and request.query_params.get("active", "").strip().lower() in TRUTHY:
            queryset = queryset.filter(deleted_at__isnull=True)
        if vehicle:
            # The Room query this replaces, in Django's spelling:
            # `category != 'car_anchored' OR vehicleId = :vehicleId`.
            # Both columns are `not null` in `public.companion_memories`
            # (read from the DDL, not assumed), so there is no third
            # NULL branch for a row to slip through.
            queryset = queryset.filter(~Q(category=CAR_ANCHORED) | Q(vehicle_id=vehicle))
        queryset = queryset.order_by(self.cursor_field, "pk")
        page, next_since = paginate_since(queryset, cursor_field=self.cursor_field)
        return Response({"results": self._serialize(page, many=True), "next": next_since})


class MemoryAuditViewSet(_MemoryViewSet):
    table = "memory_audit"
    serializer_class = MemoryAuditSerializer
    # Append-only: no DELETE in the URL map at all, so the refusal comes
    # from routing rather than from a guard inside a view that a later edit
    # could drop. See this module's doc comment for the phone-side rule it
    # mirrors.
    allow_delete = False
    # The sentence the 405 carries. It used to be hardcoded into
    # `SyncedModelViewSet.http_method_not_allowed` because this was the only
    # table that ever set `allow_delete = False`; fleet added two whose reason
    # is different (no `deleted_at` column at all), so the wording moved to the
    # viewset that means it. Same words, same table, said in the place that is
    # true of it.
    no_delete_reason = (
        "memory_audit is append-only: it is an audit trail, and a trail with rows removed "
        "from it is not one."
    )


MEMORY_VIEWSETS = [MemoryViewSet, CompanionMemoryViewSet, MemoryAuditViewSet]
