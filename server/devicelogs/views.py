"""`POST /api/device-logs/`: a device uploads its own log (Kevin, 2026-10-15).

Token-authenticated like every other device call (`DeviceTokenAuthentication`
plus `IsHouseholdMember` are the project defaults) and scoped to the caller's
household at `household_of`, the one choke point (ADR 0045). A `read`-scoped
token is refused by `IsHouseholdMember` on this POST like any other write.

The request is `multipart/form-data`: one gzip file part plus form fields for
the metadata. Multipart rather than a raw gzip body for a reason that costs
nothing to know: Django refuses a raw non-file body over
`DATA_UPLOAD_MAX_MEMORY_SIZE` (2.5 MB) before any view runs, and a file part
is exempt, so the cap here is the one this file states and enforces.

**The cap is enforced twice.** On the compressed size, and on the
DECOMPRESSED size by reading through `GzipFile` with a ceiling, so a small
gzip bomb is refused in words instead of inflating in memory.

Nothing here logs or echoes the body: it is the household's own device log, and
the only place it is ever shown is `manage.py device_logs`.
"""

from __future__ import annotations

import datetime
import gzip
import io
import zlib

from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from devicelogs.models import MAX_UPLOAD_BYTES, REASONS, RETENTION_DAYS, DeviceLog
from household.tenancy import household_of, scoped

TAGS = ["device-logs"]
_LABEL_MAX = 200


class UploadSerializer(serializers.Serializer):
    batch_id = serializers.UUIDField(
        help_text="The device's idempotency key. A retry of the same batch is answered 200, once."
    )
    device_label = serializers.CharField(max_length=_LABEL_MAX)
    app_version_name = serializers.CharField(required=False, allow_blank=True, default="", max_length=64)
    app_version_code = serializers.IntegerField(required=False, allow_null=True, default=None)
    reason = serializers.ChoiceField(choices=list(REASONS))
    window_start_ms = serializers.IntegerField(
        required=False, allow_null=True, default=None,
        help_text="Epoch milliseconds on the DEVICE's clock, which is not trusted.",
    )
    window_end_ms = serializers.IntegerField(
        required=False, allow_null=True, default=None,
        help_text="Epoch milliseconds on the DEVICE's clock, which is not trusted.",
    )
    file = serializers.FileField(help_text="The log text, gzip-compressed.")


class StoredSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    received_at = serializers.DateTimeField()
    stored_bytes = serializers.IntegerField(help_text="The compressed size kept.")
    detail = serializers.CharField()


def _from_ms(value: int | None) -> datetime.datetime | None:
    if value is None:
        return None
    try:
        return datetime.datetime.fromtimestamp(value / 1000, tz=datetime.UTC)
    except (OverflowError, OSError, ValueError):
        # A garbage device clock is stored as unknown, not as a refusal: the
        # batch is still worth keeping.
        return None


def inflate_capped(blob: bytes, cap: int | None = None) -> bytes:
    """Decompress `blob`, refusing past `cap` bytes (default: the module cap, read at
    call time). Raises ValueError in words."""
    cap = MAX_UPLOAD_BYTES if cap is None else cap
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(blob)) as handle:
            raw = handle.read(cap + 1)
    except (OSError, EOFError, zlib.error) as exc:
        raise ValueError("The file is not valid gzip data.") from exc
    if len(raw) > cap:
        raise ValueError(f"The log is larger than {cap // (1024 * 1024)} MB once decompressed.")
    return raw


class DeviceLogUploadView(APIView):
    parser_classes = [MultiPartParser, FormParser]

    @extend_schema(
        operation_id="api_device_logs_create",
        tags=TAGS,
        request={"multipart/form-data": UploadSerializer},
        responses={
            201: OpenApiResponse(response=StoredSerializer, description="Stored."),
            200: OpenApiResponse(
                response=StoredSerializer, description="This batch_id was already stored; nothing new."
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Refused, nothing stored."),
            413: OpenApiResponse(response=DetailSerializer, description="Over the size cap, nothing stored."),
        },
    )
    def post(self, request):
        household = household_of(request)
        serializer = UploadSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(
                {"detail": f"Nothing was stored. {serializer.errors}"}, status=status.HTTP_400_BAD_REQUEST
            )
        data = serializer.validated_data
        upload = data["file"]
        if upload.size > MAX_UPLOAD_BYTES:
            return _too_large()
        blob = upload.read()
        try:
            raw = inflate_capped(blob)
        except ValueError as exc:
            if "larger" in str(exc):
                return _too_large()
            return Response(
                {"detail": f"Nothing was stored. {exc}"}, status=status.HTTP_400_BAD_REQUEST
            )

        existing = scoped(DeviceLog, request).filter(batch_id=data["batch_id"]).first()
        if existing is not None:
            return Response(_stored(existing, "Already stored; nothing new was added."))
        try:
            with transaction.atomic():
                row = DeviceLog.objects.create(
                    household=household,
                    user=request.user,
                    batch_id=data["batch_id"],
                    device_label=data["device_label"],
                    app_version_name=data["app_version_name"],
                    app_version_code=data["app_version_code"],
                    reason=data["reason"],
                    window_start=_from_ms(data["window_start_ms"]),
                    window_end=_from_ms(data["window_end_ms"]),
                    line_count=raw.count(b"\n"),
                    raw_bytes=len(raw),
                    body_gz=blob,
                )
        except IntegrityError:
            # A concurrent retry of the same batch won the race: same answer.
            row = scoped(DeviceLog, request).get(batch_id=data["batch_id"])
            return Response(_stored(row, "Already stored; nothing new was added."))
        prune(household)
        return Response(_stored(row, "Stored."), status=status.HTTP_201_CREATED)


def _too_large() -> Response:
    return Response(
        {"detail": f"Nothing was stored. A log upload is capped at {MAX_UPLOAD_BYTES // (1024 * 1024)} MB."},
        status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
    )


def _stored(row: DeviceLog, detail: str) -> dict:
    return {
        "id": row.id,
        "received_at": row.received_at,
        "stored_bytes": len(bytes(row.body_gz)),
        "detail": detail,
    }


def prune(household) -> int:
    """Delete this household's logs older than RETENTION_DAYS. Returns the count.

    Run inline on upload rather than from its own cron line: Cloud Scheduler
    bills per job and the crontab already has six, and a household that never
    uploads has nothing growing.
    """
    cutoff = timezone.now() - datetime.timedelta(days=RETENTION_DAYS)
    deleted, _ = DeviceLog.objects.filter(household=household, received_at__lt=cutoff).delete()
    return deleted
