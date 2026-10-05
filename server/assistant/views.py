"""`/api/assistant/*`: the web assistant's two doors into the engine (web-assistant ticket 07).

- `POST /api/assistant/session` mints a Gemini Live token for the signed-in
  member, carrying a setup the engine assembled and locked (`assistant/live.py`).
- `POST /api/assistant/tool` runs one tool call the browser received from
  Gemini, through the SAME registry `/mcp` runs (`engine_mcp.tools.run_tool`),
  as that member, under their own `visible()`.
- `GET /api/assistant/companion` says who the member talks to.

**Session authentication only, CSRF enforced** (ticket 02). A browser carries
the Django session cookie and `X-CSRFToken`; DRF's `SessionAuthentication`
refuses an unsafe method without the token. No device token is accepted here,
and `/mcp` accepts no session: two doors, each with one key, so a cookie can
never reach `/mcp` and a leaked device token can never mint a voice session.

**Throttled per member, audited per call** (`AssistantCall`): who, when, which
door, which tool, how it ended. Never the prompt, the arguments, a result, the
key or the token.

**The household's key never leaves this module's process** (ADR 0056): it goes
from the environment into one header on one request to Google, and every
answer here is built from Google's reply, never from the key.
"""

from __future__ import annotations

from datetime import UTC, datetime

from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from assistant import live
from assistant.companions import companion_for
from assistant.models import AssistantCall
from assistant.prompt import MAX_OFFSET_MINUTES, MIN_OFFSET_MINUTES
from assistant.session import token_request_for
from assistant.surface import refusal_for
from engine_mcp.models import Outcome
from engine_mcp.tools import run_tool
from household.permissions import IsHouseholdMember
from household.tenancy import household_of
from ingest.statements import gemini_key

ASSISTANT_TAGS = ["assistant"]

KEY_ABSENT = (
    "The assistant isn't set up on this server. Nothing was started: the operator has not set "
    "LEGION_GEMINI_KEY in the engine's environment."
)


class _PerMemberThrottle(SimpleRateThrottle):
    """One budget per signed-in member, so one member's looping tab spends
    their own and never the other's."""

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": user.pk}


class AssistantSessionThrottle(_PerMemberThrottle):
    scope = "assistant_session"


class AssistantToolThrottle(_PerMemberThrottle):
    scope = "assistant_tool"


def _audit(request, kind: str, outcome: str, tool: str | None = None) -> None:
    AssistantCall.objects.create(
        household=household_of(request),
        user=request.user,
        kind=kind,
        tool=tool[:200] if tool else None,
        outcome=outcome,
    )


class _AssistantView(APIView):
    authentication_classes = [SessionAuthentication]
    permission_classes = [IsHouseholdMember]
    audit_kind = ""

    def _audit_tool_name(self, request) -> str | None:
        return None

    def throttled(self, request, wait):
        _audit(request, self.audit_kind, Outcome.THROTTLED, self._audit_tool_name(request))
        raise exceptions.Throttled(
            wait,
            detail=(
                "Nothing was read, written or started. You have asked the assistant for too "
                "much in a short time; the engine refused this before running it."
            ),
        )


# =============================================================================
# POST /api/assistant/session
# =============================================================================


class AssistantSessionRequestSerializer(serializers.Serializer):
    utc_offset_minutes = serializers.IntegerField(
        required=False,
        allow_null=True,
        min_value=MIN_OFFSET_MINUTES,
        max_value=MAX_OFFSET_MINUTES,
        help_text=(
            "Minutes EAST of UTC on the person's own clock: `-new Date().getTimezoneOffset()` "
            "in a browser (Houston in summer is -300). Omit it and the engine uses the "
            "household's timezone, as a current offset, when its owner has set one; "
            "otherwise the assistant is told the offset is unknown. Never an IANA zone id: "
            "the zone's name never reaches the prompt."
        ),
    )


