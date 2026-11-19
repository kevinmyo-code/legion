"""`/api/assistant/*`: the web assistant's two doors into the engine (web-assistant ticket 07).

- `POST /api/assistant/session` mints a Gemini Live token for the signed-in
  member, carrying a setup the engine assembled and locked (`assistant/live.py`).
- `POST /api/assistant/tool` runs one tool call the browser received from
  Gemini, through the SAME registry `/mcp` runs (`engine_mcp.tools.run_tool`),
  as that member, under their own `visible()`.
- `GET /api/assistant/session` says which voice engine the caller's assistant runs on, `live` or
  `cascade`, decided by the server per role (`assistant/voice_engine.py`, ADR 0102).
- `POST /api/assistant/turn` (ADR 0102) is the cascade: one utterance or typed message in, the
  engine's transcript, tool activity, sentences and speech out as Server-Sent Events
  (`assistant/cascade.py`). Its tool calls go through the same checks as the tool door below.
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

import functools
import json
import uuid
from datetime import UTC, datetime, timedelta

from django.db import DatabaseError, transaction
from django.http import StreamingHttpResponse
from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.parsers import FormParser, JSONParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from assistant import cascade, live, read_through, turn_slots, voice_engine
from assistant.companions import companion_for
from assistant.models import AssistantCall
from assistant.prompt import MAX_OFFSET_MINUTES, MIN_OFFSET_MINUTES, RECRUITER_OPENER
from assistant.session import system_prompt_parts_for, token_request_for
from assistant.surface import recruiter_only_tool, refusal_for
from demo import budget as demo_budget
from demo import suggestions
from devicelogs import web_conversation
from engine_mcp import cards
from engine_mcp.models import Outcome
from engine_mcp.tools import TOOLS_BY_NAME, run_engine_tool, run_tool
from household.models import is_recruiter
from household.permissions import IsHouseholdMember
from household.tenancy import household_of
from ingest.statements import gemini_key
from legion.client_ip import ClientIpThrottleMixin, client_ip
from llm import cap as member_cap

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


CASCADE_ONLY = (
    "Nothing was started: this page is out of date. Reload it to talk; the assistant now answers "
    "through the engine's own voice pipeline (POST /api/assistant/turn), so no live voice token is "
    "minted."
)
LIVE_ONLY = (
    "Nothing was answered. This assistant uses live voice here (POST /api/assistant/session), "
    "not the engine's turn route."
)

EngineResponse = inline_serializer(
    name="AssistantEngine",
    fields={
        "engine": serializers.ChoiceField(
            choices=list(voice_engine.ENGINES),
            help_text=(
                "`live`: mint with `POST /api/assistant/session` and talk to Gemini Live. "
                "`cascade`: post each utterance or typed message to `POST /api/assistant/turn`; "
                "the engine transcribes, answers with its tools and speaks (ADR 0102). Decided by "
                "the server per role; the other door refuses with 409."
            ),
        ),
        "companion_name": serializers.CharField(),
        "voice_name": serializers.CharField(),
        "input_audio_rate": serializers.IntegerField(
            help_text="`cascade`: the utterance is 16-bit mono PCM at this rate."
        ),
        "output_audio_rate": serializers.IntegerField(
            help_text="`cascade`: the reply's audio is 16-bit mono PCM at this rate."
        ),
        "max_utterance_seconds": serializers.IntegerField(
            help_text="`cascade`: the longest utterance the turn route accepts."
        ),
        "session_minutes": serializers.IntegerField(
            allow_null=True,
            help_text=(
                "How long one conversation may last, for a recruiter's demo visit "
                "(`LEGION_DEMO_MAX_SESSION_MINUTES`); null for a member."
            ),
        ),
    },
)


class AssistantSessionView(_AssistantView):
    throttle_classes = [AssistantSessionThrottle]
    audit_kind = AssistantCall.SESSION

    def get_throttles(self):
        # The engine question is free: it reads two settings and the companion, starts nothing.
        if self.request.method == "GET":
            return []
        return super().get_throttles()

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_session_retrieve",
        summary="Which voice engine this caller's assistant runs on (live or cascade)",
        responses={200: EngineResponse},
    )
    def get(self, request):
        companion = companion_for(request.user)
        return Response(
            {
                "engine": voice_engine.engine_for(request.user),
                "companion_name": companion.name,
                "voice_name": companion.voice_name,
                "input_audio_rate": 16_000,
                "output_audio_rate": cascade.OUTPUT_RATE,
                "max_utterance_seconds": MAX_UTTERANCE_SECONDS,
                "session_minutes": (
                    demo_budget.max_session_minutes() if is_recruiter(request.user) else None
                ),
            },
            headers={"Cache-Control": "no-store"},
        )

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
                    '"Today\'s demo budget is used up. Try again tomorrow." For a member with '
                    "`LEGION_MEMBER_DAILY_CAP` set: today's own spend limit is used up."
                ),
            ),
            409: OpenApiResponse(
                DetailSerializer,
                description=(
                    "This caller's assistant runs on the cascade (`GET` says `cascade`); no token "
                    "is minted."
                ),
            ),
            502: OpenApiResponse(
                DetailSerializer, description="Google refused the key or the session, in words."
            ),
            503: OpenApiResponse(
                DetailSerializer,
                description=(
                    "No `LEGION_GEMINI_KEY` on this server (\"The assistant isn't set up on this "
                    'server."), or Google did not answer.'
                ),
            ),
        },
    )
    def post(self, request):
        if voice_engine.engine_for(request.user) != voice_engine.LIVE:
            _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
            return Response({"detail": CASCADE_ONLY}, status=status.HTTP_409_CONFLICT)
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

        # Q9 (architecture review 2026-11-10): a member's own daily cap, off unless
        # LEGION_MEMBER_DAILY_CAP is set. Recruiters never reach it (`llm/cap.py`).
        admitted = member_cap.admit_live_mint(request.user, household_of(request), live.MODEL)
        if admitted is None:
            _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
            return Response(
                {"detail": member_cap.USED_UP}, status=status.HTTP_429_TOO_MANY_REQUESTS
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
            admitted.release()
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
    turn = serializers.IntegerField(
        required=False,
        allow_null=True,
        default=None,
        min_value=0,
        help_text=(
            "Which model turn of the conversation this call is in: the count of `turnComplete`s "
            "heard so far. `remember` is refused in a turn that already ran a tool returning "
            "web text (`web_search`, `brief_latest`). Omitted means unknown, which treats any "
            "such tool earlier in the conversation as this turn's."
        ),
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
                "checklist, diagram, resume, readings, status_list or trend. Absent when the "
                "tool has none. Never sent to Gemini: "
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
        read_through.set_turn(
            request, body.validated_data["conversation_id"], body.validated_data["turn"]
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
        # Beside `response`, never inside it: the card is for the screen, not the model. A
        # recruiter gets only the kinds /try always had (`cards.for_viewer`).
        card = cards.for_viewer(result.card, is_recruiter(request.user))
        if card is not None:
            payload["card"] = card
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


# =============================================================================
# POST /api/assistant/turn (ADR 0102)
# =============================================================================

# The longest utterance a turn takes, and so the most audio bytes (16 kHz, 16-bit mono). The
# browser stops recording at this too (`frontend/src/assistant/endpointer.ts`).
# Spec 13: the phone's utterance cap.
MAX_UTTERANCE_SECONDS = 28
MAX_AUDIO_BYTES = MAX_UTTERANCE_SECONDS * 16_000 * 2
MAX_TYPED_CHARS = 2_000
MAX_PREFACE_CHARS = 400

TIME_LIMIT = "Nothing was answered. This demo conversation reached its time limit; start a new one."
TURN_FAILED = "Not answered: the engine hit an error answering that. Nothing was changed."
# The same error after a change went through (finding 2): what did happen, not "nothing".
TURN_FAILED_AFTER_WRITE = (
    "The answer was cut short by an error on the engine, but the change you asked for went "
    "through. Check it before asking again."
)
# The tools that change something: a change that went through stays in the history (finding 5).
WRITE_TOOLS = frozenset(name for name, tool in TOOLS_BY_NAME.items() if tool.writes)
# Past this the conversation continues in a fresh record (finding 9); the cap is 5 MiB.
RECORD_ROLLOVER_BYTES = 4 * 1024 * 1024
# The turn a conversation's opening greeting answers, in its history (`web_conversation.history`).
OPENED_LINE = "(They opened the assistant.)"


class AssistantTurnThrottle(_PerMemberThrottle):
    scope = "assistant_turn"


class AssistantTurnIpThrottle(ClientIpThrottleMixin, SimpleRateThrottle):
    """A recruiter's visit is a fresh user every time, so its turns are also counted per IP
    address. Members are not counted here."""

    scope = "assistant_turn_ip"

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated or not is_recruiter(user):
            return None
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}


class AssistantTurnRequestSerializer(serializers.Serializer):
    audio = serializers.FileField(
        required=False,
        allow_empty_file=False,
        help_text=(
            "One utterance: raw 16-bit little-endian mono PCM at 16 kHz, no header, at most "
            f"{MAX_UTTERANCE_SECONDS} seconds. Send this OR `text`. Never stored."
        ),
    )
    text = serializers.CharField(
        required=False,
        allow_blank=False,
        max_length=MAX_TYPED_CHARS,
        help_text="A typed message, answered in text only (no speech). Send this OR `audio`.",
    )
    conversation_id = serializers.UUIDField(
        required=False,
        allow_null=True,
        help_text=(
            "The conversation this turn continues: the `conversation_id` of an earlier turn's "
            "`turn` event. Omit it to start a new conversation."
        ),
    )
    utc_offset_minutes = serializers.IntegerField(
        required=False,
        allow_null=True,
        min_value=MIN_OFFSET_MINUTES,
        max_value=MAX_OFFSET_MINUTES,
        help_text="As for `POST /api/assistant/session`.",
    )
    client_now_ms = serializers.IntegerField(
        required=False,
        allow_null=True,
        help_text="As for `POST /api/assistant/session`; read when a conversation starts.",
    )
    spoken = serializers.BooleanField(
        required=False,
        default=False,
        help_text=(
            "With `text`: the page transcribed the utterance early (`POST "
            "/api/assistant/transcribe`) and the answer is spoken, as for `audio`."
        ),
    )
    barge_in = serializers.BooleanField(
        # Optional, with no default, so it is not required in the schema: old pages never send it
        # and the page sends it only on a barge-in turn.
        required=False,
        help_text=(
            "With `audio`: the utterance began while a reply was playing (a barge-in). Only such a "
            "turn is checked for the reply heard back (an echo): one that repeats the reply that "
            "was playing ends as no speech. A turn without it is never checked."
        ),
    )
    turn_id = serializers.UUIDField(
        required=False,
        allow_null=True,
        help_text=(
            "The browser's own id for this turn, so it can be stopped with "
            "`POST /api/assistant/turn/cancel`."
        ),
    )
    tour = serializers.IntegerField(
        required=False,
        allow_null=True,
        min_value=0,
        max_value=20,
        help_text=(
            "A demo visit's one-minute tour: the beat to play (from 0). With `audio` or `text`, "
            "what was said or typed during the tour: 'skip' plays this beat, 'stop' ends the "
            "tour, anything else is a question and the tour ends."
        ),
    )
    greet = serializers.BooleanField(
        required=False,
        default=False,
        help_text=(
            "The greeting on open, spoken: no `audio` or `text` with it. A member's is "
            "built from their greeting facts; a demo visit's is scripted."
        ),
    )
    greeting_history = serializers.CharField(
        required=False,
        allow_blank=True,
        max_length=2_000,
        help_text=(
            "With `greet`: JSON the page keeps for the greeting's caps, "
            '`{"today": [kinds led with today], "last": kind, "hellos": [last hellos]}`.'
        ),
    )
    chip = serializers.CharField(
        required=False,
        allow_blank=True,
        max_length=4_000,
        help_text=(
            "With `text`: the token of a suggestion chip the engine offered this visitor "
            "(`POST /api/demo/chips`), whose question `text` is word for word. The chip's own "
            "checked lookup then answers it; any other token is ignored and the turn is a plain "
            "typed one."
        ),
    )
    preface = serializers.CharField(
        required=False,
        allow_blank=True,
        max_length=MAX_PREFACE_CHARS,
        help_text=(
            "A hidden line the page puts ahead of the person's words (the demo's opener). Never "
            "shown, never recorded as theirs."
        ),
    )


def _cascade_clients():
    """The text model and the speech client, on the engine's key. Tests replace this."""
    from agents.runtime.model import GeminiClient
    from assistant.prompt_cache import CachingModel
    from assistant.speech import GeminiSpeech

    return (
        CachingModel(GeminiClient(key=gemini_key)),
        GeminiSpeech(gemini_key, stt_model=voice_engine.text_model()),
    )


