"""`GET /api/changes?since=<iso>&aspects=events,checklists` - the ONE
endpoint the phone's cache is meant to live on (django-engine ticket 04's
own rule 4), replacing a Realtime socket and a per-table pull with one
shape.

**Phase 5 widened the vocabulary.** This file used to say "only `events`
and `checklists` exist as aspects here; widening the `aspects` vocabulary
is later tickets' job (execution-plan.md Phase 5), not a per-feature
addition to this file's own logic." That is now done for four more:
`places`, `voice_notes`, `body` and `memory`, and it cost no per-aspect
logic at all - they come from `api/registry.py`, the same list `api/urls.py`
routes from, so an aspect cannot be routable and invisible here (or the
reverse). Ledger, pantry and fleet are still absent, on purpose: the first
two wait on the section 4 gate moving server-side (ticket 03) and fleet has
four identity shapes and a 20,796-row `obd_samples` table that needs a
windowed pull.

`aspects` selects which top-level keys get populated - `checklists` pulls
in `checklists`, `checklist_items`, AND `checklist_ticks` together (they
are one aspect's three tables, not three aspects), `events` pulls in just
`events`, `body` pulls in all eight of its tables, `memory` all three.
**Missing/blank `aspects` means every known aspect**, the same "absence is
never evidence of wanting less" posture `api/sync.parse_since` already
takes for `since` - a caller that forgot the parameter should see too much,
never silently see nothing.

Unlike the per-table routes, this feed is NOT paged: it answers with every
changed row for every requested aspect. That was true of the Phase 2 slice
and is unchanged here, and it is the reason a client syncing a large first
pull should use the per-table `?since=` routes, which do page.
"""
from __future__ import annotations

from django.db import connection
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.fields import DateTimeField
from rest_framework.response import Response
from rest_framework.views import APIView

from api.events import EventSerializer
from api.registry import SYNCED_ASPECTS, SYNCED_VIEWSETS
from api.schema import SINCE_PARAMETER, DetailSerializer
from api.sync import parse_since
from checklists.models import Checklist, ChecklistItem, ChecklistTick
from checklists.serializers import (
    ChecklistItemSerializer,
    ChecklistSerializer,
    ChecklistTickSerializer,
)
from legacy.models.dates import Event

# The two hand-written aspects (Phase 2), then everything on the generic
# shape (Phase 5). Order matters only for the message a 400 prints.
KNOWN_ASPECTS = ("events", "checklists", *sorted(SYNCED_ASPECTS))


def _build_changes_serializer() -> type[serializers.Serializer]:
    """The response body, described for `server/openapi.yaml`, built from
    the same two sources the `get` below reads: the four hand-written
    Phase 2 keys and `api/registry.SYNCED_VIEWSETS`.

    Generated rather than typed out for the reason the whole registry
    exists: a table added to the feed and forgotten here would leave the
    contract quietly describing a smaller response than the server sends,
    and a generated client would have no field to decode it into.

    **Every table key is optional, and that is not hedging.** `?aspects=`
    selects which keys are populated, so a request for one aspect gets a
    body with only that aspect's keys in it. `server_time` is the only key
    always present.
    """
    fields: dict = {
        "server_time": serializers.DateTimeField(
            help_text=(
                "Postgres's own clock, read before any row query. Store THIS as the next "
                "`since`, rather than a max(updated_at) computed from the rows returned."
            )
        ),
        "events": EventSerializer(many=True, required=False),
        "checklists": ChecklistSerializer(many=True, required=False),
        "checklist_items": ChecklistItemSerializer(many=True, required=False),
        "checklist_ticks": ChecklistTickSerializer(many=True, required=False),
    }
    for viewset in SYNCED_VIEWSETS:
        fields[viewset.table] = viewset.serializer_class(many=True, required=False)
    return type("ChangesSerializer", (serializers.Serializer,), fields)


ChangesSerializer = _build_changes_serializer()

ASPECTS_PARAMETER = OpenApiParameter(
    name="aspects",
    location=OpenApiParameter.QUERY,
    required=False,
    style="form",
    explode=False,
    type={"type": "array", "items": {"type": "string", "enum": list(KNOWN_ASPECTS)}},
    description=(
        "Comma-separated aspect names. Selects which top-level keys get populated: "
        "`checklists` fills `checklists`, `checklist_items` AND `checklist_ticks`; `body` "
        "fills all eight of its tables; `memory` all three. **Omitted or blank means every "
        "known aspect.** An unknown name is a 400 naming it - never a silently smaller "
        "response."
    ),
)


class ChangesView(APIView):
    @extend_schema(
        operation_id="api_changes_retrieve",
        tags=["changes"],
        parameters=[SINCE_PARAMETER, ASPECTS_PARAMETER],
        responses={
            200: OpenApiResponse(
                response=ChangesSerializer,
                description=(
                    "One key per TABLE, named for the table, each holding every row changed "
                    "at or after `since` with tombstones included, oldest first. **Not "
                    "paged**: a large first pull should use the per-table `?since=` routes, "
                    "which are."
                ),
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="An aspect name this server does not know. `detail` names it and "
                "lists the ones that exist.",
            ),
        },
    )
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
        #
        # **From POSTGRES's clock, never this process's.** This line used
        # to read `server_time = timezone.now()`, which minted the phone's
        # single most load-bearing watermark on a machine that is not the
        # one stamping the `updated_at` values it will be compared against.
        # Measured 2026-09-06 against this project's own Postgres, the two
        # clocks differed by 0.53s with PYTHON BEHIND - the harmless
        # direction, in which a client merely re-fetches half a second of
        # rows it already has. Reversed, `server_time` would sit in the
        # database's FUTURE, and every row written inside that window would
        # be skipped by the next pull, forever, with nothing logged: the
        # client has been told "everything before here is already mine"
        # about rows it never received. Nothing in the old code chose the
        # safe direction. One round trip is the correct price for a value a
        # client hands back as its own idea of what it already has.
        # `api/events.py` and `checklists/views.py` were put on the same
        # clock the same day, for the same reason; `api/synced.py` always
        # was.
        #
        # Known limit, unchanged by this and NOT fixable with a different
        # clock: a write transaction that STARTED before this read but
        # commits after it carries a trigger-stamped `updated_at` of its
        # own transaction start, older than `server_time`, so the next pull
        # will not see it either. That is a snapshot problem, not a clock
        # problem - `now()` in place of `statement_timestamp()` would not
        # close it, and closing it properly means a commit-order cursor
        # rather than a timestamp at all.
        with connection.cursor() as cursor:
            cursor.execute("SELECT statement_timestamp()")
            server_time = cursor.fetchone()[0]

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

        # Everything on the generic shape. One key per TABLE, named for the
        # table (`bodyweight_logs`, `memories`, ...), never for the aspect -
        # the same relationship `checklists` above has with its three keys.
        # Tombstones included, ordered by `updated_at`, exactly as the
        # per-table routes return them; the ordering is what lets a client
        # apply the rows in the order they happened.
        for aspect in requested:
            for viewset in SYNCED_ASPECTS.get(aspect, ()):
                model = viewset.model()
                rows = model.objects.filter(updated_at__gte=since).order_by("updated_at", "pk")
                body[viewset.table] = viewset.serializer_class(rows, many=True).data

        return Response(body)
