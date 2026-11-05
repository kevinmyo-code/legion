"""Household-finance ticket 08: budgets and pace.

Synthetic household: caps Groceries 500.00, Dining 100.00, Subscriptions
50.00. November 2026 has 30 days.
"""

from __future__ import annotations

import datetime as dt

import pytest

from api.spend import Month
from finance.budgets import budgets
from tests.finance_synthetic import CARD_LAST4, Caller, bank_row, budget, make_household
from tests.test_spend_api import line, statement

pytestmark = pytest.mark.django_db

NOVEMBER = Month(2026, 11)


@pytest.fixture
def world(household_a, household_user):
    make_household(household_a)
    budget(household_a, "Groceries", 50_000)
    budget(household_a, "Dining", 10_000)
    budget(household_a, "Subscriptions", 5_000)
    for month in ("08", "09", "10"):
        statement(household_a, "1000", "US checking",
                  [line(f"2026-{month}-03", "STREAMCO 4000", -1_000, "Subscriptions")],
                  start=f"2026-{month}-01", end=f"2026-{month}-28")
    statement(
        household_a, "1000", "US checking",
        [
            line("2026-11-02", "GROCER", -20_000, "Groceries"),
            line("2026-11-03", "STREAMCO 4000", -1_000, "Subscriptions"),
            line("2026-11-04", "NEWAPP", -500, "Subscriptions"),
            line("2026-11-04", "DINER", -10_000, "Dining"),
            line("2026-11-01", "LANDLORD CO", -100_000, "Housing"),
        ],
        start="2026-11-01", end="2026-11-04",
    )
    bank_row(household_a, CARD_LAST4, dt.date(2026, 11, 12), -10_000, "GROCER", "Groceries",
             pending=True)
    return household_a, Caller(household_user)


def by_category(body):
    return {line["category"]: line for line in body["categories"]}


def test_pace_and_projection_from_day_ten(world):
    household, caller = world
    body = budgets(caller, household, NOVEMBER, dt.date(2026, 11, 15))
    groceries = by_category(body)["Groceries"]
    assert (groceries["spent_cents"], groceries["cap_cents"], groceries["percent"]) == (
        30_000, 50_000, 60
    )
    # Even pace on the 15th of 30 days: half the cap. Projection: 300 x 30 / 15.
    assert groceries["pace_cents"] == 25_000
    assert groceries["projection_cents"] == 60_000 and groceries["projection_is_estimate"]
    assert groceries["status"] == "ok"
    assert by_category(body)["Dining"]["status"] == "over"


def test_no_projection_before_day_ten(world):
    household, caller = world
    body = budgets(caller, household, NOVEMBER, dt.date(2026, 11, 9))
    assert body["days_elapsed"] == 9
    assert all(line["projection_cents"] is None for line in body["categories"])
    assert by_category(body)["Groceries"]["pace_cents"] is not None


def test_pending_rows_are_counted_and_labelled(world):
    household, caller = world
    groceries = by_category(budgets(caller, household, NOVEMBER, dt.date(2026, 11, 15)))[
        "Groceries"
    ]
    assert (groceries["pending_cents"], groceries["pending_count"]) == (10_000, 1)


def test_the_month_total_and_fixed_categories(world):
    household, caller = world
    body = budgets(caller, household, NOVEMBER, dt.date(2026, 11, 15))
    assert body["total"]["spent_cents"] == 41_500 and body["total"]["cap_cents"] == 65_000
    assert body["total"]["percent"] == 63
    assert "Housing" not in by_category(body)
    fixed = {f["category"]: f for f in body["fixed"]}
    assert fixed["Housing"] == {"category": "Housing", "paid": True, "paid_cents": 100_000}
    assert fixed["Utilities"]["paid"] is False


def test_a_recurring_and_a_new_subscription_are_found(world):
    household, caller = world
    body = budgets(caller, household, NOVEMBER, dt.date(2026, 11, 15))
    found = {s["merchant"]: s for s in body["subscriptions"]}
    assert found["STREAMCO"]["months"] == 4 and found["STREAMCO"]["typical_cents"] == 1_000
    assert found["STREAMCO"]["next_on"] == dt.date(2026, 12, 3)
    assert body["new_subscriptions"] == ["NEWAPP"]


def test_a_past_month_is_judged_at_its_end(world):
    household, caller = world
    body = budgets(caller, household, Month(2026, 10), dt.date(2026, 11, 15))
    assert body["days_elapsed"] == 31
    subs = by_category(body)["Subscriptions"]
    assert subs["pace_cents"] == subs["cap_cents"] == 5_000


def test_the_route_and_the_tool(world, token_a, token_b, settings):
    from tests.test_engine_mcp import call, client_with

    assert token_a.get("/api/finance/budgets", {"month": "2026-11"}).status_code == 200
    assert token_a.get("/api/finance/budgets", {"month": "11/2026"}).status_code == 400
    assert token_b.get("/api/finance/budgets", {"month": "2026-11"}).data["categories"] == []
    settings.LEGION_MCP = True
    _, caller = world
    is_error, text, _ = call(client_with(caller.user), "budgets", {"month": "2026-10"})
    assert not is_error and "Subscriptions 10.00 USD of 50.00 USD (20%)" in text
