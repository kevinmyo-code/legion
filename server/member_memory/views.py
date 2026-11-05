"""`/api/member-memories/`: memory v2 over REST (ADR 0075, memory-and-vault tickets 01 and 04).

The phone's Memory screen, its voice tools and the one-time import call these; the web assistant
and `/mcp` reach the same operations through the engine tools. Authentication is the API default
(a device token or a browser session). Every route runs `service.refusal` first (recruiters, the
work laptop), then reads through `visible()`: another member's private memory is a 404.

**DELETE is a hard delete** (Kevin, 2026-11-05: "forget that" deletes it for real). There is no
tombstone and no `?since=` feed, so a client cache refreshes from the whole list.

Nothing here logs a memory's text.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from member_memory import importer, scoring, service
from member_memory.models import (
    CLIENT_SOURCES,
    DECAY_CLASSES,
    DECAY_DURABLE,
    IMPORTANCE_DEFAULT,
    IMPORTANCE_MAX,
    IMPORTANCE_MIN,
    KIND_FACT,
    KINDS,
    SOURCE_MANUAL,
    SOURCES,
    TEXT_MAX_CHARS,
)

TAGS = ["member-memories"]
LIST_LIMIT_DEFAULT = 100
LIST_LIMIT_MAX = 500


class MemberMemorySerializer(serializers.Serializer):
    id = serializers.UUIDField()
    text = serializers.CharField()
    kind = serializers.ChoiceField(choices=KINDS)
    importance = serializers.IntegerField(min_value=IMPORTANCE_MIN, max_value=IMPORTANCE_MAX)
    decay_class = serializers.ChoiceField(choices=DECAY_CLASSES)
    source = serializers.ChoiceField(choices=SOURCES)
    shared = serializers.BooleanField(
        help_text="True for a household fact every member sees; false while private to the "
        "member it is about."
    )
    about_you = serializers.BooleanField(help_text="True when the memory is about the caller.")
    supersedes = serializers.UUIDField(allow_null=True)
    superseded_at = serializers.DateTimeField(allow_null=True)
    last_recalled_at = serializers.DateTimeField(allow_null=True)
    recall_count = serializers.IntegerField()
    from_conversation = serializers.UUIDField(
        allow_null=True, help_text="The conversation record it was distilled from, if any."
    )
    created_at = serializers.DateTimeField()
    updated_at = serializers.DateTimeField()


class MemoryListSerializer(serializers.Serializer):
    results = MemberMemorySerializer(many=True)


class MemoryCreateSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=TEXT_MAX_CHARS)
    kind = serializers.ChoiceField(choices=KINDS, default=KIND_FACT)
    importance = serializers.IntegerField(
        min_value=IMPORTANCE_MIN, max_value=IMPORTANCE_MAX, default=IMPORTANCE_DEFAULT
    )
    decay_class = serializers.ChoiceField(choices=DECAY_CLASSES, default=DECAY_DURABLE)
    shared = serializers.BooleanField(default=False)
    source = serializers.ChoiceField(
        choices=CLIENT_SOURCES,
        default=SOURCE_MANUAL,
        help_text="`phone` for the phone's voice tools, `manual` for a screen.",
    )
    supersedes = serializers.UUIDField(required=False, allow_null=True)


class MemoryUpdateSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=TEXT_MAX_CHARS, required=False)
    kind = serializers.ChoiceField(choices=KINDS, required=False)
    importance = serializers.IntegerField(
        min_value=IMPORTANCE_MIN, max_value=IMPORTANCE_MAX, required=False
    )
    decay_class = serializers.ChoiceField(choices=DECAY_CLASSES, required=False)
    shared = serializers.BooleanField(required=False)


class RecallRequestSerializer(serializers.Serializer):
    query = serializers.CharField(required=False, allow_blank=True, default="")
    limit = serializers.IntegerField(
        required=False,
        min_value=1,
        max_value=scoring.RECALL_LIMIT_MAX,
        default=scoring.RECALL_LIMIT_DEFAULT,
    )


class RecalledSerializer(MemberMemorySerializer):
    score = serializers.FloatField()


class RecallResultSerializer(serializers.Serializer):
    results = RecalledSerializer(many=True)


class ImportRowSerializer(serializers.Serializer):
    store = serializers.ChoiceField(
        choices=importer.STORES,
        help_text="`companion_memories` (learned) or `memories` (what `remember` saved).",
    )
    origin_guid = serializers.CharField(max_length=64)
    text = serializers.CharField()
    category = serializers.CharField(required=False, allow_blank=True, default="")
    source = serializers.CharField(required=False, allow_blank=True, default="")
    importance = serializers.IntegerField(required=False, allow_null=True, default=None)
    logged_at = serializers.DateTimeField()
    last_accessed_at = serializers.DateTimeField(required=False, allow_null=True, default=None)


class ImportRequestSerializer(serializers.Serializer):
    rows = ImportRowSerializer(many=True, max_length=importer.BATCH_MAX)


class ImportResultSerializer(serializers.Serializer):
    imported = serializers.IntegerField()
    already_here = serializers.IntegerField()
    refused_sensitive = serializers.IntegerField(
        help_text="Rows that looked like a vault identifier; never stored."
    )
    refused_invalid = serializers.IntegerField()


def _refused(request):
    reason = service.refusal(request)
    if reason is None:
        return None
    return Response({"detail": reason}, status=status.HTTP_403_FORBIDDEN)


def _bad(serializer):
    errors = "; ".join(
        f"{field}: {' '.join(str(m) for m in messages)}"
        for field, messages in serializer.errors.items()
    )
    return Response(
        {"detail": f"Nothing was saved. {errors}"}, status=status.HTTP_400_BAD_REQUEST
    )


def _from_exc(exc: service.MemoryRefused):
    return Response({"detail": exc.message}, status=exc.status)


class MemoryListCreateView(APIView):
    @extend_schema(
        operation_id="api_member_memories_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter("kind", str, enum=list(KINDS)),
            OpenApiParameter("q", str, description="Only memories whose text contains this."),
            OpenApiParameter(
                "include_superseded", bool, description="Also older versions a newer fact replaced."
            ),
            OpenApiParameter(
                "limit", int, description=f"Default {LIST_LIMIT_DEFAULT}, max {LIST_LIMIT_MAX}."
            ),
        ],
        responses={200: MemoryListSerializer, 403: DetailSerializer},
        description=(
            "The caller's own memories and the household's shared ones, newest first. A whole "
            "list, never a feed: forgetting is a hard delete, so a cache replaces itself from this."
        ),
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        kind = request.query_params.get("kind") or None
        if kind and kind not in KINDS:
            return Response(
                {"detail": f"Nothing was read. kind {kind!r} is not one of {', '.join(KINDS)}."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            limit = int(request.query_params.get("limit") or LIST_LIMIT_DEFAULT)
        except ValueError:
            limit = LIST_LIMIT_DEFAULT
        limit = max(1, min(limit, LIST_LIMIT_MAX))
        include = (request.query_params.get("include_superseded") or "").lower() in (
            "1", "true", "yes",
        )
        rows = service.listing(
            request,
            kind=kind,
            query=(request.query_params.get("q") or "").strip() or None,
            include_superseded=include,
            limit=limit,
        )
        return Response({"results": [service.as_dict(row, request) for row in rows]})

    @extend_schema(
        operation_id="api_member_memories_create",
        tags=TAGS,
        request=MemoryCreateSerializer,
        responses={201: MemberMemorySerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "Saves a memory about the caller, private unless `shared`. Refused in words when "
            "the text looks like a vault identifier."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = MemoryCreateSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        data = serializer.validated_data
        try:
            row = service.create(
                request,
                text=data["text"],
                source=data["source"],
                kind=data["kind"],
                importance=data["importance"],
                decay_class=data["decay_class"],
                shared=data["shared"],
                supersedes=data.get("supersedes"),
            )
        except service.MemoryRefused as exc:
            return _from_exc(exc)
        return Response(service.as_dict(row, request), status=status.HTTP_201_CREATED)


class MemoryDetailView(APIView):
    @extend_schema(
        operation_id="api_member_memories_retrieve",
        tags=TAGS,
        responses={200: MemberMemorySerializer, 403: DetailSerializer, 404: DetailSerializer},
    )
    def get(self, request, memory_id):
        refused = _refused(request)
        if refused:
            return refused
        row = service.find(request, memory_id)
        if row is None:
            return Response(
                {"detail": "Nothing was read. There is no memory with that id that you can see."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(service.as_dict(row, request))

    @extend_schema(
        operation_id="api_member_memories_partial_update",
        tags=TAGS,
        request=MemoryUpdateSerializer,
        responses={
            200: MemberMemorySerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description=(
            "Changes text, kind, importance, decay class or sharing. Only the member a memory is "
            "about may share it or make it private again."
        ),
    )
    def patch(self, request, memory_id):
        refused = _refused(request)
        if refused:
            return refused
        serializer = MemoryUpdateSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        try:
            row = service.update(request, memory_id, dict(serializer.validated_data))
        except service.MemoryRefused as exc:
            return _from_exc(exc)
        return Response(service.as_dict(row, request))

    @extend_schema(
        operation_id="api_member_memories_destroy",
        tags=TAGS,
        responses={
            204: OpenApiResponse(
                description="Deleted for good, with every older version it superseded."
            ),
            403: DetailSerializer,
            404: DetailSerializer,
        },
    )
    def delete(self, request, memory_id):
        refused = _refused(request)
        if refused:
            return refused
        if service.forget(request, memory_id) is None:
            return Response({"detail": service.NOT_FOUND}, status=status.HTTP_404_NOT_FOUND)
        return Response(status=status.HTTP_204_NO_CONTENT)


class MemoryRecallView(APIView):
    @extend_schema(
        operation_id="api_member_memories_recall",
        tags=TAGS,
        request=RecallRequestSerializer,
        responses={200: RecallResultSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "The best live memories for `query` by recency (decaying by class), importance and "
            "relevance; an empty query gives the newest and most important. Rehearses what it "
            "returns."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = RecallRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        best = service.recall(
            request, serializer.validated_data["query"], serializer.validated_data["limit"]
        )
        return Response(
            {
                "results": [
                    {**service.as_dict(s.memory, request), "score": round(s.score, 4)}
                    for s in best
                ]
            }
        )


class MemoryImportView(APIView):
    @extend_schema(
        operation_id="api_member_memories_import",
        tags=TAGS,
        request=ImportRequestSerializer,
        responses={200: ImportResultSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "The one-time upload of the phone's own memories (`companion_memories` and "
            f"`memories` rows), at most {importer.BATCH_MAX} a call. Idempotent by store and "
            "origin_guid. Every row becomes private to the caller."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = ImportRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        result = importer.import_rows(request, serializer.validated_data["rows"])
        return Response(result)
