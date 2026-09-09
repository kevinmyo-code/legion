"""Views for `/api/checklists` (django-engine ticket 04, Phase 2 slice).
See `checklists/serializers.py`'s own doc comment for the measured-tick
refusal wording this ticket asks to be reused verbatim, and
`ChecklistController.kt` for the semantics every method here mirrors:
idempotent tick, revive-on-retick, soft-delete-only.
"""
from __future__ import annotations

from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import NOT_FOUND, SINCE_PARAMETER, WRITE_REFUSED, paged_serializer
from api.sync import paginate_since, parse_since, save_or_400
from checklists.models import Checklist, ChecklistItem, ChecklistTick
from checklists.serializers import (
    ChecklistItemSerializer,
    ChecklistSerializer,
    ChecklistTickSerializer,
    TickRequestSerializer,
)
from household.tenancy import household_of, scoped

CHECKLIST_TAGS = ["checklists"]

# `POST` here is idempotent on `sync_id`, and the 200-versus-201 split is
# the ONLY thing that tells a caller which happened - the body is the row
# either way. Worded once and reused by both collection POSTs so the two
# cannot describe the same behaviour differently.
IDEMPOTENT_REPEAT = (
    "**Not created - this `sync_id` already exists**, and the body is the row that was "
    "already there, unchanged. A retried create is a no-op; a client tells the two apart by "
    "the status code, never by the body."
)


def _idempotent_or_none(model, sync_id, request):
    """`sync_id` honoured on POST for idempotent create (this ticket's own
    rule 5: "the phone retries") - a retried create with the same
    `sync_id` returns the row that already exists rather than making a
    second one. Never treated as a match when blank/absent - `sync_id` is
    nullable+unique, and Postgres already treats every NULL as distinct
    from every other NULL, so this mirrors that at the application layer
    too rather than matching two callers who both sent nothing."""
    if not sync_id:
        return None
    # Household-scoped (ADR 0045). Unscoped, household B's retry of its own
    # `sync_id` would find household A's row and be answered with A's data as
    # though it were the row B had just created - the same trap
    # `api/events.py`'s POST idempotency has, closed the same way.
    return scoped(model, request).filter(sync_id=sync_id).first()


class ChecklistListCreateView(APIView):
    """`GET /api/checklists?since=<iso>` and `POST /api/checklists`."""

    @extend_schema(
        operation_id="api_checklists_list",
        tags=CHECKLIST_TAGS,
        parameters=[SINCE_PARAMETER],
        responses={
            200: OpenApiResponse(
                response=paged_serializer(ChecklistSerializer),
                description=(
                    "Checklists changed at or after `since`, tombstones included, oldest "
                    "first, 500 to a page. Items and ticks are their own routes; "
                    "GET /api/changes returns all three together."
                ),
            )
        },
    )
    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        queryset = (
            scoped(Checklist, request).filter(updated_at__gte=since).order_by("updated_at")
        )
        page, next_since = paginate_since(queryset)
        return Response({"results": ChecklistSerializer(page, many=True).data, "next": next_since})

    @extend_schema(
        operation_id="api_checklists_create",
        tags=CHECKLIST_TAGS,
        request=ChecklistSerializer,
        responses={
            201: OpenApiResponse(
                response=ChecklistSerializer,
                description="Created. The body is the row as stored.",
            ),
            200: OpenApiResponse(response=ChecklistSerializer, description=IDEMPOTENT_REPEAT),
            400: WRITE_REFUSED,
        },
    )
    def post(self, request):
        existing = _idempotent_or_none(Checklist, request.data.get("sync_id"), request)
        if existing is not None:
            return Response(ChecklistSerializer(existing).data, status=status.HTTP_200_OK)

        serializer = ChecklistSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        # ADR 0045: the household comes from the request, never the body -
        # `household` is on no serializer's `Meta.fields`. `ChecklistItem` and
        # `ChecklistTick` are NOT assigned one here on purpose: their models
        # derive it from their parent in `save()`, because an item's household
        # IS its checklist's and two places to set it is two places to get it
        # wrong.
        instance, error = save_or_400(
            lambda: serializer.save(household=household_of(request))
        )
        if error is not None:
            return error
        return Response(ChecklistSerializer(instance).data, status=status.HTTP_201_CREATED)


