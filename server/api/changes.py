"""`GET /api/changes?since=<iso>&aspects=events,checklists` - the ONE
endpoint the phone's cache is meant to live on (django-engine ticket 04's
own rule 4), replacing a Realtime socket and a per-table pull with one
shape. Phase 2 slice: only `events` and `checklists` exist as aspects here;
widening the `aspects` vocabulary is later tickets' job (execution-plan.md
Phase 5), not a per-feature addition to this file's own logic.

`aspects` selects which top-level keys get populated - `checklists` pulls
in `checklists`, `checklist_items`, AND `checklist_ticks` together (they
are one aspect's three tables, not three aspects), `events` pulls in just
`events`. **Missing/blank `aspects` means every known aspect**, the same
"absence is never evidence of wanting less" posture `api/sync.parse_since`
already takes for `since` - a caller that forgot the parameter should see
too much, never silently see nothing.
"""
from __future__ import annotations

from django.utils import timezone
from rest_framework import status
from rest_framework.fields import DateTimeField
from rest_framework.response import Response
from rest_framework.views import APIView

from api.events import EventSerializer
from api.sync import parse_since
from checklists.models import Checklist, ChecklistItem, ChecklistTick
from checklists.serializers import (
    ChecklistItemSerializer,
    ChecklistSerializer,
    ChecklistTickSerializer,
)
from legacy.models.dates import Event

KNOWN_ASPECTS = ("events", "checklists")


class ChangesView(APIView):
    def get(self, request):
        raw_aspects = request.query_params.get("aspects", "")
        requested = [a.strip() for a in raw_aspects.split(",") if a.strip()]
        if not requested:
            requested = list(KNOWN_ASPECTS)

        unknown = sorted(set(requested) - set(KNOWN_ASPECTS))
        if unknown:
            return Response(
                {
                    "detail": (
                        f"Unknown aspect(s): {', '.join(unknown)}. "
                        f"Use one of: {', '.join(KNOWN_ASPECTS)}."
                    )
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        since = parse_since(request.query_params.get("since"))
        # Captured before any query runs, and returned as the response's
        # own watermark - the client stores THIS as its next `since` rather
        # than deriving one from the rows it happened to see, so a row
        # committed the same instant this request is being served is never
        # silently skipped by a client-computed max(updated_at) that ran a
        # moment too early.
        server_time = timezone.now()

        # DRF's own DateTimeField, not a bare `.isoformat()` - Python's
        # isoformat() renders a UTC offset as `+00:00`, while DRF's default
        # rendering (used everywhere else in this response, every
        # `updated_at`/`created_at` included) uses a `Z` suffix. A raw `+`
        # in an un-percent-encoded query string decodes to a literal SPACE
        # (the `application/x-www-form-urlencoded` convention every URL
        # parser follows), so a client that round-trips this value straight
        # back as `?since=<server_time>` without encoding it would silently
        # corrupt its own watermark - found exactly this way, empirically,
        # while writing this ticket's own tests
        # (`tests/test_checklists_api.py`'s `test_since_feed_excludes_
        # checklists_before_the_watermark`). Matching DRF's own format
        # everywhere removes the `+` from the picture entirely rather than
        # asking every caller to remember to encode it.
        body: dict = {"server_time": DateTimeField().to_representation(server_time)}

        if "events" in requested:
            events = Event.objects.filter(updated_at__gte=since).order_by("updated_at")
            body["events"] = EventSerializer(events, many=True).data
        if "checklists" in requested:
            checklists = Checklist.objects.filter(updated_at__gte=since).order_by("updated_at")
            items = ChecklistItem.objects.filter(updated_at__gte=since).order_by("updated_at")
            ticks = ChecklistTick.objects.filter(updated_at__gte=since).order_by("updated_at")
            body["checklists"] = ChecklistSerializer(checklists, many=True).data
            body["checklist_items"] = ChecklistItemSerializer(items, many=True).data
            body["checklist_ticks"] = ChecklistTickSerializer(ticks, many=True).data

        return Response(body)
