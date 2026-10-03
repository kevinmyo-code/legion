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
from household.tenancy import scoped
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
            )
        ]
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
        body = household_spend(
            rows, list(categories), list(targets), list(statements), month, currency
        )
        return Response(SpendSerializer(body).data)
