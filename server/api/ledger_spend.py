"""`GET /api/ledger/spend` (web-revamp ticket 11, spec D5): the route over
`api/spend.py`, which holds every rule and names the Kotlin each one ports.

Reads through `scoped()` like every other ledger route (ADR 0045), so it is
not blocked on Postgres RLS (web-and-households 02b); the spec overrides
web-and-households ticket 11's blocker for this one endpoint.
"""

from __future__ import annotations

import re

from django.db import connection
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from api.spend import BadRequest, household_spend, resolve_month
from household.tenancy import household_of, scoped
from ingest.category_overrides import with_effective_category
from legacy.models.ledger import BudgetTarget, Category, LedgerTransaction, Statement

# The phone reads both as unverified (`LedgerTransactionsMirror.ledgerIngestMethodFor`):
# a row no reconciliation gate ever checked.
UNVERIFIED_PROVENANCE = frozenset({"UNRECONCILED", "USER"})


class SpendAccountSerializer(serializers.Serializer):
    account_last4 = serializers.CharField()
    label = serializers.CharField(
        help_text='The card\'s most recent bank-file name, else "Card ending 7823".'
    )
    spend_cents = serializers.IntegerField(
        help_text="Categorised outflows this budget month, after transfers and not-spending "
        "categories are taken out. Uncategorised money is not in it."
    )
    unverified = serializers.BooleanField(
        help_text="True when any row counted in spend_cents is unverified (no gate ever "
        "checked it). Say the word beside the figure."
    )
    unverified_cents = serializers.IntegerField()
    latest_row_at = serializers.DateTimeField(
        allow_null=True, help_text="When the newest row for this card reached the engine."
    )


class SpendCategorySerializer(serializers.Serializer):
    category = serializers.CharField()
    spend_cents = serializers.IntegerField()
    target_cents = serializers.IntegerField(
        allow_null=True, help_text="This month's budget target, or null when none is set."
    )
    unverified = serializers.BooleanField()


class InternalReasonSerializer(serializers.Serializer):
    reason = serializers.ChoiceField(
        choices=["MEMBER_TRANSFER", "CROSS_BORDER_PAIR"],
        help_text="MEMBER_TRANSFER: to or from a household member. CROSS_BORDER_PAIR: the "
        "two legs of a transfer between the household's accounts in two countries.",
    )
    cents = serializers.IntegerField()


class SpendExcludedSerializer(serializers.Serializer):
    not_spending_cents = serializers.IntegerField(
        help_text="Outflows filed under a category marked not spending (e.g. Transfers)."
    )
    not_spending_categories = serializers.ListField(child=serializers.CharField())
    own_account_moves_cents = serializers.IntegerField(
        help_text="Outflows whose description names one of the household's own accounts."
    )
    early_charges_moved_cents = serializers.IntegerField(
        help_text="Housing charges in a month's last 3 days moved across this month's edges, "
        "both directions together; the two fields after this split them."
    )
    early_charges_counted_here_cents = serializers.IntegerField(
        help_text="Dated last month, counted in this one."
    )
    early_charges_counted_next_month_cents = serializers.IntegerField(
        help_text="Dated this month, counted in the next one."
    )
    household_internal_cents = serializers.IntegerField(
        help_text="Outflows that moved money inside the household (to a spouse, or between "
        "the household's own accounts in two countries). Not spending; say so."
    )
    household_internal_reasons = InternalReasonSerializer(many=True)


class SpendSerializer(serializers.Serializer):
    month = serializers.CharField(help_text="YYYY-MM, the budget month these figures are for.")
    currency = serializers.CharField()
    accounts = SpendAccountSerializer(many=True)
    categories = SpendCategorySerializer(many=True)
    uncategorised_cents = serializers.IntegerField(
        help_text="Outflows nobody has categorised. NOT counted in any spend figure; say so "
        "beside it."
    )
    uncategorised_unverified = serializers.BooleanField()
    excluded = SpendExcludedSerializer()
    complete = serializers.BooleanField(
        help_text="True only when every account active this month has gated statements "
        "covering every day of it. The current month, read from provisional activity, is "
        "never complete."
    )


