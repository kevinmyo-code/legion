"""`GET /api/assistant/pack`: the Alfred pack, for the phone (alfred-ota ticket 01).

The phone reads it at session start with its household device token (any member; the API's
default authentication and `IsHouseholdMember`), and keeps the bundled pack when the engine is
down or refuses, which is ticket 02's half. This door only serves what `assistant.pack.current()`
loaded from the committed `packs/alfred.yaml`; nothing here writes.

- `ETag` is the pack's `sha256`; a request whose `If-None-Match` names it gets a 304 and no body.
- A recruiter's visit is refused (`household.recruiter_routes.RECRUITER_DENIED`): a visitor talks
  through the web assistant, whose prompt the engine already builds.
- A pack the engine refuses to load (a locked name, over 64 KB, a malformed file) is answered with
  a 503 saying so, never a partial pack; `tests/test_assistant_pack.py` keeps that from shipping.
"""

from __future__ import annotations

from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import (
    OpenApiParameter,
    OpenApiResponse,
    extend_schema,
    inline_serializer,
)
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from assistant import pack as alfred_pack

PACK_CLIENTS = ("phone",)

ToolTextSerializer = inline_serializer(
    name="AssistantPackToolText",
    fields={
        "description": serializers.CharField(required=False),
        "result_prefix": serializers.CharField(required=False),
    },
)

PackResponse = inline_serializer(
    name="AssistantPack",
    fields={
        "version": serializers.IntegerField(help_text="Bumped on every change to the wording."),
        "sha256": serializers.CharField(
            help_text="Hash of this payload (version included); also the ETag."
        ),
        "clauses": serializers.DictField(
            child=serializers.CharField(),
            help_text=(
                "Prompt clauses by the names in shared_clauses.txt, plus pack-only ones. Never "
                "the locked set (CRISIS, SAFETY_INSTRUCTIONS, CANNOT_CLAUSE, ASK_BEFORE, "
                "NO_PASSWORDS); the app ignores those if sent."
            ),
        ),
        "tool_text": serializers.DictField(
            child=ToolTextSerializer, help_text="Tool name to wording overrides."
        ),
        "flags": serializers.DictField(
            child=serializers.JSONField(), help_text="Name to true/false or a whole number."
        ),
        "extra_rules": serializers.ListField(
            child=serializers.CharField(), help_text="Short rules appended to the prompt."
        ),
    },
)


def _etag_matches(header: str | None, sha256: str) -> bool:
    if not header:
        return False
    for tag in header.split(","):
        tag = tag.strip()
        if tag == "*":
            return True
        if tag.startswith("W/"):
            tag = tag[2:]
        if tag.strip('"') == sha256:
            return True
    return False


class AssistantPackView(APIView):
    @extend_schema(
        tags=["assistant"],
        operation_id="api_assistant_pack_retrieve",
        summary="The Alfred pack: versioned wording the phone reads at session start",
        parameters=[
            OpenApiParameter(
                "client",
                OpenApiTypes.STR,
                enum=list(PACK_CLIENTS),
                required=False,
                description="Which client asks. Only `phone` today, and the default.",
            ),
            OpenApiParameter(
                "If-None-Match",
                OpenApiTypes.STR,
                location=OpenApiParameter.HEADER,
                required=False,
                description="The ETag the client holds; a match answers 304 with no body.",
            ),
        ],
        responses={
            200: PackResponse,
            304: OpenApiResponse(description="The client's pack is current. No body."),
            400: DetailSerializer,
            403: DetailSerializer,
            503: OpenApiResponse(
                response=DetailSerializer,
                description="The engine refused its own pack, so none was sent.",
            ),
        },
    )
    def get(self, request):
        client = request.query_params.get("client", "phone")
        if client not in PACK_CLIENTS:
            return Response(
                {
                    "detail": (
                        f"No pack was sent. There is no pack for client {client!r}; "
                        f"ask for one of: {', '.join(PACK_CLIENTS)}."
                    )
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        try:
            pack = alfred_pack.current()
        except (alfred_pack.PackError, OSError) as exc:
            return Response(
                {
                    "detail": (
                        f"No pack was sent. The engine refused its own Alfred pack: {exc} "
                        f"The phone keeps the wording it was built with."
                    )
                },
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        etag = f'"{pack.sha256}"'
        if _etag_matches(request.headers.get("If-None-Match"), pack.sha256):
            response = Response(status=status.HTTP_304_NOT_MODIFIED)
        else:
            response = Response(pack.payload())
        response["ETag"] = etag
        response["Cache-Control"] = "private, no-cache"
        return response
