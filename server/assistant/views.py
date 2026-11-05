"""`/api/assistant/*`: the web assistant's two doors into the engine (web-assistant ticket 07).

- `POST /api/assistant/session` mints a Gemini Live token for the signed-in
  member, carrying a setup the engine assembled and locked (`assistant/live.py`).
- `POST /api/assistant/tool` runs one tool call the browser received from
  Gemini, through the SAME registry `/mcp` runs (`engine_mcp.tools.run_tool`),
  as that member, under their own `visible()`.
- `GET /api/assistant/companion` says who the member talks to.
- `POST /api/assistant/conversation` takes the browser's half of the conversation record (Kevin,
  2026-11-03: "recorded to improve ... and seeing what tool calls etc went wrong"). The mint opens
  the record and the tool door writes every tool call into it; this door adds what only the
  browser hears. `devicelogs/web_conversation.py` owns the format.

**Session authentication only, CSRF enforced** (ticket 02). A browser carries
the Django session cookie and `X-CSRFToken`; DRF's `SessionAuthentication`
refuses an unsafe method without the token. No device token is accepted here,
and `/mcp` accepts no session: two doors, each with one key, so a cookie can
never reach `/mcp` and a leaked device token can never mint a voice session.

**Throttled per member, audited per call** (`AssistantCall`): who, when, which
door, which tool, how it ended. Never the prompt, the arguments, a result, the
key or the token. The conversation RECORD (`devicelogs/web_conversation.py`) is
separate and does keep a tool's arguments and trimmed result, the way the
phone's conversation batches do; never audio, never the key or the token.

**The household's key never leaves this module's process** (ADR 0056): it goes
from the environment into one header on one request to Google, and every
answer here is built from Google's reply, never from the key.
"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime

from django.db import transaction
from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.parsers import FormParser, JSONParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from assistant import live
from assistant.companions import companion_for
from assistant.models import AssistantCall
from assistant.prompt import MAX_OFFSET_MINUTES, MIN_OFFSET_MINUTES
from assistant.session import token_request_for
from assistant.surface import recruiter_only_tool, refusal_for
from demo import budget as demo_budget
from devicelogs import web_conversation
from engine_mcp.models import Outcome
from engine_mcp.tools import run_engine_tool, run_tool
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


class AssistantConversationThrottle(_PerMemberThrottle):
    scope = "assistant_conversation"


def _conversation_id(request) -> uuid.UUID | None:
    """The body's `conversation_id`, or None when absent or not a uuid. For the paths that record
    a refusal before (or without) the serializer."""
    try:
        raw = request.data.get("conversation_id")
    except Exception:  # noqa: BLE001 - an unparseable body names no conversation
        return None
    try:
        return uuid.UUID(str(raw)) if raw else None
    except ValueError:
        return None


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
    # ADR 0068: the assistant is the one door a recruiter visitor may use. What
    # it may do through it is `surface.RECRUITER_TOOLS`, checked per call.
    allows_recruiter = True
    # Every call here is a POST whether it reads or writes, so the write fence
    # is applied per TOOL in `run_tool` (as on `/mcp`), never per method. For
    # an owner or member's session this changes nothing: a session may write.
    scope_checked_per_call = True

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
    client_now_ms = serializers.IntegerField(
        required=False,
        allow_null=True,
        help_text=(
            "The browser's clock, epoch milliseconds (`Date.now()`), so the conversation record "
            "can put the browser's lines on the engine's clock. Omit it and they are kept as sent."
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
            help_text=(
                "The conversation cannot outlive this: about 30 minutes for a member, "
                "`LEGION_DEMO_MAX_SESSION_MINUTES` (5) for a recruiter's demo visit."
            )
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
        "conversation_id": serializers.UUIDField(
            help_text=(
                "This conversation's record on the engine. Send it with every tool call and "
                "with `POST /api/assistant/conversation`. Conversations may be recorded to "
                "improve the assistant; no audio is kept."
            )
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
            429: OpenApiResponse(
                DetailSerializer,
                description=(
                    "Too many starts; nothing started. For a recruiter's demo visit, also: "
                    "\"Today's demo budget is used up. Try again tomorrow.\""
                ),
            ),
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

        # recruiter-demo ticket 04: a recruiter's mint reserves its worst case
        # against the global daily demo budget first, and no token is minted
        # when it does not fit. Members, and visitors on the tester code, never
        # reach the budget (`demo_budget.counts`).
        reservation = None
        if demo_budget.counts(request.user):
            reservation = demo_budget.reserve_voice_mint(user=request.user)
            if reservation is None:
                _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
                return Response(
                    {"detail": demo_budget.used_up_message(request.user, "voice")},
                    status=status.HTTP_429_TOO_MANY_REQUESTS,
                )

        token_request, companion = token_request_for(
            request.user,
            now=datetime.now(UTC),
            utc_offset_minutes=body.validated_data.get("utc_offset_minutes"),
        )
        try:
            minted = live.mint(key, token_request)
        except live.MintFailed as exc:
            if reservation is not None:
                demo_budget.release(reservation)
            _audit(request, AssistantCall.SESSION, Outcome.FAILED)
            return Response({"detail": exc.message}, status=exc.status)
        _audit(request, AssistantCall.SESSION, Outcome.OK)
        record = web_conversation.start(
            request, model=live.MODEL, client_now_ms=body.validated_data.get("client_now_ms")
        )
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
                "conversation_id": record.batch_id,
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
    conversation_id = serializers.UUIDField(
        required=False,
        allow_null=True,
        default=None,
        help_text=(
            "The mint's `conversation_id`. The engine writes this call, its arguments and its "
            "result into that conversation's record. One that is not the caller's is ignored."
        ),
    )
    call_id = serializers.CharField(
        required=False,
        allow_blank=True,
        default="",
        max_length=200,
        help_text="`functionCalls[].id`, as Gemini sent it, for the record.",
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
        "card": serializers.JSONField(
            required=False,
            allow_null=True,
            help_text=(
                "What the screen draws beside the transcript, built by the engine from the "
                "tool's own rows (`engine_mcp/cards.py`): `card` is bars, timeline, car, steps, "
                "checklist or diagram. Absent when the tool has none. Never sent to Gemini: "
                "`response` is the whole of what the browser forwards."
            ),
        ),
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

    def _record(self, request, started, *, success: bool, outcome: str, text: str) -> None:
        """Write this call into the caller's conversation record, if the body named one."""
        try:
            args = request.data.get("args", {})
            call_id = request.data.get("call_id") or ""
        except Exception:  # noqa: BLE001 - an unparseable body: record what is known
            args, call_id = {}, ""
        web_conversation.record_tool(
            request,
            _conversation_id(request),
            call_id=str(call_id),
            name=self._audit_tool_name(request) or "?",
            args=args,
            started=started,
            ended=datetime.now(UTC),
            success=success,
            outcome=outcome,
            text=text,
        )

    def throttled(self, request, wait):
        self._record(
            request,
            datetime.now(UTC),
            success=False,
            outcome=Outcome.THROTTLED,
            text="Too many tool calls just now; the engine refused this before running it.",
        )
        super().throttled(request, wait)

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
        started = datetime.now(UTC)
        body = AssistantToolRequestSerializer(data=request.data)
        if not body.is_valid():
            text = f"Nothing was read or written. The tool call did not fit: {body.errors}"
            _audit(request, AssistantCall.TOOL, Outcome.REFUSED, self._audit_tool_name(request))
            self._record(request, started, success=False, outcome=Outcome.REFUSED, text=text)
            return Response(
                {"detail": text, "response": _forward(False, text)},
                status=status.HTTP_400_BAD_REQUEST,
            )
        name = body.validated_data["name"]
        refusal = refusal_for(name, request.user, body.validated_data["args"])
        if refusal is not None:
            _audit(request, AssistantCall.TOOL, Outcome.REFUSED, name)
            self._record(request, started, success=False, outcome=Outcome.REFUSED, text=refusal)
            return Response(
                {"detail": refusal, "response": _forward(False, refusal)},
                status=status.HTTP_400_BAD_REQUEST,
            )
        only_here = recruiter_only_tool(name, request.user)
        if only_here is not None:
            result = run_engine_tool(request, only_here, body.validated_data["args"])
        else:
            result = run_tool(request, name, body.validated_data["args"])
        _audit(request, AssistantCall.TOOL, result.outcome, name)
        self._record(
            request, started, success=not result.is_error, outcome=result.outcome, text=result.text
        )
        payload = {
            "name": name,
            "is_error": result.is_error,
            "outcome": result.outcome,
            "text": result.text,
            "response": _forward(not result.is_error, result.text),
        }
        # Beside `response`, never inside it: the card is for the screen, not the model.
        if result.card is not None:
            payload["card"] = result.card
        return Response(
            payload,
            headers={"Cache-Control": "no-store"},
        )


