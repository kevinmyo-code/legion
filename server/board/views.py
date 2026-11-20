"""`/api/board*`: the factory board's off-laptop copy, DARK.

Every route here is a 404 before authentication unless `LEGION_BOARD=on` (`board.flags`).

- `GET /api/board`: the calling member's own latest snapshot, its freshness and the lines a voice
  says (`board.reading`). Device token or browser session. Never a recruiter (`recruiter_routes`),
  never a head-unit token (not on its list), never the board writer token.
- `PUT /api/board`: the laptop sends `board.json` as it is. The board writer token ONLY
  (`board.authentication`). Cleaned (`board.cards.clean_board`), capped at 64 KB, latest only.
  10 a minute per writer.
- `POST /api/board/writer`: a signed-in member mints the laptop's writer token, shown once; any
  earlier one dies now. Browser session or a write device token; 5 an hour per member.
- `DELETE /api/board/writer`: revoke it. The snapshot stays readable.

Logs carry sizes, counts and ages only, never card text.
"""

from __future__ import annotations

import json
import logging

from django.db import transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from board import cards as board_cards
from board import flags, reading
from board.authentication import BoardWriterAuthentication
from board.models import BoardSnapshot
from household.authentication import DeviceTokenAuthentication
from household.models import is_recruiter
from household.permissions import IsHouseholdMember
from household.tenancy import household_of

logger = logging.getLogger(__name__)

TAGS = ["board"]
OFF_WORDS = (
    "Nothing was done: the factory board is switched off on this server (LEGION_BOARD is not on)."
)
WRITER_ONLY = (
    "Nothing was stored. The board is sent by the laptop with its board writer token, not with a "
    "member's own sign-in."
)
TOO_BIG = f"Nothing was stored. The board is larger than {board_cards.MAX_BYTES // 1024} KB."
NOT_JSON = "Nothing was stored. The body is not JSON."
NOT_FOR_RECRUITER = "Nothing was issued. A demo visit has no laptop board."
NO_WRITER = "Nothing was revoked: no board writer token is live for you."


class _OffBeforeAnything(APIView):
    def initial(self, request, *args, **kwargs):
        if not flags.board_enabled():
            raise exceptions.NotFound(OFF_WORDS)
        super().initial(request, *args, **kwargs)


class PushThrottle(SimpleRateThrottle):
    """Per writer row, for PUT only."""

    scope = "board_push"

    def get_cache_key(self, request, view):
        if request.method != "PUT" or not isinstance(request.auth, BoardSnapshot):
            return None
        return self.cache_format % {"scope": self.scope, "ident": request.auth.pk}


class MintThrottle(SimpleRateThrottle):
    scope = "board_mint"

    def get_cache_key(self, request, view):
        if request.method != "POST" or not request.user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": request.user.pk}


class BoardCardSerializer(serializers.Serializer):
    id = serializers.CharField()
    col = serializers.ChoiceField(choices=list(board_cards.COLS))
    kind = serializers.ChoiceField(choices=list(board_cards.KINDS))
    title = serializers.CharField()
    status = serializers.CharField(allow_blank=True)
    waiting = serializers.CharField(allow_blank=True)
    owner = serializers.CharField(allow_blank=True)
    ask = serializers.CharField(allow_blank=True)
    age_min = serializers.IntegerField(help_text="Minutes since the card changed, as of now.")
    stale = serializers.BooleanField()


class BoardSnapshotSerializer(serializers.Serializer):
    generated_at = serializers.DateTimeField(
        allow_null=True, help_text="The laptop's own clock: display only."
    )
    received_at = serializers.DateTimeField(help_text="When the engine received it.")
    build_slot = serializers.CharField(allow_blank=True)
    counts = serializers.DictField(child=serializers.IntegerField())
    needs_kevin = serializers.ListField(child=serializers.CharField())
    cards = BoardCardSerializer(many=True)


class BoardWriterInfoSerializer(serializers.Serializer):
    created_at = serializers.DateTimeField()
    last_used_at = serializers.DateTimeField(allow_null=True)


class BoardSerializer(serializers.Serializer):
    snapshot = BoardSnapshotSerializer(allow_null=True)
    live = serializers.BooleanField(help_text="Received within the last 3 minutes.")
    age_s = serializers.IntegerField(allow_null=True)
    laptop_seen_at = serializers.DateTimeField(allow_null=True)
    laptop_on = serializers.BooleanField()
    writer = BoardWriterInfoSerializer(allow_null=True)
    spoken = serializers.ListField(
        child=serializers.CharField(), help_text="Lines a voice says word for word, built by code."
    )


class BoardStoredSerializer(serializers.Serializer):
    cards = serializers.IntegerField()
    bytes = serializers.IntegerField()
    received_at = serializers.DateTimeField()