def spend_for(request, month, currency: str) -> dict:
    """One budget month's spend body for the request's household. The route
    and the engine MCP tool (`engine_mcp/tools.py` `monthly_spend`) both call
    this, so a figure read over MCP is the figure the web shows.

    **One layer above the port** (household-finance ticket 02, ADR 0073): rows
    `finance.internal` marks household-internal (money between the spouses, a
    cross-border transfer between the household's own accounts) are taken out
    BEFORE `household_spend` runs, and disclosed under `excluded`.
    `api/spend.py` itself is unchanged, so the parity test still replays the
    phone's own rules exactly."""
    rows, internal = _rows_and_internal(request)
    body = household_spend(
        rows,
        *_spend_inputs(request),
        month,
        currency,
    )
    body["excluded"].update(internal_disclosure(internal, month, currency))
    return body


def internal_disclosure(internal: list[tuple[dict, object]], month, currency: str) -> dict:
    """The household-internal outflows of one budget month, in one currency."""
    from api.spend import budget_month_of_row

    out = [
        (row, mark)
        for row, mark in internal
        if row["currency"] == currency
        and row["amount_cents"] < 0
        and budget_month_of_row(row) == month
    ]
    reasons: dict[str, int] = {}
    for row, mark in out:
        reasons[mark.reason] = reasons.get(mark.reason, 0) - row["amount_cents"]
    return {
        "household_internal_cents": sum(reasons.values()),
        "household_internal_reasons": [
            {"reason": reason, "cents": cents} for reason, cents in sorted(reasons.items())
        ],
    }


def _rows_and_internal(request):
    """Every ledger row the spend rules read, less the household-internal ones,
    and those ones with their marks."""
    from finance.internal import marks_for

    rows = [
        {
            **row,
            "provenance": "UNRECONCILED"
            if row["provenance"] in UNVERIFIED_PROVENANCE
            else row["provenance"],
        }
        for row in with_effective_category(scoped(LedgerTransaction, request)).values(
            "id",
            "created_at",
            "account_last4",
            "account_nickname",
            "currency",
            "txn_date",
            "description",
            "amount_cents",
            "effective_category",
            "provenance",
            "bank_pending",
        )
    ]
    marks = marks_for(household_of(request), rows)
    kept = [row for row in rows if str(row["id"]) not in marks]
    internal = [(row, marks[str(row["id"])]) for row in rows if str(row["id"]) in marks]
    return kept, internal


def _spend_inputs(request) -> tuple[list, list, list]:
    """Categories, budget targets and statements, as `household_spend` reads them."""
    categories = (
        scoped(Category, request)
        .filter(deleted_at__isnull=True)
        .values("name", "excluded_from_spend")
    )
    targets = (
        scoped(BudgetTarget, request)
        .filter(deleted_at__isnull=True)
        .values("category", "currency", "amount_cents", "effective_from_month")
    )
    statements = scoped(Statement, request).values(
        "account_last4", "currency", "period_start", "period_end"
    )
    return list(categories), list(targets), list(statements)


class SpendView(APIView):
    @extend_schema(
        operation_id="api_ledger_spend_retrieve",
        tags=["ledger"],
        parameters=[
            OpenApiParameter(
                "month",
                str,
                OpenApiParameter.QUERY,
                required=False,
                description="YYYY-MM. Defaults to the current month in `tz`.",
            ),
            OpenApiParameter(
                "tz",
                str,
                OpenApiParameter.QUERY,
                required=False,
                description="The browser's IANA zone, used only to pick the default month. "
                "Unknown or absent means UTC.",
            ),
            OpenApiParameter(
                "currency",
                str,
                OpenApiParameter.QUERY,
                required=False,
                description="ISO code. Defaults to USD.",
            ),
        ],
        responses={
            200: OpenApiResponse(
                response=SpendSerializer,
                description="One budget month's spend, per account and per category, computed "
                "by the same rules as the phone's Money screen.",
            ),
            400: OpenApiResponse(
                response=DetailSerializer,
                description="`month` or `currency` is malformed. Nothing was computed.",
            ),
        },
    )
    def get(self, request):
        currency = (request.query_params.get("currency") or "USD").upper()
        if not re.fullmatch(r"[A-Z]{3}", currency):
            return Response(
                {"detail": f"{currency!r} is not a currency code. Nothing was computed."},
                status=status.HTTP_400_BAD_REQUEST,
            )
        with connection.cursor() as cursor:
            cursor.execute("SELECT statement_timestamp()")
            now = cursor.fetchone()[0]
        try:
            month = resolve_month(
                request.query_params.get("month"), request.query_params.get("tz"), now
            )
        except BadRequest as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_400_BAD_REQUEST)

        body = spend_for(request, month, currency)
        return Response(SpendSerializer(body).data)