def _turn_sse(
    request,
    turn_input,
    row_id,
    history,
    turn_no,
    system,
    context,
    voice,
    reservation,
    reserved_rounds,
    companion_name="",
    held=None,
    reserved_speech=0,
    cancelled=None,
    answered=None,
    more_speech=None,
):
    report = cascade.TurnReport()
    answered = answered or (lambda: None)
    spoken = turn_input.audio is not None or turn_input.greet
    turn = None
    try:
        model, speech = _cascade_clients()
        turn = cascade.run_turn(
            request,
            turn_input,
            conversation_id=row_id,
            history=history,
            turn=turn_no,
            system=system,
            context=context,
            voice=voice,
            companion_name=companion_name,
            cancelled=cancelled or (lambda: False),
            on_write=answered,
            more_speech=more_speech or (lambda n: True),
            model_name=voice_engine.text_model(),
            model=model,
            speech=speech,
            report=report,
        )
        for event in turn:
            yield event.sse()
            if event.type == "done":
                # Written to the browser: a re-send of this turn is now a duplicate (N5).
                answered()
    except GeneratorExit:
        # The browser hung up (an interrupt aborts the request): no further round, tool call or
        # speech starts, and the record says the turn was cut.
        report.status = "cancelled"
        raise
    except Exception:  # noqa: BLE001 - the stream must end in words, always
        cascade.log.warning("assistant cascade: turn failed", exc_info=True)
        report.status = "failed"
        wrote = report.door is not None and report.door.wrote()
        said = cascade.failure_audio(voice, wrote) if spoken else None
        if said is not None:
            yield said.sse()
        yield cascade.ev.error(TURN_FAILED_AFTER_WRITE if wrote else TURN_FAILED).sse()
    finally:
        if turn is not None:
            # Closes the loop and the speech now, not whenever the generator is collected.
            turn.close()
        now = datetime.now(UTC)
        lines = []
        said = " ".join(report.said).strip()
        if said:
            replay = report.history_text()
            if replay != said:
                # History replays this instead of what was said (his profile, a tour line about
                # him: `TurnReport.replay`); the record keeps what was said, for him to read.
                lines.append(web_conversation.engine_replay_line(now, replay))
            lines.append(web_conversation.engine_assistant_line(now, said))
        if report.status == "cancelled":
            lines.append(web_conversation.engine_interrupted_line(now))
        timing = cascade.timing_line(report, spoken=spoken)
        lines.append(web_conversation.engine_latency_line(now, timing))
        web_conversation.record_lines(request, row_id, lines)
        # Numbers and names only (`cascade.timing_line`), never words.
        cascade.log.warning("assistant %s", timing)
        if reservation is not None:
            demo_budget.release_unused(
                reservation,
                rounds_unused=max(
                    reserved_rounds - report.rounds - _count(report.timings, "stt_retries"), 0
                ),
                # No voice in the audio: no paid speech-to-text and nothing spoken.
                speech_unused=report.status == "nothing_heard"
                and not report.timings.get("stt_paid"),
                speech_requests_reserved=reserved_speech,
                speech_requests_used=_count(report.timings, "tts_requests")
                + _count(report.timings, "speech_fetched"),
            )
        if held is not None:
            held.release()


