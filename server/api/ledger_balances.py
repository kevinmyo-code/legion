"""Balances per account: the last statement's closing balance, plus what
moved since (parity quick wins, 2026-11-02; the phone's
`LedgerController.accountBalances` and `get_balance`).

Per (account, currency), never summed across currencies:

- **anchor**: the closing balance printed on the latest statement on file
  (by `period_end`). A statement only exists because it passed the
  reconciliation gate, so this figure is verified (section 4 rules 2 and 8:
  the anchor is stored in its own column).
- **since**: rows dated after the anchor's `period_end` (every row, when there
  is no statement). Split by provenance: the bank's own feed (`BANK_API`,
  fact, ADR 0057) and gated rows count as fact; `UNRECONCILED` and `USER`
  rows are UNVERIFIED (`api.ledger_spend.UNVERIFIED_PROVENANCE`).
- **pending**: rows a person logged as pending (`pending_logged_at`), which
  the bank has not confirmed, counted whatever their date, as the phone does.

No statement means no verified balance. Activity alone is movement, not a
balance, and is never presented as one.

Every read goes through `household.tenancy.scoped()`.
"""

from __future__ import annotations

from household.tenancy import scoped


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
    keys = {(s["account_last4"], s["currency"]) for s in statements}
    keys |= {(r["account_last4"], r["currency"]) for r in rows}

    wanted = (account or "").strip().lower()
    out = []
    for last4, currency in sorted(keys, key=lambda k: (k[1] != "USD", k[1], k[0])):
        own_statements = [
            s for s in statements if s["account_last4"] == last4 and s["currency"] == currency
        ]
        own_rows = [r for r in rows if r["account_last4"] == last4 and r["currency"] == currency]
        label = _label(own_statements + own_rows, last4)
        if wanted and wanted not in last4 and wanted not in label.lower():
            continue
        anchor = (
            max(own_statements, key=lambda s: (s["period_end"], s["created_at"]))
            if own_statements
            else None
        )
        after = [
            r
            for r in own_rows
            if r["pending_logged_at"] is None
            and (anchor is None or r["txn_date"] > anchor["period_end"])
        ]
        fact = [r for r in after if r["provenance"] not in UNVERIFIED_PROVENANCE]
        unverified = [r for r in after if r["provenance"] in UNVERIFIED_PROVENANCE]
        pending = [r for r in own_rows if r["pending_logged_at"] is not None]
        fact_cents = sum(r["amount_cents"] for r in fact)
        unverified_cents = sum(r["amount_cents"] for r in unverified)
        pending_cents = sum(r["amount_cents"] for r in pending)
        verified_balance = (
            anchor["closing_balance_cents"] + fact_cents if anchor is not None else None
        )
        out.append(
            {
                "account_last4": last4,
                "label": label,
                "currency": currency,
                "statement_closing_cents": anchor["closing_balance_cents"] if anchor else None,
                "statement_period_end": anchor["period_end"] if anchor else None,
                "fact_since_cents": fact_cents,
                "fact_since_count": len(fact),
                "verified_balance_cents": verified_balance,
                "unverified_since_cents": unverified_cents,
                "unverified_since_count": len(unverified),
                "pending_cents": pending_cents,
                "pending_count": len(pending),
                # The figure with everything unconfirmed added in. None when
                # there is no anchor: activity is not a balance.
                "with_unverified_cents": (
                    verified_balance + unverified_cents + pending_cents
                    if verified_balance is not None
                    else None
                ),
                "verified": anchor is not None and not unverified and not pending,
                "latest_activity_on": max((r["txn_date"] for r in own_rows), default=None),
            }
        )
    return out


def _label(rows: list[dict], last4: str) -> str:
    """The most recent nickname with a letter in it, else "Account ending 1234"
    (as `api.spend._label`)."""
    named = [r for r in rows if any(ch.isalpha() for ch in (r.get("account_nickname") or ""))]
    if not named:
        return f"Account ending {last4}"
    return max(named, key=lambda r: r["created_at"])["account_nickname"]
