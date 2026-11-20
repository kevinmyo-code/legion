"""`/api/agents/*`: the engine's agent turn (ADR 0095), DARK, and ticket 01's SSE probe.

- `POST /api/agents/turn`: one turn, answered as Server-Sent Events (`agents/runtime/events.py`):
  `turn`, then `sentence`s as they are ready, `working` and `tool` as tools run, and `done`. One
  utterance: a `transcript` (JSON or form) or an `audio` clip (multipart: a 16 kHz 16-bit mono WAV
  of at most 28 s), heard and answered aloud through the engine's speech stage
  (`agents/voice/pipeline.py`, which lists every event and field). `kind` is `user` (the default)
  or `say` (`text` spoken as it is); the other turn kinds are refused as not built yet. Every
  refusal before the stream costs nothing: no speech-to-text, no model.
  **Refused unless `LEGION_AGENTS_TURN=on`** (`agents/legion/flags.py`): right after
  authentication and the household fence, before anything else, so with the switch off (the
  default) a member gets 404 "switched off" and nothing is read, written or sent to a model.
  Never for a recruiter (`household.recruiter_routes` denies the route; the turn's own route
  refuses again).
  Device token or session, as the member: every tool runs under their `visible()`.
- `POST /api/agents/turn/<id>/cancel`: marks the member's own turn cancelled; the loop checks it
  between rounds and before every tool call (plan section 4.2).
- `POST /api/agents/limb`: a device attaches on the limb contract (ADR 0098,
  `agents/legion/limbs.py`): its manifest is upserted on the row keyed by its device token, and the
  answer names the brain that answers its member's turns. Same switch as the turn: 404 while off,
  nothing stored. A browser session is not a limb (403). A recruiter is denied the route.
- `HEAD /api/agents/warm`: an empty 204, sent by a limb at wake so a cold instance starts while
  he is still speaking (plan 1116 section 4.5). Reads and writes nothing past authentication. Same
  switch: 404 while off.
- `GET /api/agents/probe/sse`: five events 200 ms apart, each with the server's clock, for
  measuring whether Cloud Run delivers a stream unbuffered (ticket 01). Reads and writes nothing.

The household's key goes from the environment into one header per Gemini request in this process
(ADR 0056); no answer here carries it.
"""

from __future__ import annotations

import io
import logging
import time
import uuid
import wave

from django.http import StreamingHttpResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiResponse, extend_schema, extend_schema_field
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.parsers import BaseParser, FormParser, JSONParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from agents.legion import flags, limbs
from agents.legion.turn import cancel, open_conversation
from agents.runtime import events as ev
from agents.runtime.model import GeminiClient
from agents.voice import allowance, pipeline, stage, streaming
from agents.voice.hedge import HedgedSpeech
from agents.voice.speech import INPUT_RATE, MAX_AUDIO_BYTES, MAX_UTTERANCE_SECONDS, GeminiSpeech
from api.schema import DetailSerializer
from assistant import voice_engine
from headunit_signin import scope as headunit_scope
from headunit_signin import spend as unit_spend
from headunit_signin.authentication import HeadUnitTokenAuthentication
from household.authentication import DeviceTokenAuthentication
from household.models import DeviceToken
from household.permissions import IsHouseholdMember
from ingest.statements import gemini_key
from llm import cap as member_cap

log = logging.getLogger(__name__)

AGENTS_TAGS = ["agents"]
OFF_WORDS = (
    "Nothing was answered: the engine's agent turn is switched off on this server "
    "(LEGION_AGENTS_TURN is not on)."
)
NOT_A_DEVICE = (
    "Nothing was stored. A limb is a device: it attaches with its own device token, not a browser "
    "session."
)
KEY_ABSENT = "Nothing was answered: the engine has no Gemini key (LEGION_GEMINI_KEY is not set)."
FAILED_WORDS = "The engine hit an error answering that, so the turn stopped."
UNIT_NO_SAY = "A head unit cannot have the engine say a line of its own; only `user` turns."
PROBE_EVENTS = 5
PROBE_GAP_S = 0.2


class AgentTurnThrottle(SimpleRateThrottle):
    scope = "agents_turn"

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": user.pk}


class AgentSayThrottle(AgentTurnThrottle):
    # `kind=say` speaks a client's own line with no model: its own, lower rate (review C4).
    scope = "agents_say"


