"""The budget-pace watch (daily-loops ticket 04, Kevin 2026-11-12): one alert when a budget
category goes clearly over pace for the month.

Same synthetic household as `test_finance_budgets.py`: caps Groceries 500.00, Dining 100.00,
Subscriptions 50.00, fixed Housing 1,000.00. November 2026 has 30 days. On the 15th, Groceries has
300.00 spent (projection 600.00, 120% of the cap), Dining has 100.00 (already at its cap) and
Subscriptions 15.00 (projection 30.00, under).
"""

from __future__ import annotations

import datetime as dt

import pytest

from tests.test_finance_budgets import world  # noqa: F401 - the shared synthetic household
from watches.evaluate import evaluate_household
from watches.models import KIND_BUDGET_PACE, WatchFiring
from watches.registry import BUILTINS, label_for

pytestmark = pytest.mark.django_db

URL = "/api/watches/"
UTC = dt.UTC
NOV_15 = dt.datetime(2026, 11, 15, 18, 0, tzinfo=UTC)
NOV_9 = dt.datetime(2026, 11, 9, 18, 0, tzinfo=UTC)


def _set(client, params=None):
    made = client.post(URL, {"kind": KIND_BUDGET_PACE, "params": params or {}}, format="json")
    assert made.status_code == 201, made.data
    return made.data["watch"]["id"]


def _said(user):
    return {
        f.detail["category"]: f.facts
        for f in WatchFiring.objects.filter(owner_user=user, kind=KIND_BUDGET_PACE)
    }


def test_over_pace_and_already_over_fire_once_and_on_pace_says_nothing(
    world, auth_client, household_user  # noqa: F811
):
    household, _ = world
    _set(auth_client)
    report = evaluate_household(household, NOV_15)
    assert report.errors == []
    said = _said(household_user)
    assert set(said) == {"Groceries", "Dining"}
    assert said["Groceries"] == (
        "Groceries: $300.00 spent of a $500.00 budget by day 15 of 30. At this pace it reaches "
        "about $600.00 by the end of November, an estimate."
    )
    assert said["Dining"] == (
        "Dining: $100.00 spent of a $100.00 budget by day 15 of 30. It is already over."
    )
    # The same month again: nothing new, whatever the figures do.
    again = evaluate_household(household, NOV_15 + dt.timedelta(days=1))
    assert again.fired == 0


def test_the_margin_holds_back_a_category_only_a_little_over_pace(
    world, auth_client, household_user  # noqa: F811
):
    household, _ = world
    _set(auth_client, {"over_percent": 25})
    evaluate_household(household, NOV_15)
    # Groceries runs 20% over: under a 25% margin. Dining is already over: said regardless.
    assert set(_said(household_user)) == {"Dining"}


def test_a_category_param_narrows_the_watch(world, auth_client, household_user):  # noqa: F811
    household, _ = world
    _set(auth_client, {"category": "groc"})
    evaluate_household(household, NOV_15)
    assert set(_said(household_user)) == {"Groceries"}


def test_before_day_ten_only_already_over_is_said(world, auth_client, household_user):  # noqa: F811
    household, _ = world
    _set(auth_client)
    evaluate_household(household, NOV_9)
    # No projection before day 10 (ten days say too little), so only the plain fact.
    assert set(_said(household_user)) == {"Dining"}


def test_a_firing_expires_at_the_end_of_its_month(world, auth_client, household_user):  # noqa: F811
    household, _ = world
    _set(auth_client)
    evaluate_household(household, NOV_15)
    firing = WatchFiring.objects.get(owner_user=household_user, detail__category="Groceries")
    assert firing.expires_at == dt.datetime(2026, 12, 1, tzinfo=UTC)
    assert firing.detail["projection_is_estimate"] is True
    assert firing.fact_key == "pace:2026-11:Groceries"


def test_it_is_a_builtin_and_its_label_says_what_it_watches():
    assert any(b.kind == KIND_BUDGET_PACE for b in BUILTINS)
    assert label_for(KIND_BUDGET_PACE, {"over_percent": 10}) == (
        "A budget on pace to go over by 10% or more"
    )
    assert label_for(KIND_BUDGET_PACE, {"category": "Dining", "over_percent": 0}) == (
        "Dining on pace to go over by 0% or more"
    )
