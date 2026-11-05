"""Cash flow, runway, the US top-up check and safe to spend (ticket 07, ADR 0073).

All money is int cents; every conversion is at a stored rate and says which
(`finance.fx`), and a figure that needs a rate when none is stored is refused
in words rather than guessed. Every projection is an ESTIMATE: it rests on the
income plan and on an average, not on anything a bank has stated.

**Wave 1's spend is the ledger's**: the accounts with ledger rows (today the
two US accounts on the bank feed). Each body names which liquid accounts have
no rows, so "the household's spend" is never claimed for accounts it cannot
see.

Definitions (ADR 0073):

- **Cash flow** for a month: income from the PLAN (an estimate) and income on
  file (the ledger's inflows the transfer rules leave standing), spend (every
  netted outflow, household-internal money out), the mortgage while it is not
  in the ledger, and net = planned income - spend - mortgage. Test A (no burn)
  passes when net is not negative.
- **Runway**: average netted spend over the last 3 FULL months, plus the
  mortgage, against the income plan, month by month for `HORIZON_MONTHS`, per
  side (US in USD, Singapore in SGD) and for the household in the headline
  currency at today's rate. It returns the first month household liquid falls
  below the floor, and the cross-border transfer the US side needs to stay
  above its own floor (the next 30 days of US bills) through every gap month:
  the largest shortfall, by the first day of the first short month less
  `transfer_lead_days`. Both scenarios: `expected` and `fallback` (plan.py).
- **US top-up check**: Kevin's checking minus what is owed on the card,
  against the next 30 days of US bills; warned `topup_warn_days` before the
  card's due day. What is owed is the card's CURRENT balance plus its pending
  charges: the statement balance is not on the bank feed, so current stands in
  for it (an estimate, and never smaller than the statement balance once the
  statement has closed).
- **Safe to spend** (Kevin, 2026-11-05): checking, minus everything owed on
  the card (current plus pending), minus the US fixed bills due before `until`:
  the next expected income date, or 30 days out when that is sooner
  (`until_reason`: `next_income` | `30_days`, the top-up rule's own window). A
  top-up is needed when it is below zero, or when the top-up check above is
  short; by `until` less `topup_warn_days`.
"""

from __future__ import annotations

import calendar
import datetime as dt

from api.spend import Month
from finance import fx
from finance.balances import balance_of, latest_balances
from finance.models import FinancePolicy, IncomePlan, Layer
from finance.networth import rate_body, today_rate
from finance.plan import SCENARIOS, add_months, payments
from finance.recurring import recurring
from finance.spending import Ledger

HORIZON_MONTHS = 24
AVERAGE_MONTHS = 3
DETECT_BILLS_DAYS = 100
# Safe to spend looks at most this far ahead, like the US top-up rule.
SAFE_WINDOW_DAYS = 30
HEADLINE = "USD"


class NeedsRate(Exception):
    """A figure needs the USD/SGD rate and none is stored. Said in words."""


def _rate_on(day: dt.date):
    try:
        return fx.rate_on(day)
    except fx.NoRate:
        return None


def _to(cents: int, currency: str, target: str, rate) -> int:
    if currency == target or cents == 0:
        return cents
    if rate is None:
        raise NeedsRate(
            f"No USD/SGD rate is stored yet, so {currency} money cannot be stated in {target}. "
            f"Nothing was converted; the daily rate job has not run."
        )
    return fx.apply_rate(cents, currency, target, rate.rate_micros)


def _policy(household) -> FinancePolicy | None:
    return (
        FinancePolicy.objects.filter(household=household)
        .select_related("topup_checking", "topup_card")
        .first()
    )


def _plan(household) -> list[IncomePlan]:
    return list(
        IncomePlan.objects.filter(household=household).select_related("account").order_by("key")
    )


def _side(line) -> str:
    """US or SG: where a plan line's money lands."""
    if line.account is not None:
        return line.account.country
    return "US" if line.currency == "USD" else "SG"


def _mortgage(policy) -> tuple[int, str]:
    if policy is None or policy.mortgage_in_ledger or not policy.mortgage_monthly_cents:
        return 0, "SGD"
    return policy.mortgage_monthly_cents, policy.mortgage_currency


