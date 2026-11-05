"""The ONE synthetic household every finance test uses (household-finance map).

**Invented, on purpose, and round.** The real household's figures, names,
account numbers and employers live only in a private file outside the repo
(ADR 0073). `tests/test_finance_privacy.py` holds every finance test and every
file under `server/finance/` to the values named here, so a figure typed into a
test from anywhere else fails the suite.

Two members, Alex and Sam River, one US checking and card on the bank feed
(masks 1000 and 7823, the same masks `tests/test_plaid.py` invents), and a
Singapore account and card from statements.
"""

from __future__ import annotations

import datetime as dt

MEMBERS = {
    "alex": {"label": "Alex", "payer_names": ["ALEX RIVER"]},
    "sam": {"label": "Sam", "payer_names": ["SAM RIVER", "S RIVER"]},
}

CHECKING_LAST4 = "1000"
CARD_LAST4 = "7823"
SG_LAST4 = "2000"
SG_CARD_LAST4 = "3000"
# Every four-digit account number a finance test may use.
LAST4S = frozenset({CHECKING_LAST4, CARD_LAST4, SG_LAST4, SG_CARD_LAST4, "4000", "5000"})

ACCOUNTS = [
    {"key": "us-checking", "label": "US checking", "owner": "alex", "institution": "Bank A",
     "country": "US", "currency": "USD", "kind": "depository", "layer": "liquid",
     "source": "plaid", "last4": CHECKING_LAST4},
    {"key": "us-card", "label": "US card", "owner": "alex", "institution": "Bank A",
     "country": "US", "currency": "USD", "kind": "credit", "layer": "liquid",
     "source": "plaid", "last4": CARD_LAST4},
    {"key": "sg-savings", "label": "SG savings", "owner": "sam", "institution": "Bank B",
     "country": "SG", "currency": "SGD", "kind": "depository", "layer": "liquid",
     "source": "statement", "last4": SG_LAST4},
    {"key": "sg-card", "label": "SG card", "owner": "sam", "institution": "Bank C",
     "country": "SG", "currency": "SGD", "kind": "credit", "layer": "liquid",
     "source": "statement", "last4": SG_CARD_LAST4},
    {"key": "brokerage", "label": "Brokerage", "owner": "sam", "institution": "Broker D",
     "country": "US", "currency": "USD", "kind": "brokerage", "layer": "investments",
     "source": "ibkr_flex", "last4": None},
    {"key": "pension", "label": "Pension", "owner": "alex", "institution": "Board E",
     "country": "SG", "currency": "SGD", "kind": "cpf", "layer": "cpf",
     "source": "statement", "last4": None},
    {"key": "flat", "label": "Flat", "owner": None, "institution": "",
     "country": "SG", "currency": "SGD", "kind": "property", "layer": "property",
     "source": "computed", "last4": None},
    {"key": "flat-loan", "label": "Flat loan", "owner": None, "institution": "",
     "country": "SG", "currency": "SGD", "kind": "loan", "layer": "property",
     "source": "computed", "last4": None},
    {"key": "car", "label": "Car", "owner": "alex", "institution": "",
     "country": "US", "currency": "USD", "kind": "vehicle", "layer": "other",
     "source": "manual", "last4": None},
]

# Every money figure a finance test may type, in cents. Round on purpose.
FIGURES = frozenset(
    {
        0, 100, 500, 1_000, 2_000, 2_500, 5_000, 10_000, 12_500, 20_000, 30_000, 40_000, 50_000,
        60_000, 80_000, 100_000, 120_000, 124_000, 150_000, 200_000, 250_000, 300_000, 400_000,
        500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 50_000_000,
    }
)

# 1 USD = 1.25 SGD and 1.30 SGD: round rates, so expected conversions are exact.
RATE_A = 1_250_000
RATE_B = 1_300_000

POLICY = {
    "currency": "USD",
    "liquid_floor_cents": 1_000_000,
    "mortgage_monthly_cents": 100_000,
    "mortgage_currency": "SGD",
    "us_bills_30d_cents": 200_000,
    "topup_warn_days": 7,
    "transfer_lead_days": 7,
    "card_due_day": 20,
    "fixed_categories": ["Housing", "Utilities", "Insurance"],
}

BUDGETS_USD = {"Groceries": 50_000, "Dining": 10_000, "Subscriptions": 5_000}


def make_household(household, *, with_policy: bool = True, with_plan: bool = True):
    """The synthetic household's members, accounts, policy and income plan,
    created directly (the loader's own tests go through the loader)."""
    from finance.models import FinanceAccount, FinanceMember, FinancePolicy, IncomePlan

    members = {
        key: FinanceMember.objects.create(household=household, key=key, **fields)
        for key, fields in MEMBERS.items()
    }
    accounts = {}
    for spec in ACCOUNTS:
        fields = dict(spec)
        owner = fields.pop("owner")
        accounts[spec["key"]] = FinanceAccount.objects.create(
            household=household, owner=members[owner] if owner else None, **fields
        )
    if with_policy:
        FinancePolicy.objects.create(
            household=household,
            topup_checking=accounts["us-checking"],
            topup_card=accounts["us-card"],
            **POLICY,
        )
    if with_plan:
        IncomePlan.objects.create(
            household=household, key="alex-job", member=members["alex"], label="Alex's job",
            amount_cents=200_000, currency="USD", cadence="monthly",
            start_on=dt.date(2026, 1, 1), end_on=dt.date(2026, 11, 30), confidence="confirmed",
            account=accounts["us-checking"],
        )
        IncomePlan.objects.create(
            household=household, key="alex-next-job", member=members["alex"],
            label="Alex's next job", amount_cents=300_000, currency="USD", cadence="monthly",
            start_on=dt.date(2027, 3, 1), confidence="expected",
            account=accounts["us-checking"],
        )
        IncomePlan.objects.create(
            household=household, key="alex-fallback", member=members["alex"],
            label="Alex's fallback", amount_cents=100_000, currency="USD", cadence="monthly",
            start_on=dt.date(2027, 1, 1), confidence="fallback",
            account=accounts["us-checking"],
        )
        IncomePlan.objects.create(
            household=household, key="rent-in", member=members["sam"], label="Rent received",
            amount_cents=250_000, currency="SGD", cadence="monthly", confidence="confirmed",
            account=accounts["sg-savings"],
        )
    return members, accounts


def rate(on_date: dt.date, rate_micros: int = RATE_A, source: str = "test"):
    from finance.models import FxRate

    return FxRate.objects.create(
        on_date=on_date, base="USD", quote="SGD", rate_micros=rate_micros, source=source
    )


def snapshot(household, account, as_of: dt.date, cents: int, *, source=None, estimate=False):
    from finance.models import BalanceSnapshot

    return BalanceSnapshot.objects.create(
        household=household, account=account, as_of=as_of, amount_cents=cents,
        currency=account.currency, source=source or account.source, evidence="test",
        is_estimate=estimate,
    )
