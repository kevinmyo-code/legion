"""`/api/member-memories/turns`: each conversation turn, saved as it happens (ticket 450).

POST is the phone's per-turn save (`member_memory.turns.save` decides what is kept); GET reads the
caller's own recent turns back, which is what a new chat (ticket 452) and the summary on close
(ticket 451) are built on. Both run `service.refusal` first (recruiters, the work laptop) and read
through `visible()`: another member's turn does not exist for the caller.
"""

from __future__ import annotations

import uuid

from django.utils.dateparse import parse_datetime
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.tenancy import visible
from member_memory import service, turns
from member_memory.models import SURFACE_VOICE, SURFACES, ConversationTurn

TAGS = ["member-memories"]


class ToolCallSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=turns.NAME_MAX_CHARS)
    ok = serializers.BooleanField(default=True)


class TurnWriteSerializer(serializers.Serializer):
    chat_id = serializers.UUIDField(help_text="One chat: a Live session or a typed chat.")
    seq = serializers.IntegerField(
        min_value=0, max_value=turns.SEQ_MAX, help_text="The turn's place in the chat, from 0."
    )
    surface = serializers.ChoiceField(choices=SURFACES, default=SURFACE_VOICE)
    user_text = serializers.CharField(
        required=False, allow_null=True, allow_blank=True, max_length=turns.TEXT_ACCEPT_CHARS
    )
    tool_calls = ToolCallSerializer(
        many=True, required=False, max_length=turns.MAX_TOOL_CALLS_ACCEPTED
    )
    interrupted = serializers.BooleanField(
        required=False, default=False, help_text="The turn was cut off before it finished."
    )
    spoken_at = serializers.DateTimeField(required=False, allow_null=True)


class TurnSavedSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    trimmed = serializers.BooleanField(
        help_text="True when his words were cut or tool calls past the cap were dropped."
    )


class TurnSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    chat_id = serializers.UUIDField()
    seq = serializers.IntegerField()
    surface = serializers.ChoiceField(choices=SURFACES)
    user_text = serializers.CharField(allow_null=True)
    tool_calls = ToolCallSerializer(many=True)
    trimmed = serializers.BooleanField()
    interrupted = serializers.BooleanField()
    spoken_at = serializers.DateTimeField(allow_null=True)
    received_at = serializers.DateTimeField()


class TurnListSerializer(serializers.Serializer):
    results = TurnSerializer(many=True)


def _refused(request):
    reason = service.refusal(request)
    if reason is None:
        return None
    return Response({"detail": reason}, status=status.HTTP_403_FORBIDDEN)


def _nothing_read(why: str) -> Response:
    return Response({"detail": f"Nothing was read. {why}."}, status=status.HTTP_400_BAD_REQUEST)


def _as_dict(row: ConversationTurn) -> dict:
    return {
        "id": row.id,
        "chat_id": row.chat_id,
        "seq": row.seq,
        "surface": row.surface,
        "user_text": row.user_text,
        "tool_calls": row.tool_calls,
        "trimmed": row.trimmed,
        "interrupted": row.interrupted,
        "spoken_at": row.spoken_at,
        "received_at": row.received_at,
    }


class TurnListCreateView(APIView):
    @extend_schema(
        operation_id="api_member_memories_turns_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter("chat_id", str, description="Only this chat's turns, oldest first."),
            OpenApiParameter(
                "since", str, description="Only turns the engine received after this ISO time."
            ),
            OpenApiParameter(
                "limit",
                int,
                description=f"Default {turns.LIST_LIMIT_DEFAULT}, max {turns.LIST_LIMIT_MAX}.",
            ),
        ],
        responses={200: TurnListSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "The caller's own recent conversation turns, newest first (oldest first within one "
            "`chat_id`). Another member's turns are never returned. A turn is his words and the "
            "names of the tools called, nothing else."
        ),
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        rows = visible(ConversationTurn, request).filter(member=request.user)
        chat_id = request.query_params.get("chat_id")
        if chat_id:
            try:
                uuid.UUID(chat_id)
            except ValueError:
                return _nothing_read("chat_id is not a valid id")
            rows = rows.filter(chat_id=chat_id)
        since = request.query_params.get("since")
        if since:
            try:
                moment = parse_datetime(since)
            except ValueError:
                moment = None
            if moment is None:
                return _nothing_read("since is not an ISO time")
            rows = rows.filter(received_at__gt=moment)
        try:
            limit = int(request.query_params.get("limit") or turns.LIST_LIMIT_DEFAULT)
        except ValueError:
            limit = turns.LIST_LIMIT_DEFAULT
        limit = max(1, min(limit, turns.LIST_LIMIT_MAX))
        ordered = rows.order_by("seq") if chat_id else rows.order_by("-received_at", "-seq")
        return Response({"results": [_as_dict(row) for row in ordered[:limit]]})

    @extend_schema(
        operation_id="api_member_memories_turns_create",
        tags=TAGS,
        request=TurnWriteSerializer,
        responses={
            200: TurnSavedSerializer,
            201: TurnSavedSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            409: DetailSerializer,
        },
        description=(
            "Saves one finished turn for the caller. Idempotent on `(chat_id, seq)`: a repeat is "
            "a 200 with the stored row; the same pair with other content, or from another member, "
            "is a 409 and nothing is overwritten. Only his words and the tool names are stored: "
            "never the assistant's reply or a tool result. Words past 4000 characters and tool "
            "calls past 20 are trimmed and the row marked."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = TurnWriteSerializer(data=request.data)
        if not serializer.is_valid():
            errors = "; ".join(
                f"{field}: {' '.join(str(m) for m in messages)}"
                for field, messages in serializer.errors.items()
            )
            return Response(
                {"detail": f"Nothing was saved. {errors}"}, status=status.HTTP_400_BAD_REQUEST
            )
        try:
            row, created = turns.save(request, serializer.validated_data)
        except turns.TurnConflict:
            return Response(
                {
                    "detail": "Nothing was saved. That chat id and turn number "
                    "already hold a different turn."
                },
                status=status.HTTP_409_CONFLICT,
            )
        return Response(
            {"id": row.id, "trimmed": row.trimmed},
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )
