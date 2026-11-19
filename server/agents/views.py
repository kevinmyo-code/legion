"""`/api/agents/*`: the engine's agent turn (ADR 0095), DARK, and ticket 01's SSE probe.

- `POST /api/agents/turn`: one turn, answered as Server-Sent Events (`agents/runtime/events.py`):
  `turn`, then `sentence`s as they are ready, `working` and `tool` as tools run, and `done`.
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

import logging
import time

from django.http import StreamingHttpResponse
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from agents.legion import flags, limbs
from agents.legion.turn import cancel, open_conversation, run_turn
from agents.runtime import events as ev
from agents.runtime.model import GeminiClient
from api.schema import DetailSerializer
from household.authentication import DeviceTokenAuthentication
from household.models import DeviceToken
from household.permissions import IsHouseholdMember
from ingest.statements import gemini_key

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
PROBE_EVENTS = 5
PROBE_GAP_S = 0.2


class AgentTurnThrottle(SimpleRateThrottle):
    scope = "agents_turn"

    def get_cache_key(self, request, view):
        user = getattr(request, "user", None)
        if user is None or not user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": user.pk}


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


class TurnRequestSerializer(serializers.Serializer):
    transcript = serializers.CharField(max_length=4000, trim_whitespace=True)
    conversation_id = serializers.UUIDField(required=False, allow_null=True)
    client = serializers.ChoiceField(choices=["phone", "web", "headunit"], default="phone")


def _stream(request, data):
    conversation = open_conversation(request, data.get("conversation_id"), data["client"])
    model = GeminiClient(key=gemini_key)
    try:
        for event in run_turn(request, data["transcript"], model, conversation=conversation):
            yield event.sse()
    except Exception:  # noqa: BLE001 - the stream must end in words, always
        log.warning("agents: turn failed", exc_info=True)
        yield ev.error(FAILED_WORDS).sse()


def _sse(body) -> StreamingHttpResponse:
    response = StreamingHttpResponse(body, content_type="text/event-stream")
    response["Cache-Control"] = "no-cache, no-store"
    response["X-Accel-Buffering"] = "no"
    return response


class AgentTurnView(_SwitchedAgentsView):
    throttle_classes = [AgentTurnThrottle]

    @extend_schema(
        tags=AGENTS_TAGS,
        operation_id="api_agents_turn_create",
        summary="One agent turn, answered as Server-Sent Events (dark: off unless switched on)",
        request=TurnRequestSerializer,
        responses={
            200: OpenApiResponse(description="text/event-stream: turn, sentence, tool, done"),
            400: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="The agent turn is switched off."),
            503: OpenApiResponse(DetailSerializer, description="No Gemini key on this engine."),
        },
    )
    def post(self, request):
        body = TurnRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was answered. The request did not fit: {body.errors}"},
                status=status.HTTP_400_BAD_REQUEST,
            )
        if not gemini_key():
            return Response({"detail": KEY_ABSENT}, status=status.HTTP_503_SERVICE_UNAVAILABLE)
        return _sse(_stream(request, body.validated_data))


class AgentCancelView(_SwitchedAgentsView):
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
            400: DetailSerializer,
            403: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="The agent turn is switched off."),
            409: OpenApiResponse(
                DetailSerializer, description="A contract version this engine does not serve."
            ),
        },
    )
    def post(self, request):
        if not isinstance(request.auth, DeviceToken):
            return Response({"detail": NOT_A_DEVICE}, status=status.HTTP_403_FORBIDDEN)
        # The version is read first: another version's manifest may have another shape, and the
        # client needs "update the app", not a list of field errors.
        version = _contract_version(request.data)
        if version is not None and version != limbs.CONTRACT_VERSION:
            return Response(
                {
                    "detail": (
                        f"Nothing was stored. This engine serves limb contract "
                        f"{limbs.CONTRACT_VERSION}; this app speaks {version}. Update the app."
                    ),
                    "contract_version": limbs.CONTRACT_VERSION,
                },
                status=status.HTTP_409_CONFLICT,
            )
        body = limbs.LimbManifestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was stored. The manifest did not fit: {body.errors}"},
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
