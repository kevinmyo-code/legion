"""`/api/purchases`: the household bought log (purchase-log ticket 06).

The phone reaches this online only (ticket 04, Kevin): there is no Room
replica and no `/api/changes` key, so these routes serve the phone's screen
and voice directly, and the web's. Authentication is the API default, the same
as checklists: a device token (the phone, `/mcp`) or a browser session (the
web). Every read and write goes through `visible()`, so another household's
entry and another member's private entry are both a 404 (ADR 0045, ADR 0052).

Entries made here are `MANUAL`. `GROCERIES_TICK` entries are made only by the
tick route (`purchases/groceries.py`, ADR 0055); they may be edited (a price
added, say) and deleted here like any other.
"""

from __future__ import annotations

from django.db.models.functions import Now
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import (
    OpenApiParameter,
    OpenApiResponse,
    extend_schema,
    inline_serializer,
)
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import NOT_FOUND, WRITE_REFUSED, DetailSerializer
from api.sync import save_or_400
from household.tenancy import VISIBILITY_PRIVATE, household_of, owner_after_change, visible
from purchases import queries
from purchases.matching import is_searchable, say_last_bought
from purchases.models import SOURCE_MANUAL, SOURCES, Purchase
from purchases.serializers import PurchaseSerializer

PURCHASE_TAGS = ["purchases"]

IDEMPOTENT_REPEAT = (
    "**Not created - this `sync_id` already exists**, and the body is the entry that was already "
    "there, unchanged. A client tells the two apart by the status code, never by the body."
)


def _render(rows, request, many=True):
    return PurchaseSerializer(rows, many=many, context={"request": request}).data


def _not_found(purchase_id):
    return Response(
        {"detail": f"Nothing was changed. There is no bought entry {purchase_id}."},
        status=status.HTTP_404_NOT_FOUND,
    )


def _int_param(request, name: str):
    """(value, refusal). An absent parameter is (None, None)."""
    raw = request.query_params.get(name)
    if raw in (None, ""):
        return None, None
    try:
        return int(raw), None
    except ValueError:
        return None, Response(
            {
                "detail": (
                    f"Nothing was read. {name}={raw!r} is not a whole number (a local epoch "
                    f"day, or a count)."
                )
            },
            status=status.HTTP_400_BAD_REQUEST,
        )


ListResponse = inline_serializer(
    name="PurchaseList",
    fields={
        "results": PurchaseSerializer(many=True),
        "truncated": serializers.BooleanField(
            help_text="True when more entries matched than `limit`; only the newest are here."
        ),
        "message": serializers.CharField(
            allow_null=True,
            help_text=(
                "A sentence to show when `results` is empty, else null. With `q` it is "
                "\"I have no record of buying ...\", never \"never bought\"."
            ),
        ),
    },
)

LastBoughtResponse = inline_serializer(
    name="LastBought",
    fields={
        "query": serializers.CharField(),
        "matches": inline_serializer(
            name="LastBoughtMatch",
            fields={
                "entry": PurchaseSerializer(),
                "exact": serializers.BooleanField(
                    help_text=(
                        "True when the entry's text IS the query (case and spacing aside). "
                        "False for a loose match: say the entry's own text, never the query's."
                    )
                ),
                "times_logged": serializers.IntegerField(
                    help_text="How many live entries carry this same item text."
                ),
            },
            many=True,
        ),
        "message": serializers.CharField(
            help_text="The sentence to show or say. It always names the entries it matched."
        ),
    },
)