def _count(timings: dict, key: str) -> int:
    value = timings.get(key)
    return value if isinstance(value, int) and value > 0 else 0


def _greeting_history(raw: str | None) -> dict:
    """The page's greeting history, or {} when absent or not the shape asked for."""
    try:
        parsed = json.loads(raw) if raw else {}
    except ValueError:
        return {}
    if not isinstance(parsed, dict):
        return {}
    today = [str(k)[:20] for k in parsed.get("today") or [] if isinstance(k, str)][:10]
    hellos = [str(h)[:200] for h in parsed.get("hellos") or [] if isinstance(h, str)][:3]
    last = parsed.get("last") if isinstance(parsed.get("last"), str) else None
    return {"today": today, "last": last, "hellos": hellos}


def _refuse(request, detail: str, code: int, slug: str | None = None, **extra) -> Response:
    """A refusal in words. On the turn route every refusal also carries a `code` slug the page
    acts on (`TURN_REFUSAL_CODES`), never its words."""
    _audit(request, AssistantCall.SESSION, Outcome.REFUSED)
    body = {"detail": detail}
    if slug is not None:
        body["code"] = slug
    body.update(extra)
    return Response(body, status=code)


# The turn route's refusal codes (the page's contract with the server, 2026-11-17: interrupt must
# not read as the time limit). `busy` also carries `retry_ms`.
TURN_REFUSAL_CODES = {
    "bad_request": "400: the body did not fit (one audio or one text; a tour is the demo's).",
    "no_conversation": "404: no conversation with that id is the caller's.",
    "live_engine": "409: this caller's assistant runs on live voice.",
    "time_limit": "409: a demo conversation reached its time limit.",
    "busy": "409: the caller's own earlier turn has not released yet; retry after `retry_ms`.",
    "duplicate": "409: a turn with this `turn_id` was already answered (a re-send).",
    "too_long": "413: the utterance is over the length limit.",
    "member_cap": "429: the member's own daily spend limit is used up.",
    "demo_full": "429: every demo slot is in use, or another demo turn from this address runs.",
    "budget": "429: today's demo budget is used up.",
    "throttled": "429: too many turns in a short time.",
    "no_key": "503: no Gemini key on this server.",
}
BUSY_RETRY_MS = 1000


