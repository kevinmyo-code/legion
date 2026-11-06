"""Budgets and pace (household-finance ticket 08, map ruling 5).

The caps are the household's `BudgetTarget` rows (USD), loaded from the
private file. Spend is the Money page's (`finance.spending.Ledger`):
household-internal money out, transfers netted, the Housing month shift kept.

Per category:

- `spent_cents`, `cap_cents`, `percent` (whole percent, rounded down);
- `pace_cents`: where spending would be today on an even pace,
  cap x days elapsed / days in the month (the phone's pace marker);
- `projection_cents`: spent / days elapsed x days in the month, an ESTIMATE,
  given only from day `PACE_FROM_DAY` (before it, ten days of a month say too
  little to project from);
- `pending_cents` / `pending_count`: the bank's own not-yet-posted rows,
  INCLUDED in spent and said as pending.

Fixed categories (rent, utilities, insurance) are only checked for being paid,
and carry their own `cap_cents` (the household's target, or null) so a caller
can say the whole budget, fixed included. `total` stays day-to-day only.
Subscriptions are the recurring merchants of the last `SUBSCRIPTION_MONTHS`
months (`finance.recurring`); one is NEW when its merchant first appears this
month in the Subscriptions category, with no earlier charge on file.
"""

from __future__ import annotations

import datetime as dt

from api.spend import Month, _targets_for
from finance.fx import div_half_even
from finance.models import FinancePolicy
from finance.recurring import merchant_of, recurring
from finance.spending import Ledger

PACE_FROM_DAY = 10
WARN_PERCENT = 80
SUBSCRIPTION_MONTHS = 6
SUBSCRIPTIONS_CATEGORY = "Subscriptions"
CURRENCY = "USD"


def days_elapsed(month: Month, today: dt.date) -> int:
    current = Month.of(today)
    if month < current:
        return month.last.day
    if month == current:
        return today.day
    return 0


def _status(spent: int, cap: int | None) -> str:
    if not cap:
        return "no cap"
    if spent >= cap:
        return "over"
    if spent * 100 >= cap * WARN_PERCENT:
        return "near"
    return "ok"


def budgets(request, household, month: Month, today: dt.date) -> dict:
    ledger = Ledger(request)
    body = ledger.month(month, CURRENCY)
    days = month.last.day
    elapsed = days_elapsed(month, today)
    # The budget month, not calendar dates: rent paid on the 30th is next month's.
    rows = ledger.rows_in_budget_month(month, CURRENCY)
    pending: dict[str | None, list[int]] = {}
    for row in rows:
        if row.get("bank_pending") and row["amount_cents"] < 0:
            pending.setdefault(row["effective_category"], []).append(-row["amount_cents"])
    policy = FinancePolicy.objects.filter(household=household).first()
    fixed = set(policy.fixed_categories if policy else [])

    lines = []
    for category in body["categories"]:
        name = category["category"]
        if name in fixed:
            continue
        spent, cap = category["spend_cents"], category["target_cents"]
        own_pending = pending.get(name, [])
        lines.append(
            {
                "category": name,
                "spent_cents": spent,
                "cap_cents": cap,
                "percent": spent * 100 // cap if cap else None,
                "pace_cents": div_half_even(cap * elapsed, days) if cap else None,
                "projection_cents": (
                    div_half_even(spent * days, elapsed) if elapsed >= PACE_FROM_DAY else None
                ),
                "projection_is_estimate": True,
                "status": _status(spent, cap),
                "pending_cents": sum(own_pending),
                "pending_count": len(own_pending),
                "unverified": category["unverified"],
            }
        )
    capped = [line for line in lines if line["cap_cents"]]
    total_spent = sum(line["spent_cents"] for line in capped)
    total_cap = sum(line["cap_cents"] for line in capped)

    caps = _targets_for(ledger.targets, CURRENCY, month)
    fixed_lines = []
    for name in sorted(fixed):
        paid = [r for r in rows if r["effective_category"] == name and r["amount_cents"] < 0]
        fixed_lines.append(
            {"category": name, "paid": bool(paid),
             "paid_cents": -sum(r["amount_cents"] for r in paid),
             "cap_cents": caps.get(name)}
        )

    history_start = month.plus(-(SUBSCRIPTION_MONTHS - 1)).first
    history = ledger.rows_between(history_start, month.last, CURRENCY)
    found = recurring(history)
    first_seen: dict[str, dt.date] = {}
    for row in ledger.rows_between(dt.date.min, month.last, CURRENCY):
        merchant = merchant_of(row["description"])
        if row["amount_cents"] < 0 and merchant:
            first_seen[merchant] = min(first_seen.get(merchant, row["txn_date"]), row["txn_date"])
    new = sorted(
        {
            merchant_of(r["description"])
            for r in rows
            if r["effective_category"] == SUBSCRIPTIONS_CATEGORY
            and r["amount_cents"] < 0
            and first_seen.get(merchant_of(r["description"]), month.first) >= month.first
        }
        - {""}
    )
    return {
        "month": str(month),
        "currency": CURRENCY,
        "days_in_month": days,
        "days_elapsed": elapsed,
        "projection_from_day": PACE_FROM_DAY,
        "categories": lines,
        "total": {
            "spent_cents": total_spent,
            "cap_cents": total_cap,
            "percent": total_spent * 100 // total_cap if total_cap else None,
            "pace_cents": div_half_even(total_cap * elapsed, days) if total_cap else None,
        },
        "uncategorised_cents": body["uncategorised_cents"],
        "fixed": fixed_lines,
        "subscriptions": [
            {
                "merchant": r.merchant,
                "category": r.category,
                "typical_cents": r.typical_cents,
                "months": r.months,
                "last_on": r.last_on,
                "next_on": r.next_on,
                "is_estimate": True,
            }
            for r in found
        ],
        "new_subscriptions": new,
    }
