"""Household-finance ticket 02: household-internal money (map ruling 10).

The layer runs above the ported spend rules (`api/spend.py` is untouched, so
`tests/test_spend_parity.py` still replays the phone's own tests). Synthetic
names only: the payer names are `tests/finance_synthetic.py`'s.
"""

from __future__ import annotations

import datetime as dt

import pytest

from api.spend import MATCHED_TRANSFER, Txn, analyze_transfers
from finance.internal import (
    CROSS_BORDER_PAIR,
    MEMBER_TRANSFER,
    Row,
    household_internal,
    transfer_shaped,
)
from tests.finance_synthetic import RATE_A, make_household, rate
from tests.test_spend_api import line, statement

NAMES = ["ALEX RIVER", "SAM RIVER", "S RIVER"]
DAY = dt.date(2026, 9, 10)


def row(rid, account, cents, description, *, currency="USD", day=DAY):
    return Row(rid, account, currency, day, description, cents)


def no_rate(_day):
    return None


def test_a_zelle_to_a_member_is_internal_even_with_one_side_visible():
    marks = household_internal(
        [row("a", "1000", -50_000, "Zelle payment to SAM RIVER Conf# abc123")], NAMES, no_rate
    )
    assert marks == {"a": marks["a"]} and marks["a"].reason == MEMBER_TRANSFER
    assert marks["a"].paired_with is None


def test_both_legs_of_a_member_transfer_name_each_other():
    marks = household_internal(
        [
            row("out", "1000", -50_000, "Zelle payment to SAM RIVER"),
            row("in", "2000", 50_000, "Zelle payment from ALEX RIVER", day=DAY.replace(day=12)),
        ],
        NAMES,
        no_rate,
    )
    assert (marks["out"].paired_with, marks["in"].paired_with) == ("in", "out")


def test_a_members_name_on_a_payroll_line_is_income_not_internal():
    marks = household_internal(
        [row("pay", "1000", 200_000, "ACME CO DES:PAYROLL INDN:ALEX RIVER CO ID:X")],
        NAMES,
        no_rate,
    )
    assert marks == {}


def test_a_transfer_to_someone_outside_the_household_still_counts():
    marks = household_internal(
        [row("a", "1000", -5_000, "Zelle payment to JORDAN LAKE")], NAMES, no_rate
    )
    assert marks == {}


def test_a_wire_fee_is_never_internal():
    assert not transfer_shaped("WIRE TRANSFER FEE")
    marks = household_internal(
        [row("fee", "1000", -2_500, "INTL WIRE FEE SAM RIVER")], NAMES, no_rate
    )
    assert marks == {}


def test_transfer_words_are_whole_words():
    assert not transfer_shaped("OTHERWISE BOUTIQUE")
    assert transfer_shaped("Wise Payments Ltd") and transfer_shaped("Online Banking transfer")


def _rate_a(_day):
    return RATE_A


def test_a_cross_border_transfer_pairs_within_tolerance_and_ten_days():
    # 1000.00 USD out; 1240.00 SGD in nine days later (1250.00 at the rate, less 0.8%).
    usd = row("usd", "1000", -100_000, "WIRE TRANSFER OUT")
    sgd = row("sgd", "2000", 124_000, "TELEGRAPHIC TRANSFER IN", currency="SGD",
              day=DAY + dt.timedelta(days=9))
    marks = household_internal([usd, sgd], [], _rate_a)
    assert marks["usd"].reason == CROSS_BORDER_PAIR and marks["usd"].paired_with == "sgd"


@pytest.mark.parametrize(
    ("sgd_cents", "days", "rate_for", "pairs"),
    [
        (124_000, 9, _rate_a, True),
        (120_000, 9, _rate_a, False),  # 4% short: not the same money
        (124_000, 11, _rate_a, False),  # past the window
        (124_000, 9, no_rate, False),  # no rate on file: no pair
    ],
)
def test_cross_border_pairing_bounds(sgd_cents, days, rate_for, pairs):
    usd = row("usd", "1000", -100_000, "WIRE TRANSFER OUT")
    sgd = row("sgd", "2000", sgd_cents, "REMITTANCE IN", currency="SGD",
              day=DAY + dt.timedelta(days=days))
    assert ("usd" in household_internal([usd, sgd], [], rate_for)) is pairs


def test_card_payments_are_already_netted_by_the_port():
    """Reasoned shapes of a bank's card-payment lines (not read from real data):
    the checking side and the card side, equal and opposite, two days apart."""
    rows = [
        Txn(1, "1000", -40_000, DAY, "BANK CREDIT CARD Bill Payment", None),
        Txn(2, "7823", 40_000, DAY + dt.timedelta(days=2), "PAYMENT - THANK YOU", None),
    ]
    analysis = analyze_transfers(rows, rows)
    assert analysis.operating == []
    assert {e.reason for e in analysis.excluded} == {MATCHED_TRANSFER}


@pytest.mark.django_db
def test_the_spend_route_leaves_a_zelle_to_a_member_out_and_says_so(auth_client, household_a):
    make_household(household_a)
    statement(
        household_a,
        "1000",
        "US checking",
        [
            line("2026-09-03", "GROCER", -10_000, "Groceries"),
            line("2026-09-05", "Zelle payment to SAM RIVER Conf# x1", -50_000, "Transfer out"),
            line("2026-09-06", "Zelle payment to JORDAN LAKE Conf# x2", -5_000, "Gifts"),
        ],
    )
    body = auth_client.get("/api/ledger/spend", {"month": "2026-09"}).data
    spent = {c["category"]: c["spend_cents"] for c in body["categories"]}
    assert spent == {"Groceries": 10_000, "Gifts": 5_000}
    assert body["excluded"]["household_internal_cents"] == 50_000
    assert body["excluded"]["household_internal_reasons"] == [
        {"reason": MEMBER_TRANSFER, "cents": 50_000}
    ]


@pytest.mark.django_db
def test_a_cross_border_pair_uses_the_stored_rate(auth_client, household_a):
    make_household(household_a)
    rate(dt.date(2026, 9, 1))
    statement(household_a, "1000", "US checking",
              [line("2026-09-05", "WIRE TRANSFER OUT", -100_000, "Transfers")])
    body = auth_client.get("/api/ledger/spend", {"month": "2026-09"}).data
    # Only one leg is on file: no pair, so the port's own rules decide.
    assert body["excluded"]["household_internal_cents"] == 0