class ChecklistDetailView(APIView):
    """`GET`/`PATCH`/`DELETE /api/checklists/<checklist_id>`."""

    def _get(self, checklist_id, request):
        return scoped(Checklist, request).filter(pk=checklist_id).first()

    @extend_schema(
        operation_id="api_checklists_retrieve",
        tags=CHECKLIST_TAGS,
        responses={200: ChecklistSerializer, 404: NOT_FOUND},
    )
    def get(self, request, checklist_id):
        instance = self._get(checklist_id, request)
        if instance is None:
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(ChecklistSerializer(instance).data)

    @extend_schema(
        operation_id="api_checklists_partial_update",
        tags=CHECKLIST_TAGS,
        request=ChecklistSerializer,
        responses={200: ChecklistSerializer, 400: WRITE_REFUSED, 404: NOT_FOUND},
    )
    def patch(self, request, checklist_id):
        instance = self._get(checklist_id, request)
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

    @extend_schema(
        operation_id="api_checklists_destroy",
        tags=CHECKLIST_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Done. Soft-deleted, and NOT cascaded to items or ticks - a checklist's "
                    "history is never rewritten by deleting the checklist."
                )
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, checklist_id):
        instance = self._get(checklist_id, request)
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
        tags=CHECKLIST_TAGS,
        parameters=[SINCE_PARAMETER],
        responses={
            200: OpenApiResponse(
                response=paged_serializer(ChecklistItemSerializer),
                description=(
                    "Items on this checklist changed at or after `since`, tombstones "
                    "included, oldest first, 500 to a page. An unknown `checklist_id` is an "
                    "empty page here, not a 404 - this route does not read the parent."
                ),
            )
        },
    )
    def get(self, request, checklist_id):
        since = parse_since(request.query_params.get("since"))
        queryset = (
            scoped(ChecklistItem, request)
            .filter(checklist_id=checklist_id, updated_at__gte=since)
            .order_by("updated_at")
        )
        page, next_since = paginate_since(queryset)
        return Response(
            {"results": ChecklistItemSerializer(page, many=True).data, "next": next_since}
        )

    @extend_schema(
        operation_id="api_checklists_items_create",
        tags=CHECKLIST_TAGS,
        request=ChecklistItemSerializer,
        responses={
            201: OpenApiResponse(
                response=ChecklistItemSerializer,
                description=(
                    "Created. The URL's `checklist_id` wins over any `checklist` in the body."
                ),
            ),
            200: OpenApiResponse(response=ChecklistItemSerializer, description=IDEMPOTENT_REPEAT),
            400: WRITE_REFUSED,
            404: NOT_FOUND,
        },
    )
    def post(self, request, checklist_id):
        if not scoped(Checklist, request).filter(pk=checklist_id).exists():
            return Response(
                {"detail": f"No checklist with id {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )

        existing = _idempotent_or_none(ChecklistItem, request.data.get("sync_id"), request)
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

    def _get(self, checklist_id, item_id, request):
        return (
            scoped(ChecklistItem, request)
            .filter(pk=item_id, checklist_id=checklist_id)
            .first()
        )

    @extend_schema(
        operation_id="api_checklists_items_retrieve",
        tags=CHECKLIST_TAGS,
        responses={200: ChecklistItemSerializer, 404: NOT_FOUND},
    )
    def get(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id, request)
        if instance is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        return Response(ChecklistItemSerializer(instance).data)

    @extend_schema(
        operation_id="api_checklists_items_partial_update",
        tags=CHECKLIST_TAGS,
        request=ChecklistItemSerializer,
        responses={
            200: OpenApiResponse(
                response=ChecklistItemSerializer,
                description=(
                    "The row as stored. A `checklist` in the body is DROPPED, not honoured: "
                    "an item cannot be reparented, and the URL is the only authority on "
                    "which checklist it belongs to."
                ),
            ),
            400: WRITE_REFUSED,
            404: NOT_FOUND,
        },
    )
    def patch(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id, request)
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

    @extend_schema(
        operation_id="api_checklists_items_destroy",
        tags=CHECKLIST_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Done. Soft-deleted, and NOT cascaded to its ticks - dropping an item "
                    "must not rewrite the history of days it was already ticked."
                )
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, checklist_id, item_id):
        instance = self._get(checklist_id, item_id, request)
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
        operation_id="api_checklists_items_tick_create",
        tags=CHECKLIST_TAGS,
        request=TickRequestSerializer,
        responses={
            201: OpenApiResponse(
                response=ChecklistTickSerializer,
                description="A first tick for that day.",
            ),
            200: OpenApiResponse(
                response=ChecklistTickSerializer,
                description=(
                    "A tick already existed for that day. Either an idempotent no-op (the "
                    "FIRST tap's `ticked_at`/`value`/`source` are kept) or, if it had been "
                    "unticked, the same row revived with a fresh `ticked_at`."
                ),
            ),
            400: OpenApiResponse(
                response=WRITE_REFUSED.response,
                description=(
                    "Nothing was recorded. Chiefly the measured-item refusal: an item with a "
                    "`measure_unit` needs a `value`, and the message is the same sentence "
                    "the phone's own controller uses."
                ),
            ),
            404: NOT_FOUND,
        },
    )
    def post(self, request, checklist_id, item_id):
        item = (
            scoped(ChecklistItem, request)
            .filter(pk=item_id, checklist_id=checklist_id)
            .first()
        )
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

        # Not `scoped(...)`, and that is deliberate rather than a miss: `item`
        # was resolved through `scoped(ChecklistItem, request)` above, and a
        # tick's household is its item's by construction
        # (`ChecklistTick.save`). Filtering on the item IS the household
        # filter here. The same is true of the `delete` handler below and of
        # the `objects.create` in `_write`, whose household is derived.
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

    @extend_schema(
        operation_id="api_checklists_items_tick_destroy",
        tags=CHECKLIST_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Done. Idempotent: no tick on that day is still a 204, not a 404 - "
                    "'already untouched' and 'just unticked' read the same to a caller that "
                    "does not care which happened."
                )
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, checklist_id, item_id, day):
        item = (
            scoped(ChecklistItem, request)
            .filter(pk=item_id, checklist_id=checklist_id)
            .first()
        )
        if item is None:
            return Response(
                {"detail": f"No item {item_id} on checklist {checklist_id}."},
                status=status.HTTP_404_NOT_FOUND,
            )

        # Scoped through `item` - see the note in `ChecklistTickView.post`.
        tick = ChecklistTick.objects.filter(item=item, day=day).first()
        if tick is not None and tick.deleted_at is None:
            # The database's clock - see ChecklistDetailView.delete above.
            tick.deleted_at = Now()
            tick.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