SessionResponse = inline_serializer(
    name="AssistantSession",
    fields={
        "token": serializers.CharField(
            help_text=(
                "The ephemeral token's full name (`auth_tokens/...`). Pass it as the "
                "`access_token` query parameter on `ws_url`. One conversation; the setup it "
                "carries is locked, so the browser's own `setup` message is ignored."
            )
        ),
        "model": serializers.CharField(),
        "ws_url": serializers.CharField(
            help_text="Append `?access_token=<token, URL-encoded>` and open a WebSocket."
        ),
        "expires_at": serializers.DateTimeField(
            help_text="The conversation cannot outlive this (about 30 minutes)."
        ),
        "connect_by": serializers.DateTimeField(
            help_text="The WebSocket must be opened before this (about 60 seconds)."
        ),
        "companion_name": serializers.CharField(),
        "voice_name": serializers.CharField(),
        "input_audio_mime": serializers.CharField(
            help_text="What `realtimeInput.audio.mimeType` must say: 16-bit PCM, 16 kHz, mono."
        ),
        "output_audio_rate": serializers.IntegerField(
            help_text="Sample rate of the 16-bit PCM Gemini sends back."
        ),
    },
)


class AssistantSessionView(_AssistantView):
    throttle_classes = [AssistantSessionThrottle]
    audit_kind = AssistantCall.SESSION

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_session_create",
        summary="Start a live conversation: mint a locked Gemini Live token",
        request=AssistantSessionRequestSerializer,
        responses={
            200: SessionResponse,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            429: OpenApiResponse(DetailSerializer, description="Too many starts; nothing started."),
            502: OpenApiResponse(
                DetailSerializer, description="Google refused the key or the session, in words."
            ),
            503: OpenApiResponse(
                DetailSerializer,
                description=(
                    "No `LEGION_GEMINI_KEY` on this server (\"The assistant isn't set up on this "
                    "server.\"), or Google did not answer."
                ),
            ),
        },
    )
    def post(self, request):
        body = AssistantSessionRequestSerializer(data=request.data)
        if not body.is_valid():
            _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
            return Response(
                {"detail": f"Nothing was started. The request did not fit: {body.errors}"},
                status=status.HTTP_400_BAD_REQUEST,
            )
        key = gemini_key()
        if key is None:
            _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
            return Response({"detail": KEY_ABSENT}, status=status.HTTP_503_SERVICE_UNAVAILABLE)

        token_request, companion = token_request_for(
            request.user,
            now=datetime.now(UTC),
            utc_offset_minutes=body.validated_data.get("utc_offset_minutes"),
        )
        try:
            minted = live.mint(key, token_request)
        except live.MintFailed as exc:
            _audit(request, AssistantCall.SESSION, Outcome.FAILED)
            return Response({"detail": exc.message}, status=exc.status)
        _audit(request, AssistantCall.SESSION, Outcome.OK)
        return Response(
            {
                "token": minted.token,
                "model": live.MODEL,
                "ws_url": live.WS_URL,
                "expires_at": minted.expires_at,
                "connect_by": minted.connect_by,
                "companion_name": companion.name,
                "voice_name": companion.voice_name,
                "input_audio_mime": live.INPUT_AUDIO_MIME,
                "output_audio_rate": live.OUTPUT_AUDIO_RATE,
            },
            headers={"Cache-Control": "no-store"},
        )


# =============================================================================
# POST /api/assistant/tool
# =============================================================================


class AssistantToolRequestSerializer(serializers.Serializer):
    name = serializers.CharField(help_text="`functionCalls[].name`, as Gemini sent it.")
    args = serializers.DictField(
        required=False,
        default=dict,
        help_text="`functionCalls[].args`, as Gemini sent it. Omitted means `{}`.",
    )


ForwardSerializer = inline_serializer(
    name="AssistantToolForward",
    fields={
        "success": serializers.BooleanField(),
        "message": serializers.CharField(),
    },
)

