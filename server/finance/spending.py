"""The household's netted spend, read once per request (tickets 02, 07, 08).

One read of the ledger, the same rows `GET /api/ledger/spend` reads
(`api.ledger_spend`), household-internal rows already taken out, then any
number of budget months and currencies from it through the ported rules in
`api/spend.py`. A figure here is the Money page's figure.
"""

from __future__ import annotations

import datetime as dt
from functools import cached_property

from api.spend import (
    MAX_DAYS_APART,
    Month,
    Txn,
    analyze_transfers,
    budget_month_rows,
    household_spend,
)


class Ledger:
    def __init__(self, request):
        from api.ledger_spend import _rows_and_internal, _spend_inputs

        self.rows, self.internal = _rows_and_internal(request)
        self.categories, self.targets, self.statements = _spend_inputs(request)

    def month(self, month: Month, currency: str) -> dict:
        from api.ledger_spend import internal_disclosure

        body = household_spend(
            self.rows, self.categories, self.targets, self.statements, month, currency
        )
        body["excluded"].update(internal_disclosure(self.internal, month, currency))
        return body

    def spend_cents(self, month: Month, currency: str) -> int:
        """Every netted outflow: categorised plus uncategorised. Uncategorised
        money was still spent; only the Money page's category total leaves it
        out, and says so."""
        body = self.month(month, currency)
        return sum(a["spend_cents"] for a in body["accounts"]) + body["uncategorised_cents"]

    @cached_property
    def not_spending(self) -> set[str]:
        return {c["name"] for c in self.categories if c["excluded_from_spend"]}

    def inflows_cents(self, month: Month, currency: str) -> int:
        """Money in on file: inflows the transfer rules leave standing (not a
        transfer, not between the household's own accounts or people)."""
        ordered = sorted(self.rows, key=lambda r: (r["created_at"], str(r["id"])))
        txns = [
            Txn(i, r["account_last4"], r["amount_cents"], r["txn_date"], r["description"],
                r["effective_category"], r["currency"])
            for i, r in enumerate(ordered)
            if r["currency"] == currency
        ]
        window, in_period = budget_month_rows(txns, currency, month)
        own = {t.account_id for t in txns}
        analysis = analyze_transfers(in_period, window, MAX_DAYS_APART, own)
        return sum(
            t.amount_cents
            for t in analysis.operating
            if t.amount_cents > 0 and t.category not in self.not_spending
        )

    def accounts_with_rows(self, month: Month) -> set[str]:
        return {
            r["account_last4"] for r in self.rows if month.first <= r["txn_date"] <= month.last
        }

    def rows_between(self, start: dt.date, end: dt.date, currency: str) -> list[dict]:
        return [
            r for r in self.rows
            if r["currency"] == currency and start <= r["txn_date"] <= end
        ]
