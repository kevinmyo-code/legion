"""Balance snapshots from the bank's own balance (household-finance ticket 01).

A Plaid-linked `FinanceAccount` gets one `BalanceSnapshot` a day from what
the last sync stored on `PlaidItem.accounts` (`ingest.plaid_sync.account_record`):

- **the figure is Plaid's `current`**, the bank's own balance (ADR 0057: fact,
  no gate). For a credit account `current` is what is owed, a positive number,
  so it is stored NEGATIVE (debt is negative, ADR 0073);
- **`as_of` is the day the bank stated it**, in the household's zone, never
  the day this ran: a balance the bank last updated yesterday is yesterday's;
- **pending is kept beside it, never in it**: the bank's own not-yet-posted
  rows on the account (`ledger_transactions.bank_pending`), summed in the
  ledger's sign, with their count, so a surface can say them as pending;
- **one row per (account, day, source)**: a second run on the same day updates
  that day's row, so the daily job is safe to re-run.

An account is matched to Plaid's record by `plaid_account_id` when the loader
stored one, else by the four-digit mask, which is the ledger's `account_last4`.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

from django.db.models import Count, Sum
from django.utils.dateparse import parse_datetime

from finance.models import BalanceSnapshot, FinanceAccount, SourceKind

CREDIT_TYPES = frozenset({"credit"})


@dataclass
class SnapshotReport:
    written: int = 0
    unmatched: list[str] = field(default_factory=list)
    no_balance: list[str] = field(default_factory=list)


def signed_current(record: dict) -> int | None:
    """Plaid's `current` in the finance sign: a credit balance is debt."""
    current = record.get("balance_current_cents")
    if current is None:
        return None
    return -current if record.get("type") in CREDIT_TYPES else current


def _records(household) -> list[tuple[object, dict]]:
    from ingest.models import PlaidItem

    out = []
    for item in PlaidItem.objects.filter(household=household):
        for record in item.accounts or []:
            out.append((item, record))
    return out


def _match(account: FinanceAccount, records):
    for item, record in records:
        if account.plaid_account_id and record.get("account_id") == account.plaid_account_id:
            return item, record
    if account.last4:
        for item, record in records:
            if str(record.get("mask") or "") == account.last4:
                return item, record
    return None


def _stated_day(record: dict, zone, fallback: dt.date) -> dt.date:
    stated = parse_datetime(record.get("balance_as_of") or "")
    if stated is None:
        return fallback
    if stated.tzinfo is None:
        stated = stated.replace(tzinfo=dt.UTC)
    return stated.astimezone(zone).date()


def _pending(household, account: FinanceAccount) -> tuple[int | None, int]:
    from legacy.models.ledger import LedgerTransaction

    if not account.last4:
        return None, 0
    found = LedgerTransaction.objects.filter(
        household=household,
        account_last4=account.last4,
        currency=account.currency,
        bank_pending=True,
    ).aggregate(cents=Sum("amount_cents"), count=Count("id"))
    return (found["cents"] or 0), found["count"]


def snapshot_plaid(household, today: dt.date, zone) -> SnapshotReport:
    report = SnapshotReport()
    records = _records(household)
    accounts = FinanceAccount.objects.filter(
        household=household, source=SourceKind.PLAID, active=True
    ).order_by("key")
    for account in accounts:
        matched = _match(account, records)
        if matched is None:
            report.unmatched.append(account.key)
            continue
        item, record = matched
        amount = signed_current(record)
        currency = record.get("balance_currency") or account.currency
        if amount is None or currency != account.currency:
            report.no_balance.append(account.key)
            continue
        pending_cents, pending_count = _pending(household, account)
        BalanceSnapshot.objects.update_or_create(
            account=account,
            as_of=_stated_day(record, zone, today),
            source=SourceKind.PLAID,
            defaults={
                "household": household,
                "amount_cents": amount,
                "currency": currency,
                "evidence": f"plaid item {item.item_id}",
                "is_estimate": False,
                "pending_cents": pending_cents,
                "pending_count": pending_count,
            },
        )
        report.written += 1
    return report
