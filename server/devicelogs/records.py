"""Conversation records the Claude worker can read (alfred-builds ticket 02).

    GET /api/device-logs/?reason=conversation&since=<iso>&device=web&limit=50
    GET /api/device-logs/<id>/render[?verbose=1]

Both are household reads like every other: the project's default authentication (a device token,
which is what the worker holds, or a browser session) and `IsHouseholdMember`, then
`household.tenancy.scoped`, the one choke point (ADR 0045). A recruiter visit's record has NO
household (`demo_visitor`), so no member token can list or render it, and a recruiter is refused
both routes outright (`household.recruiter_routes.RECRUITER_DENIED`).

**The render is the operator command's render.** `devicelogs.render.render_batch` is the one
function `manage.py device_logs --render` prints, so the worker reads byte-for-byte what Kevin
would. Mail was redacted on the device before upload (`[redacted: third-party content]`,
CLAUDE.md section 7) and is served redacted; nothing here can undo that.

Nothing here logs a record's text.
"""

from __future__ import annotations

from django.utils.dateparse import parse_datetime
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from devicelogs.models import REASONS, DeviceLog
from devicelogs.render import render_batch
from household.tenancy import scoped

TAGS = ["device-logs"]
DEFAULT_LIMIT = 50
MAX_LIMIT = 200

LIST_PARAMETERS = [
    OpenApiParameter(
        "reason", str, enum=list(REASONS), description="Keep only this upload reason."
    ),
    OpenApiParameter(
        "since", str, description="ISO 8601 time; only batches the engine received after it."
    ),
    OpenApiParameter("device", str, description="Keep only this device label, e.g. web."),
    OpenApiParameter(
        "limit", int, description=f"At most this many (default {DEFAULT_LIMIT}, max {MAX_LIMIT})."
    ),
]


class RecordSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    device_label = serializers.CharField()
    reason = serializers.CharField()
    received_at = serializers.DateTimeField(help_text="The engine's clock.")
    line_count = serializers.IntegerField(help_text="Raw lines in the batch, before rendering.")
    app_version_name = serializers.CharField()
    app_version_code = serializers.IntegerField(allow_null=True)


class RecordListSerializer(serializers.Serializer):
    results = RecordSerializer(many=True)


class RenderedSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    device_label = serializers.CharField()
    reason = serializers.CharField()
    received_at = serializers.DateTimeField()
    header = serializers.CharField(help_text="The header line `manage.py device_logs` prints.")
    lines = serializers.ListField(
        child=serializers.CharField(),
        help_text="The timeline `manage.py device_logs --render` prints: who said what, each tool "
        "call and result, reply latency. Mail stays redacted as stored.",
    )


def _bad(detail: str) -> Response:
    return Response({"detail": f"Nothing was read. {detail}"}, status=status.HTTP_400_BAD_REQUEST)


def _own(request):
    """The caller's own records in their household. A conversation record carries one member's
    words and tool results, private rows among them (ADR 0052/0075), so a household member never
    reads another member's, the same rule `web_conversation.owned()` applies to the write."""
    return scoped(DeviceLog, request).filter(user=request.user)


def list_records(request) -> Response:
    rows = _own(request)
    reason = request.query_params.get("reason")
    if reason:
        if reason not in REASONS:
            return _bad(f"reason must be one of {', '.join(REASONS)}.")
        rows = rows.filter(reason=reason)
    since = request.query_params.get("since")
    if since:
        moment = parse_datetime(since)
        if moment is None:
            return _bad("since must be an ISO 8601 time, e.g. 2026-11-05T09:00:00Z.")
        rows = rows.filter(received_at__gt=moment)
    device = request.query_params.get("device")
    if device:
        rows = rows.filter(device_label__iexact=device)
    try:
        limit = int(request.query_params.get("limit", DEFAULT_LIMIT))
    except ValueError:
        return _bad("limit must be a whole number.")
    limit = max(1, min(limit, MAX_LIMIT))
    rows = rows.defer("body_gz").order_by("-received_at")[:limit]
    return Response(
        {
            "results": [
                {
                    "id": row.id,
                    "device_label": row.device_label,
                    "reason": row.reason,
                    "received_at": row.received_at,
                    "line_count": row.line_count,
                    "app_version_name": row.app_version_name,
                    "app_version_code": row.app_version_code,
                }
                for row in rows
            ]
        }
    )


class DeviceLogRenderView(APIView):
    @extend_schema(
        operation_id="api_device_logs_render",
        tags=TAGS,
        parameters=[
            OpenApiParameter("verbose", bool, description="Also mic and activity lines."),
        ],
        responses={
            200: OpenApiResponse(response=RenderedSerializer, description="The rendered record."),
            404: OpenApiResponse(response=DetailSerializer, description="No such record here."),
            422: OpenApiResponse(response=DetailSerializer, description="Stored, but unreadable."),
        },
    )
    def get(self, request, log_id):
        row = _own(request).filter(id=log_id).first()
        if row is None:
            return Response(
                {"detail": "No device log with that id in this household."},
                status=status.HTTP_404_NOT_FOUND,
            )
        verbose = request.query_params.get("verbose", "").lower() in ("1", "true", "yes")
        try:
            header, lines = render_batch(row, verbose=verbose)
        except ValueError as exc:
            return Response(
                {"detail": f"That record is stored but cannot be read: {exc}"},
                status=status.HTTP_422_UNPROCESSABLE_ENTITY,
            )
        return Response(
            {
                "id": row.id,
                "device_label": row.device_label,
                "reason": row.reason,
                "received_at": row.received_at,
                "header": header,
                "lines": lines,
            }
        )
