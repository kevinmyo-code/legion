"""Household-finance ticket 07: net worth, cash flow, runway, the US top-up
check and safe to spend. One synthetic household (`tests/finance_synthetic.py`):

- 1 USD = 1.25 SGD, stored for 1 Nov.
- US checking 10000.00, US card owing 1000.00, Singapore savings 20000.00 SGD.
- Spend: 3000.00 a month on groceries in Aug, Sep and Oct (plus one Zelle to
  Sam, which is household-internal and not spend). Mortgage 1000.00 SGD.
- Plan: Alex's job 2000.00 a month until 30 Nov, a next job from March
  (expected) or a 1000.00 fallback from January; rent 2500.00 SGD a month.
- Today is 5 Nov 2026.
"""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

import pytest

from api.spend import Month
from finance.models import FinancePolicy, IncomePlan
from finance.networth import net_worth
from finance.runway import NeedsRate, cash_flow, runway, safe_to_spend, us_topup
from tests.finance_synthetic import RATE_A, Caller, make_household, rate, snapshot
from tests.test_spend_api import line, statement

pytestmark = pytest.mark.django_db

TODAY = dt.date(2026, 11, 5)
UTC = ZoneInfo("UTC")


def _spend_history(household):
    for month, end in (("08", "31"), ("09", "30"), ("10", "31")):
        lines = [line(f"2026-{month}-10", "GROCER", -300_000, "Groceries")]
        if month == "10":
            lines.append(line("2026-10-12", "Zelle payment to SAM RIVER", -100_000, "Transfers"))
        statement(household, "1000", "US checking", lines,
                  start=f"2026-{month}-01", end=f"2026-{month}-{end}")


@pytest.fixture
def world(household_a, household_user):
    _, accounts = make_household(household_a)
    rate(dt.date(2026, 11, 1), RATE_A, "frankfurter")
    snapshot(household_a, accounts["us-checking"], dt.date(2026, 11, 4), 1_000_000)
    snapshot(household_a, accounts["us-card"], dt.date(2026, 11, 4), -100_000)
    snapshot(household_a, accounts["sg-savings"], dt.date(2026, 10, 31), 2_000_000)
    snapshot(household_a, accounts["flat"], dt.date(2026, 10, 1), 50_000_000, estimate=True)
    snapshot(household_a, accounts["flat-loan"], dt.date(2026, 10, 1), -10_000_000,
             estimate=True)
    _spend_history(household_a)
    return household_a, accounts, Caller(household_user)


def test_net_worth_is_per_layer_debt_negative_and_estimates_flagged(world):
    household, _, _ = world
    body = net_worth(household, TODAY, UTC, "USD")
    layers = {layer["layer"]: layer for layer in body["layers"]}
    # 10000 - 1000 + 20000 SGD at 1.25 (16000).
    assert layers["liquid"]["total_cents"] == 2_500_000
    assert layers["liquid"]["is_estimate"] is False
    # 500000 SGD flat - 100000 SGD loan, at 1.25.
    assert layers["property"]["total_cents"] == 32_000_000
    assert layers["property"]["is_estimate"] is True and body["total_is_estimate"] is True
    assert body["rate"] == {"rate_micros": RATE_A, "rate_date": dt.date(2026, 11, 1),
                            "source": "frankfurter"}
    assert body["runway_cents"] == 2_500_000
    assert set(body["missing"]) == {"sg-card", "brokerage", "pension", "car"}
    sgd = net_worth(household, TODAY, UTC, "SGD")
    assert {lay["layer"]: lay["total_cents"] for lay in sgd["layers"]}["liquid"] == 3_125_000


def test_net_worth_without_a_rate_leaves_sgd_out_in_words(household_a):
    _, accounts = make_household(household_a)
    snapshot(household_a, accounts["sg-savings"], dt.date(2026, 10, 31), 2_000_000)
    snapshot(household_a, accounts["us-checking"], dt.date(2026, 11, 4), 1_000_000)
    body = net_worth(household_a, TODAY, UTC, "USD")
    assert body["unconverted"] == ["sg-savings"] and body["total_cents"] == 1_000_000