# =============================================================================
# POST /api/assistant/conversation
# =============================================================================


class ConversationEventSerializer(serializers.Serializer):
    kind = serializers.ChoiceField(choices=list(web_conversation.EVENT_KINDS))
    at_ms = serializers.IntegerField(help_text="When, epoch milliseconds on the browser's clock.")
    text = serializers.CharField(
        required=False,
        allow_blank=True,
        max_length=20_000,
        help_text="`user` and `assistant`: the words (a transcription, or what was typed).",
    )
    typed = serializers.BooleanField(required=False, default=False, help_text="`user`: typed.")
    reason = serializers.CharField(
        required=False,
        allow_blank=True,
        max_length=400,
        help_text=(
            "`session_end`, `mic_open`, `mic_closed`: why. `interrupted`: `server` or `barge_in`."
        ),
    )
    id = serializers.CharField(required=False, allow_blank=True, max_length=200)
    name = serializers.CharField(required=False, allow_blank=True, max_length=200)
    metric = serializers.ChoiceField(
        choices=list(web_conversation.LATENCY_METRICS), required=False, allow_null=True
    )
    ms = serializers.IntegerField(required=False, allow_null=True, min_value=0, max_value=3_600_000)


class ConversationUploadSerializer(serializers.Serializer):
    conversation_id = serializers.UUIDField(help_text="The mint's `conversation_id`.")
    events = serializers.CharField(
        help_text=(
            "A JSON array of events: `{kind, at_ms, text?, typed?, reason?, id?, name?, metric?, "
            "ms?}`, kind one of " + ", ".join(web_conversation.EVENT_KINDS) + ". A form field, so "
            "that `navigator.sendBeacon` can send it with `csrfmiddlewaretoken` beside it while "
            "the page closes. Send only events not yet stored; a repeat is stored once."
        ),
    )


