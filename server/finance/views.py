"""`/api/finance/*` (household-finance map, ADR 0073). Read-only, members only.

Every read goes through `household.tenancy.scoped()` / `household_of()`, the
ADR 0045 choke point. Every finance row is visible to every member of the
household (map ruling: no private finance rows), so nothing here reads through
`visible()`. Serializers list their fields by hand: no household id, user id or
Plaid secret is ever in a body.
"""

from __future__ import annotations

import datetime as dt

from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from finance.models import BalanceSnapshot, FinanceAccount
from household.tenancy import scoped

TAG = "finance"


def _bad(message: str) -> Response:
    return Response({"detail": message}, status=status.HTTP_400_BAD_REQUEST)


def parse_day(raw: str | None, name: str) -> dt.date | None:
    if not raw:
        return None
    try:
        return dt.date.fromisoformat(raw)
    except ValueError as exc:
        raise ValueError(
            f"{name}={raw!r} is not a date. Use YYYY-MM-DD. Nothing was read."
        ) from exc


# =============================================================================
# Ticket 01: accounts and snapshots
# =============================================================================


class FinanceAccountSerializer(serializers.Serializer):
    key = serializers.CharField(help_text="The household's own name for the account.")
    label = serializers.CharField()
    owner = serializers.CharField(
        allow_null=True, help_text="The member's key, or null for a joint account."
    )
    institution = serializers.CharField(allow_blank=True)
    country = serializers.ChoiceField(choices=["US", "SG"])
    currency = serializers.ChoiceField(choices=["USD", "SGD"])
    kind = serializers.CharField()
    layer = serializers.CharField(help_text="liquid | investments | cpf | property | other.")
    source = serializers.CharField(help_text="plaid | statement | ibkr_flex | computed | manual.")
    last4 = serializers.CharField(allow_null=True)
    is_debt = serializers.BooleanField(help_text="True for a card or loan: balances are negative.")
    active = serializers.BooleanField()


def account_body(account: FinanceAccount) -> dict:
    return {
        "key": account.key,
        "label": account.label,
        "owner": account.owner.key if account.owner_id else None,
        "institution": account.institution,
        "country": account.country,
        "currency": account.currency,
        "kind": account.kind,
        "layer": account.layer,
        "source": account.source,
        "last4": account.last4,
        "is_debt": account.is_debt,
        "active": account.active,
    }


class FinanceAccountsView(APIView):
    @extend_schema(
        operation_id="api_finance_accounts_list",
        tags=[TAG],
        responses={200: FinanceAccountSerializer(many=True)},
        description="Every account the household holds or owes on, in both countries.",
    )
    def get(self, request):
        accounts = scoped(FinanceAccount, request).select_related("owner").order_by("key")
        return Response([account_body(a) for a in accounts])


class SnapshotSerializer(serializers.Serializer):
    account = serializers.CharField(help_text="The account's key.")
    as_of = serializers.DateField(help_text="The day the figure was true.")
    amount_cents = serializers.IntegerField(help_text="Signed: a debt is negative.")
    currency = serializers.CharField()
    source = serializers.CharField()
    evidence = serializers.CharField(allow_blank=True, help_text="What the figure stands on.")
    is_estimate = serializers.BooleanField(
        help_text="True when worked out rather than stated. Say 'estimate' beside it."
    )
    pending_cents = serializers.IntegerField(
        allow_null=True,
        help_text="The bank's own not-yet-posted rows at the time, NOT in amount_cents. "
        "Say them as pending.",
    )
    pending_count = serializers.IntegerField()


def snapshot_body(row: BalanceSnapshot) -> dict:
    return {
        "account": row.account.key,
        "as_of": row.as_of,
        "amount_cents": row.amount_cents,
        "currency": row.currency,
        "source": row.source,
        "evidence": row.evidence,
        "is_estimate": row.is_estimate,
        "pending_cents": row.pending_cents,
        "pending_count": row.pending_count,
    }


class SnapshotsView(APIView):
    @extend_schema(
        operation_id="api_finance_snapshots_list",
        tags=[TAG],
        parameters=[
            OpenApiParameter("account", str, OpenApiParameter.QUERY, required=False,
                             description="An account key."),
            OpenApiParameter("from", str, OpenApiParameter.QUERY, required=False,
                             description="YYYY-MM-DD, inclusive."),
            OpenApiParameter("to", str, OpenApiParameter.QUERY, required=False,
                             description="YYYY-MM-DD, inclusive."),
        ],
        responses={
            200: SnapshotSerializer(many=True),
            400: OpenApiResponse(DetailSerializer, description="A bad date, in words."),
        },
        description="Dated balances per account, newest first.",
    )
    def get(self, request):
        try:
            start = parse_day(request.query_params.get("from"), "from")
            end = parse_day(request.query_params.get("to"), "to")
        except ValueError as exc:
            return _bad(str(exc))
        rows = scoped(BalanceSnapshot, request).select_related("account")
        key = request.query_params.get("account")
        if key:
            rows = rows.filter(account__key=key)
        if start:
            rows = rows.filter(as_of__gte=start)
        if end:
            rows = rows.filter(as_of__lte=end)
        rows = rows.order_by("account__key", "-as_of", "source")
        return Response([snapshot_body(r) for r in rows])