def test_cash_flow_nets_household_internal_money_and_runs_test_a(world):
    household, _, caller = world
    body = cash_flow(caller, household, Month(2026, 10), TODAY, UTC)
    assert body["spend_cents"] == 300_000  # the Zelle to Sam is not spend
    assert [row["reason"] for row in body["household_internal"]] == ["MEMBER_TRANSFER"]
    # Planned: 2000 USD + 2500 SGD (2000 USD at 1.25). Mortgage: 1000 SGD (800 USD).
    assert body["income_planned_cents"] == 400_000 and body["income_planned_is_estimate"]
    assert body["mortgage_cents"] == 80_000
    assert body["net_cents"] == 20_000 and body["test_a_passes"] is True
    assert "sg-savings" in body["accounts_without_rows"]


def test_a_gap_month_recommends_a_transfer_with_its_date_and_rate(world):
    household, _, caller = world
    body = runway(caller, household, TODAY, UTC)
    assert body["is_estimate"] is True and body["rate"]["rate_micros"] == RATE_A
    assert body["average_spend"] == {
        "months": ["2026-08", "2026-09", "2026-10"], "usd_cents": 300_000, "sgd_cents": 0,
    }
    expected = body["scenarios"]["expected"]
    # US: 9000 now, -3000 a month, no pay from December until March: short from January.
    assert expected["months"][0]["us_end_cents"] == 600_000
    transfer = expected["transfer"]
    assert transfer["first_short_month"] == "2027-01"
    assert transfer["by"] == dt.date(2026, 12, 25)
    assert transfer["usd_cents"] == 500_000 and transfer["sgd_cents"] == 625_000
    assert expected["floor_crossing_month"] is None
    # Without the next job, the household falls below its floor.
    assert body["scenarios"]["fallback"]["floor_crossing_month"] == "2027-12"


def test_runway_without_any_rate_refuses_in_words(world):
    household, _, caller = world
    from finance.models import FxRate

    FxRate.objects.all().delete()
    with pytest.raises(NeedsRate, match="No USD/SGD rate is stored yet"):
        runway(caller, household, TODAY, UTC)


def test_the_us_topup_check_counts_what_is_owed_against_30_days_of_bills(world):
    household, accounts, _ = world
    check = us_topup(household, TODAY, UTC)
    assert (check["headroom_cents"], check["short_cents"], check["status"]) == (
        900_000, 0, "ok"
    )
    snapshot(household, accounts["us-checking"], dt.date(2026, 11, 5), 200_000)
    check = us_topup(household, TODAY, UTC)
    assert check["short_cents"] == 100_000 and check["card_due_on"] == dt.date(2026, 11, 20)
    assert check["transfer_by"] == dt.date(2026, 11, 13) and check["card_owed_is_estimate"]


@pytest.fixture
def paid_biweekly(household_a, household_user):
    _, accounts = make_household(household_a, with_plan=False)
    IncomePlan.objects.create(
        household=household_a, key="pay", label="Pay", amount_cents=100_000, currency="USD",
        cadence="biweekly", start_on=dt.date(2026, 10, 30), confidence="confirmed",
        account=accounts["us-checking"],
    )
    FinancePolicy.objects.filter(household=household_a).update(
        fixed_bills=[
            {"label": "Rent", "amount_cents": 100_000, "day": 1},
            {"label": "Power", "amount_cents": 10_000, "day": 10},
        ]
    )
    snapshot(household_a, accounts["us-card"], dt.date(2026, 11, 4), -100_000, pending=-20_000)
    return household_a, accounts, Caller(household_user)