class _AgentsView(APIView):
    authentication_classes = [DeviceTokenAuthentication, SessionAuthentication]
    permission_classes = [IsHouseholdMember]
    # Every tool's write fence is applied per call (`run_engine_tool`), as on `/mcp`.
    scope_checked_per_call = True


class _SwitchedAgentsView(_AgentsView):
    # A route that answers only while `LEGION_AGENTS_TURN=on`. (A comment, not a docstring: the
    # schema generator would publish a docstring as every subclass's description.)

    def initial(self, request, *args, **kwargs):
        # Authentication and the household fence first (a recruiter gets the fence's own 403, a
        # stranger 401/403, as every route does), then the switch: nothing below runs while off.
        super().initial(request, *args, **kwargs)
        if not flags.turn_enabled():
            raise exceptions.NotFound(OFF_WORDS)


# Voice contract versions this door serves: N and N-1, as the wire doc says, floored at 1 because
# no version 0 was ever published or spoken by a client. Today that is 1 alone.
SERVED_VERSIONS = range(max(1, limbs.CONTRACT_VERSION - 1), limbs.CONTRACT_VERSION + 1)

# A multipart body's most: the clip, its WAV header and a few form fields.
MAX_BODY_BYTES = MAX_AUDIO_BYTES + 64 * 1024


@extend_schema_field(OpenApiTypes.BINARY)
class _AudioField(serializers.FileField):
    """A file field the schema types as binary (the clip's bytes), not as a link."""


class _TurnThrottled(exceptions.APIException):
    """DRF's `Throttled`, with wire v1's `code` in its body. `wait` keeps the `Retry-After` header:
    DRF's exception handler sets it from this attribute."""

    status_code = status.HTTP_429_TOO_MANY_REQUESTS

    def __init__(self, wait=None):
        self.wait = None if wait is None else max(int(wait), 1)
        super().__init__(
            detail={
                "detail": "Nothing was answered: too many turns in a short time.",
                "code": "throttled",
            }
        )


class TurnRequestSerializer(serializers.Serializer):
    transcript = serializers.CharField(
        max_length=4000,
        trim_whitespace=True,
        required=False,
        help_text="What was said, as text. Send this OR `audio`.",
    )
    audio = _AudioField(
        required=False,
        allow_empty_file=False,
        help_text=(
            f"One utterance: a WAV of 16 kHz 16-bit mono PCM, at most {MAX_UTTERANCE_SECONDS} "
            "seconds (multipart). Transcribed and answered aloud. The audio is never stored; the "
            "transcript is kept on the turn row, like every text turn."
        ),
    )
    kind = serializers.ChoiceField(
        choices=list(pipeline.KINDS),
        default="user",
        help_text="`user` (his words) or `say` (`text` spoken as it is). Others: not built yet.",
    )
    text = serializers.CharField(
        max_length=pipeline.MAX_SAY_CHARS,
        trim_whitespace=True,
        required=False,
        help_text="With `kind=say`: the line to speak.",
    )
    speak = serializers.BooleanField(
        required=False,
        default=False,
        help_text="With `transcript`: answer aloud too (`audio` events).",
    )
    conversation_id = serializers.UUIDField(required=False, allow_null=True)
    client = serializers.ChoiceField(choices=["phone", "web", "headunit"], default="phone")
    contract_version = serializers.IntegerField(
        required=False,
        help_text=(
            "The voice contract the client speaks (wire v1: 1). Another version is refused with "
            "`contract_unsupported`."
        ),
    )


def _refuse(detail: str, code: str, http: int) -> Response:
    return Response({"detail": f"Nothing was answered. {detail}", "code": code}, status=http)


def _shape_refusal(data) -> Response | None:
    """One utterance per turn, of a kind that is built."""
    kind, has_audio = data["kind"], data.get("audio") is not None
    if kind not in pipeline.BUILT_KINDS:
        return _refuse(
            f"The `{kind}` turn kind is not built on the engine yet; only `user` and `say` are.",
            "kind_not_built",
            status.HTTP_400_BAD_REQUEST,
        )
    if kind == "say":
        if not data.get("text") or has_audio or data.get("transcript"):
            return _refuse(
                "A `say` turn carries `text` only.", "bad_request", status.HTTP_400_BAD_REQUEST
            )
        return None
    if has_audio == bool(data.get("transcript")):
        return _refuse(
            "Send one utterance as `audio` or one `transcript`.",
            "bad_request",
            status.HTTP_400_BAD_REQUEST,
        )
    return None


