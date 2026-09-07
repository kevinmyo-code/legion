"""Views for `/api/checklists` (django-engine ticket 04, Phase 2 slice).
See `checklists/serializers.py`'s own doc comment for the measured-tick
refusal wording this ticket asks to be reused verbatim, and
`ChecklistController.kt` for the semantics every method here mirrors:
idempotent tick, revive-on-retick, soft-delete-only.
"""
from __future__ import annotations

from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.errors import DetailErrorSerializer
from api.sync import paginate_since, parse_since, save_or_400
from checklists.models import Checklist, ChecklistItem, ChecklistTick
from checklists.serializers import (
    ChecklistItemSerializer,
    ChecklistSerializer,
    ChecklistTickSerializer,
    TickRequestSerializer,
)

# The one `?since=` query param every paged GET on this file takes -
# `api/sync.parse_since`'s own doc comment states the "missing means
# everything" contract; this just gives that same parameter one shared
# schema entry rather than four hand-typed copies.
SINCE_PARAM = OpenApiParameter(
    name="since",
    type=str,
    location=OpenApiParameter.QUERY,
    required=False,
    description=(
        "ISO-8601 watermark. Rows with updated_at >= since are returned. "
        "Missing or unparsable means fetch everything, never fetch nothing."
    ),
)


class ChecklistPageSerializer(serializers.Serializer):
    """Documentation-only shape for `{results, next}` - the page
    `paginate_since` returns. See that function's own doc comment for what
    `next` means (null on the last page, an ISO watermark otherwise)."""

    results = ChecklistSerializer(many=True)
    next = serializers.CharField(allow_null=True)


class ChecklistItemPageSerializer(serializers.Serializer):
    """Same page shape as `ChecklistPageSerializer`, over checklist items."""

    results = ChecklistItemSerializer(many=True)
    next = serializers.CharField(allow_null=True)


def _idempotent_or_none(model, sync_id):
    """`sync_id` honoured on POST for idempotent create (this ticket's own
    rule 5: "the phone retries") - a retried create with the same
    `sync_id` returns the row that already exists rather than making a
    second one. Never treated as a match when blank/absent - `sync_id` is
    nullable+unique, and Postgres already treats every NULL as distinct
    from every other NULL, so this mirrors that at the application layer
    too rather than matching two callers who both sent nothing."""
    if not sync_id:
        return None
    return model.objects.filter(sync_id=sync_id).first()


class ChecklistListCreateView(APIView):
    """`GET /api/checklists?since=<iso>` and `POST /api/checklists`."""

    @extend_schema(
        operation_id="api_checklists_list",
        parameters=[SINCE_PARAM],
        responses={200: ChecklistPageSerializer},
    )
    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        queryset = Checklist.objects.filter(updated_at__gte=since).order_by("updated_at")
        page, next_since = paginate_since(queryset)
        return Response({"results": ChecklistSerializer(page, many=True).data, "next": next_since})

    @extend_schema(
        request=ChecklistSerializer,
        responses={200: ChecklistSerializer, 201: ChecklistSerializer, 400: DetailErrorSerializer},
    )
    def post(self, request):
        existing = _idempotent_or_none(Checklist, request.data.get("sync_id"))
        if existing is not None:
            return Response(ChecklistSerializer(existing).data, status=status.HTTP_200_OK)

        serializer = ChecklistSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        instance, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(ChecklistSerializer(instance).data, status=status.HTTP_201_CREATED)