def test_safe_to_spend_is_checking_less_card_owed_less_bills_before_payday(paid_biweekly):
    household, accounts, caller = paid_biweekly
    snapshot(household, accounts["us-checking"], dt.date(2026, 11, 4), 1_000_000)
    body = safe_to_spend(caller, household, TODAY, UTC)
    assert body["until"] == dt.date(2026, 11, 13)
    parts = body["parts"]
    # Card: 1000 owed plus 200 pending. Bills: only Power falls before the 13th.
    assert (parts["checking_cents"], parts["card_owed_cents"]) == (1_000_000, 120_000)
    assert [b["label"] for b in parts["bills"]] == ["Power"] and not parts["bills_detected"]
    assert body["safe_to_spend_cents"] == 870_000
    assert (body["top_up_needed_cents"], body["top_up_by"]) == (0, None)


def test_below_zero_safe_to_spend_needs_a_top_up_a_week_before_payday(paid_biweekly):
    household, accounts, caller = paid_biweekly
    snapshot(household, accounts["us-checking"], dt.date(2026, 11, 4), 100_000)
    body = safe_to_spend(caller, household, TODAY, UTC)
    assert body["safe_to_spend_cents"] == -30_000
    # The trigger asks more: 2000 of bills against 100 - 1200.
    assert body["top_up_needed_cents"] == 220_000
    assert body["top_up_by"] == dt.date(2026, 11, 6) and body["top_up_reason"] == "below zero"


def test_without_a_bill_list_the_fixed_categories_recurring_merchants_are_used(paid_biweekly):
    household, accounts, caller = paid_biweekly
    FinancePolicy.objects.filter(household=household).update(fixed_bills=[])
    snapshot(household, accounts["us-checking"], dt.date(2026, 11, 4), 1_000_000)
    for month in ("08", "09", "10"):
        statement(household, "1000", "US checking",
                  [line(f"2026-{month}-08", "LANDLORD CO 0042", -100_000, "Housing")],
                  start=f"2026-{month}-01", end=f"2026-{month}-28")
    parts = safe_to_spend(caller, household, TODAY, UTC)["parts"]
    assert parts["bills_detected"] is True
    assert [(b["label"], b["due_on"]) for b in parts["bills"]] == [
        ("Landlord Co", dt.date(2026, 11, 8))
    ]


def test_no_policy_says_what_to_load(household_a, household_user):
    make_household(household_a, with_policy=False)
    body = safe_to_spend(Caller(household_user), household_a, TODAY, UTC)
    assert body["available"] is False and "finance file" in body["reason"]


def test_the_routes_answer_for_members_and_never_leak(world, token_a, token_b):
    for path in ("networth", "cashflow?month=2026-10", "runway", "safe-to-spend"):
        assert token_a.get(f"/api/finance/{path}").status_code == 200, path
    b_worth = token_b.get("/api/finance/networth").data
    assert b_worth["total_cents"] == 0 and b_worth["missing"] == []
    assert token_b.get("/api/finance/safe-to-spend").data["available"] is False
    assert token_a.get("/api/finance/networth", {"ccy": "EUR"}).status_code == 400
    assert token_a.get("/api/finance/cashflow", {"month": "Oct"}).status_code == 400


def test_the_tools_say_their_figures_and_their_estimates(world, settings):
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    _, _, caller = world
    client = client_with(caller.user)
    is_error, text, body = call(client, "net_worth", {})
    assert not is_error and "ESTIMATE" in text and "Liquid: 25,000.00 USD" in text
    is_error, text, _ = call(client, "cash_flow", {"month": "2026-10"})
    assert not is_error and "ESTIMATE" in text and "left out of spend" in text
    is_error, text, _ = call(client, "runway", {})
    assert not is_error and text.startswith("Every figure here is an ESTIMATE")
    is_error, text, _ = call(client, "safe_to_spend", {})
    assert not is_error and text.startswith("Safe to spend:")
    is_error, text, _ = call(client, "cash_flow", {"month": "2026-13"})
    assert is_error and "Nothing was" in text
