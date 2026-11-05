"""`/api/finance/{networth,cashflow,runway,safe-to-spend,budgets}` (tickets 07
and 08, ADR 0073). Read-only, members only, scoped through `household_of()`.

The figures are computed in `finance.networth`, `finance.runway` and
`finance.budgets`; the engine's MCP tools call the same functions, so a figure
Alfred says is the figure a screen shows. Serializers list every field by hand.
"""

from __future__ import annotations

from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import DetailSerializer
from household.tenancy import household_of

TAG = "finance"


def _bad(message: str) -> Response:
    return Response({"detail": message}, status=status.HTTP_400_BAD_REQUEST)


def household_today(request):
    """(today, zone) in the household's own zone, else UTC."""
    from zoneinfo import ZoneInfo

    from household.timezones import local_date, zone_named

    zone = zone_named(getattr(household_of(request), "timezone", None)) or ZoneInfo("UTC")
    return local_date(zone), zone


def month_param(raw, today):
    from api.spend import Month

    if not raw:
        return Month.of(today)
    try:
        return Month.parse(raw)
    except ValueError as exc:
        raise ValueError(
            f"month={raw!r} is not a month. Use YYYY-MM, e.g. 2026-10. Nothing was computed."
        ) from exc


MONTH_PARAM = OpenApiParameter(
    "month", str, OpenApiParameter.QUERY, required=False,
    description="YYYY-MM. Defaults to the current month in the household's zone.",
)
BAD_MONTH = OpenApiResponse(DetailSerializer, description="A bad month, in words.")
NO_RATE = OpenApiResponse(
    DetailSerializer, description="An SGD figure needs a stored USD/SGD rate and none is on file."
)


class RateSerializer(serializers.Serializer):
    rate_micros = serializers.IntegerField(help_text="1 USD = rate_micros / 1e6 SGD.")
    rate_date = serializers.DateField()
    source = serializers.CharField()


# =============================================================================
# Net worth
# =============================================================================


class NetWorthLineSerializer(serializers.Serializer):
    account = serializers.CharField()
    label = serializers.CharField()
    currency = serializers.CharField()
    amount_cents = serializers.IntegerField(help_text="In the account's currency; debt negative.")
    as_of = serializers.DateField()
    source = serializers.CharField()
    is_estimate = serializers.BooleanField()
    stale = serializers.BooleanField(help_text="Older than 45 days: say 'as of' the day.")
    pending_cents = serializers.IntegerField(allow_null=True)
    converted_cents = serializers.IntegerField(
        allow_null=True, help_text="In the view currency at today's rate; null when no rate."
    )


class NetWorthLayerSerializer(serializers.Serializer):
    layer = serializers.CharField()
    total_cents = serializers.IntegerField()
    is_estimate = serializers.BooleanField()
    accounts = NetWorthLineSerializer(many=True)


class NetWorthSerializer(serializers.Serializer):
    currency = serializers.CharField()
    as_of = serializers.DateField()
    rate = RateSerializer(allow_null=True)
    layers = NetWorthLayerSerializer(many=True)
    total_cents = serializers.IntegerField()
    total_is_estimate = serializers.BooleanField()
    runway_cents = serializers.IntegerField(help_text="Liquid plus investments.")
    missing = serializers.ListField(
        child=serializers.CharField(), help_text="Accounts with no balance on file, in no total."
    )
    unconverted = serializers.ListField(
        child=serializers.CharField(), help_text="Accounts left out for want of a rate."
    )


class NetWorthView(APIView):
    @extend_schema(
        operation_id="api_finance_networth_retrieve",
        tags=[TAG],
        parameters=[
            OpenApiParameter("ccy", str, OpenApiParameter.QUERY, required=False,
                             description="USD (default) or SGD, at today's rate."),
        ],
        responses={
            200: NetWorthSerializer,
            400: OpenApiResponse(DetailSerializer, description="A bad currency."),
        },
        description="Net worth per layer and in total. Computed values are estimates.",
    )
    def get(self, request):
        from finance.networth import net_worth

        currency = (request.query_params.get("ccy") or "USD").upper()
        if currency not in ("USD", "SGD"):
            return _bad(f"ccy={currency!r} is not USD or SGD. Nothing was computed.")
        today, zone = household_today(request)
        body = net_worth(household_of(request), today, zone, currency)
        return Response(NetWorthSerializer(body).data)


# =============================================================================
# Cash flow
# =============================================================================


class CurrencyCentsSerializer(serializers.Serializer):
    currency = serializers.CharField()
    cents = serializers.IntegerField()


class InternalRowSerializer(serializers.Serializer):
    txn_date = serializers.DateField()
    description = serializers.CharField()
    amount_cents = serializers.IntegerField()
    currency = serializers.CharField()
    reason = serializers.CharField(help_text="MEMBER_TRANSFER or CROSS_BORDER_PAIR.")
    paired = serializers.BooleanField(help_text="Both legs are on file.")