def _pcm_of(upload) -> tuple[bytes | None, Response | None]:
    """The clip's samples, or the refusal: 16 kHz, 16-bit, mono, uncompressed WAV, at most
    `MAX_UTTERANCE_SECONDS`, judged on its frames (the web door judges raw PCM on its bytes)."""
    if upload.size > MAX_AUDIO_BYTES + 4096:
        return None, _too_long()
    try:
        with wave.open(io.BytesIO(upload.read(MAX_AUDIO_BYTES + 4097)), "rb") as clip:
            shape = (clip.getnchannels(), clip.getsampwidth(), clip.getframerate())
            frames = clip.getnframes()
            if clip.getcomptype() != "NONE" or shape != (1, 2, INPUT_RATE):
                return None, _bad_audio()
            if frames > MAX_UTTERANCE_SECONDS * INPUT_RATE:
                return None, _too_long()
            pcm = clip.readframes(frames)
    except (wave.Error, EOFError):
        return None, _bad_audio()
    return pcm[: len(pcm) - len(pcm) % 2], None


def _too_long() -> Response:
    return _refuse(
        f"An utterance may last at most {MAX_UTTERANCE_SECONDS} seconds.",
        "too_long",
        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
    )


def _bad_audio() -> Response:
    return _refuse(
        "`audio` must be a WAV of 16 kHz 16-bit mono PCM.",
        "bad_audio",
        status.HTTP_400_BAD_REQUEST,
    )


def _speech_client():
    speech = GeminiSpeech(gemini_key, stt_model=voice_engine.text_model())
    return HedgedSpeech(speech) if flags.stt_hedge_enabled() else speech


def _stream(request, data, utterance, arrived_at=None):
    unit = request.auth if headunit_scope.is_headunit(request) else None
    before = unit_spend.member_spent(request.user) if unit is not None else 0
    speech = _speech_client() if utterance.spoken else None
    turn = pipeline.voice_turn(
        request,
        utterance,
        lambda: GeminiClient(key=gemini_key),
        speech,
        conversation_for=lambda: open_conversation(
            request, data.get("conversation_id"), data["client"]
        ),
        voice=voice_engine.voice_for(request.user),
        arrived_at=arrived_at,
    )
    try:
        for event in turn:
            yield event.sse()
    except Exception:  # noqa: BLE001 - the stream must end in words, always
        log.warning("agents: turn failed", exc_info=True)
        yield ev.error(FAILED_WORDS).sse()
    finally:
        # A hang-up closes this stream: the turn's speech stops and is metered now, on this
        # request, not whenever the generator is collected (review C3).
        turn.close()
        if unit is not None:
            unit_spend.charge(unit, unit_spend.member_spent(request.user) - before)


def _sse(body) -> StreamingHttpResponse:
    response = StreamingHttpResponse(body, content_type="text/event-stream")
    response["Cache-Control"] = "no-cache, no-store"
    response["X-Accel-Buffering"] = "no"
    return response


