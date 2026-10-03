"""`/api/events/<id>/skips` - "not this one" for a repeating event
(web-revamp ticket 08, spec D4).

A skip removes one local date from a series' occurrences
(`api/recurrence.py` subtracts it during expansion, and it still counts
toward an `AFTER_COUNT` end). "Just this one" on delete is a POST here; on
edit it is a POST here plus a one-off event whose `origin_guid` is
`<series id>:<YYYY-MM-DD>`, so a retry does not duplicate it. "All of them"
is a PATCH or DELETE of the series itself.

Every route reads the parent event through `household.tenancy.visible()`
(ADR 0052): a skip inherits its event's visibility, so another member's
private event's skips are a 404 here and only redacted tombstones in
`/api/changes`. A DELETE tombstones, so the removal reaches every replica.
"""
from __future__ import annotations

import datetime as dt
import uuid

from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import NOT_FOUND, WRITE_REFUSED, DetailSerializer
from api.sync import save_or_400
from household.tenancy import visible
from legacy.models.dates import Event, EventSkip

SKIP_TAGS = ["events"]


class EventSkipSerializer(serializers.ModelSerializer):
    event = serializers.PrimaryKeyRelatedField(read_only=True)

    class Meta:
        model = EventSkip
        fields = ["id", "event", "skip_date", "created_at", "updated_at", "deleted_at"]
        read_only_fields = ["id", "event", "created_at", "updated_at", "deleted_at"]


class SkipRequestSerializer(serializers.Serializer):
    skip_date = serializers.DateField(
        help_text="The occurrence's LOCAL date, YYYY-MM-DD, in the zone the series is read in."
    )


def _event_or_404(request, pk):
    event = visible(Event, request).filter(pk=pk).first()
    if event is None:
        return None, Response(
            {"detail": f"No event with id {pk}. No skip was read or written."},
            status=status.HTTP_404_NOT_FOUND,
        )
    return event, None


class EventSkipListCreateView(APIView):
    """`GET`/`POST /api/events/<id>/skips`."""

    @extend_schema(
        operation_id="api_events_skips_list",
        tags=SKIP_TAGS,
        responses={
            200: OpenApiResponse(
                response=EventSkipSerializer(many=True),
                description=(
                    "Every skip of this event, tombstones included (a client filters "
                    "`deleted_at` itself), by date. Not paged: a series has few."
                ),
            ),
            404: NOT_FOUND,
        },
    )
    def get(self, request, pk):
        event, missing = _event_or_404(request, pk)
        if missing is not None:
            return missing
        skips = EventSkip.objects.filter(event=event).order_by("skip_date", "id")
        return Response(EventSkipSerializer(skips, many=True).data)

    @extend_schema(
        operation_id="api_events_skips_create",
        tags=SKIP_TAGS,
        request=SkipRequestSerializer,
        responses={
            201: OpenApiResponse(
                response=EventSkipSerializer, description="Skipped. A new skip for that date."
            ),
            200: OpenApiResponse(
                response=EventSkipSerializer,
                description=(
                    "That date was already skipped, and the skip is returned unchanged; or it "
                    "had been un-skipped and the same row is a skip again. Idempotent on "
                    "(event, skip_date): a retry never makes a second row."
                ),
            ),
            400: WRITE_REFUSED,
            404: NOT_FOUND,
        },
    )
    def post(self, request, pk):
        event, missing = _event_or_404(request, pk)
        if missing is not None:
            return missing
        body = SkipRequestSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        skip_date = body.validated_data["skip_date"]
        existing = EventSkip.objects.filter(event=event, skip_date=skip_date).first()

        def _write():
            if existing is None:
                skip = EventSkip.objects.create(
                    id=uuid.uuid4(),
                    event=event,
                    household_id=event.household_id,
                    skip_date=skip_date,
                    created_at=Now(),
                    updated_at=Now(),
                )
                skip.refresh_from_db()
                return skip
            if existing.deleted_at is not None:
                existing.deleted_at = None
                existing.save(update_fields=["deleted_at"])
                existing.refresh_from_db()
            return existing

        skip, error = save_or_400(_write)
        if error is not None:
            return error
        code = status.HTTP_201_CREATED if existing is None else status.HTTP_200_OK
        return Response(EventSkipSerializer(skip).data, status=code)


class EventSkipDetailView(APIView):
    """`DELETE /api/events/<id>/skips/<YYYY-MM-DD>`."""

    @extend_schema(
        operation_id="api_events_skips_destroy",
        tags=SKIP_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Un-skipped: the skip is tombstoned, so the occurrence comes back on every "
                    "replica. Idempotent: no skip on that date is still a 204."
                )
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="The date in the path is not YYYY-MM-DD. Nothing was changed.",
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, pk, skip_date):
        try:
            day = dt.date.fromisoformat(skip_date)
        except ValueError:
            return Response(
                {"detail": f"{skip_date!r} is not a date (YYYY-MM-DD). Nothing was changed."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        event, missing = _event_or_404(request, pk)
        if missing is not None:
            return missing
        skip = EventSkip.objects.filter(event=event, skip_date=day, deleted_at__isnull=True).first()
        if skip is not None:
            skip.deleted_at = Now()
            skip.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