class TurnRefusalSerializer(serializers.Serializer):
    """A refusal of `POST /api/assistant/turn` (round 3 N8): the words, and the `code` the page
    acts on (`TURN_REFUSAL_CODES`)."""

    detail = serializers.CharField(help_text="What did not happen, in words to show.")
    code = serializers.ChoiceField(
        choices=sorted(TURN_REFUSAL_CODES), help_text="What the page does next."
    )
    retry_ms = serializers.IntegerField(
        required=False, help_text="With `busy`: when to send the same request again."
    )


def _more_speech(user):
    """How a running turn reserves more speech requests (round 3 S4): against the demo budget for
    a visit, without limit (bar the turn's ceiling) for a member."""
    if not demo_budget.counts(user):
        return lambda n: True
    return lambda n: demo_budget.reserve_speech_requests(n, user=user) is not None


def _answered(user, turn_id) -> bool:
    """Whether `turn_id` already reached a state `user` could have received (round 3 N5)."""
    from assistant.models import TurnSeen

    return TurnSeen.objects.filter(turn_id=turn_id, user=user).exists()


def _mark_answered(user, turn_id) -> None:
    """Records `turn_id` as answered, once a write in it committed or its `done` was sent; never
    on arrival, so a turn refused or broken before either is re-sent and runs (round 3 N5)."""
    from assistant.models import TurnSeen

    if not turn_id:
        return
    # Not swallowed (round 4 R4): without the mark, a re-send after a committed write would make
    # the change again. Tried once more; then logged as an error (the history's "(Done: x)" line
    # is the last guard).
    for attempt in (1, 2):
        try:
            with transaction.atomic():
                TurnSeen.objects.get_or_create(turn_id=turn_id, user=user)
            break
        except DatabaseError:
            if attempt == 2:
                cascade.log.error(
                    "assistant cascade: turn mark failed twice; a re-send may repeat this turn",
                    exc_info=True,
                )
                return
    try:
        TurnSeen.objects.filter(at__lt=datetime.now(UTC) - timedelta(days=1)).delete()
    except DatabaseError:
        cascade.log.warning("assistant cascade: turn mark prune failed", exc_info=True)