class CashFlowSerializer(serializers.Serializer):
    month = serializers.CharField()
    currency = serializers.CharField()
    rate = RateSerializer(allow_null=True)
    income_planned_cents = serializers.IntegerField(help_text="From the income plan: an estimate.")
    income_planned_is_estimate = serializers.BooleanField()
    income_on_file_cents = serializers.IntegerField(
        help_text="Inflows on the ledger the transfer rules leave standing."
    )
    spend_cents = serializers.IntegerField(
        help_text="Every netted outflow, household-internal money out."
    )
    spend_by_currency = CurrencyCentsSerializer(many=True)
    mortgage_cents = serializers.IntegerField()
    net_cents = serializers.IntegerField(help_text="Planned income - spend - mortgage.")
    test_a_passes = serializers.BooleanField(help_text="No burn: net is not negative.")
    accounts_without_rows = serializers.ListField(
        child=serializers.CharField(),
        help_text="Liquid accounts with no ledger rows this month: their spend is not in it.",
    )
    household_internal = InternalRowSerializer(many=True)


class CashFlowView(APIView):
    @extend_schema(
        operation_id="api_finance_cashflow_retrieve",
        tags=[TAG],
        parameters=[MONTH_PARAM],
        responses={200: CashFlowSerializer, 400: BAD_MONTH, 409: NO_RATE},
        description="One month's household income, spend, mortgage and net, in USD.",
    )
    def get(self, request):
        from finance.runway import NeedsRate, cash_flow

        today, zone = household_today(request)
        try:
            month = month_param(request.query_params.get("month"), today)
        except ValueError as exc:
            return _bad(str(exc))
        try:
            body = cash_flow(request, household_of(request), month, today, zone)
        except NeedsRate as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_409_CONFLICT)
        return Response(CashFlowSerializer(body).data)


# =============================================================================
# Runway
# =============================================================================


class RunwayMonthSerializer(serializers.Serializer):
    month = serializers.CharField()
    income_us_cents = serializers.IntegerField()
    income_sg_cents = serializers.IntegerField(help_text="SGD.")
    us_end_cents = serializers.IntegerField(help_text="US liquid at month end, USD.")
    sg_end_cents = serializers.IntegerField(help_text="Singapore liquid at month end, SGD.")
    liquid_end_cents = serializers.IntegerField(help_text="Household liquid, USD.")


class TransferSerializer(serializers.Serializer):
    usd_cents = serializers.IntegerField()
    sgd_cents = serializers.IntegerField(help_text="At today's rate.")
    first_short_month = serializers.CharField()
    by = serializers.DateField(help_text="Start the transfer by this day.")
    sg_side_lowest_after_cents = serializers.IntegerField(
        help_text="Singapore liquid's lowest point after the transfer, SGD."
    )


class ScenarioSerializer(serializers.Serializer):
    months = RunwayMonthSerializer(many=True)
    floor_crossing_month = serializers.CharField(allow_null=True)
    transfer = TransferSerializer(allow_null=True)


class ScenariosSerializer(serializers.Serializer):
    expected = ScenarioSerializer()
    fallback = ScenarioSerializer()


class AverageSpendSerializer(serializers.Serializer):
    months = serializers.ListField(child=serializers.CharField())
    usd_cents = serializers.IntegerField()
    sgd_cents = serializers.IntegerField()


class TopUpSerializer(serializers.Serializer):
    available = serializers.BooleanField()
    reason = serializers.CharField(required=False)
    checking_cents = serializers.IntegerField(required=False)
    checking_as_of = serializers.DateField(required=False)
    card_owed_cents = serializers.IntegerField(required=False)
    card_as_of = serializers.DateField(required=False, allow_null=True)
    card_owed_is_estimate = serializers.BooleanField(required=False)
    headroom_cents = serializers.IntegerField(required=False)
    bills_30d_cents = serializers.IntegerField(required=False)
    short_cents = serializers.IntegerField(required=False)
    card_due_on = serializers.DateField(required=False, allow_null=True)
    transfer_by = serializers.DateField(required=False, allow_null=True)
    status = serializers.CharField(required=False)


class RunwaySerializer(serializers.Serializer):
    currency = serializers.CharField()
    as_of = serializers.DateField()
    rate = RateSerializer(allow_null=True)
    is_estimate = serializers.BooleanField()
    liquid_now_cents = serializers.IntegerField()
    runway_assets_cents = serializers.IntegerField(help_text="Liquid plus investments.")
    floor_cents = serializers.IntegerField(allow_null=True)
    us_floor_cents = serializers.IntegerField(allow_null=True)
    average_spend = AverageSpendSerializer()
    mortgage_sgd_cents = serializers.IntegerField()
    monthly_burn_cents = serializers.IntegerField()
    missing_balances = serializers.ListField(child=serializers.CharField())
    scenarios = ScenariosSerializer()
    us_topup = TopUpSerializer()


class RunwayView(APIView):
    @extend_schema(
        operation_id="api_finance_runway_retrieve",
        tags=[TAG],
        responses={200: RunwaySerializer, 409: NO_RATE},
        description="Liquid projected month by month from the income plan and the last 3 full "
        "months' average spend: an estimate.",
    )
    def get(self, request):
        from finance.runway import NeedsRate, runway

        today, zone = household_today(request)
        try:
            body = runway(request, household_of(request), today, zone)
        except NeedsRate as exc:
            return Response({"detail": str(exc)}, status=status.HTTP_409_CONFLICT)
        return Response(RunwaySerializer(body).data)


