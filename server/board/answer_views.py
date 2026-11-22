"""`/api/board/answers`, `/api/board/votes`, `/api/board/inbox*`: the control room's Decisions and
backlog votes (C2), DARK with the rest of the board (`board.flags`). The rules are `board.asks`.

- `POST /api/board/answers`: his answer to one question: `card_id`, the `ask_sha256` the phone
  showed, a `choice` and/or `text`. 201 taken; 409 the question changed or already has an answer;
  410 the card left the board or stopped asking; 400 refused in words (a deploy, publish, flag or
  spend is answered in chat). Device token or browser session; never a recruiter.
- `POST /api/board/votes`: his vote on a backlog idea (`p1`..`p3`, `up`, `down`, `clear`).
- `GET /api/board/inbox` and `POST /api/board/inbox/delivered`: the laptop's writer token ONLY
  (`writer_methods`). It pulls answers and votes, applies each answer only while its own card still
  has the same hash, and reports each one `applied` or `refused`. No inbound connection to the
  laptop: everything waits here until it pulls.

Logs carry counts only, never an answer's words.
"""

from __future__ import annotations

import logging

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response

from api.schema import DetailSerializer
from board import asks
from board.idea_views import OFF, _MemberIdeaView, _WriterIdeaView
from board.models import VOTES
from board.views import TAGS
from household.tenancy import household_of

logger = logging.getLogger(__name__)


class AnswerRequestSerializer(serializers.Serializer):
    card_id = serializers.CharField(max_length=60)
    ask_sha256 = serializers.CharField(max_length=64)
    choice = serializers.CharField(max_length=200, required=False, allow_blank=True, default="")
    text = serializers.CharField(max_length=2000, required=False, allow_blank=True, default="")


class AnswerTakenSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    words = serializers.CharField(help_text="What the screen says, word for word.")


class VoteRequestSerializer(serializers.Serializer):
    card_id = serializers.CharField(max_length=60)
    vote = serializers.ChoiceField(choices=list(VOTES))


class VoteTakenSerializer(serializers.Serializer):
    card_id = serializers.CharField()
    vote = serializers.CharField()


class InboxAnswerSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    ask_sha256 = serializers.CharField()
    choice = serializers.CharField(allow_blank=True)
    text = serializers.CharField(allow_blank=True)
    at = serializers.DateTimeField()


class InboxVoteSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    card_id = serializers.CharField()
    vote = serializers.CharField()
    at = serializers.DateTimeField()


class InboxSerializer(serializers.Serializer):
    answers = InboxAnswerSerializer(many=True)
    votes = InboxVoteSerializer(many=True)


class DeliveredAnswerSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    outcome = serializers.ChoiceField(choices=["applied", "refused"])


class DeliveredRequestSerializer(serializers.Serializer):
    answers = DeliveredAnswerSerializer(many=True, required=False, default=list)
    votes = serializers.ListField(child=serializers.UUIDField(), required=False, default=list)


class DeliveredSerializer(serializers.Serializer):
    answers = serializers.IntegerField()
    votes = serializers.IntegerField()


TAKEN = "Sent to the laptop. The agent picks it up the next time it checks the board."
BAD_BODY = "Nothing was sent. The answer needs the card, its question's hash and a choice or text."


def _refused(exc: asks.Refused) -> Response:
    return Response({"detail": str(exc)}, status=exc.status)


class AnswerListView(_MemberIdeaView):
    @extend_schema(
        operation_id="api_board_answers_create",
        tags=TAGS,
        request=AnswerRequestSerializer,
        responses={
            201: AnswerTakenSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
            409: OpenApiResponse(DetailSerializer, description="Changed, or already answered."),
            410: OpenApiResponse(DetailSerializer, description="The card left or stopped asking."),
        },
    )
    def post(self, request):
        body = AnswerRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": BAD_BODY}, status=status.HTTP_400_BAD_REQUEST)
        data = body.validated_data
        try:
            answer = asks.record_answer(
                household=household_of(request),
                owner_user=request.user,
                card_id=data["card_id"],
                ask_sha256=data["ask_sha256"],
                choice=data["choice"],
                text=data["text"],
            )
        except asks.Refused as exc:
            logger.info("board answer refused: %d", exc.status)
            return _refused(exc)
        logger.info("board answer taken")
        return Response(
            {"id": answer.id, "card_id": answer.card_id, "words": TAKEN},
            status=status.HTTP_201_CREATED,
        )


class VoteListView(_MemberIdeaView):
    @extend_schema(
        operation_id="api_board_votes_create",
        tags=TAGS,
        request=VoteRequestSerializer,
        responses={
            201: VoteTakenSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
            410: DetailSerializer,
        },
    )
    def post(self, request):
        body = VoteRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": asks.BAD_VOTE}, status=status.HTTP_400_BAD_REQUEST)
        try:
            vote = asks.record_vote(
                household=household_of(request),
                owner_user=request.user,
                card_id=body.validated_data["card_id"],
                vote=body.validated_data["vote"],
            )
        except asks.Refused as exc:
            return _refused(exc)
        return Response(
            {"card_id": vote.card_id, "vote": vote.vote}, status=status.HTTP_201_CREATED
        )


class InboxView(_WriterIdeaView):
    writer_methods = ("GET",)

    @extend_schema(
        operation_id="api_board_inbox",
        tags=TAGS,
        responses={200: InboxSerializer, 401: DetailSerializer, 403: DetailSerializer, 404: OFF},
    )
    def get(self, request):
        found = asks.inbox(request.user)
        logger.info(
            "board inbox pulled: %d answers, %d votes", len(found["answers"]), len(found["votes"])
        )
        return Response(found)


class InboxDeliveredView(_WriterIdeaView):
    writer_methods = ("POST",)

    @extend_schema(
        operation_id="api_board_inbox_delivered",
        tags=TAGS,
        request=DeliveredRequestSerializer,
        responses={
            200: DeliveredSerializer,
            400: DetailSerializer,
            401: DetailSerializer,
            403: DetailSerializer,
            404: OFF,
        },
    )
    def post(self, request):
        body = DeliveredRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": "Nothing was marked. The body is not a delivery report."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        data = body.validated_data
        answers = [{"id": a["id"], "outcome": a["outcome"]} for a in data["answers"]]
        return Response(asks.mark_delivered(request.user, answers, data["votes"]))
