"""Balances per account (parity quick wins, 2026-11-02; the phone's
`LedgerController.accountBalances` and `get_balance`).

Per (account, currency), never summed across currencies, the anchor is the
newest of:

- **the bank's own balance** (Kevin, 2026-11-03: "store them"; ADR 0057 as
  amended): what Plaid reported for the account, stored by
  `ingest.plaid_sync.account_record` with its as-of time. Fact, said plainly,
  always with its as-of time ("from the bank as of 9:14 AM"), never as "now".
  An account is matched to its ledger rows the way the sync files them:
  Plaid's account id through `plaid_sync.account_map`, whose four-digit mask
  is the rows' `account_last4`. The ledger keeps no Plaid account id, so the
  id resolves the record and the mask is the join.
- **the closing balance printed on the latest statement** (by `period_end`).
  A statement only exists because it passed the reconciliation gate, so the
  figure is verified (section 4 rules 2 and 8).

Then what moved since the anchor's date (every row, when there is none):
gated rows and the bank's feed (`BANK_API`) are fact; `UNRECONCILED` and
`USER` rows are UNVERIFIED (`api.ledger_spend.UNVERIFIED_PROVENANCE`). With a
bank anchor, the feed's own rows are already inside the bank's figure and are
not added again. Rows a person logged as pending (`pending_logged_at`) are
counted whatever their date, as the phone does.

**The current figure leads** (audit of a live conversation, 2026-11-03: a
statement's closing balance and the movement since it were read as two
figures). `current_cents` is anchor plus fact since; the tool's words say it
first, as "Now: ...", and the anchor second, as where it came from.

No anchor means no balance on file. For an account the feed supplies that is
NOT "unverified": its transactions are fact and there is simply no figure to
state yet (the phone: "not stated", verified). Activity alone is movement, not
a balance, and is never presented as one.

Every read goes through `household.tenancy.scoped()` / `household_of()`.
"""

from __future__ import annotations

import datetime as dt

from django.utils.dateparse import parse_datetime

from household.tenancy import household_of, scoped

CREDIT_TYPES = frozenset({"credit"})


def bank_balances(request) -> dict[tuple[str, str], dict]:
    """{(last4, currency): the bank's stored balance} for this household's
    linked accounts that carry one."""
    from ingest.models import PlaidItem
    from ingest.plaid_sync import account_map

    out: dict[tuple[str, str], dict] = {}
    for item in PlaidItem.objects.filter(household=household_of(request)):
        records = item.accounts or []
        filed = account_map(records)
        for record in records:
            current = record.get("balance_current_cents")
            available = record.get("balance_available_cents")
            if current is None and available is None:
                continue
            account = filed.get(str(record.get("account_id") or ""))
            currency = record.get("balance_currency")
            if account is None or account.last4 is None or not currency:
                continue
            as_of = parse_datetime(record.get("balance_as_of") or "")
            out[(account.last4, currency)] = {
                "account_id": record.get("account_id"),
                "name": account.nickname,
                "type": record.get("type"),
                "current_cents": current,
                "available_cents": available,
                "as_of": as_of,
                "institution_name": item.institution_name,
            }
    return out


def _signed(bank: dict) -> int | None:
    """The bank's balance in the ledger's sign: a credit account's `current`
    is what is owed, a positive number, so it is money out (negative)."""
    current = bank["current_cents"]
    if current is None:
        return None
    return -current if bank["type"] in CREDIT_TYPES else current