class ChecklistDetailView(APIView):
    """`GET`/`PATCH`/`DELETE /api/checklists/<checklist_id>`."""

    def _get(self, checklist_id):
        return Checklist.objects.filter(pk=checklist_id).first()

    @extend_schema(
        operation_id="api_checklists_retrieve",
        responses={200: ChecklistSerializer, 404: DetailErrorSerializer},
    )
    def get(self, request, checklist_id):
        instance = self._get(checklist_id)
        if instance is None:
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(ChecklistSerializer(instance).data)

    @extend_schema(
        request=ChecklistSerializer,
        responses={200: ChecklistSerializer, 400: DetailErrorSerializer, 404: DetailErrorSerializer},
    )
    def patch(self, request, checklist_id):
        instance = self._get(checklist_id)
        if instance is None:
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        serializer = ChecklistSerializer(instance, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        _saved, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(ChecklistSerializer(instance).data)

    @extend_schema(request=None, responses={204: None, 404: DetailErrorSerializer})
    def delete(self, request, checklist_id):
        instance = self._get(checklist_id)
        if instance is None:
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        # Idempotent, matching EventDetailView.delete's own posture -
        # "already gone" and "just removed" read the same to a caller that
        # does not care which happened. Does NOT cascade to items/ticks -
        # ChecklistController.deleteChecklist's own doc comment: a
        # checklist's history is never rewritten by deleting the checklist
        # any more than by deleting one of its items.
        if instance.deleted_at is None:
            # `Now()` (SQL, evaluated by Postgres), never `timezone.now()`.
            # This line used to read the Python clock, which is a DIFFERENT
            # machine's clock from the one `checklists_touch_updated_at()`
            # stamps `updated_at` with on the very same UPDATE - measured
            # 0.53s apart against this project's own Postgres on
            # 2026-09-06, Python behind. `created_at`/`updated_at` on these
            # tables were already safe (`db_default=Now()` on the model, so
            # Postgres fills them on INSERT); `deleted_at` was the one
            # timestamp this app wrote from the wrong clock. See
            # `api/events.py`'s `EventSerializer.create` for the full
            # reasoning and `api/synced.py` for the rule.
            instance.deleted_at = Now()
            instance.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class ChecklistItemListCreateView(APIView):
    """`GET`/`POST /api/checklists/<checklist_id>/items`."""

    @extend_schema(
        operation_id="api_checklists_items_list",
        parameters=[SINCE_PARAM],
        responses={200: ChecklistItemPageSerializer},
    )
    def get(self, request, checklist_id):
        since = parse_since(request.query_params.get("since"))
        queryset = (
            ChecklistItem.objects.filter(checklist_id=checklist_id, updated_at__gte=since)
            .order_by("updated_at")
        )
        page, next_since = paginate_since(queryset)
        return Response(
            {"results": ChecklistItemSerializer(page, many=True).data, "next": next_since}
        )

    @extend_schema(
        request=ChecklistItemSerializer,
        responses={
            200: ChecklistItemSerializer,
            201: ChecklistItemSerializer,
            400: DetailErrorSerializer,
            404: DetailErrorSerializer,
        },
    )
    def post(self, request, checklist_id):
        if not Checklist.objects.filter(pk=checklist_id).exists():
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )

        existing = _idempotent_or_none(ChecklistItem, request.data.get("sync_id"))
        if existing is not None:
            return Response(ChecklistItemSerializer(existing).data, status=status.HTTP_200_OK)

        # The URL's checklist_id is authoritative - overwrites anything the
        # caller may have sent under "checklist" in the body, the same
        # precedence ChecklistItemDetailView.patch enforces on edit.
        data = dict(request.data)
        data["checklist"] = checklist_id
        serializer = ChecklistItemSerializer(data=data)
        serializer.is_valid(raise_exception=True)
        instance, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(ChecklistItemSerializer(instance).data, status=status.HTTP_201_CREATED)