# =============================================================================
# Cash flow
# =============================================================================


def cash_flow(request, household, month: Month, today: dt.date, zone) -> dict:
    ledger = Ledger(request)
    # The month's own last rate; before rates were first stored, today's. The
    # body says which rate and day it used.
    rate = _rate_on(min(month.last, today)) or today_rate(today)
    policy = _policy(household)
    spend = {c: ledger.spend_cents(month, c) for c in ("USD", "SGD")}
    inflows = {c: ledger.inflows_cents(month, c) for c in ("USD", "SGD")}
    planned = payments(_plan(household), month.first, month.last, "expected")
    mortgage_cents, mortgage_currency = _mortgage(policy)
    income_planned = sum(_to(line.amount_cents, line.currency, HEADLINE, rate)
                         for _, line in planned)
    spend_total = sum(_to(cents, c, HEADLINE, rate) for c, cents in spend.items())
    mortgage = _to(mortgage_cents, mortgage_currency, HEADLINE, rate)
    net = income_planned - spend_total - mortgage
    seen = ledger.accounts_with_rows(month)
    from finance.models import FinanceAccount

    liquid = FinanceAccount.objects.filter(household=household, active=True, layer=Layer.LIQUID)
    internal = [
        {
            "txn_date": row["txn_date"],
            "description": row["description"],
            "amount_cents": row["amount_cents"],
            "currency": row["currency"],
            "reason": mark.reason,
            "paired": mark.paired_with is not None,
        }
        for row, mark in ledger.internal
        if month.first <= row["txn_date"] <= month.last
    ]
    return {
        "month": str(month),
        "currency": HEADLINE,
        "rate": rate_body(rate),
        "income_planned_cents": income_planned,
        "income_planned_is_estimate": True,
        "income_on_file_cents": sum(_to(c, k, HEADLINE, rate) for k, c in inflows.items()),
        "spend_cents": spend_total,
        "spend_by_currency": [{"currency": c, "cents": spend[c]} for c in ("USD", "SGD")],
        "mortgage_cents": mortgage,
        "net_cents": net,
        "test_a_passes": net >= 0,
        "accounts_without_rows": sorted(
            a.key for a in liquid if not a.last4 or a.last4 not in seen
        ),
        "household_internal": internal,
    }


# =============================================================================
# The US top-up check
# =============================================================================


def _owed(card_balance) -> int:
    """What is owed on a card: its balance (negative is owed) plus pending charges."""
    if card_balance is None:
        return 0
    return -card_balance.cents - (card_balance.pending_cents or 0)


def _next_day_of_month(today: dt.date, day: int) -> dt.date:
    year, month = today.year, today.month
    for _ in range(2):
        candidate = dt.date(year, month, min(day, calendar.monthrange(year, month)[1]))
        if candidate >= today:
            return candidate
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return candidate


def us_topup(household, today: dt.date, zone, policy=None) -> dict:
    policy = policy or _policy(household)
    if policy is None or policy.topup_checking is None:
        return {"available": False,
                "reason": "No US top-up trigger is set: load the household's finance file."}
    checking = balance_of(household, policy.topup_checking, today, zone)
    card = balance_of(household, policy.topup_card, today, zone)
    if checking is None:
        return {"available": False,
                "reason": "The checking account has no balance on file yet."}
    owed = _owed(card)
    headroom = checking.cents - owed
    required = policy.us_bills_30d_cents or 0
    short = max(0, required - headroom)
    due = _next_day_of_month(today, policy.card_due_day) if policy.card_due_day else None
    warn_on = due - dt.timedelta(days=policy.topup_warn_days) if due else today
    return {
        "available": True,
        "checking_cents": checking.cents,
        "checking_as_of": checking.as_of,
        "card_owed_cents": owed,
        "card_as_of": card.as_of if card else None,
        "card_owed_is_estimate": True,
        "headroom_cents": headroom,
        "bills_30d_cents": required,
        "short_cents": short,
        "card_due_on": due,
        "transfer_by": max(today, warn_on) if short else None,
        "status": "short" if short else "ok",
    }


# =============================================================================
# Runway
# =============================================================================