class _TurnThrottled(exceptions.APIException):
    """DRF's `Throttled`, with the turn route's `code` in its body (`Throttled` appends its wait
    to a string detail, so it cannot carry one)."""

    status_code = status.HTTP_429_TOO_MANY_REQUESTS

    def __init__(self, wait):
        self.wait = None if wait is None else max(int(wait), 1)
        super().__init__(
            detail={
                "detail": (
                    "Nothing was read, written or started. You have asked the assistant for too "
                    "much in a short time; the engine refused this before running it."
                ),
                "code": "throttled",
            }
        )


class AssistantTurnView(_AssistantView):
    """One cascade turn (ADR 0102), answered as Server-Sent Events."""

    throttle_classes = [AssistantTurnThrottle, AssistantTurnIpThrottle]
    audit_kind = AssistantCall.SESSION
    parser_classes = [MultiPartParser, FormParser, JSONParser]

    def throttled(self, request, wait):
        _audit(request, self.audit_kind, Outcome.THROTTLED, None)
        raise _TurnThrottled(wait)

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_turn_create",
        summary="One assistant turn on the engine's voice pipeline, answered as Server-Sent Events",
        description=(
            "text/event-stream, one event per thing that happened: `turn` "
            "{conversation_id, turn}; `heard` {text} (a spoken turn's transcript); `greeting` "
            "{kind, text} (a greeting turn); `working` {tool}; `filler` {tool, text} (the line "
            "said while a tool runs; `tool` is empty for the acknowledgement); `tool` {name, ok, "
            "outcome, text, ms, card?}; `sentence` {text} (exact figures, no tags); `audio` {pcm "
            "(base64 16-bit mono PCM), rate, filler?} (`filler` true on a filler's audio); "
            "`speech_failed` {reason}; `speech_stopped` {reason} (the rest of the answer is "
            "shown, not spoken); `done` {status, final_text, writes_ran, words?, end? (the "
            "conversation closes), ignored? (`not_addressed`, `unsure` or `no_speech`: nothing "
            "was answered), tour? {beat, next, of}, cost_usd? and tools_ms? (a demo visit's "
            "estimated turn cost and each tool's time)}; `error` {words}. A typed turn gets no "
            "`audio`. Every refusal body is {detail, code}: `bad_request` (400), "
            "`no_conversation` (404), `live_engine`, `time_limit`, `busy` (with `retry_ms`) and "
            "`duplicate` (409), `too_long` (413), `member_cap`, `demo_full`, `budget` and "
            "`throttled` (429), `no_key` (503). `turn_id` names the turn for "
            "`POST /api/assistant/turn/cancel`."
        ),
        request={"multipart/form-data": AssistantTurnRequestSerializer},
        responses={
            200: OpenApiResponse(description="text/event-stream, as described."),
            400: OpenApiResponse(
                TurnRefusalSerializer, description="The body did not fit; nothing ran."
            ),
            404: OpenApiResponse(
                TurnRefusalSerializer, description="No conversation with that id is the caller's."
            ),
            409: OpenApiResponse(
                TurnRefusalSerializer,
                description=(
                    "`live_engine`, `time_limit`, `busy` (the caller's own earlier turn has not "
                    "released; send the same request again after `retry_ms`) or `duplicate` (this "
                    "`turn_id` was already answered). Nothing ran."
                ),
            ),
            413: OpenApiResponse(
                TurnRefusalSerializer,
                description=(
                    "The utterance is too long: over MAX_UTTERANCE_SECONDS of 16 kHz 16-bit mono "
                    "PCM, judged on the upload's size in bytes."
                ),
            ),
            429: OpenApiResponse(
                TurnRefusalSerializer,
                description=(
                    "`throttled` (too many turns per member, and per address for a demo visit), "
                    "`demo_full` (every demo slot or this address's slot is in use), `budget` or "
                    "`member_cap`. Nothing ran."
                ),
            ),
            503: OpenApiResponse(
                TurnRefusalSerializer, description="No Gemini key on this server."
            ),
        },
    )
    def post(self, request):
        if voice_engine.engine_for(request.user) != voice_engine.CASCADE:
            return _refuse(request, LIVE_ONLY, status.HTTP_409_CONFLICT, "live_engine")
        body = AssistantTurnRequestSerializer(data=request.data)
        if not body.is_valid():
            return _refuse(
                request,
                f"Nothing was answered. The request did not fit: {body.errors}",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        data = body.validated_data
        upload, text = data.get("audio"), (data.get("text") or "").strip()
        greet = bool(data.get("greet"))
        tour_beat = data.get("tour")
        if tour_beat is not None and not is_recruiter(request.user):
            return _refuse(
                request,
                "Nothing was answered. The tour is the demo's.",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        if greet and (upload is not None or text):
            return _refuse(
                request,
                "Nothing was answered. A greeting carries no `audio` or `text`.",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        if tour_beat is not None and text and upload is not None:
            return _refuse(
                request,
                "Nothing was answered. A tour turn carries `audio` or `text`, not both.",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        if not greet and tour_beat is None and (upload is None) == (not text):
            return _refuse(
                request,
                "Nothing was answered. Send one utterance as `audio` or one `text`.",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        audio = None
        if upload is not None:
            if upload.size > MAX_AUDIO_BYTES:
                return _refuse(
                    request,
                    "Nothing was answered. An utterance may last at most "
                    f"{MAX_UTTERANCE_SECONDS} seconds.",
                    status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                    "too_long",
                )
            audio = upload.read(MAX_AUDIO_BYTES + 1)[:MAX_AUDIO_BYTES]
            audio = audio[: len(audio) - len(audio) % 2]
        if gemini_key() is None:
            return _refuse(request, KEY_ABSENT, status.HTTP_503_SERVICE_UNAVAILABLE, "no_key")

        row = None
        if data.get("conversation_id"):
            with transaction.atomic():
                row = web_conversation.owned(request, data["conversation_id"])
            if row is None:
                return _refuse(
                    request,
                    "Nothing was answered. No conversation with that id is yours.",
                    status.HTTP_404_NOT_FOUND,
                    "no_conversation",
                )
            limit = timedelta(minutes=demo_budget.max_session_minutes())
            if is_recruiter(request.user) and datetime.now(UTC) - row.window_start > limit:
                return _refuse(request, TIME_LIMIT, status.HTTP_409_CONFLICT, "time_limit")

        if member_cap.over_cap(request.user):
            return _refuse(
                request, member_cap.USED_UP, status.HTTP_429_TOO_MANY_REQUESTS, "member_cap"
            )
        # One turn in flight per caller (waiting briefly for the caller's own earlier turn to
        # release, as after an interrupt), and a small cap on demo turns engine-wide.
        held, busy, busy_status = turn_slots.acquire(request.user, client_ip(request))
        if held is None:
            if busy == turn_slots.BUSY_SELF:
                return _refuse(request, busy, busy_status, "busy", retry_ms=BUSY_RETRY_MS)
            return _refuse(request, busy, busy_status, "demo_full")
        reservation = None
        try:
            if data.get("turn_id") and _answered(request.user, data["turn_id"]):
                # The page re-sends a `busy` turn with its id; one that already ran is never a
                # second turn or a second charge.
                held.release()
                return _refuse(
                    request,
                    "Nothing was answered again: this turn has already been answered.",
                    status.HTTP_409_CONFLICT,
                    "duplicate",
                )
            # The loop's rounds plus the one tool-free ask again (spec 23); a greeting is one call.
            # One more for a prompt cache the turn may have to make (round 2 turn review 8).
            reserved_rounds = (
                1
                if greet
                else 0
                if tour_beat is not None and audio is None and not text
                else cascade.MAX_ROUNDS + 1
            ) + 1
            if audio is not None:
                # Speech-to-text may be tried once more (round 3, finding 8 leftovers): reserved
                # as one more call, given back when it was not needed.
                reserved_rounds += 1
            spoken_turn = (
                greet
                or audio is not None
                or bool(data.get("spoken"))
                or (tour_beat is not None and not text)
            )
            voice = voice_engine.voice_for(request.user)
            # Speech reserved per request it may make: the turn's own, and the filler and failure
            # lines it may have to make first for this voice (round 2 turn review 8).
            reserved_speech = (
                cascade.MAX_TTS_REQUESTS + cascade.FILLER_AUDIO.missing(voice) if spoken_turn else 0
            )
            if greet:
                # The scripted greeting's own clip, fetched for next time (round 3, finding 8).
                reserved_speech += 1
            if demo_budget.counts(request.user):
                reservation = demo_budget.reserve_cascade_turn(
                    rounds=reserved_rounds,
                    spoken=spoken_turn,
                    speech_requests=reserved_speech,
                    user=request.user,
                )
                if reservation is None:
                    held.release()
                    return _refuse(
                        request,
                        demo_budget.used_up_message(request.user, "text"),
                        status.HTTP_429_TOO_MANY_REQUESTS,
                        "budget",
                    )

            carried_window = None
            if row is not None and row.raw_bytes > RECORD_ROLLOVER_BYTES:
                # A full record would freeze the turn number (finding 9): the conversation goes
                # on in a new record, and the page learns its id from the first event. It keeps
                # its time window, so a demo's time limit is not reset by it (round 2 finding 9).
                carried_window = row.window_start
                row = None
            if row is None:
                row = web_conversation.start(
                    request,
                    model=f"cascade/{voice_engine.text_model()}",
                    client_now_ms=data.get("client_now_ms"),
                )
                if carried_window is not None:
                    row.window_start = carried_window
                    row.save(update_fields=["window_start"])
            history, turn_no = web_conversation.history(
                row,
                max_turns=cascade.HISTORY_TURNS,
                opened_line=RECRUITER_OPENER if is_recruiter(request.user) else OPENED_LINE,
                writes=WRITE_TOOLS,
            )
            system, context, companion = system_prompt_parts_for(
                request.user,
                now=datetime.now(UTC),
                utc_offset_minutes=data.get("utc_offset_minutes"),
                cascade=True,
            )
            _audit(request, AssistantCall.SESSION, Outcome.OK)
            turn_input = cascade.TurnInput(
                audio=audio,
                text=text or None,
                preface=(data.get("preface") or "").strip() or None,
                greet=greet,
                greeting_history=_greeting_history(data.get("greeting_history")),
                spoken=bool(data.get("spoken")) and bool(text),
                tour_beat=tour_beat,
                heard_before=web_conversation.utterances_received(row),
                barge_in=bool(data.get("barge_in")) and audio is not None,
                last_reply=web_conversation.last_reply(row),
                chip=suggestions.chip_for(request.user, data.get("chip"), text),
            )
            response = StreamingHttpResponse(
                _turn_sse(
                    request,
                    turn_input,
                    row.batch_id,
                    history,
                    turn_no,
                    system,
                    context,
                    # Per role, not the companion's stored voice (that one is Live's): ADR 0103.
                    voice,
                    reservation,
                    reserved_rounds,
                    companion.name,
                    held,
                    reserved_speech,
                    cascade.CancelCheck(request.user.pk, data.get("turn_id")),
                    functools.partial(_mark_answered, request.user, data.get("turn_id")),
                    _more_speech(request.user),
                ),
                content_type="text/event-stream",
            )
            # The slot goes back however the stream ends, even when it never started.
            response._resource_closers.append(held.release)
            response["Cache-Control"] = "no-cache, no-store"
            response["X-Accel-Buffering"] = "no"
            return response
        except BaseException:
            # Anything that fails before the stream owns the slot and the reservation gives both
            # back here (round 2 finding 3): a leaked advisory lock would stay on this thread's
            # pooled connection, and a re-entrant one would never come free.
            held.release()
            if reservation is not None:
                demo_budget.release(reservation)
            raise


# =============================================================================
# POST /api/assistant/transcribe (the phone's speculation, adapted)
# =============================================================================


class AssistantTranscribeRequestSerializer(serializers.Serializer):
    audio = serializers.FileField(
        allow_empty_file=False,
        help_text=(
            "What has been said so far: raw 16-bit little-endian mono PCM at 16 kHz, at most "
            f"{MAX_UTTERANCE_SECONDS} seconds. Never stored."
        ),
    )


TranscribeResponse = inline_serializer(
    name="AssistantTranscript",
    fields={
        "text": serializers.CharField(allow_blank=True, help_text="The words; empty for none."),
        "ms": serializers.IntegerField(help_text="How long the transcription took."),
    },
)


class AssistantTranscribeView(_AssistantView):
    """Speech to text only, for the page's early start (the phone's speculation, `Speculation.kt`,
    adapted): at about 300 ms of silence the page sends what was said so far, and when the turn
    ends with no more speech it sends the words to `POST /api/assistant/turn` as `text` with
    `spoken`. If the person speaks again the transcript is thrown away. Nothing is answered here,
    no tool runs and nothing is recorded; a demo visit's budget is charged one speech share."""

    throttle_classes = [AssistantTurnThrottle, AssistantTurnIpThrottle]
    audit_kind = AssistantCall.SESSION
    parser_classes = [MultiPartParser, FormParser]

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_transcribe_create",
        summary="Transcribe what has been said so far (no answer, nothing stored)",
        request={"multipart/form-data": AssistantTranscribeRequestSerializer},
        responses={
            200: TranscribeResponse,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            409: OpenApiResponse(DetailSerializer, description="This caller is on live voice."),
            413: OpenApiResponse(DetailSerializer, description="Too long."),
            429: OpenApiResponse(DetailSerializer, description="Too many, or over budget."),
            502: OpenApiResponse(DetailSerializer, description="The words could not be had."),
            503: OpenApiResponse(DetailSerializer, description="No Gemini key on this server."),
        },
    )
    def post(self, request):
        if not voice_engine.early_transcripts_on():
            # OFF by default (round 2 review: no slot, no per-utterance cap, no cancel, members
            # unmetered). Off is a plain 404: no paid call, nothing metered, nothing reserved.
            return Response({"detail": "Not found."}, status=status.HTTP_404_NOT_FOUND)
        if voice_engine.engine_for(request.user) != voice_engine.CASCADE:
            return _refuse(request, LIVE_ONLY, status.HTTP_409_CONFLICT)
        body = AssistantTranscribeRequestSerializer(data=request.data)
        if not body.is_valid():
            return _refuse(
                request,
                f"Nothing was transcribed. The request did not fit: {body.errors}",
                status.HTTP_400_BAD_REQUEST,
            )
        upload = body.validated_data["audio"]
        if upload.size > MAX_AUDIO_BYTES:
            return _refuse(
                request,
                "Nothing was transcribed: too long.",
                status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            )
        if gemini_key() is None:
            return _refuse(request, KEY_ABSENT, status.HTTP_503_SERVICE_UNAVAILABLE)
        if member_cap.over_cap(request.user):
            return _refuse(request, member_cap.USED_UP, status.HTTP_429_TOO_MANY_REQUESTS)
        if demo_budget.counts(request.user):
            reserved = demo_budget.reserve_cascade_turn(rounds=0, spoken=True, user=request.user)
            if reserved is None:
                return _refuse(
                    request,
                    demo_budget.used_up_message(request.user, "text"),
                    status.HTTP_429_TOO_MANY_REQUESTS,
                )
        audio = upload.read(MAX_AUDIO_BYTES)
        audio = audio[: len(audio) - len(audio) % 2]
        _model, speech = _cascade_clients()
        try:
            heard = speech.transcribe(audio)
        except Exception:  # noqa: BLE001 - said in words; the page falls back to the full turn
            cascade.log.warning("assistant transcribe failed", exc_info=True)
            return _refuse(
                request, "Nothing was transcribed just now.", status.HTTP_502_BAD_GATEWAY
            )
        _audit(request, AssistantCall.SESSION, Outcome.OK)
        cascade.log.warning(
            "assistant transcribe audio_ms=%d stt_ms=%d prompt=%d out=%d",
            heard.audio_ms,
            heard.ms,
            heard.prompt_tokens,
            heard.output_tokens,
        )
        return Response({"text": heard.text, "ms": heard.ms}, headers={"Cache-Control": "no-store"})


class AssistantRoboticRequestSerializer(serializers.Serializer):
    conversation_id = serializers.UUIDField(help_text="The caller's own conversation.")
    turn = serializers.IntegerField(min_value=0, max_value=10_000, help_text="The reply's turn.")


class AssistantRoboticView(_AssistantView):
    """The `robotic` marker's hands path (voice-persona section 8): a thumbs-down on a reply that
    sounded robotic. The caller's own conversation record gets the turn number; no words are
    sent or kept. The spoken path is "that sounded robotic" (`cascade.is_robotic_remark`)."""

    throttle_classes = [AssistantTurnThrottle, AssistantTurnIpThrottle]
    audit_kind = AssistantCall.SESSION

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_robotic_create",
        summary="Mark a reply as sounding robotic (the turn number only)",
        request=AssistantRoboticRequestSerializer,
        responses={
            204: None,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            403: OpenApiResponse(
                DetailSerializer,
                description="A demo visit that did not come in on the tester code (`tester_only`).",
            ),
            404: OpenApiResponse(DetailSerializer, description="Not this caller's conversation."),
        },
    )
    def post(self, request):
        # The marker is the demo strip's button, and the strip is Kevin's tester visit's only
        # (Kevin, 2026-11-19: "B hide the whole strip"): an ordinary visit cannot drive it by hand.
        if is_recruiter(request.user) and not demo_budget.tester_visit(request.user):
            return _refuse(
                request,
                "Nothing was marked: this demo visit has no robotic marker.",
                status.HTTP_403_FORBIDDEN,
                "tester_only",
            )
        body = AssistantRoboticRequestSerializer(data=request.data)
        if not body.is_valid():
            return _refuse(
                request, f"Nothing was marked. {body.errors}", status.HTTP_400_BAD_REQUEST
            )
        conversation = body.validated_data["conversation_id"]
        # `owned` locks its row: only inside a transaction (round 2, finding 10).
        with transaction.atomic():
            mine = web_conversation.owned(request, conversation) is not None
        if not mine:
            return _refuse(
                request, "Nothing was marked: no such conversation.", status.HTTP_404_NOT_FOUND
            )
        cascade.mark_robotic(request, conversation, body.validated_data["turn"], source="tap")
        return Response(status=status.HTTP_204_NO_CONTENT)


class AssistantTurnCancelThrottle(_PerMemberThrottle):
    scope = "assistant_turn_cancel"


class AssistantTurnCancelIpThrottle(ClientIpThrottleMixin, SimpleRateThrottle):
    """The cancel route per address too (round 3 N6): every visit is a fresh user."""

    scope = "assistant_turn_cancel_ip"

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}


class AssistantTurnCancelRequestSerializer(serializers.Serializer):
    turn_id = serializers.UUIDField(help_text="The id the browser gave the turn to stop.")


class AssistantTurnCancelView(_AssistantView):
    """Stop one of the caller's own turns (2026-11-17: an interrupt must stop the engine's work,
    and the server only notices a dropped stream at its next write). Marks the turn cancelled in
    the database, from any worker or instance. The running turn looks at its stage boundaries
    (before transcription, each model round, each tool, each speech request, and every 250 ms
    while it waits on speech): from then on it starts no further paid call. What is already in
    flight runs to its end (one model round, one tool, or the speech requests already sent), and
    the turn's slot comes free when its stream ends. Idempotent; 204 whether or not the turn is
    still running; costs nothing; a row stops only the caller's own turn."""

    throttle_classes = [AssistantTurnCancelThrottle, AssistantTurnCancelIpThrottle]
    audit_kind = AssistantCall.SESSION

    @extend_schema(
        tags=ASSISTANT_TAGS,
        operation_id="api_assistant_turn_cancel_create",
        summary="Stop one of the caller's own turns (idempotent, costs nothing)",
        request=AssistantTurnCancelRequestSerializer,
        responses={
            204: None,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            429: OpenApiResponse(
                DetailSerializer,
                description="Too many stops in a short time, from this caller or this address.",
            ),
        },
    )
    def post(self, request):
        from assistant.models import TurnCancel

        body = AssistantTurnCancelRequestSerializer(data=request.data)
        if not body.is_valid():
            return _refuse(
                request,
                f"Nothing was stopped. {body.errors}",
                status.HTTP_400_BAD_REQUEST,
                "bad_request",
            )
        TurnCancel.objects.get_or_create(turn_id=body.validated_data["turn_id"], user=request.user)
        # The table only needs the last day: a turn lasts a minute at most.
        TurnCancel.objects.filter(at__lt=datetime.now(UTC) - timedelta(days=1)).delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