class BoardWriterIssuedSerializer(serializers.Serializer):
    token = serializers.CharField(help_text="Shown once. The engine keeps only its hash.")
    created_at = serializers.DateTimeField()


def _payload(request, now=None) -> dict:
    r = reading.read(request, now)
    row = r.row
    snapshot = None
    if row is not None and row.received_at is not None:
        cards = reading.cards_now(r)
        snapshot = {
            "generated_at": row.generated_at,
            "received_at": row.received_at,
            "build_slot": row.build_slot,
            "counts": board_cards.counts(cards),
            "needs_kevin": [c["id"] for c in board_cards.needs_kevin(cards)],
            "cards": cards,
        }
    writer = None
    if row is not None and row.writer_key_hash:
        writer = {"created_at": row.writer_created_at, "last_used_at": row.writer_last_used_at}
    return {
        "snapshot": snapshot,
        "live": r.live,
        "age_s": r.age_s,
        "laptop_seen_at": r.laptop_seen_at,
        "laptop_on": r.laptop_on,
        "writer": writer,
        "spoken": reading.spoken_lines(request, r),
    }


class BoardView(_OffBeforeAnything):
    authentication_classes = [
        BoardWriterAuthentication,
        DeviceTokenAuthentication,
        SessionAuthentication,
    ]
    permission_classes = [IsHouseholdMember]
    throttle_classes = [PushThrottle]

    @extend_schema(
        operation_id="api_board_retrieve",
        tags=TAGS,
        responses={
            200: BoardSerializer,
            404: OpenApiResponse(DetailSerializer, description="The board is switched off."),
        },
    )
    def get(self, request):
        return Response(BoardSerializer(_payload(request)).data)

    @extend_schema(
        operation_id="api_board_update",
        tags=TAGS,
        request={"application/json": {"type": "object"}},
        responses={
            200: BoardStoredSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="The board is switched off."),
            413: DetailSerializer,
            429: DetailSerializer,
        },
    )
    def put(self, request):
        row = request.auth
        if not isinstance(row, BoardSnapshot):
            return Response({"detail": WRITER_ONLY}, status=status.HTTP_403_FORBIDDEN)
        raw = request.body
        if len(raw) > board_cards.MAX_BYTES:
            logger.info("board push refused: %d bytes", len(raw))
            return Response({"detail": TOO_BIG}, status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE)
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return Response({"detail": NOT_JSON}, status=status.HTTP_400_BAD_REQUEST)
        try:
            clean = board_cards.clean_board(body)
        except board_cards.Refused as exc:
            logger.info("board push refused: %d bytes, shape", len(raw))
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)
        now = timezone.now()
        BoardSnapshot.objects.filter(pk=row.pk).update(
            cards=clean.cards,
            build_slot=clean.build_slot,
            generated_at=clean.generated_at,
            received_at=now,
            size_bytes=len(raw),
        )
        logger.info("board push stored: %d bytes, %d cards", len(raw), len(clean.cards))
        return Response({"cards": len(clean.cards), "bytes": len(raw), "received_at": now})


class BoardWriterView(_OffBeforeAnything):
    authentication_classes = [DeviceTokenAuthentication, SessionAuthentication]
    permission_classes = [IsHouseholdMember]
    throttle_classes = [MintThrottle]

    @extend_schema(
        operation_id="api_board_writer_create",
        tags=TAGS,
        request=None,
        responses={
            201: OpenApiResponse(
                BoardWriterIssuedSerializer, description="The token, shown this once."
            ),
            403: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="The board is switched off."),
            429: DetailSerializer,
        },
    )
    def post(self, request):
        if is_recruiter(request.user):
            return Response({"detail": NOT_FOR_RECRUITER}, status=status.HTTP_403_FORBIDDEN)
        with transaction.atomic():
            row, raw = BoardSnapshot.mint_writer(
                household=household_of(request), owner_user=request.user
            )
        return Response(
            {"token": raw, "created_at": row.writer_created_at}, status=status.HTTP_201_CREATED
        )

    @extend_schema(
        operation_id="api_board_writer_destroy",
        tags=TAGS,
        responses={
            204: OpenApiResponse(description="Revoked: the laptop can no longer send the board."),
            404: DetailSerializer,
        },
    )
    def delete(self, request):
        count = BoardSnapshot.objects.filter(
            owner_user=request.user, writer_key_hash__isnull=False
        ).update(writer_key_hash=None, writer_created_at=None, writer_last_used_at=None)
        if not count:
            return Response({"detail": NO_WRITER}, status=status.HTTP_404_NOT_FOUND)
        return Response(status=status.HTTP_204_NO_CONTENT)
