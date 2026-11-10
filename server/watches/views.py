"""`/api/watches/`: standing watches over REST (ADR 0083). The phone's `watch` voice tool and its
Watches screen both call these (ADR 0035). Every route runs `service.refusal` first (recruiters,
Claude worker tokens, demo visits), then reads through `visible()`: another member's watch or
firing is a 404 or simply absent.

- `GET  /api/watches/`               the caller's watches (built-ins seeded on first read), kinds
- `POST /api/watches/`               set one (kind + params); 400 names the kinds when none fits
- `PATCH/DELETE /api/watches/<id>`   pause, resume, edit; "stop watching X"
- `GET  /api/watches/firings`        `?pending=1`: not said yet; otherwise recent history
- `POST /api/watches/firings/ack`    the phone said (or posted, or silenced) these
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from watches import service
from watches.models import KINDS, OUTCOMES, SOURCE_SCREEN, SOURCE_VOICE
from watches.registry import WatchRefused

TAGS = ["watches"]


class WatchSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    kind = serializers.ChoiceField(choices=KINDS)
    title = serializers.CharField()
    label = serializers.CharField()
    params = serializers.DictField()
    repeat = serializers.BooleanField()
    paused = serializers.BooleanField()
    status = serializers.ChoiceField(choices=("on", "paused", "done", "expired"))
    builtin = serializers.CharField(allow_null=True)
    evaluated_on = serializers.ChoiceField(choices=("engine", "phone"))
    expires_at = serializers.DateTimeField(allow_null=True)
    last_fired_at = serializers.DateTimeField(allow_null=True)
    created_at = serializers.DateTimeField(allow_null=True)


class KindSerializer(serializers.Serializer):
    kind = serializers.ChoiceField(choices=KINDS)
    title = serializers.CharField()
    says = serializers.CharField()
    needs = serializers.CharField()
    evaluated_on = serializers.CharField()
    params = serializers.ListField(child=serializers.DictField())


class WatchListSerializer(serializers.Serializer):
    results = WatchSerializer(many=True)
    kinds = KindSerializer(many=True)


class WatchCreateSerializer(serializers.Serializer):
    kind = serializers.CharField()
    params = serializers.JSONField(required=False, default=dict)
    label = serializers.CharField(required=False, allow_blank=True, allow_null=True)
    repeat = serializers.BooleanField(required=False, default=True)
    expires_at = serializers.CharField(required=False, allow_null=True, allow_blank=True)
    source = serializers.ChoiceField(
        choices=(SOURCE_VOICE, SOURCE_SCREEN), required=False, default=SOURCE_SCREEN
    )


class WatchCreatedSerializer(serializers.Serializer):
    watch = WatchSerializer()
    note = serializers.CharField(
        allow_null=True, help_text="What the watch cannot see yet, in words; null when nothing."
    )


class WatchUpdateSerializer(serializers.Serializer):
    params = serializers.JSONField(required=False)
    label = serializers.CharField(required=False, allow_blank=True, allow_null=True)
    repeat = serializers.BooleanField(required=False)
    paused = serializers.BooleanField(required=False)
    expires_at = serializers.CharField(required=False, allow_null=True, allow_blank=True)


class FiringSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    watch_id = serializers.UUIDField()
    watch_label = serializers.CharField()
    kind = serializers.ChoiceField(choices=KINDS)
    facts = serializers.CharField(
        help_text="The engine's own sentence, from the rows named in sources."
    )
    detail = serializers.DictField()
    sources = serializers.ListField(child=serializers.DictField())
    fired_at = serializers.DateTimeField()
    expires_at = serializers.DateTimeField()
    acked_at = serializers.DateTimeField(allow_null=True)
    outcome = serializers.CharField(allow_null=True)


class FiringListSerializer(serializers.Serializer):
    results = FiringSerializer(many=True)


class AckSerializer(serializers.Serializer):
    ids = serializers.ListField(child=serializers.UUIDField(), min_length=1, max_length=100)
    outcome = serializers.ChoiceField(choices=OUTCOMES)


class AckedSerializer(serializers.Serializer):
    acked = serializers.IntegerField()


def _refused(request):
    reason = service.refusal(request)
    if reason is None:
        return None
    return Response({"detail": reason}, status=status.HTTP_403_FORBIDDEN)


def _bad(serializer, verb="set"):
    errors = "; ".join(
        f"{field}: {' '.join(str(m) for m in messages)}"
        for field, messages in serializer.errors.items()
    )
    return Response({"detail": f"Nothing was {verb}. {errors}"}, status=status.HTTP_400_BAD_REQUEST)


def _from(exc: WatchRefused):
    return Response({"detail": exc.message}, status=exc.status)


class WatchListCreateView(APIView):
    @extend_schema(
        operation_id="api_watches_list",
        tags=TAGS,
        responses={200: WatchListSerializer, 403: DetailSerializer},
        description="The caller's own watches (the built-ins seeded on first read) and every kind.",
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        rows = service.listing(request)
        return Response(
            {"results": [service.watch_dict(w) for w in rows], "kinds": service.kinds_list()}
        )

    @extend_schema(
        operation_id="api_watches_create",
        tags=TAGS,
        request=WatchCreateSerializer,
        responses={201: WatchCreatedSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description=(
            "Sets a watch of one registry kind. A kind that is not in the registry is a 400 whose "
            "detail names the kinds there are and the nearest ones."
        ),
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        body = WatchCreateSerializer(data=request.data)
        if not body.is_valid():
            return _bad(body)
        data = body.validated_data
        try:
            watch, note = service.create(
                request,
                kind=data["kind"],
                params=data.get("params"),
                label=data.get("label"),
                repeat=data.get("repeat", True),
                expires_at=data.get("expires_at"),
                source=data.get("source", SOURCE_SCREEN),
            )
        except WatchRefused as exc:
            return _from(exc)
        return Response(
            {"watch": service.watch_dict(watch), "note": note}, status=status.HTTP_201_CREATED
        )


class WatchDetailView(APIView):
    @extend_schema(
        operation_id="api_watches_update",
        tags=TAGS,
        request=WatchUpdateSerializer,
        responses={
            200: WatchSerializer,
            400: DetailSerializer,
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description="Pause, resume (re-arms: nothing from the pause fires), rename or re-param.",
    )
    def patch(self, request, watch_id):
        refused = _refused(request)
        if refused:
            return refused
        body = WatchUpdateSerializer(data=request.data, partial=True)
        if not body.is_valid():
            return _bad(body, "changed")
        try:
            watch = service.change(request, watch_id, dict(body.validated_data))
        except WatchRefused as exc:
            return _from(exc)
        return Response(service.watch_dict(watch))

    @extend_schema(
        operation_id="api_watches_delete",
        tags=TAGS,
        responses={
            200: WatchSerializer,
            204: OpenApiResponse(description="Removed."),
            403: DetailSerializer,
            404: DetailSerializer,
        },
        description=(
            '"Stop watching X". A watch the member set: 204, never evaluated or listed again. '
            "A built-in is PAUSED instead (200 with the watch): it stays listed and can be resumed."
        ),
    )
    def delete(self, request, watch_id):
        refused = _refused(request)
        if refused:
            return refused
        try:
            watch = service.remove(request, watch_id)
        except WatchRefused as exc:
            return _from(exc)
        if watch.builtin:
            return Response(service.watch_dict(watch))
        return Response(status=status.HTTP_204_NO_CONTENT)


class FiringListView(APIView):
    @extend_schema(
        operation_id="api_watches_firings",
        tags=TAGS,
        parameters=[
            OpenApiParameter(
                "pending", bool, description="Only what the phone has not said yet (oldest first)."
            )
        ],
        responses={200: FiringListSerializer, 403: DetailSerializer},
    )
    def get(self, request):
        refused = _refused(request)
        if refused:
            return refused
        want = (request.query_params.get("pending") or "").lower() in ("1", "true", "yes")
        rows = service.pending(request) if want else service.recent(request)
        return Response({"results": [service.firing_dict(f) for f in rows]})


class FiringAckView(APIView):
    @extend_schema(
        operation_id="api_watches_firings_ack",
        tags=TAGS,
        request=AckSerializer,
        responses={200: AckedSerializer, 400: DetailSerializer, 403: DetailSerializer},
        description="The phone said, posted or silenced these firings; they are not offered again.",
    )
    def post(self, request):
        refused = _refused(request)
        if refused:
            return refused
        body = AckSerializer(data=request.data)
        if not body.is_valid():
            return _bad(body, "marked")
        data = body.validated_data
        try:
            count = service.ack(request, [str(i) for i in data["ids"]], data["outcome"])
        except WatchRefused as exc:
            return _from(exc)
        return Response({"acked": count})