ToolResponse = inline_serializer(
    name="AssistantToolResult",
    fields={
        "name": serializers.CharField(),
        "is_error": serializers.BooleanField(
            help_text="True when the tool was refused or failed. `text` says what did not happen."
        ),
        "outcome": serializers.ChoiceField(choices=Outcome.choices),
        "text": serializers.CharField(help_text="The result in words, for the model."),
        "response": ForwardSerializer,
    },
)

ToolRefusal = inline_serializer(
    name="AssistantToolRefusal",
    fields={"detail": serializers.CharField(), "response": ForwardSerializer},
)


def _forward(success: bool, message: str) -> dict:
    """What the browser sends back as `functionResponses[].response`."""
    return {"success": success, "message": message}


class AssistantToolView(_AssistantView):
    throttle_classes = [AssistantToolThrottle]
    audit_kind = AssistantCall.TOOL

    def _audit_tool_name(self, request) -> str | None:
        try:
            name = request.data.get("name")
        except Exception:  # noqa: BLE001 - an unparseable body has no name to record
            return None
        return name if isinstance(name, str) else None

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_tool_create",
        summary="Run one tool call from a live conversation, as the signed-in member",
        request=AssistantToolRequestSerializer,
        responses={
            200: ToolResponse,
            400: OpenApiResponse(
                ToolRefusal,
                description=(
                    "Not a tool the web assistant has, or a body that did not fit. Nothing ran. "
                    "`response` is ready to forward to Gemini."
                ),
            ),
            429: OpenApiResponse(DetailSerializer, description="Too many calls; nothing ran."),
        },
    )
    def post(self, request):
        body = AssistantToolRequestSerializer(data=request.data)
        if not body.is_valid():
            text = f"Nothing was read or written. The tool call did not fit: {body.errors}"
            _audit(request, AssistantCall.TOOL, Outcome.REFUSED, self._audit_tool_name(request))
            return Response(
                {"detail": text, "response": _forward(False, text)},
                status=status.HTTP_400_BAD_REQUEST,
            )
        name = body.validated_data["name"]
        refusal = refusal_for(name)
        if refusal is not None:
            _audit(request, AssistantCall.TOOL, Outcome.REFUSED, name)
            return Response(
                {"detail": refusal, "response": _forward(False, refusal)},
                status=status.HTTP_400_BAD_REQUEST,
            )
        result = run_tool(request, name, body.validated_data["args"])
        _audit(request, AssistantCall.TOOL, result.outcome, name)
        return Response(
            {
                "name": name,
                "is_error": result.is_error,
                "outcome": result.outcome,
                "text": result.text,
                "response": _forward(not result.is_error, result.text),
            },
            headers={"Cache-Control": "no-store"},
        )


# =============================================================================
# GET /api/assistant/companion
# =============================================================================


CompanionResponse = inline_serializer(
    name="AssistantCompanion",
    fields={
        "name": serializers.CharField(help_text="What the companion is called."),
        "persona": serializers.CharField(help_text="The built-in register it wears."),
        "voice_name": serializers.CharField(help_text="The Gemini voice it speaks in."),
        "custom_register": serializers.BooleanField(
            help_text="True when the household wrote its own register for it."
        ),
        "stored": serializers.BooleanField(
            help_text=(
                "False when no companion has been set for this member and this is the default "
                "the engine assigns."
            )
        ),
    },
)


class AssistantCompanionView(APIView):
    """Read only. Device tokens may read it too (the API default), so the
    phone can show which companion a member has on the web. Setting one is
    the admin's or `manage.py set_companion`'s; the REST write is not built."""

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_companion_retrieve",
        summary="Who the signed-in member talks to",
        responses={200: CompanionResponse},
    )
    def get(self, request):
        companion = companion_for(request.user)
        return Response(
            {
                "name": companion.name,
                "persona": companion.persona_key,
                "voice_name": companion.voice_name,
                "custom_register": companion.custom_register,
                "stored": companion.stored,
            }
        )