class AgentTurnView(_SwitchedAgentsView):
    # T3b: the head unit's own token too (`headunit_signin.scope`); its turn reaches only the
    # unit's tools (`agents.legion.gateway`) and its own conversations (`agents.legion.turn`).
    authentication_classes = [
        HeadUnitTokenAuthentication,
        DeviceTokenAuthentication,
        SessionAuthentication,
    ]
    throttle_classes = [AgentTurnThrottle]
    parser_classes = [JSONParser, MultiPartParser, FormParser]

    def throttled(self, request, wait):
        raise _TurnThrottled(wait)

    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_turn_create",
        summary="One agent turn, answered as Server-Sent Events (dark: off unless switched on)",
        request={
            "application/json": TurnRequestSerializer,
            "multipart/form-data": TurnRequestSerializer,
            "application/x-www-form-urlencoded": TurnRequestSerializer,
        },
        responses={
            200: OpenApiResponse(
                description=(
                    "text/event-stream: turn, heard, crisis, working, filler, tool, sentence, "
                    "audio, speech_failed, speech_stopped, done, error "
                    "(`agents/voice/pipeline.py`)"
                )
            ),
            400: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="The agent turn is switched off."),
            413: OpenApiResponse(DetailSerializer, description="The utterance is too long."),
            429: OpenApiResponse(DetailSerializer, description="The member's daily cap is spent."),
            503: OpenApiResponse(DetailSerializer, description="No Gemini key on this engine."),
        },
    )
    def post(self, request):
        # `done.timing` counts from here (wire v1: offsets from the request).
        arrived = time.monotonic()
        try:
            length = int(request.META.get("CONTENT_LENGTH") or 0)
        except ValueError:
            length = 0
        if length > MAX_BODY_BYTES:
            return _too_long()
        body = TurnRequestSerializer(data=request.data)
        if not body.is_valid():
            return _refuse(
                f"The request did not fit: {body.errors}",
                "bad_request",
                status.HTTP_400_BAD_REQUEST,
            )
        data = body.validated_data
        version = data.get("contract_version")
        if version is not None and version not in SERVED_VERSIONS:
            return _refuse(
                f"This engine serves voice contract {limbs.CONTRACT_VERSION}; this client speaks "
                f"{version}. Update the app.",
                limbs.CODE_UNSUPPORTED,
                status.HTTP_409_CONFLICT,
            )
        refusal = _shape_refusal(data)
        if refusal is not None:
            return refusal
        if not gemini_key():
            return Response(
                {"detail": KEY_ABSENT, "code": "no_key"},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        unit = request.auth if headunit_scope.is_headunit(request) else None
        if unit is not None:
            # T3b review B6: a head unit never has the engine say a line of its choosing, and
            # spends its own daily cap inside the member's.
            if data["kind"] == "say":
                return _refuse(UNIT_NO_SAY, "headunit_say", status.HTTP_403_FORBIDDEN)
            if unit_spend.over_cap(unit):
                return _refuse(
                    unit_spend.USED_UP, "headunit_cap", status.HTTP_429_TOO_MANY_REQUESTS
                )
        if data["kind"] == "say" and not AgentSayThrottle().allow_request(request, self):
            return _refuse(
                "Too many spoken lines in a short time.",
                "throttled",
                status.HTTP_429_TOO_MANY_REQUESTS,
            )
        if allowance.over_cap(request.user):
            # Before any paid speech-to-text (the route's own cap check runs after it).
            return _refuse(member_cap.USED_UP, "member_cap", status.HTTP_429_TOO_MANY_REQUESTS)
        pcm = None
        if data.get("audio") is not None:
            pcm, refusal = _pcm_of(data["audio"])
            if refusal is not None:
                return refusal
        utterance = pipeline.Utterance(
            kind=data["kind"],
            audio=pcm,
            transcript=data.get("transcript"),
            text=data.get("text"),
            speak=bool(data.get("speak")),
        )
        return _sse(_stream(request, data, utterance, arrived))


STREAM_OFF_WORDS = (
    "Nothing was answered: streamed speech-to-text is switched off on this server "
    "(LEGION_AGENTS_STREAM_STT is not on)."
)


class _UnreadBody(BaseParser):
    # Accepts any body and reads none of it: the stream door reads its own frames.
    media_type = "*/*"

    def parse(self, stream, media_type=None, parser_context=None):
        return {}


class AgentTurnStreamView(_SwitchedAgentsView):
    # One utterance as one long request (`agents/voice/streaming.py`): the body is framed audio,
    # sent while he speaks; the answer streams back as Server-Sent Events after the end frame.
    throttle_classes = [AgentTurnThrottle]
    # The body is read raw, frame by frame (`streaming.FrameReader`); DRF never parses it.
    parser_classes = [_UnreadBody]

    def throttled(self, request, wait):
        raise _TurnThrottled(wait)

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        if not flags.stream_stt_enabled():
            raise exceptions.NotFound(STREAM_OFF_WORDS)

    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_turn_stream_create",
        summary=(
            "One spoken turn streamed in while it is spoken, answered as Server-Sent Events "
            "(dark: off unless both switches are on)"
        ),
        description=(
            "The body is frames, `<type: 1 byte><length: 4 bytes big-endian><payload>`: `M` "
            "metadata JSON first (conversation_id, client, contract_version, probe), then `A` "
            "audio (16 kHz 16-bit mono PCM) as it is spoken, then `E` the client's end of turn, "
            'JSON {"bytes": audio total}. The events are those of `POST /api/agents/turn`. With '
            '`probe: true` (and `T` frames, JSON {"client_ms"}) the answer is JSON listing when '
            "each audio frame arrived: no model, no cost. The audio is never stored; the "
            "transcript is kept on the turn row."
        ),
        request={"application/octet-stream": OpenApiTypes.BINARY},
        responses={
            200: OpenApiResponse(description="text/event-stream, or the probe's JSON."),
            400: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="A switch is off."),
            409: DetailSerializer,
            413: DetailSerializer,
            429: DetailSerializer,
            503: DetailSerializer,
        },
    )
    def post(self, request):
        arrived = time.monotonic()
        try:
            length = int(request.META.get("CONTENT_LENGTH") or "")
        except ValueError:
            length = None
        reader = streaming.FrameReader(request.META.get("wsgi.input"), length)
        try:
            first = reader.next()
            if first is None or first[0] != b"M":
                return _refuse("The first frame must be the metadata.", "bad_request", 400)
            meta = streaming.json_payload(first[1])
        except streaming.FrameError as exc:
            return _refuse(exc.detail, exc.code, _FRAME_STATUS.get(exc.code, 400))
        version = meta.get("contract_version")
        if version is not None and version not in SERVED_VERSIONS:
            return _refuse(
                f"This engine serves voice contract {limbs.CONTRACT_VERSION}; this client speaks "
                f"{version}. Update the app.",
                limbs.CODE_UNSUPPORTED,
                status.HTTP_409_CONFLICT,
            )
        if allowance.over_cap(request.user):
            # Before the probe or any session reads on (review 1120, finding 2).
            return _refuse(member_cap.USED_UP, "member_cap", status.HTTP_429_TOO_MANY_REQUESTS)
        if meta.get("probe") is True:
            try:
                return Response(
                    streaming.probe(reader, arrived_at=arrived, max_audio_bytes=MAX_AUDIO_BYTES)
                )
            except streaming.FrameError as exc:
                return _refuse(exc.detail, exc.code, _FRAME_STATUS.get(exc.code, 400))
        data, refusal = _stream_meta(meta)
        if refusal is not None:
            return refusal
        key = gemini_key()
        if not key:
            return Response(
                {"detail": KEY_ABSENT, "code": "no_key"},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        try:
            streamed = streaming.take_utterance(
                reader, key=key, max_audio_bytes=MAX_AUDIO_BYTES, arrived_at=arrived
            )
        except streaming.FrameError as exc:
            prompt, output = getattr(exc, "spent", (0, 0))
            if prompt or output:
                stage.meter_speech(
                    request,
                    {"stt_stream_prompt_tokens": prompt, "stt_stream_output_tokens": output},
                    pipeline.FEATURE,
                )
            return _refuse(exc.detail, exc.code, _FRAME_STATUS.get(exc.code, 400))
        utterance = pipeline.Utterance(kind="user", audio=streamed.pcm, streamed=streamed)
        return _sse(_stream(request, data, utterance, arrived))


_FRAME_STATUS = {"too_long": status.HTTP_413_REQUEST_ENTITY_TOO_LARGE}


def _stream_meta(meta: dict) -> tuple[dict, Response | None]:
    """The metadata frame's fields, checked as the form door checks them."""
    client = meta.get("client", "phone")
    if client not in ("phone", "web", "headunit"):
        return {}, _refuse("`client` is phone, web or headunit.", "bad_request", 400)
    conversation = meta.get("conversation_id")
    if conversation is not None:
        try:
            conversation = uuid.UUID(str(conversation))
        except ValueError:
            return {}, _refuse("`conversation_id` is not an id.", "bad_request", 400)
    return {"client": client, "conversation_id": conversation}, None


class AgentCancelView(_SwitchedAgentsView):
    authentication_classes = AgentTurnView.authentication_classes

    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_turn_cancel",
        summary="Cancel the member's own running turn",
        request=None,
        responses={200: DetailSerializer, 404: DetailSerializer},
    )
    def post(self, request, turn_id):
        if not cancel(request, turn_id):
            return Response(
                {"detail": "No turn of yours with that id; nothing was cancelled."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response({"detail": "Cancelled: no further round or tool call will start."})


def _body_refusal(request) -> Response | None:
    """The body's size, from its declared length, before anything parses it. Django does not bound
    a body DRF's parser reads from the stream, so this is the cap."""
    try:
        length = int(request.META.get("CONTENT_LENGTH") or "")
    except ValueError:
        return Response(
            {
                "detail": "Nothing was stored. Send the manifest with its Content-Length.",
                "code": limbs.CODE_LENGTH_REQUIRED,
            },
            status=status.HTTP_411_LENGTH_REQUIRED,
        )
    if length > limbs.MAX_MANIFEST_BYTES:
        return Response(
            {
                "detail": (
                    f"Nothing was stored. The manifest is {length} bytes; the limit is "
                    f"{limbs.MAX_MANIFEST_BYTES}."
                ),
                "code": limbs.CODE_TOO_LARGE,
            },
            status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        )
    return None


def _contract_version(data) -> int | None:
    try:
        return serializers.IntegerField().run_validation(data.get("contract_version"))
    except (AttributeError, serializers.ValidationError):
        return None  # missing or not a number: the manifest's own validation says so


class AgentLimbView(_SwitchedAgentsView):
    # The manifest upload is one plain write: a read-scoped token is refused at the household
    # fence like any REST write (the base opts out only because the turn checks per tool call).
    scope_checked_per_call = False

    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_limb_attach",
        summary="Attach this device as a limb: upload its manifest, learn which brain answers",
        request=limbs.LimbManifestSerializer,
        responses={
            200: limbs.LimbAnswerSerializer,
            400: limbs.LimbRefusalSerializer,
            403: limbs.LimbRefusalSerializer,
            404: OpenApiResponse(DetailSerializer, description="The agent turn is switched off."),
            409: OpenApiResponse(
                limbs.LimbRefusalSerializer,
                description="A contract version this engine does not serve.",
            ),
            411: OpenApiResponse(limbs.LimbRefusalSerializer, description="No Content-Length."),
            413: OpenApiResponse(
                limbs.LimbRefusalSerializer, description="A body over the manifest cap."
            ),
        },
    )
    def post(self, request):
        if not isinstance(request.auth, DeviceToken):
            return Response(
                {"detail": NOT_A_DEVICE, "code": limbs.CODE_NOT_A_DEVICE},
                status=status.HTTP_403_FORBIDDEN,
            )
        refusal = _body_refusal(request)
        if refusal is not None:
            return refusal
        try:
            data = request.data
        except RecursionError:  # JSON nested past Python's limit: the parser does not catch it
            return Response(
                {
                    "detail": "Nothing was stored. The manifest is nested too deep to read.",
                    "code": limbs.CODE_INVALID,
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        # The version is read first: another version's manifest may have another shape, and the
        # client needs "update the app", not a list of field errors.
        version = _contract_version(data)
        if version is not None and version not in SERVED_VERSIONS:
            return Response(
                {
                    "detail": (
                        f"Nothing was stored. This engine serves limb contract "
                        f"{limbs.CONTRACT_VERSION}; this app speaks {version}. Update the app."
                    ),
                    "code": limbs.CODE_UNSUPPORTED,
                    "contract_version": limbs.CONTRACT_VERSION,
                },
                status=status.HTTP_409_CONFLICT,
            )
        body = limbs.LimbManifestSerializer(data=data)
        if not body.is_valid():
            return Response(
                {
                    "detail": f"Nothing was stored. The manifest did not fit: {body.errors}",
                    "code": limbs.CODE_INVALID,
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        return Response(limbs.attach(request, body.validated_data))


class AgentWarmView(_SwitchedAgentsView):
    # HEAD only, and drf-spectacular leaves HEAD out of `openapi.yaml`; the route is described in
    # this module's docstring instead.
    def head(self, request):
        return Response(status=status.HTTP_204_NO_CONTENT)


def _probe():
    start = time.monotonic()
    for i in range(PROBE_EVENTS):
        if i:
            time.sleep(PROBE_GAP_S)
        yield ev.Event("probe", {"i": i, "server_ms": int((time.monotonic() - start) * 1000)}).sse()


class AgentProbeSseView(_AgentsView):
    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_probe_sse",
        summary="Five events 200 ms apart, to measure streaming through Cloud Run",
        responses={200: OpenApiResponse(description="text/event-stream: probe x5")},
    )
    def get(self, request):
        return _sse(_probe())