# =============================================================================
# Safe to spend
# =============================================================================


class BillSerializer(serializers.Serializer):
    label = serializers.CharField()
    due_on = serializers.DateField()
    amount_cents = serializers.IntegerField()


class SafePartsSerializer(serializers.Serializer):
    checking_cents = serializers.IntegerField()
    checking_as_of = serializers.DateField()
    checking_pending_cents = serializers.IntegerField(allow_null=True)
    card_owed_cents = serializers.IntegerField(help_text="Current balance plus pending charges.")
    card_as_of = serializers.DateField(allow_null=True)
    bills_before_until_cents = serializers.IntegerField()
    bills = BillSerializer(many=True)
    bills_detected = serializers.BooleanField(
        help_text="True when the bills were worked out from recurring merchants: an estimate."
    )


class SafeToSpendSerializer(serializers.Serializer):
    available = serializers.BooleanField()
    reason = serializers.CharField(required=False)
    currency = serializers.CharField(required=False)
    safe_to_spend_cents = serializers.IntegerField(required=False)
    until = serializers.DateField(
        required=False, allow_null=True, help_text="The next expected income date (the plan's)."
    )
    until_is_estimate = serializers.BooleanField(required=False)
    parts = SafePartsSerializer(required=False)
    top_up_needed_cents = serializers.IntegerField(required=False)
    top_up_by = serializers.DateField(required=False, allow_null=True)
    top_up_reason = serializers.CharField(required=False, allow_null=True)


class SafeToSpendView(APIView):
    @extend_schema(
        operation_id="api_finance_safe_to_spend_retrieve",
        tags=[TAG],
        responses={200: SafeToSpendSerializer},
        description="Checking, minus everything owed on the card, minus the US fixed bills due "
        "before the next expected income date.",
    )
    def get(self, request):
        from finance.runway import safe_to_spend

        today, zone = household_today(request)
        body = safe_to_spend(request, household_of(request), today, zone)
        return Response(SafeToSpendSerializer(body).data)


# =============================================================================
# Budgets
# =============================================================================


class BudgetLineSerializer(serializers.Serializer):
    category = serializers.CharField()
    spent_cents = serializers.IntegerField(help_text="Pending rows included.")
    cap_cents = serializers.IntegerField(allow_null=True)
    percent = serializers.IntegerField(allow_null=True)
    pace_cents = serializers.IntegerField(
        allow_null=True, help_text="cap x days elapsed / days in month: the pace marker."
    )
    projection_cents = serializers.IntegerField(
        allow_null=True, help_text="An estimate; null before day 10."
    )
    projection_is_estimate = serializers.BooleanField()
    status = serializers.CharField(help_text="ok | near (80%) | over | no cap.")
    pending_cents = serializers.IntegerField()
    pending_count = serializers.IntegerField()
    unverified = serializers.BooleanField()


class BudgetTotalSerializer(serializers.Serializer):
    spent_cents = serializers.IntegerField()
    cap_cents = serializers.IntegerField()
    percent = serializers.IntegerField(allow_null=True)
    pace_cents = serializers.IntegerField(allow_null=True)


class FixedSerializer(serializers.Serializer):
    category = serializers.CharField()
    paid = serializers.BooleanField()
    paid_cents = serializers.IntegerField()


class FinanceSubscriptionSerializer(serializers.Serializer):
    merchant = serializers.CharField()
    category = serializers.CharField(allow_null=True)
    typical_cents = serializers.IntegerField()
    months = serializers.IntegerField()
    last_on = serializers.DateField()
    next_on = serializers.DateField(help_text="An estimate.")
    is_estimate = serializers.BooleanField()


class BudgetsSerializer(serializers.Serializer):
    month = serializers.CharField()
    currency = serializers.CharField()
    days_in_month = serializers.IntegerField()
    days_elapsed = serializers.IntegerField()
    projection_from_day = serializers.IntegerField()
    categories = BudgetLineSerializer(many=True)
    total = BudgetTotalSerializer()
    uncategorised_cents = serializers.IntegerField()
    fixed = FixedSerializer(many=True)
    subscriptions = FinanceSubscriptionSerializer(many=True)
    new_subscriptions = serializers.ListField(child=serializers.CharField())


class BudgetsView(APIView):
    @extend_schema(
        operation_id="api_finance_budgets_retrieve",
        tags=[TAG],
        parameters=[MONTH_PARAM],
        responses={200: BudgetsSerializer, 400: BAD_MONTH},
        description="Per category spent against its cap, with the pace marker and, from day 10, "
        "a projected month total (an estimate).",
    )
    def get(self, request):
        from finance.budgets import budgets

        today, _ = household_today(request)
        try:
            month = month_param(request.query_params.get("month"), today)
        except ValueError as exc:
            return _bad(str(exc))
        body = budgets(request, household_of(request), month, today)
        return Response(BudgetsSerializer(body).data)