class ChecklistItemDetailView(APIView):
    """`GET`/`PATCH`/`DELETE /api/checklists/<checklist_id>/items/<item_id>`."""

    def _get(self, checklist_id, item_id):
        return ChecklistItem.objects.filter(pk=item_id, checklist_id=checklist_id).first()

    @extend_schema(
        operation_id="api_checklists_items_retrieve",
        responses={200: ChecklistItemSerializer, 404: DetailErrorSerializer},
    )
    def get(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id)
        if instance is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(ChecklistItemSerializer(instance).data)

    @extend_schema(
        request=ChecklistItemSerializer,
        responses={200: ChecklistItemSerializer, 400: DetailErrorSerializer, 404: DetailErrorSerializer},
    )
    def patch(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id)
        if instance is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        data = dict(request.data)
        # A client cannot reparent an item to a different checklist via
        # PATCH - the URL's checklist_id is the only authority on which
        # checklist an item belongs to.
        data.pop("checklist", None)
        serializer = ChecklistItemSerializer(instance, data=data, partial=True)
        serializer.is_valid(raise_exception=True)
        _saved, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(ChecklistItemSerializer(instance).data)

    @extend_schema(request=None, responses={204: None, 404: DetailErrorSerializer})
    def delete(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id)
        if instance is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        # Soft-delete only, never cascaded to ticks - ChecklistItem.kt's own
        # doc comment, "trap 2": dropping an item must not rewrite the
        # history of days it was already ticked.
        if instance.deleted_at is None:
            # The database's clock - see ChecklistDetailView.delete above.
            instance.deleted_at = Now()
            instance.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class ChecklistItemTickView(APIView):
    """`POST /api/checklists/<checklist_id>/items/<item_id>/tick` -
    `{day, value?, source?}`. See `checklists/serializers.py`'s
    `TickRequestSerializer` for the measured-item refusal, reused verbatim
    from `ChecklistController.tick`.
    """

    @extend_schema(
        request=TickRequestSerializer,
        responses={
            200: ChecklistTickSerializer,
            201: ChecklistTickSerializer,
            400: DetailErrorSerializer,
            404: DetailErrorSerializer,
        },
    )
    def post(self, request, checklist_id, item_id):
        item = ChecklistItem.objects.filter(pk=item_id, checklist_id=checklist_id).first()
        if item is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )

        request_serializer = TickRequestSerializer(data=request.data, context={"item": item})
        request_serializer.is_valid(raise_exception=True)
        day = request_serializer.validated_data["day"]
        value = request_serializer.validated_data.get("value")
        source = request_serializer.validated_data.get("source", "USER_REPORTED")

        existing = ChecklistTick.objects.filter(item=item, day=day).first()

        def _write():
            # `Now()` throughout, never `timezone.now()`. `ChecklistTick`
            # already declares `db_default=Now()` for `ticked_at`, so the
            # Python `now` this function used to compute was overriding the
            # clock the schema itself asks for, on a row whose `updated_at`
            # the `checklists_touch_updated_at()` trigger stamps from
            # Postgres regardless - two clocks on one row. See
            # ChecklistDetailView.delete above.
            if existing is None:
                tick = ChecklistTick.objects.create(
                    item=item, day=day, value=value, source=source, ticked_at=Now()
                )
                # `Now()` stays an unevaluated SQL expression in Python
                # until Postgres runs it, and this response body renders
                # `ticked_at`, so the row is read back first.
                tick.refresh_from_db()
                return tick
            if existing.deleted_at is None:
                # Idempotent no-op, matching ChecklistController.tick's own
                # posture: a double-tap does not overwrite the FIRST tap's
                # ticked_at/value/source.
                return existing
            # Untick-then-retick-same-day: revive the tombstoned row with a
            # fresh ticked_at (and the caller's fresh value/source),
            # matching ChecklistTickDao.retick's exact semantics - never a
            # second INSERT, which the (item, day) unique constraint would
            # reject anyway.
            existing.deleted_at = None
            existing.value = value
            existing.source = source
            existing.ticked_at = Now()
            existing.save(update_fields=["deleted_at", "value", "source", "ticked_at"])
            # Reads back the expression Postgres just evaluated, and the
            # `updated_at` the trigger overwrote on the same statement.
            existing.refresh_from_db()
            return existing

        tick, error = save_or_400(_write)
        if error is not None:
            return error
        status_code = status.HTTP_201_CREATED if existing is None else status.HTTP_200_OK
        return Response(ChecklistTickSerializer(tick).data, status=status_code)


class ChecklistItemUntickView(APIView):
    """`DELETE /api/checklists/<checklist_id>/items/<item_id>/tick/<day>` -
    soft-deletes the tick for that day, matching `ChecklistController.untick`.
    Idempotent: no tick on that day is still a 204, not a 404 - "already
    untouched" and "just unticked" read the same to a caller that does not
    care which happened.
    """

    @extend_schema(request=None, responses={204: None, 404: DetailErrorSerializer})
    def delete(self, request, checklist_id, item_id, day):
        item = ChecklistItem.objects.filter(pk=item_id, checklist_id=checklist_id).first()
        if item is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )

        tick = ChecklistTick.objects.filter(item=item, day=day).first()
        if tick is not None and tick.deleted_at is None:
            # The database's clock - see ChecklistDetailView.delete above.
            tick.deleted_at = Now()
            tick.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
