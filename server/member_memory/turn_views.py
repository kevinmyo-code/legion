"""`/api/member-memories/turns`: each conversation turn, saved as it ends (issue #450).

`POST` saves one finished turn (`member_memory.turns.save_turn` decides what is kept), `GET` lists
the caller's own newest turns, and `DELETE ?session=` forgets one conversation's turns (a hard
delete, as forgetting a memory is). Authentication is the API default; every route runs
`service.refusal` first (recruiters, the work laptop) and reads through `visible()`, so another
member's turns are never in an answer.

`POST /turns/end` says a conversation ended (issue #451): the engine writes its summary at once and
`GET /summaries` lists the caller's own newest ones.

A turn that is not kept is a 200 with `kept: false` and the reason in words, never an error: the
client must not retry it. Nothing here logs turn text.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.tenancy import household_of
from member_memory import summaries, turns
from member_memory.models import (
    SUMMARY_ENDED_BY,
    SUMMARY_ENDED_CLOSED,
    SUMMARY_ENDED_IDLE,
    TURN_SESSION_MAX_CHARS,
    TURN_SOURCE_PHONE,
    TURN_SOURCES,
)
from member_memory.views import _bad, _refused

TAGS = ["member-memories"]
TOOLS_MAX = 50


class TurnSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    session = serializers.CharField()
    turn = serializers.IntegerField()
    source = serializers.ChoiceField(choices=TURN_SOURCES)
    user_text = serializers.CharField(allow_blank=True)
    assistant_text = serializers.CharField(allow_blank=True)
    tools = serializers.ListField(child=serializers.CharField(), help_text="Names only.")
    spoken_at = serializers.DateTimeField()


class TurnListSerializer(serializers.Serializer):
    results = TurnSerializer(many=True)


class TurnSaveSerializer(serializers.Serializer):
    session = serializers.CharField(
        max_length=TURN_SESSION_MAX_CHARS, help_text="The client's own id for the conversation."
    )
    turn = serializers.IntegerField(min_value=0, help_text="Counted from 0 within the session.")
    source = serializers.ChoiceField(choices=TURN_SOURCES, default=TURN_SOURCE_PHONE)
    user_text = serializers.CharField(
        allow_blank=True, required=False, default="", trim_whitespace=False
    )
    assistant_text = serializers.CharField(
        allow_blank=True,
        required=False,
        default="",
        trim_whitespace=False,
        help_text="What was spoken back, a tool turn's included. Never a tool's result.",
    )
    tools = serializers.ListField(
        child=serializers.CharField(max_length=100),
        required=False,
        default=list,
        max_length=TOOLS_MAX,
        help_text="The names of the tools the turn called, never arguments.",
    )
    read_through = serializers.BooleanField(
        default=False,
        help_text="The client saw third-party text in this turn; the turn is then not kept.",
    )
    spoken_at = serializers.DateTimeField(required=False, allow_null=True, default=None)


class TurnSavedSerializer(serializers.Serializer):
    kept = serializers.BooleanField()
    reason = serializers.CharField(
        allow_null=True,
        help_text="Why it was not kept: read_through, crisis, sensitive or empty. Null when kept.",
    )
    detail = serializers.CharField(allow_null=True, help_text="The reason in words.")
    turn = TurnSerializer(allow_null=True)


class TurnForgetSerializer(serializers.Serializer):
    deleted = serializers.IntegerField()


class ConversationTurnsView(APIView):
    @extend_schema(
        operation_id="api_member_memories_turns_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter("session", str, description="Only this conversation's turns."),
            OpenApiParameter(
                "limit",
                int,
                description=f"Default {turns.RECENT_LIMIT_DEFAULT}, max {turns.RECENT_LIMIT_MAX}.",
            ),
        ],
        responses={200: TurnListSerializer, 403: DetailSerializer},
        description="The caller's own conversation turns, newest first.",
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        try:
            limit = int(request.query_params.get("limit") or turns.RECENT_LIMIT_DEFAULT)
        except ValueError:
            limit = turns.RECENT_LIMIT_DEFAULT
        session = (request.query_params.get("session") or "").strip() or None
        rows = turns.recent(request, session=session, limit=limit)
        return Response({"results": [turns.as_dict(row) for row in rows]})

    @extend_schema(
        operation_id="api_member_memories_turns_save",
        tags=TAGS,
        request=TurnSaveSerializer,
        responses={
            200: TurnSavedSerializer,
            201: TurnSavedSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
        },
        description=(
            "Saves one finished turn for the caller the moment it ends. Saving the same session "
            "and turn again rewrites it (200). A turn that read third-party text, holds a crisis "
            "phrase (every turn of that session is then deleted), or looks like it holds an "
            "identity or bank number is not kept: 200 with `kept: false`, never retry it."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = TurnSaveSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        data = serializer.validated_data
        saved = turns.save_turn(
            request,
            session=data["session"],
            turn=data["turn"],
            source=data["source"],
            user_text=data["user_text"],
            assistant_text=data["assistant_text"],
            tools=data["tools"],
            read_through=data["read_through"],
            spoken_at=data["spoken_at"],
        )
        body = {
            "kept": saved.kept,
            "reason": saved.reason,
            "detail": saved.sentence,
            "turn": turns.as_dict(saved.row) if saved.row is not None else None,
        }
        code = status.HTTP_201_CREATED if saved.created else status.HTTP_200_OK
        return Response(body, status=code)

    @extend_schema(
        operation_id="api_member_memories_turns_forget",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "session", str, required=True, description="The conversation to forget."
            ),
        ],
        responses={200: TurnForgetSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description="Deletes every turn of one of the caller's conversations, for good.",
    )
    def delete(self, request):
        refused = _refused(request)
        if refused:
            return refused
        session = (request.query_params.get("session") or "").strip()
        if not session:
            return Response(
                {"detail": "Nothing was deleted. Name the conversation with ?session=."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        return Response({"deleted": turns.forget_session(request, session)})


# Issue #451. `idle` is the engine's own word for a chat whose end never arrived.
CLIENT_ENDED_BY = tuple(word for word in SUMMARY_ENDED_BY if word != SUMMARY_ENDED_IDLE)
SUMMARIES_LIMIT_DEFAULT = 10
SUMMARIES_LIMIT_MAX = 50


class SessionEndSerializer(serializers.Serializer):
    session = serializers.CharField(
        max_length=TURN_SESSION_MAX_CHARS, help_text="The client's own id for the conversation."
    )
    ended_by = serializers.ChoiceField(
        choices=CLIENT_ENDED_BY,
        default=SUMMARY_ENDED_CLOSED,
        help_text="closed (by hand), timeout (the idle auto-close) or dropped (a failure).",
    )


class SummarySerializer(serializers.Serializer):
    id = serializers.UUIDField()
    session = serializers.CharField()
    source = serializers.ChoiceField(choices=TURN_SOURCES)
    text = serializers.CharField()
    ended_by = serializers.ChoiceField(choices=SUMMARY_ENDED_BY)
    turns_read = serializers.IntegerField()
    started_at = serializers.DateTimeField()
    ended_at = serializers.DateTimeField()


class SummaryListSerializer(serializers.Serializer):
    results = SummarySerializer(many=True)


class SessionEndedSerializer(serializers.Serializer):
    summarized = serializers.BooleanField()
    reason = serializers.CharField(
        allow_null=True,
        help_text=(
            "Why there is no summary: no_turns, no_words, no_key, failed, sensitive or forgotten. "
            "Null when there is one."
        ),
    )
    detail = serializers.CharField(allow_null=True, help_text="The reason in words.")
    summary = SummarySerializer(allow_null=True)


class SessionEndView(APIView):
    @extend_schema(
        operation_id="api_member_memories_turns_end",
        tags=TAGS,
        request=SessionEndSerializer,
        responses={200: SessionEndedSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "Says one of the caller's conversations ended, by hand, by timeout or by a failure. "
            "The engine writes a short summary of it from its saved turns before answering, then "
            "summarizes any other of the caller's chats whose end never arrived. No summary is a "
            "200 with `summarized: false` and the reason; never retry it, the engine tries again "
            "itself."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        serializer = SessionEndSerializer(data=request.data)
        if not serializer.is_valid():
            return _bad(serializer)
        data = serializer.validated_data
        household = household_of(request)
        outcome = summaries.summarize(
            household_id=household.pk,
            member_id=request.user.pk,
            session=data["session"],
            ended_by=data["ended_by"],
        )
        if outcome.reason != summaries.NOT_WRITTEN_NO_KEY:
            summaries.sweep(
                household=household,
                member=request.user,
                exclude_session=data["session"],
                limit=summaries.SWEEP_ON_END,
            )
        return Response(
            {
                "summarized": outcome.written,
                "reason": outcome.reason,
                "detail": outcome.sentence,
                "summary": summaries.as_dict(outcome.row) if outcome.row is not None else None,
            }
        )


class ConversationSummariesView(APIView):
    @extend_schema(
        operation_id="api_member_memories_summaries_list",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "limit",
                int,
                description=f"Default {SUMMARIES_LIMIT_DEFAULT}, max {SUMMARIES_LIMIT_MAX}.",
            ),
        ],
        responses={200: SummaryListSerializer, 403: DetailSerializer},
        description="The caller's own conversation summaries, the most recently ended first.",
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        try:
            limit = int(request.query_params.get("limit") or SUMMARIES_LIMIT_DEFAULT)
        except ValueError:
            limit = SUMMARIES_LIMIT_DEFAULT
        limit = max(1, min(limit, SUMMARIES_LIMIT_MAX))
        rows = turns.own_summaries(request).order_by("-ended_at")[:limit]
        return Response({"results": [summaries.as_dict(row) for row in rows]})
