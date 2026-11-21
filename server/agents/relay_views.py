"""`POST /api/agents/turn/<id>/tool_result`: a limb's result for a call the engine relayed to it
(ADR 0098; engine-brain ticket 04, slice E2). DARK like every agents route: authentication, the
household fence and the throttle first, then 404 "switched off" unless `LEGION_AGENTS_TURN=on`.

One post per call (`agents/legion/relay_turn.py` holds the rules). Answers:
- the post that completes the paused round: the rest of the turn as Server-Sent Events, the same
  events as the turn it continues (a `turn` event with the next `stream_no` first; spoken when the
  first stream was, whether that began on `/turn` or `/turn/stream`);
- 202 `{waiting_for: [call ids]}`: calls of the round still open;
- 200 `{recorded: true, status}`: recorded, no stream (`late`, `cancelled`, or the turn already
  resumed by another post: `running`, `done`);
- 404 `no_turn`, 409 `unknown_call` / `wrong_tool`, 413 `result_too_large`, 400 `bad_request`:
  nothing recorded.
"""

from __future__ import annotations

import json
import logging
import time

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response

from agents import views
from agents.legion import limbs, relay_turn
from agents.legion.relay_turn import RESULT_OUTCOMES, RelayRefusal
from agents.legion.turn import context
from agents.models import AgentTurn
from agents.runtime import events as ev
from agents.voice import pipeline
from api.schema import DetailSerializer
from assistant import voice_engine
from ingest.statements import gemini_key

log = logging.getLogger(__name__)

# The whole post, and the `result` object in it, as JSON bytes. A result past this is refused in
# words: the limb trims it or posts `failed` saying it was too long (P1 contract).
MAX_BODY_BYTES = 32 * 1024
MAX_RESULT_BYTES = 16 * 1024
MAX_WORDS_CHARS = 2_000


class AgentToolResultThrottle(views.AgentTurnThrottle):
    scope = "agents_tool_result"


class ToolResultSerializer(serializers.Serializer):
    call_id = serializers.CharField(max_length=64)
    name = serializers.RegexField(
        limbs.NAME_PATTERN,
        required=False,
        help_text="The tool the limb ran; if sent, it must be the call's own.",
    )
    outcome = serializers.ChoiceField(choices=list(RESULT_OUTCOMES))
    result = serializers.DictField(required=False, help_text="The tool's output, when `ok`.")
    words = serializers.CharField(
        max_length=MAX_WORDS_CHARS, help_text="What did or did not happen, in words."
    )
    ms = serializers.IntegerField(required=False, min_value=0)
    # Wire v1's report (section 5a): accepted, not yet used (the fact ledger is owed).
    played = serializers.ListField(child=serializers.CharField(max_length=64), required=False)
    cut_off = serializers.BooleanField(required=False)

    def validate(self, attrs):
        result = attrs.get("result")
        if result is not None:
            if len(json.dumps(result).encode()) > MAX_RESULT_BYTES:
                raise serializers.ValidationError("result_too_large")
            problem = limbs.storable_problem(result, limbs.MAX_PARAMETER_DEPTH)
            if problem:
                raise serializers.ValidationError(problem)
        if "\x00" in attrs["words"]:
            raise serializers.ValidationError("A NUL character (U+0000) cannot be stored.")
        return attrs


def _refuse(detail: str, code: str, http: int) -> Response:
    return Response({"detail": f"Nothing was recorded. {detail}", "code": code}, status=http)


class AgentToolResultView(views._SwitchedAgentsView):
    throttle_classes = [AgentToolResultThrottle]

    def throttled(self, request, wait):
        raise views._TurnThrottled(wait)

    @extend_schema(
        tags=views.AGENTS_TAGS,
        operation_id="api_agents_turn_tool_result",
        summary="A limb's result for a relayed call; the round's last one streams the rest",
        request=ToolResultSerializer,
        responses={
            200: OpenApiResponse(
                description=(
                    "text/event-stream: the rest of the turn (as `POST /api/agents/turn`), or "
                    "JSON {recorded, status} when nothing more streams."
                )
            ),
            202: OpenApiResponse(description="JSON {waiting_for: [call ids]}."),
            400: DetailSerializer,
            404: DetailSerializer,
            409: DetailSerializer,
            413: DetailSerializer,
            503: DetailSerializer,
        },
    )
    def post(self, request, turn_id):
        arrived = time.monotonic()
        try:
            length = int(request.META.get("CONTENT_LENGTH") or 0)
        except ValueError:
            length = 0
        if length > MAX_BODY_BYTES:
            return _refuse("The post is too large.", "result_too_large", 413)
        body = ToolResultSerializer(data=request.data)
        if not body.is_valid():
            if "result_too_large" in json.dumps(body.errors):
                return _refuse(
                    f"`result` may be at most {MAX_RESULT_BYTES} bytes; trim it or post `failed`.",
                    "result_too_large",
                    413,
                )
            return _refuse(f"The post did not fit: {body.errors}", "bad_request", 400)
        if not gemini_key():
            return Response(
                {"detail": views.KEY_ABSENT, "code": "no_key"},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        try:
            recorded = relay_turn.record_result(request, turn_id, body.validated_data)
        except RelayRefusal as refusal:
            return _refuse(refusal.detail, refusal.code, refusal.http)
        if recorded.waiting_for:
            return Response({"waiting_for": recorded.waiting_for}, status=202)
        if recorded.resume is None:
            return Response({"recorded": True, "status": recorded.status})
        turn = AgentTurn.objects.get(pk=turn_id)
        return views._sse(_rest_of_turn(request, turn, recorded, arrived))


def _rest_of_turn(request, turn, recorded, arrived: float):
    paused = recorded.resume
    said = (paused["state"].get("client_facts") or {}).get("voice") or {}
    spoken = bool(said.get("spoken"))
    model = views.GeminiClient(key=gemini_key)
    events = relay_turn.resume(
        request,
        turn,
        paused,
        model,
        context=lambda r, store, started: context(r, store, started, model=model),
        fresh=recorded.fresh,
    )
    stream = pipeline.voice_resume(
        request,
        events,
        views._speech_client() if spoken else None,
        voice=voice_engine.voice_for(request.user),
        spoken=spoken,
        exact=bool(said.get("exact")),
        arrived_at=arrived,
    )
    try:
        for event in stream:
            yield event.sse()
    except Exception:  # noqa: BLE001 - the stream must end in words, always
        log.warning("agents: a relay continuation failed", exc_info=True)
        yield ev.error(views.FAILED_WORDS).sse()
    finally:
        stream.close()