def account_balances(request, account: str | None = None) -> list[dict]:
    from api.ledger_spend import UNVERIFIED_PROVENANCE
    from legacy.models.ledger import LedgerTransaction, Statement

    statements = list(
        scoped(Statement, request).values(
            "account_last4",
            "account_nickname",
            "currency",
            "period_start",
            "period_end",
            "closing_balance_cents",
            "created_at",
        )
    )
    rows = list(
        scoped(LedgerTransaction, request).values(
            "account_last4",
            "account_nickname",
            "currency",
            "txn_date",
            "amount_cents",
            "provenance",
            "pending_logged_at",
            "created_at",
        )
    )
    banks = bank_balances(request)
    keys = {(s["account_last4"], s["currency"]) for s in statements}
    keys |= {(r["account_last4"], r["currency"]) for r in rows}
    keys |= set(banks)

    wanted = (account or "").strip().lower()
    out = []
    for last4, currency in sorted(keys, key=lambda k: (k[1] != "USD", k[1], k[0])):
        own_statements = [
            s for s in statements if s["account_last4"] == last4 and s["currency"] == currency
        ]
        own_rows = [r for r in rows if r["account_last4"] == last4 and r["currency"] == currency]
        bank = banks.get((last4, currency))
        label = _label(own_statements + own_rows, last4, bank)
        if wanted and wanted not in last4 and wanted not in label.lower():
            continue
        statement = (
            max(own_statements, key=lambda s: (s["period_end"], s["created_at"]))
            if own_statements
            else None
        )
        bank_day = bank["as_of"].date() if bank and bank["as_of"] else None
        use_bank = bank is not None and _signed(bank) is not None and (
            statement is None or bank_day is None or bank_day >= statement["period_end"]
        )
        if use_bank:
            since: dt.date | None = bank_day
            anchor_cents = _signed(bank)
            source = "bank"
        elif statement is not None:
            since = statement["period_end"]
            anchor_cents = statement["closing_balance_cents"]
            source = "statement"
        else:
            since, anchor_cents, source = None, None, None

        after = [
            r
            for r in own_rows
            if r["pending_logged_at"] is None and (since is None or r["txn_date"] > since)
        ]
        # The bank's figure already holds its own feed's rows.
        fact = [
            r
            for r in after
            if r["provenance"] not in UNVERIFIED_PROVENANCE
            and not (source == "bank" and r["provenance"] == "BANK_API")
        ]
        unverified = [r for r in after if r["provenance"] in UNVERIFIED_PROVENANCE]
        pending = [r for r in own_rows if r["pending_logged_at"] is not None]
        fact_cents = sum(r["amount_cents"] for r in fact)
        unverified_cents = sum(r["amount_cents"] for r in unverified)
        pending_cents = sum(r["amount_cents"] for r in pending)
        balance = anchor_cents + fact_cents if anchor_cents is not None else None
        out.append(
            {
                "account_last4": last4,
                "label": label,
                "currency": currency,
                "anchor": source,
                "statement_closing_cents": (
                    statement["closing_balance_cents"] if statement else None
                ),
                "statement_period_end": statement["period_end"] if statement else None,
                "bank": (
                    {
                        "current_cents": bank["current_cents"],
                        "available_cents": bank["available_cents"],
                        "type": bank["type"],
                        "as_of": bank["as_of"],
                    }
                    if bank
                    else None
                ),
                "fact_since_cents": fact_cents,
                "fact_since_count": len(fact),
                # How many of those came from the bank's own feed (fact too).
                "fact_since_bank_feed_count": sum(
                    1 for r in fact if r["provenance"] == "BANK_API"
                ),
                # The figure to say: the anchor plus fact since it, in the
                # ledger's sign (audit 2026-11-03: the anchor was said as the
                # balance). Same value as `verified_balance_cents`; None when
                # there is no anchor.
                "current_cents": balance,
                # The balance in the ledger's sign, from the anchor plus fact
                # since. None when there is no anchor.
                "verified_balance_cents": balance,
                "unverified_since_cents": unverified_cents,
                "unverified_since_count": len(unverified),
                "pending_cents": pending_cents,
                "pending_count": len(pending),
                "with_unverified_cents": (
                    balance + unverified_cents + pending_cents if balance is not None else None
                ),
                # As the phone's `get_balance`: nothing unconfirmed in the
                # figure. Whether one can be stated at all is
                # `verified_balance_cents is not None`.
                "verified": not unverified and not pending,
                "from_bank_feed": bank is not None
                or any(r["provenance"] == "BANK_API" for r in own_rows),
                "latest_activity_on": max((r["txn_date"] for r in own_rows), default=None),
            }
        )
    return out


def _label(rows: list[dict], last4: str, bank: dict | None) -> str:
    """The most recent nickname with a letter in it, else the bank's own name
    for the account, else "Account ending 1234" (as `api.spend._label`)."""
    named = [r for r in rows if any(ch.isalpha() for ch in (r.get("account_nickname") or ""))]
    if named:
        return max(named, key=lambda r: r["created_at"])["account_nickname"]
    if bank and bank.get("name"):
        return bank["name"]
    return f"Account ending {last4}"
