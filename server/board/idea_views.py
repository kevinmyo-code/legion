"""`/api/board/ideas*`: backlog intake, DARK with the rest of the board (`board.flags`).

Kevin, 2026-11-22: "yes score each idea", "read it back first" (`board.ideas` holds the rules).

- `POST /api/board/ideas`: the idea in the member's words: cleaned, scored (`board.rubric`) and
  stored as a DRAFT, with the read-back a voice says. Device token or browser session; never a
  recruiter. 20 drafts a day per member (`board.ideas.DRAFTS_PER_DAY`, and `board_idea` here).
- `POST /api/board/ideas/<id>/confirm`: his yes; the draft he heard becomes pending.
- `DELETE /api/board/ideas/<id>`: his no; the draft is dropped.
- `GET /api/board/ideas/pending` and `POST /api/board/ideas/<id>/delivered`: the laptop's board
  writer token ONLY (`writer_methods`), so `board.sh intake` turns pending ideas into queued cards.
  A member's own sign-in is refused there, and the writer token reaches nothing else.

Logs carry counts only, never an idea's words.
"""

from __future__ import annotations

import logging

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle

from api.schema import DetailSerializer
from board import ideas as board_ideas
from board.authentication import BoardWriterAuthentication
from board.models import BoardIdea, BoardSnapshot
from board.views import TAGS, WRITER_ONLY, BoardRiceSerializer, _OffBeforeAnything
from household.authentication import DeviceTokenAuthentication
from household.models import is_recruiter
from household.permissions import IsHouseholdMember
from household.tenancy import household_of

logger = logging.getLogger(__name__)

NOT_FOR_RECRUITER = "Nothing was added. A demo visit has no laptop board."
OFF = OpenApiResponse(DetailSerializer, description="The board is switched off.")


class IdeaThrottle(SimpleRateThrottle):
    """Per member, on the draft only: each draft is one model call."""

    scope = "board_idea"

    def get_cache_key(self, request, view):
        if request.method != "POST" or not request.user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": request.user.pk}


class IdeaDraftRequestSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=2000, help_text="The idea in the member's own words.")


class IdeaDraftSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    title = serializers.CharField()
    scored = serializers.BooleanField()
    rice = BoardRiceSerializer(allow_null=True)
    blast = serializers.CharField(allow_blank=True)
    readback = serializers.CharField(
        help_text="What a voice says before the yes, word for word: title, score, question."
    )


class IdeaWordsSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    words = serializers.CharField(help_text="What a voice says, word for word.")


class PendingIdeaSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    title = serializers.CharField()
    text = serializers.CharField()
    rice = BoardRiceSerializer(allow_null=True)
    blast = serializers.CharField(allow_blank=True)
    scored_by = serializers.CharField(allow_blank=True)
    scored = serializers.BooleanField()
    confirmed_at = serializers.DateTimeField(allow_null=True)


class PendingIdeasSerializer(serializers.Serializer):
    ideas = PendingIdeaSerializer(many=True)


def draft_payload(idea: BoardIdea) -> dict:
    scores = idea.scores or {}
    return {
        "id": idea.id,
        "card_id": idea.card_id,
        "title": idea.title,
        "scored": bool(scores.get("scored")),
        "rice": scores.get("rice"),
        "blast": scores.get("blast") or "",
        "readback": board_ideas.readback(idea),
    }


def _words(idea: BoardIdea, words: str) -> Response:
    return Response({"id": idea.id, "card_id": idea.card_id, "words": words})


def _refused(exc: Exception) -> Response:
    return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)


class _MemberIdeaView(_OffBeforeAnything):
    authentication_classes = [DeviceTokenAuthentication, SessionAuthentication]
    permission_classes = [IsHouseholdMember]

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        if is_recruiter(request.user):
            raise exceptions.PermissionDenied(NOT_FOR_RECRUITER)


class IdeaListView(_MemberIdeaView):
    throttle_classes = [IdeaThrottle]

    @extend_schema(
        operation_id="api_board_ideas_create",
        tags=TAGS,
        request=IdeaDraftRequestSerializer,
        responses={
            201: OpenApiResponse(IdeaDraftSerializer, description="Drafted; nothing saved yet."),
            400: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
            429: DetailSerializer,
        },
    )
    def post(self, request):
        body = IdeaDraftRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": board_ideas.EMPTY}, status=status.HTTP_400_BAD_REQUEST)
        try:
            idea = board_ideas.draft(
                household=household_of(request),
                owner_user=request.user,
                text=body.validated_data["text"],
            )
        except board_ideas.Refused as exc:
            return _refused(exc)
        logger.info("board idea drafted: scored=%s", bool(idea.scores.get("scored")))
        return Response(draft_payload(idea), status=status.HTTP_201_CREATED)


class IdeaConfirmView(_MemberIdeaView):
    @extend_schema(
        operation_id="api_board_ideas_confirm",
        tags=TAGS,
        request=None,
        responses={
            200: IdeaWordsSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
        },
    )
    def post(self, request, idea_id):
        try:
            idea = board_ideas.confirm(owner_user=request.user, idea_id=idea_id)
        except board_ideas.Refused as exc:
            return _refused(exc)
        return _words(idea, board_ideas.SAVED)


class IdeaDetailView(_MemberIdeaView):
    @extend_schema(
        operation_id="api_board_ideas_destroy",
        tags=TAGS,
        responses={
            200: IdeaWordsSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
        },
    )
    def delete(self, request, idea_id):
        try:
            idea = board_ideas.discard(owner_user=request.user, idea_id=idea_id)
        except board_ideas.Refused as exc:
            return _refused(exc)
        return _words(idea, board_ideas.DROPPED)


class _WriterIdeaView(_OffBeforeAnything):
    """The laptop's writer token only: a member's own sign-in is refused here."""

    authentication_classes = [BoardWriterAuthentication]
    permission_classes = [IsHouseholdMember]

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        if not isinstance(request.auth, BoardSnapshot):
            raise exceptions.NotAuthenticated(WRITER_ONLY)


class PendingIdeasView(_WriterIdeaView):
    writer_methods = ("GET",)

    @extend_schema(
        operation_id="api_board_ideas_pending",
        tags=TAGS,
        responses={
            200: PendingIdeasSerializer,
            401: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
        },
    )
    def get(self, request):
        found = board_ideas.pending_for(request.user)
        logger.info("board ideas pulled: %d pending", len(found))
        return Response({"ideas": [board_ideas.as_card(i) for i in found]})


class DeliveredIdeaView(_WriterIdeaView):
    writer_methods = ("POST",)

    @extend_schema(
        operation_id="api_board_ideas_delivered",
        tags=TAGS,
        request=None,
        responses={
            200: IdeaWordsSerializer,
            400: DetailSerializer,
            401: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
        },
    )
    def post(self, request, idea_id):
        try:
            idea = board_ideas.mark_delivered(owner_user=request.user, idea_id=idea_id)
        except board_ideas.Refused as exc:
            return _refused(exc)
        return _words(idea, "Delivered.")