ConversationStored = inline_serializer(
    name="AssistantConversationStored",
    fields={"stored_lines": serializers.IntegerField(), "detail": serializers.CharField()},
)


class AssistantConversationView(_AssistantView):
    """The browser's half of a conversation's record. Write only: there is no read here, for a
    member or a recruiter; the record is read with `manage.py device_logs` on the engine.

    A recruiter may use this door (`allows_recruiter`, as on the other assistant doors) for its
    OWN conversation only: `web_conversation.owned` matches the id to the caller, and a visit's
    record to a visit. `/api/device-logs/` stays denied to a recruiter."""

    throttle_classes = [AssistantConversationThrottle]
    parser_classes = [MultiPartParser, FormParser, JSONParser]

    def throttled(self, request, wait):
        # Not audited: `AssistantCall` records mints and tool calls only.
        raise exceptions.Throttled(
            wait, detail="Nothing was stored. Too many conversation uploads in a short time."
        )

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_conversation_create",
        summary="Add the browser's transcript events to the caller's own conversation record",
        request={"multipart/form-data": ConversationUploadSerializer},
        responses={
            200: ConversationStored,
            400: OpenApiResponse(DetailSerializer, description="Refused; nothing stored."),
            404: OpenApiResponse(
                DetailSerializer, description="No conversation with that id is the caller's."
            ),
            413: OpenApiResponse(DetailSerializer, description="Over the cap; nothing stored."),
            429: OpenApiResponse(DetailSerializer, description="Too many uploads; nothing stored."),
        },
    )
    def post(self, request):
        body = ConversationUploadSerializer(data=request.data)
        if not body.is_valid():
            return _not_stored(f"{body.errors}")
        try:
            raw = json.loads(body.validated_data["events"])
        except ValueError:
            return _not_stored("`events` is not JSON.")
        if not isinstance(raw, list) or len(raw) > web_conversation.MAX_EVENTS:
            return _not_stored(f"`events` must be a list of at most {web_conversation.MAX_EVENTS}.")
        events = ConversationEventSerializer(data=raw, many=True)
        if not events.is_valid():
            return _not_stored(f"{events.errors}")
        with transaction.atomic():
            row = web_conversation.owned(request, body.validated_data["conversation_id"])
            if row is None:
                return Response(
                    {"detail": "Nothing was stored. No conversation with that id is yours."},
                    status=status.HTTP_404_NOT_FOUND,
                )
            try:
                stored = web_conversation.append(
                    row, web_conversation.browser_lines(row, events.validated_data)
                )
            except web_conversation.TooLarge as exc:
                return Response(
                    {"detail": str(exc)}, status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
                )
        return Response(
            {"stored_lines": stored, "detail": "Stored."}, headers={"Cache-Control": "no-store"}
        )


def _not_stored(why: str) -> Response:
    return Response({"detail": f"Nothing was stored. {why}"}, status=status.HTTP_400_BAD_REQUEST)


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
    the admin's or `manage.py set_companion`'s; the REST write is not built.

    A recruiter's visit may read it too (ADR 0068, ticket 05): the chat header
    shows the companion's name, and for a recruiter that is always Alfred
    (`companions.recruiter_companion`)."""

    allows_recruiter = True

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
