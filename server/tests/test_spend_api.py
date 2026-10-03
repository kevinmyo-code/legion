"""`GET /api/ledger/spend` over real rows (web-revamp ticket 11, spec D5).

`tests/test_spend_parity.py` proves the rules against the phone's own tests;
this proves the route reads the household's rows the way the spec says:
one account per `account_last4` however its rows were named, `unverified`
exactly when a counted row is unverified, the Housing month shift, the
not-spending flag disclosed, targets for the month, and completeness from
gated statements. Its leak test is in `tests/test_tenancy.py`.
"""

from __future__ import annotations

import uuid

import pytest

from ingest import gate
from ingest.provisional import commit_provisional
from ingest.views import commit_statement
from tests.test_ledger_categories_and_paging import a_rule

pytestmark = pytest.mark.django_db

SPEND = "/api/ledger/spend"


def statement(household, last4, nickname, lines, *, start="2026-09-01", end="2026-09-30"):
    total = sum(line["amount_cents"] for line in lines)
    result = commit_statement(
        {
            "content_sha256": f"sha-{uuid.uuid4()}",
            "provenance": "DETERMINISTIC",
            "account_last4": last4,
            "account_nickname": nickname,
            "currency": "USD",
            "stated_total_cents": None,
            "opening_balance_cents": 1_000_000,
            "closing_balance_cents": 1_000_000 + total,
            "period_start": start,
            "period_end": end,
            "lines": [{"line_ref": f"{last4}:{index}", **line} for index, line in enumerate(lines)],
        },
        household,
    )
    assert result.outcome == gate.COMMITTED, result.body
    return result


def provisional(household, last4, nickname, lines):
    result = commit_provisional(
        {
            "content_sha256": f"sha-{uuid.uuid4()}",
            "provenance": "UNRECONCILED",
            "account_last4": last4,
            "account_nickname": nickname,
            "currency": "USD",
            "lines": [
                {"line_ref": f"p{last4}:{index}", **line} for index, line in enumerate(lines)
            ],
        },
        household,
    )
    return result


def line(date, description, cents, category=None):
    out = {"txn_date": date, "description": description, "amount_cents": cents}
    if category is not None:
        out["category"] = category
    return out


def accounts(body):
    return {a["account_last4"]: a for a in body["accounts"]}


def test_nickname_drift_on_one_card_is_one_account_named_by_its_latest_label(
    auth_client, household_a
):
    statement(household_a, "7823", "7823", [line("2026-09-03", "HEB", -1_000, "Groceries")])
    statement(
        household_a,
        "7823",
        "BofA card",
        [line("2026-09-05", "DINER", -500, "Dining")],
        start="2026-09-04",
    )
    body = auth_client.get(SPEND, {"month": "2026-09"}).data
    assert body["month"] == "2026-09" and body["currency"] == "USD"
    assert list(accounts(body)) == ["7823"]
    card = accounts(body)["7823"]
    assert card["spend_cents"] == 1_500
    assert card["label"] == "BofA card"
    assert card["latest_row_at"] is not None


def test_an_account_with_only_a_bare_id_is_called_by_its_last_four(auth_client, household_a):
    statement(household_a, "5042", "5042", [line("2026-09-03", "HEB", -1_000, "Groceries")])
    assert accounts(auth_client.get(SPEND, {"month": "2026-09"}).data)["5042"]["label"] == (
        "Card ending 5042"
    )


def test_unverified_is_true_exactly_when_a_counted_row_is_unverified(auth_client, household_a):
    statement(
        household_a, "5042", "BofA checking", [line("2026-09-03", "HEB", -1_000, "Groceries")]
    )
    a_rule(household_a, "UBER", "Transport")
    provisional(
        household_a,
        "7823",
        "BofA card",
        [line("2026-09-10", "UBER *TRIP", -1_200), line("2026-09-11", "MYSTERY", -300)],
    )
    body = auth_client.get(SPEND, {"month": "2026-09"}).data
    checking, card = accounts(body)["5042"], accounts(body)["7823"]
    assert (checking["unverified"], checking["unverified_cents"]) == (False, 0)
    assert (card["spend_cents"], card["unverified"], card["unverified_cents"]) == (
        1_200,
        True,
        1_200,
    )
    # The uncategorised provisional row counts in nothing, so it makes nothing unverified.
    assert body["uncategorised_cents"] == 300
    assert body["uncategorised_unverified"] is True
    categories = {c["category"]: c for c in body["categories"]}
    assert categories["Transport"]["unverified"] is True
    assert categories["Groceries"]["unverified"] is False


def test_an_uncategorised_unverified_row_alone_does_not_make_spend_unverified(
    auth_client, household_a
):
    provisional(household_a, "7823", "BofA card", [line("2026-09-11", "MYSTERY", -300)])
    card = accounts(auth_client.get(SPEND, {"month": "2026-09"}).data)["7823"]
    assert (card["spend_cents"], card["unverified"]) == (0, False)