def _average(ledger: Ledger, current: Month, currency: str) -> int:
    months = [current.plus(-i) for i in range(1, AVERAGE_MONTHS + 1)]
    total = sum(ledger.spend_cents(m, currency) for m in months)
    return fx.div_half_even(total, AVERAGE_MONTHS)


def _scenario(name, lines, start_us, start_sg, avg, mortgage, policy, today, rate) -> dict:
    us, sg = start_us, start_sg
    floor = policy.liquid_floor_cents if policy else None
    us_floor = (policy.us_bills_30d_cents or 0) if policy else 0
    lead = policy.transfer_lead_days if policy else 7
    months, crossing, first_short, largest_short = [], None, None, 0
    current = Month.of(today)
    for i in range(HORIZON_MONTHS):
        month = current.plus(i)
        start = today + dt.timedelta(days=1) if i == 0 else month.first
        paid = payments(lines, start, month.last, name)
        income_us = sum(
            _to(line.amount_cents, line.currency, "USD", rate)
            for _, line in paid if _side(line) == "US"
        )
        income_sg = sum(
            _to(line.amount_cents, line.currency, "SGD", rate)
            for _, line in paid if _side(line) == "SG"
        )
        us += income_us - avg["USD"]
        sg += income_sg - avg["SGD"] - mortgage
        household = us + _to(sg, "SGD", HEADLINE, rate)
        months.append({
            "month": str(month), "income_us_cents": income_us, "income_sg_cents": income_sg,
            "us_end_cents": us, "sg_end_cents": sg, "liquid_end_cents": household,
        })
        if crossing is None and floor is not None and household < floor:
            crossing = str(month)
        short = us_floor - us
        if short > 0:
            first_short = first_short or month
            largest_short = max(largest_short, short)
    transfer = None
    if first_short is not None:
        by = max(today, first_short.first - dt.timedelta(days=lead))
        # With no rate stored, the SGD side of the transfer is unknown: said
        # as null, never guessed, and the USD figure still stands.
        sgd = _to(largest_short, "USD", "SGD", rate) if rate is not None else None
        transfer = {
            "usd_cents": largest_short,
            "sgd_cents": sgd,
            "first_short_month": str(first_short),
            "by": by,
            "sg_side_lowest_after_cents": (
                min(m["sg_end_cents"] for m in months) - sgd if sgd is not None else None
            ),
        }
    return {"months": months, "floor_crossing_month": crossing, "transfer": transfer}


def runway(request, household, today: dt.date, zone) -> dict:
    policy = _policy(household)
    rate = today_rate(today)
    ledger = Ledger(request)
    current = Month.of(today)
    balances, missing = latest_balances(household, today, zone)
    liquid = [b for b in balances if b.account.layer == Layer.LIQUID]
    investments = [b for b in balances if b.account.layer == Layer.INVESTMENTS]
    start_us = sum(b.cents for b in liquid if b.currency == "USD")
    start_sg = sum(b.cents for b in liquid if b.currency == "SGD")
    avg = {c: _average(ledger, current, c) for c in ("USD", "SGD")}
    mortgage_cents, mortgage_currency = _mortgage(policy)
    mortgage_sgd = _to(mortgage_cents, mortgage_currency, "SGD", rate)
    lines = _plan(household)
    scenarios = {
        name: _scenario(name, lines, start_us, start_sg, avg, mortgage_sgd, policy, today, rate)
        for name in SCENARIOS
    }
    liquid_now = start_us + _to(start_sg, "SGD", HEADLINE, rate)
    runway_now = liquid_now + sum(_to(b.cents, b.currency, HEADLINE, rate) for b in investments)
    burn = avg["USD"] + _to(avg["SGD"] + mortgage_sgd, "SGD", HEADLINE, rate)
    return {
        "currency": HEADLINE,
        "as_of": today,
        "rate": rate_body(rate),
        "is_estimate": True,
        "liquid_now_cents": liquid_now,
        "runway_assets_cents": runway_now,
        "floor_cents": policy.liquid_floor_cents if policy else None,
        "us_floor_cents": policy.us_bills_30d_cents if policy else None,
        "average_spend": {
            "months": [str(current.plus(-i)) for i in range(AVERAGE_MONTHS, 0, -1)],
            "usd_cents": avg["USD"],
            "sgd_cents": avg["SGD"],
        },
        "mortgage_sgd_cents": mortgage_sgd,
        "monthly_burn_cents": burn,
        "missing_balances": [a.key for a in missing],
        "scenarios": scenarios,
        "us_topup": us_topup(household, today, zone, policy),
    }


