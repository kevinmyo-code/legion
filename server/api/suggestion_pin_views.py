"""`POST /api/events/<id>/pins` and `DELETE /api/events/<id>/pins/mine`:
one member's "I want to go" on one suggestion (Kevin, 2026-10-09).

The table, its guarantees and the `pinned_by` read are `api/suggestion_pins.py`.

- POST pins as the caller. Idempotent on (event, member): a second pin
  returns the event unchanged, an unpinned one is revived.
- DELETE tombstones the caller's pin. A member can only ever reach their OWN
  pin: no route names another member's, so "cannot unpin someone else's"
  holds by construction.

Both answer with the EVENT, rendered exactly as `GET /api/events` renders it,
and both bump the event's `updated_at`, so a replica syncing events by
`?since=` re-reads the row with its new `pinned_by`. The event is read through
`household.tenancy.visible()` (ADR 0052): another member's private event, or
another household's, is a 404.
"""
from __future__ import annotations

import uuid

from django.db import transaction
from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.event_columns import KIND_SUGGESTION
from api.events import EventSerializer
from api.schema import NOT_FOUND, DetailSerializer
from api.sync import save_or_400
from household.tenancy import visible
from legacy.models.dates import Event, SuggestionPin

PIN_TAGS = ["events"]

NOT_A_SUGGESTION = (
    "Nothing was pinned. Only a suggestion can be pinned, and this event is "
    'a plan now (kind "{kind}"). It is already on the calendar as one.'
)

def _event_or_404(request, pk):
    event = visible(Event, request).filter(pk=pk, deleted_at__isnull=True).first()
    if event is None:
        return None, Response(
            {"detail": f"No suggestion with id {pk}. Nothing was pinned or unpinned."},
            status=status.HTTP_404_NOT_FOUND,
        )
    return event, None


def _event_body(event):
    event.refresh_from_db()
    return EventSerializer(event).data


def _touch_event(event) -> None:
    """Bump the event's `updated_at` (the touch trigger stamps the database
    clock), so a replica syncing events by `?since=` re-reads the row and
    its new `pinned_by`."""
    Event.objects.filter(pk=event.pk, household_id=event.household_id).update(updated_at=Now())


EVENT_WITH_PINS = (
    "The event, rendered exactly as GET /api/events renders it, with the new `pinned_by`."
)


class SuggestionPinCreateView(APIView):
    """`POST /api/events/<id>/pins`."""

    @extend_schema(
        operation_id="api_events_pins_create",
        tags=PIN_TAGS,
        request=None,
        responses={
            201: OpenApiResponse(
                response=EventSerializer,
                description="Pinned: a new pin, or one that had been unpinned is live again. "
                + EVENT_WITH_PINS
            ),
            200: OpenApiResponse(
                response=EventSerializer,
                description="Already pinned by you; nothing changed. Idempotent. " + EVENT_WITH_PINS
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="Nothing was pinned: the event is not a suggestion (it was added "
                "to the plans). `detail` says so.",
            ),
            404: NOT_FOUND,
        },
    )
    def post(self, request, pk):
        event, missing = _event_or_404(request, pk)
        if missing is not None:
            return missing
        if event.kind != KIND_SUGGESTION:
            return Response(
                {"detail": NOT_A_SUGGESTION.format(kind=event.kind)},
                status=status.HTTP_400_BAD_REQUEST,
            )
        existing = SuggestionPin.objects.filter(event=event, user=request.user).first()
        if existing is not None and existing.deleted_at is None:
            return Response(EventSerializer(event).data, status=status.HTTP_200_OK)

        def _write():
            with transaction.atomic():
                if existing is None:
                    SuggestionPin.objects.create(
                        id=uuid.uuid4(),
                        event=event,
                        user=request.user,
                        household_id=event.household_id,
                        created_at=Now(),
                        updated_at=Now(),
                    )
                else:
                    existing.deleted_at = None
                    existing.save(update_fields=["deleted_at"])
                _touch_event(event)

        _done, error = save_or_400(_write)
        if error is not None:
            return error
        return Response(_event_body(event), status=status.HTTP_201_CREATED)


class SuggestionPinMineView(APIView):
    """`DELETE /api/events/<id>/pins/mine`."""

    @extend_schema(
        operation_id="api_events_pins_mine_destroy",
        tags=PIN_TAGS,
        request=None,
        responses={
            200: OpenApiResponse(
                response=EventSerializer,
                description="Unpinned: your pin is tombstoned, so every replica drops it. "
                "Idempotent: no pin of yours is still a 200. Only ever YOUR pin; another "
                "member's cannot be named here. " + EVENT_WITH_PINS
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, pk):
        event, missing = _event_or_404(request, pk)
        if missing is not None:
            return missing
        pin = SuggestionPin.objects.filter(
            event=event, user=request.user, deleted_at__isnull=True
        ).first()
        if pin is not None:
            with transaction.atomic():
                pin.deleted_at = Now()
                pin.save(update_fields=["deleted_at"])
                _touch_event(event)
        return Response(_event_body(event), status=status.HTTP_200_OK)