class PurchaseListCreateView(APIView):
    """`GET /api/purchases/` and `POST /api/purchases/`."""

    @extend_schema(
        operation_id="api_purchases_list",
        tags=PURCHASE_TAGS,
        parameters=[
            OpenApiParameter(
                "q",
                OpenApiTypes.STR,
                description=(
                    "Text search with the same matching as `last-bought`: every word of `q` "
                    "appears in the entry, plurals folded."
                ),
            ),
            OpenApiParameter("from", OpenApiTypes.INT, description="First local epoch day."),
            OpenApiParameter("to", OpenApiTypes.INT, description="Last local epoch day."),
            OpenApiParameter("source", OpenApiTypes.STR, enum=list(SOURCES)),
            OpenApiParameter(
                "limit",
                OpenApiTypes.INT,
                description=f"At most this many (default {queries.LIST_LIMIT_DEFAULT}, "
                f"max {queries.LIST_LIMIT_MAX}).",
            ),
        ],
        responses={
            200: OpenApiResponse(
                response=ListResponse,
                description=(
                    "Live entries this member may see, newest first (`bought_on`, then "
                    "`logged_at`). Deleted entries are never listed. Another member's private "
                    "entries are not here at all."
                ),
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Nothing was read."),
        },
    )
    def get(self, request):
        parsed = {}
        for name in ("from", "to", "limit"):
            value, refusal = _int_param(request, name)
            if refusal is not None:
                return refusal
            parsed[name] = value
        source = request.query_params.get("source") or None
        if source is not None and source not in SOURCES:
            return Response(
                {
                    "detail": (
                        f"Nothing was read. {source!r} is not a source. Use one of: "
                        f"{', '.join(SOURCES)}."
                    )
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        query = (request.query_params.get("q") or "").strip() or None
        limit = parsed["limit"] or queries.LIST_LIMIT_DEFAULT
        limit = max(1, min(limit, queries.LIST_LIMIT_MAX))
        listing = queries.list_entries(
            request,
            query=query,
            from_day=parsed["from"],
            to_day=parsed["to"],
            source=source,
            limit=limit,
        )
        message = None
        if not listing.rows:
            if query:
                message = f"I have no record of buying {query}."
            elif any(parsed[k] is not None for k in ("from", "to")) or source:
                message = "No bought entries match those filters."
            else:
                message = "Nothing has been logged as bought yet."
        return Response(
            {
                "results": _render(listing.rows, request),
                "truncated": listing.truncated,
                "message": message,
            }
        )

    @extend_schema(
        operation_id="api_purchases_create",
        tags=PURCHASE_TAGS,
        request=PurchaseSerializer,
        responses={
            201: OpenApiResponse(
                response=PurchaseSerializer,
                description=(
                    "Logged. Source MANUAL, logged by the member making the request. Shared "
                    "unless `visibility` is `private`."
                ),
            ),
            200: OpenApiResponse(response=PurchaseSerializer, description=IDEMPOTENT_REPEAT),
            400: WRITE_REFUSED,
        },
    )
    def post(self, request):
        sync_id = request.data.get("sync_id")
        if sync_id:
            existing = visible(Purchase, request).filter(sync_id=sync_id).first()
            if existing is not None:
                return Response(_render(existing, request, many=False), status=status.HTTP_200_OK)

        serializer = PurchaseSerializer(data=request.data, context={"request": request})
        serializer.is_valid(raise_exception=True)
        wanted = serializer.validated_data.pop("visibility", None)
        instance, error = save_or_400(
            lambda: serializer.save(
                household=household_of(request),
                created_by=request.user,
                owner_user=request.user if wanted == VISIBILITY_PRIVATE else None,
                source=SOURCE_MANUAL,
            )
        )
        if error is not None:
            return error
        instance.refresh_from_db()
        return Response(_render(instance, request, many=False), status=status.HTTP_201_CREATED)


class PurchaseDetailView(APIView):
    """`GET`/`PATCH`/`DELETE /api/purchases/<purchase_id>`."""

    def _get(self, purchase_id, request):
        return (
            visible(Purchase, request).select_related("created_by").filter(pk=purchase_id).first()
        )

    @extend_schema(
        operation_id="api_purchases_retrieve",
        tags=PURCHASE_TAGS,
        responses={200: PurchaseSerializer, 404: NOT_FOUND},
    )
    def get(self, request, purchase_id):
        instance = self._get(purchase_id, request)
        if instance is None:
            return _not_found(purchase_id)
        return Response(_render(instance, request, many=False))

    @extend_schema(
        operation_id="api_purchases_partial_update",
        tags=PURCHASE_TAGS,
        request=PurchaseSerializer,
        responses={
            200: PurchaseSerializer,
            400: WRITE_REFUSED,
            403: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "Nothing was changed: this member may not make the entry private. `detail` "
                    "is the sentence to show."
                ),
            ),
            404: NOT_FOUND,
        },
    )
    def patch(self, request, purchase_id):
        instance = self._get(purchase_id, request)
        if instance is None or instance.deleted_at is not None:
            return _not_found(purchase_id)
        serializer = PurchaseSerializer(
            instance, data=request.data, partial=True, context={"request": request}
        )
        serializer.is_valid(raise_exception=True)
        derived: dict = {}
        wanted = serializer.validated_data.pop("visibility", None)
        if wanted is not None:
            owner, refusal = owner_after_change(instance, wanted, request.user)
            if refusal is not None:
                return Response({"detail": refusal}, status=status.HTTP_403_FORBIDDEN)
            derived["owner_user_id"] = owner
        _saved, error = save_or_400(lambda: serializer.save(**derived))
        if error is not None:
            return error
        return Response(_render(self._get(purchase_id, request), request, many=False))

    @extend_schema(
        operation_id="api_purchases_destroy",
        tags=PURCHASE_TAGS,
        responses={
            204: OpenApiResponse(
                description=(
                    "Done. Soft-deleted (`deleted_at` set); already deleted is also a 204. A "
                    "deleted entry no longer answers `last-bought` or the list."
                )
            ),
            404: NOT_FOUND,
        },
    )
    def delete(self, request, purchase_id):
        instance = self._get(purchase_id, request)
        if instance is None:
            return _not_found(purchase_id)
        if instance.deleted_at is None:
            instance.deleted_at = Now()
            instance.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)


class LastBoughtView(APIView):
    """`GET /api/purchases/last-bought?q=shampoo` (purchase-log ticket 02)."""

    @extend_schema(
        operation_id="api_purchases_last_bought",
        tags=PURCHASE_TAGS,
        parameters=[
            OpenApiParameter(
                "q",
                OpenApiTypes.STR,
                required=True,
                description="What was bought, in the asker's words (\"shampoo\").",
            )
        ],
        responses={
            200: OpenApiResponse(
                response=LastBoughtResponse,
                description=(
                    "Every distinct item text that matches, each as its newest entry, newest "
                    "first. Matching is loose (every word of `q` in the entry, plurals folded), "
                    "so an answer must name the entry's own text and date, as `message` does. "
                    "Several matches: say them all, never pick one. No match is an empty "
                    "`matches` and \"I have no record of buying ...\": absence of a record, "
                    "never \"never bought\"."
                ),
            ),
            400: OpenApiResponse(response=DetailSerializer, description="Nothing was searched."),
        },
    )
    def get(self, request):
        query = (request.query_params.get("q") or "").strip()
        if not is_searchable(query):
            return Response(
                {
                    "detail": (
                        "Nothing was searched. Say what was bought, in letters or numbers "
                        "(q=shampoo)."
                    )
                },
                status=status.HTTP_400_BAD_REQUEST,
            )
        found = queries.last_bought(request, query)
        return Response(
            {
                "query": query,
                "matches": [
                    {
                        "entry": _render(match.entry, request, many=False),
                        "exact": match.exact,
                        "times_logged": match.times_logged,
                    }
                    for match in found
                ],
                "message": say_last_bought(query, found),
            }
        )