# =============================================================================
# Safe to spend
# =============================================================================


def _next_income(lines, policy, today: dt.date) -> dt.date | None:
    us_lines = [
        line for line in lines
        if (policy.topup_checking_id and line.account_id == policy.topup_checking_id)
        or (line.account_id is None and line.currency == "USD")
    ]
    upcoming = payments(us_lines, today + dt.timedelta(days=1), add_months(today, 13), "expected")
    return upcoming[0][0] if upcoming else None


def _bills(request, policy, today: dt.date, until: dt.date) -> tuple[list[dict], bool]:
    """US fixed bills due on or after today and before `until`: the private
    file's list when it has one, else the fixed categories' recurring
    merchants (an estimate)."""
    bills = []
    if policy.fixed_bills:
        for bill in policy.fixed_bills:
            day = _next_day_of_month(today, bill["day"])
            while day < until:
                bills.append({"label": bill["label"], "due_on": day,
                              "amount_cents": bill["amount_cents"]})
                day = _next_day_of_month(day + dt.timedelta(days=1), bill["day"])
        return sorted(bills, key=lambda b: (b["due_on"], b["label"])), False
    fixed = set(policy.fixed_categories or [])
    ledger = Ledger(request)
    rows = [
        r for r in ledger.rows_between(today - dt.timedelta(days=DETECT_BILLS_DAYS), today, "USD")
        if r["effective_category"] in fixed
    ]
    for found in recurring(rows):
        day = found.next_on
        while day < until:
            if day >= today:
                bills.append({"label": found.merchant.title(), "due_on": day,
                              "amount_cents": found.typical_cents})
            day = _next_day_of_month(day + dt.timedelta(days=1), day.day)
    return sorted(bills, key=lambda b: (b["due_on"], b["label"])), True


def safe_to_spend(request, household, today: dt.date, zone) -> dict:
    policy = _policy(household)
    if policy is None or policy.topup_checking is None:
        return {"available": False,
                "reason": "No checking account is set for safe to spend: load the household's "
                          "finance file."}
    checking = balance_of(household, policy.topup_checking, today, zone)
    card = balance_of(household, policy.topup_card, today, zone)
    if checking is None:
        return {"available": False,
                "reason": "The checking account has no balance on file yet, so nothing can be "
                          "said about what is safe to spend."}
    # The window is capped at 30 days, the top-up rule's own window: with no
    # pay for months, every bill until then would otherwise be held back.
    next_income = _next_income(_plan(household), policy, today)
    cap = today + dt.timedelta(days=SAFE_WINDOW_DAYS)
    if next_income is not None and next_income <= cap:
        until, until_reason = next_income, "next_income"
    else:
        until, until_reason = cap, "30_days"
    window_end = until
    bills, detected = _bills(request, policy, today, window_end)
    owed = _owed(card)
    bills_cents = sum(b["amount_cents"] for b in bills)
    safe = checking.cents - owed - bills_cents
    trigger_short = max(0, (policy.us_bills_30d_cents or 0) - (checking.cents - owed))
    needed = max(0, -safe, trigger_short)
    top_up_by = None
    if needed:
        top_up_by = max(today, window_end - dt.timedelta(days=policy.topup_warn_days))
    return {
        "available": True,
        "currency": policy.topup_checking.currency,
        "safe_to_spend_cents": safe,
        "until": until,
        "until_reason": until_reason,
        "next_income_on": next_income,
        "until_is_estimate": True,
        "parts": {
            "checking_cents": checking.cents,
            "checking_as_of": checking.as_of,
            "checking_pending_cents": checking.pending_cents,
            "card_owed_cents": owed,
            "card_as_of": card.as_of if card else None,
            "bills_before_until_cents": bills_cents,
            "bills": bills,
            "bills_detected": detected,
        },
        "top_up_needed_cents": needed,
        "top_up_by": top_up_by,
        "top_up_reason": (
            "below zero" if safe < 0 else "below the top-up trigger" if trigger_short else None
        ),
    }
