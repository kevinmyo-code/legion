"""The relay's routes (ADR 0098; engine-brain ticket 04, slice E2). DARK like every agents route:
authentication, the household fence and the throttle first, then 404 "switched off" unless
`LEGION_AGENTS_TURN=on`.

`POST /api/agents/turn/<id>/tool_result`: a limb's result for a call the engine relayed to it, one
post per call (`agents/legion/relay_turn.py` holds the rules). The result is RECORDED before
anything else can refuse (no key, the daily allowance), so the device's work always counts.
Answers:
- the post that completes the paused round: the rest of the turn as Server-Sent Events, the same
  events as the turn it continues (a `turn` event with the next `stream_no` first; spoken when the
  first stream was, whether that began on `/turn` or `/turn/stream`);
- 202 `{waiting_for: [call ids]}`: calls of the round still open;
- 200 `{recorded: true, status, detail}`: recorded, no stream (`late`, `cancelled`, or the turn
  already resumed by another post: `running`);
- 503 `no_key` / 429 `member_cap` with `recorded: true`: recorded, the rest not run (`no_key`:
  the turn keeps waiting, post the same result again later; `member_cap`: the turn is closed);
- nothing recorded: 404 `no_turn`, 409 `unknown_call` / `wrong_tool`, 410 `too_late` (over 24
  hours after the call was asked), 411 `length_required`,
  413 `result_too_large`, 400 `bad_request`.

`GET /api/agents/turn/<id>`: the turn's status and the calls still waited for, whole, for the
device that was asked (`relay_turn.status_of`): how a limb that lost the stream learns its pending
calls again.
"""

from __future__ import annotations

import json
import logging
import time

from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers
from rest_framework.response import Response

from agents import views
from agents.legion import limbs, relay_resume, relay_turn
from agents.legion.relay_turn import RESULT_OUTCOMES, RelayRefusal
from agents.legion.turn import context
from agents.models import AgentTurn
from agents.runtime import events as ev
from agents.voice import allowance, pipeline
from api.schema import DetailSerializer
from assistant import voice_engine
from ingest.statements import gemini_key
from llm import cap as member_cap

log = logging.getLogger(__name__)

# The whole post, and the `result` object in it, as JSON bytes. A result past this is refused in
# words: the limb trims it or posts `failed` saying it was too long (P1 contract).
MAX_BODY_BYTES = 32 * 1024
MAX_RESULT_BYTES = 16 * 1024
MAX_WORDS_CHARS = 2_000
RESULT_DEPTH = limbs.MAX_PARAMETER_DEPTH


class AgentToolResultThrottle(views.AgentTurnThrottle):
    scope = "agents_tool_result"


class ToolResultSerializer(serializers.Serializer):
    call_id = serializers.CharField(max_length=64)
    name = serializers.RegexField(
        limbs.NAME_PATTERN,
        required=False,
        help_text="The tool the limb ran; if sent, it must be the call's own.",
    )
    outcome = serializers.ChoiceField(
        choices=list(RESULT_OUTCOMES),
        help_text="`not_run`: the limb never started it (a cancel came first, a locked screen).",
    )
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
            problem = limbs.storable_problem(result, RESULT_DEPTH)
            if problem:
                raise serializers.ValidationError(problem)
        if "\x00" in attrs["words"]:
            raise serializers.ValidationError("A NUL character (U+0000) cannot be stored.")
        return attrs


def _refuse(detail: str, code: str, http: int) -> Response:
    return Response({"detail": f"Nothing was recorded. {detail}", "code": code}, status=http)


