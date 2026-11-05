"""Each account's latest balance (household-finance tickets 01 and 07).

The newest `BalanceSnapshot` on or before the day asked about; for a
Plaid-linked account, also the bank's balance the last sync stored on
`PlaidItem.accounts`, and whichever is newer wins (the live one on a tie). A
figure always travels with its day, source and estimate flag, and an account
with neither has no balance on file: it is listed as missing, never zero.

`STALE_DAYS`: a balance older than this is flagged `stale` so a surface can
say "as of" in words. Statements arrive monthly, so the bound is generous.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

from finance.models import ESTIMATE_SOURCES, BalanceSnapshot, FinanceAccount, SourceKind
from finance.snapshots import _match, _pending, _records, _stated_day, signed_current

STALE_DAYS = 45


@dataclass(frozen=True)
class Balance:
    account: FinanceAccount
    cents: int
    currency: str
    as_of: dt.date
    source: str
    evidence: str
    is_estimate: bool
    pending_cents: int | None
    pending_count: int

    def stale(self, today: dt.date) -> bool:
        return (today - self.as_of).days > STALE_DAYS


def _live_plaid(household, account, records, zone, today) -> Balance | None:
    matched = _match(account, records)
    if matched is None:
        return None
    item, record = matched
    amount = signed_current(record)
    currency = record.get("balance_currency") or account.currency
    if amount is None or currency != account.currency:
        return None
    pending_cents, pending_count = _pending(household, account)
    return Balance(
        account, amount, currency, _stated_day(record, zone, today), SourceKind.PLAID,
        f"plaid item {item.item_id}", False, pending_cents, pending_count,
    )


def latest_balances(household, today: dt.date, zone) -> tuple[list[Balance], list[FinanceAccount]]:
    """(balances, accounts with none), active accounts only, by account key."""
    accounts = list(
        FinanceAccount.objects.filter(household=household, active=True)
        .select_related("owner")
        .order_by("key")
    )
    records = _records(household)
    found: list[Balance] = []
    missing: list[FinanceAccount] = []
    for account in accounts:
        row = (
            BalanceSnapshot.objects.filter(account=account, as_of__lte=today)
            .order_by("-as_of", "is_estimate", "-recorded_at")
            .first()
        )
        best = None
        if row is not None:
            best = Balance(
                account, row.amount_cents, row.currency, row.as_of, row.source, row.evidence,
                row.is_estimate or row.source in ESTIMATE_SOURCES, row.pending_cents,
                row.pending_count,
            )
        if account.source == SourceKind.PLAID:
            live = _live_plaid(household, account, records, zone, today)
            if live is not None and (best is None or live.as_of >= best.as_of):
                best = live
        if best is None:
            missing.append(account)
        else:
            found.append(best)
    return found, missing


def balance_of(household, account: FinanceAccount | None, today: dt.date, zone) -> Balance | None:
    if account is None:
        return None
    found, _ = latest_balances(household, today, zone)
    return next((b for b in found if b.account.pk == account.pk), None)
