"""Recurring merchants (household-finance tickets 07 and 08).

**The rule, decided here and recorded in ADR 0073.** A merchant is the first
three words of a row's description, upper-cased, with digits and punctuation
removed (`merchant_of`). It is RECURRING when, over the rows given (outflows in
one currency):

- it appears in at least `MIN_MONTHS` distinct calendar months;
- every amount is within `AMOUNT_BAND_PERCENT` of the median amount;
- consecutive charges are `MIN_GAP_DAYS` to `MAX_GAP_DAYS` apart (monthly).

Its next charge is expected one month after the last, on the same day of month.
Everything this infers is an ESTIMATE: no bank states that a charge will recur.
"""

from __future__ import annotations

import calendar
import datetime as dt
import re
from collections.abc import Iterable
from dataclasses import dataclass

MIN_MONTHS = 3
AMOUNT_BAND_PERCENT = 15
MIN_GAP_DAYS = 25
MAX_GAP_DAYS = 35


def merchant_of(description: str) -> str:
    words = re.sub(r"[^A-Za-z ]+", " ", description or "").upper().split()
    return " ".join(words[:3])


def next_month_same_day(day: dt.date) -> dt.date:
    year, month = (day.year + 1, 1) if day.month == 12 else (day.year, day.month + 1)
    return dt.date(year, month, min(day.day, calendar.monthrange(year, month)[1]))


@dataclass(frozen=True)
class Recurring:
    merchant: str
    category: str | None
    typical_cents: int
    months: int
    last_on: dt.date
    next_on: dt.date
    first_on: dt.date


def _median(values: list[int]) -> int:
    ordered = sorted(values)
    return ordered[len(ordered) // 2]


def recurring(rows: Iterable[dict]) -> list[Recurring]:
    """Rows: dicts with `txn_date`, `description`, `amount_cents` (outflows
    negative) and `effective_category`."""
    groups: dict[str, list[dict]] = {}
    for row in rows:
        if row["amount_cents"] < 0 and merchant_of(row["description"]):
            groups.setdefault(merchant_of(row["description"]), []).append(row)
    out = []
    for merchant, charges in groups.items():
        charges.sort(key=lambda r: r["txn_date"])
        months = {(r["txn_date"].year, r["txn_date"].month) for r in charges}
        if len(months) < MIN_MONTHS:
            continue
        amounts = [-r["amount_cents"] for r in charges]
        median = _median(amounts)
        if any(abs(a - median) * 100 > median * AMOUNT_BAND_PERCENT for a in amounts):
            continue
        gaps = [
            (b["txn_date"] - a["txn_date"]).days for a, b in zip(charges, charges[1:], strict=False)
        ]
        if any(not MIN_GAP_DAYS <= gap <= MAX_GAP_DAYS for gap in gaps):
            continue
        last = charges[-1]
        out.append(
            Recurring(
                merchant=merchant,
                category=last.get("effective_category"),
                typical_cents=median,
                months=len(months),
                last_on=last["txn_date"],
                next_on=next_month_same_day(last["txn_date"]),
                first_on=charges[0]["txn_date"],
            )
        )
    return sorted(out, key=lambda r: r.merchant)