def test_housing_on_the_29th_counts_in_the_next_month(auth_client, household_a):
    statement(
        household_a,
        "7823",
        "BofA card",
        [
            line("2026-09-29", "RPS*RENT PORTAL", -180_000, "Housing"),
            line("2026-09-15", "HEB", -1_000, "Groceries"),
        ],
    )
    statement(
        household_a,
        "7823",
        "BofA card",
        [line("2026-10-02", "HEB", -2_000, "Groceries")],
        start="2026-10-01",
        end="2026-10-31",
    )
    september = auth_client.get(SPEND, {"month": "2026-09"}).data
    october = auth_client.get(SPEND, {"month": "2026-10"}).data
    assert accounts(september)["7823"]["spend_cents"] == 1_000
    assert september["excluded"]["early_charges_counted_next_month_cents"] == 180_000
    assert accounts(october)["7823"]["spend_cents"] == 182_000
    assert october["excluded"]["early_charges_counted_here_cents"] == 180_000
    assert october["excluded"]["early_charges_moved_cents"] == 180_000


def test_transfers_are_excluded_and_disclosed(auth_client, household_a):
    made = auth_client.put(
        "/api/ledger/categories/guid-transfers/",
        {"name": "Transfers", "is_food_category": False, "excluded_from_spend": True},
        format="json",
    )
    assert made.status_code == 200, made.data
    statement(
        household_a,
        "5042",
        "BofA checking",
        [
            line("2026-09-03", "ZELLE PAYMENT TO MIA", -40_000, "Transfers"),
            line("2026-09-04", "PAYMENT TO CRD 7823", -50_000, "Payments"),
            line("2026-09-05", "HEB", -1_000, "Groceries"),
        ],
    )
    statement(household_a, "7823", "BofA card", [line("2026-09-06", "DINER", -700, "Dining")])
    body = auth_client.get(SPEND, {"month": "2026-09"}).data
    assert accounts(body)["5042"]["spend_cents"] == 1_000
    assert body["excluded"]["not_spending_cents"] == 40_000
    assert body["excluded"]["not_spending_categories"] == ["Transfers"]
    # The card payment names an account the household holds: an own-account move.
    assert body["excluded"]["own_account_moves_cents"] == 50_000
    assert "Transfers" not in {c["category"] for c in body["categories"]}


def test_targets_are_the_ones_effective_for_the_month(auth_client, household_a):
    for guid, cents, month in (
        ("t1", 40_000, "2026-08-01"),
        ("t2", 50_000, "2026-09-01"),
        ("t3", 90_000, "2026-11-01"),
    ):
        made = auth_client.put(
            f"/api/ledger/budget_targets/{guid}/",
            {
                "category": "Groceries",
                "currency": "USD",
                "amount_cents": cents,
                "effective_from_month": month,
            },
            format="json",
        )
        assert made.status_code == 200, made.data
    statement(
        household_a, "5042", "BofA checking", [line("2026-09-05", "HEB", -1_000, "Groceries")]
    )
    categories = {
        c["category"]: c for c in auth_client.get(SPEND, {"month": "2026-10"}).data["categories"]
    }  # noqa: E501
    assert categories["Groceries"]["target_cents"] == 50_000
    assert categories["Groceries"]["spend_cents"] == 0
    september = auth_client.get(SPEND, {"month": "2026-09"}).data
    assert {c["category"]: c["target_cents"] for c in september["categories"]} == {
        "Groceries": 50_000
    }


def test_complete_only_when_gated_statements_cover_the_whole_month(auth_client, household_a):
    statement(
        household_a, "5042", "BofA checking", [line("2026-09-05", "HEB", -1_000, "Groceries")]
    )
    assert auth_client.get(SPEND, {"month": "2026-09"}).data["complete"] is True
    provisional(household_a, "7823", "BofA card", [line("2026-09-11", "MYSTERY", -300)])
    assert auth_client.get(SPEND, {"month": "2026-09"}).data["complete"] is False
    assert auth_client.get(SPEND, {"month": "2026-08"}).data["complete"] is False


def test_the_default_month_follows_tz_and_bad_input_is_refused_in_words(auth_client):
    body = auth_client.get(SPEND, {"tz": "Pacific/Kiritimati"}).data
    assert len(body["month"]) == 7
    assert auth_client.get(SPEND, {"tz": "Not/AZone"}).status_code == 200
    bad = auth_client.get(SPEND, {"month": "2026-13"})
    assert bad.status_code == 400 and "Nothing was computed" in bad.data["detail"]
    bad = auth_client.get(SPEND, {"currency": "dollars"})
    assert bad.status_code == 400 and "Nothing was computed" in bad.data["detail"]


def test_an_empty_household_is_an_empty_month_not_an_error(auth_client):
    body = auth_client.get(SPEND, {"month": "2026-09"}).data
    assert body["accounts"] == [] and body["categories"] == []
    assert body["uncategorised_cents"] == 0 and body["complete"] is False


def test_unauthenticated_is_401():
    from rest_framework.test import APIClient

    assert APIClient().get(SPEND).status_code == 401
