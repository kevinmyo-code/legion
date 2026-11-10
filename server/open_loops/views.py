"""`/api/open-loops/`: proposed to-dos over REST (ADR 0085). The phone's brief line, its voice answers
(`manage_item` add/remove resolved against these rows) and its Proposed to-dos list all call
these (ADR 0035). Every route runs `service.door` first (recruiters, Claude worker tokens of any
scope, the work laptop, demo households), then reads through `visible()`: another member's
proposal is a 404 or simply absent.

- `GET  /api/open-loops/`               pending proposals (nothing counted)
- `POST /api/open-loops/offer`          the spoken brief's batch (each counted as carried)
- `POST /api/open-loops/<id>/track`     his yes: a dated reminder or an undated to-do
- `POST /api/open-loops/<id>/dismiss`   his no, for good
- `PUT  /api/open-loops/settings`       "stop proposing tasks" and back

The worker writes proposals only through the memory review's `POST review/changes`.
No route serves when a proposal was made or said.
"""

from __future__ import annotations

from drf_spectacular.utils import extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from open_loops import service
from open_loops.models import STATUSES, TASK_KINDS
from open_loops.service import LoopRefused

TAGS = ["open-loops"]


class OpenLoopProposalSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    title = serializers.CharField(help_text="The task, in the member's own words.")
    due_date = serializers.DateField(
        allow_null=True, help_text="Only a date his own words printed; null when he named none."
    )
    status = serializers.ChoiceField(choices=STATUSES)


class OpenLoopListingSerializer(serializers.Serializer):
    on = serializers.BooleanField(help_text="False when LEGION_OPEN_LOOPS is off on this engine.")
    proposing = serializers.BooleanField(help_text="False after \"stop proposing tasks\".")
    proposals = OpenLoopProposalSerializer(many=True)
    words = serializers.CharField()


class OpenLoopTrackSerializer(serializers.Serializer):
    due_date = serializers.CharField(
        required=False, allow_null=True, allow_blank=True,
        help_text="A date the member named in his answer (YYYY-MM-DD). Omit otherwise.",
    )


class OpenLoopTrackedSerializer(serializers.Serializer):
    proposal = OpenLoopProposalSerializer()
    task_kind = serializers.ChoiceField(choices=TASK_KINDS)
    task_id = serializers.UUIDField(allow_null=True)
    words = serializers.CharField()


class OpenLoopDismissedSerializer(serializers.Serializer):
    proposal = OpenLoopProposalSerializer()
    words = serializers.CharField()


class OpenLoopSettingsSerializer(serializers.Serializer):
    proposing = serializers.BooleanField()


class OpenLoopSettingsAnswerSerializer(serializers.Serializer):
    proposing = serializers.BooleanField()
    words = serializers.CharField()


def _from(exc: LoopRefused):
    return Response({"detail": exc.message}, status=exc.status)


REFUSALS = {400: DetailSerializer, 403: DetailSerializer}


class OpenLoopListView(APIView):
    @extend_schema(
        operation_id="api_open_loops_list",
        tags=TAGS,
        responses={200: OpenLoopListingSerializer, 403: DetailSerializer},
        description="The caller's pending proposed to-dos, oldest first. Counts nothing.",
    )
    def get(self, request):
        try:
            return Response(service.list_for(request))
        except LoopRefused as exc:
            return _from(exc)


class OpenLoopOfferView(APIView):
    @extend_schema(
        operation_id="api_open_loops_offer",
        tags=TAGS,
        request=None,
        responses={200: OpenLoopListingSerializer, 403: DetailSerializer},
        description=(
            "The spoken brief's batch: pending proposals carried by fewer than two spoken briefs, "
            "each now counted as carried once more."
        ),
    )
    def post(self, request):
        try:
            return Response(service.offer(request))
        except LoopRefused as exc:
            return _from(exc)


class OpenLoopTrackView(APIView):
    @extend_schema(
        operation_id="api_open_loops_track",
        tags=TAGS,
        request=OpenLoopTrackSerializer,
        responses={200: OpenLoopTrackedSerializer, **REFUSALS, 404: DetailSerializer, 409: DetailSerializer},
        description=(
            "The member's yes. With a date (his own words, or `due_date` from his answer) it "
            "becomes a reminder in `events`; without one, a line on his Todo checklist. A second "
            "call answers with the task the first one made."
        ),
    )
    def post(self, request, loop_id):
        body = OpenLoopTrackSerializer(data=request.data)
        if not body.is_valid():
            return Response({"detail": "Nothing was tracked. due_date must be text."},
                            status=status.HTTP_400_BAD_REQUEST)
        try:
            return Response(service.track(request, loop_id, body.validated_data.get("due_date")))
        except LoopRefused as exc:
            return _from(exc)


class OpenLoopDismissView(APIView):
    @extend_schema(
        operation_id="api_open_loops_dismiss",
        tags=TAGS,
        request=None,
        responses={200: OpenLoopDismissedSerializer, **REFUSALS, 404: DetailSerializer,
                   409: DetailSerializer},
        description="The member's no. That commitment is never proposed again.",
    )
    def post(self, request, loop_id):
        try:
            return Response(service.dismiss(request, loop_id))
        except LoopRefused as exc:
            return _from(exc)


class OpenLoopSettingsView(APIView):
    @extend_schema(
        operation_id="api_open_loops_settings",
        tags=TAGS,
        request=OpenLoopSettingsSerializer,
        responses={200: OpenLoopSettingsAnswerSerializer, **REFUSALS},
        description="\"Stop proposing tasks\" (false) and back (true). Stops extraction too.",
    )
    def put(self, request):
        try:
            return Response(service.set_proposing(request, request.data.get("proposing")))
        except LoopRefused as exc:
            return _from(exc)