def _gate(request):
    """Whether the rest of a turn may run now, asked only once every call is answered."""

    def gate():
        if not gemini_key():
            return (503, "no_key", views.KEY_ABSENT, False)
        if allowance.over_cap(request.user):
            return (429, "member_cap", member_cap.USED_UP, True)
        return None

    return gate


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
                    "JSON {recorded, status, detail} when nothing more streams."
                )
            ),
            202: OpenApiResponse(description="JSON {waiting_for: [call ids]}."),
            400: DetailSerializer,
            404: DetailSerializer,
            409: DetailSerializer,
            410: OpenApiResponse(
                DetailSerializer, description="`too_late`: over 24 hours after it was asked."
            ),
            411: DetailSerializer,
            413: DetailSerializer,
            429: DetailSerializer,
            503: DetailSerializer,
        },
    )
    def post(self, request, turn_id):
        arrived = time.monotonic()
        try:
            length = int(request.META.get("CONTENT_LENGTH") or "")
        except ValueError:
            # A body with no length is not read at all (Django reads none of it): refused.
            return _refuse("Send the result with its Content-Length.", "length_required", 411)
        if length > MAX_BODY_BYTES:
            return _refuse("The post is too large.", "result_too_large", 413)
        try:
            data = request.data
        except RecursionError:  # JSON nested past Python's limit: the parser does not catch it
            return _refuse("The result is nested too deep to read.", "bad_request", 400)
        body = ToolResultSerializer(data=data)
        if not body.is_valid():
            if "result_too_large" in json.dumps(body.errors):
                return _refuse(
                    f"`result` may be at most {MAX_RESULT_BYTES} bytes; trim it or post `failed`.",
                    "result_too_large",
                    413,
                )
            return _refuse(f"The post did not fit: {body.errors}", "bad_request", 400)
        try:
            recorded = relay_turn.record_result(
                request, turn_id, body.validated_data, gate=_gate(request)
            )
        except RelayRefusal as refusal:
            return _refuse(refusal.detail, refusal.code, refusal.http)
        if recorded.waiting_for:
            return Response({"waiting_for": recorded.waiting_for}, status=202)
        if recorded.refused is not None:
            http, code, words = recorded.refused
            detail = f"The result is recorded; the rest of the turn did not run. {words}"
            return Response(
                {"recorded": True, "status": recorded.status, "code": code, "detail": detail},
                status=http,
            )
        if recorded.resume is None:
            return Response(
                {
                    "recorded": True,
                    "status": recorded.status,
                    "detail": _RECORDED_WORDS.get(recorded.status, _RECORDED_WORDS["late"]),
                }
            )
        turn = AgentTurn.objects.get(pk=turn_id)
        return views._sse(_rest_of_turn(request, turn, recorded, arrived))


_RECORDED_WORDS = {
    "cancelled": "The turn was cancelled; the result is recorded.",
    "running": "The rest of the turn is already being answered; the result is recorded.",
    "late": "The turn is over; the result is recorded for the next one.",
}


class AgentTurnStatusView(views._SwitchedAgentsView):
    throttle_classes = [AgentToolResultThrottle]

    def throttled(self, request, wait):
        raise views._TurnThrottled(wait)

    @extend_schema(
        tags=views.AGENTS_TAGS,
        operation_id="api_agents_turn_status",
        summary="A relayed turn's status and the calls still waited for, for the device asked",
        responses={
            200: OpenApiResponse(
                description=(
                    "JSON {turn_id, seq, status, stream_no, calls: [tool_request data], "
                    "waiting_for: [call ids], recorded: [call ids]}."
                )
            ),
            404: DetailSerializer,
        },
    )
    def get(self, request, turn_id):
        try:
            return Response(relay_turn.status_of(request, turn_id))
        except RelayRefusal as refusal:
            return Response({"detail": refusal.detail, "code": refusal.code}, status=refusal.http)


def _rest_of_turn(request, turn, recorded, arrived: float):
    paused = recorded.resume
    said = (paused["state"].get("client_facts") or {}).get("voice") or {}
    spoken = bool(said.get("spoken"))
    model = views.GeminiClient(key=gemini_key)
    events = relay_resume.resume(
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
        # Never the exception's text: it could quote what the model read.
        log.warning("agents: a relay continuation failed")
        yield ev.error(views.FAILED_WORDS).sse()
    finally:
        stream.close()
