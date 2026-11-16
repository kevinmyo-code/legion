"""Net cash: what is left if every card were a debit card (Kevin, 2026-11-16; ADR 0094).

*"i want to know how much net cash i have. so as if the credit card is not
credit but instantly deducted like a debit."* His rulings: the household's
linked CHECKING account(s) minus what is owed on EVERY linked credit card, and
nothing else; charges still pending at the bank count as already spent; it is
net cash right now, never "safe to spend" (no bills, no pay, no estimate).

**One calculation, here.** `/api/finance/net-cash`, the engine's
`account_balances` tool and through them the phone's Home, Money screen and
`get_balance` answer all read `net_cash()`; nothing re-derives it (CLAUDE.md
section 7: a business rule lives in Django once).

## Where the figures come from (ADR 0057: the bank's own feed is fact)

Each linked account's balance is what the last sync stored on
`PlaidItem.accounts` (`ingest.plaid_sync.account_record`), Plaid's raw
`balances.current` / `balances.available` in cents with its as-of time. Plaid's
own definitions (Transactions API reference, `accounts[].balances`):

- `current`: "The total amount of funds in or owed by the account. For credit
  accounts a positive balance indicates the amount owed." It says nothing of
  pending; `available` is defined against it.
- `available`, depository: "typically equals the `current` balance less any
  pending outflows plus any pending inflows", and "does not include the
  overdraft limit". Credit: limit less current, "less any pending outflows
  plus any pending inflows".

So `current` is the POSTED figure and pending sits outside it. This repo
already reads it that way: `finance.snapshots` keeps pending "beside it, never
in it", and `finance.runway._owed` owes a card's current plus its pending.

## The formula, per account, in the ledger's sign (money out negative)

- **Checking**: the bank's own `available` when stored (basis
  `bank_available`): the bank's figure for current less pending outflows plus
  pending inflows, so pending is counted by the bank and not again here. With
  no `available`, `current` plus the account's pending feed rows (basis
  `current_plus_pending`).
- **Card**: minus `current` plus the card's pending feed rows (basis
  `current_plus_pending`): a pending charge (negative) adds to what is owed, a
  pending credit (a payment in flight, a refund) takes from it. Plaid's card
  `available` is credit LEFT, not a balance, so it cannot stand in.

Pending feed rows are `ledger_transactions` with `provenance = BANK_API` and
`bank_pending = true`, filed under the account's four-digit mask. Rows a person
logged as pending by voice (`pending_logged_at`) are NOT the bank's and are not
in net cash.

**What the stored data cannot prove.** That a given bank's card `current`
excludes pending is Plaid's definition, not something checked per bank: the
bank's own check (limit minus available equals current plus pending) needs
`balances.limit`, which `account_record` does not keep. If a bank folded pending
into `current`, pending charges would count twice. Said in ADR 0094.

## No partial sum, ever

A checking or card record with no balance, no as-of time, no currency or no
four-digit mask (its pending rows cannot be found without one) is listed in
`missing` with the reason in words, and there is NO net figure. Parts in two
currencies give no net figure either (never summed across currencies). No
linked checking account gives none. No linked card is fine: net cash is then
checking alone, and the parts say so by having no card.

`as_of` is the OLDER of the parts' as-of times. Whether it is stale is the
reader's call against its own clock (a saved copy ages on the phone).

## The 30-day line (posted basis)

Past pending cannot be rebuilt (a pending row is deleted when it posts), so
each past day is the end-of-day POSTED figure: each account's signed
`current`, walked back through its posted feed rows dated after that day, up to
its own as-of day. Today's point (the as-of day) is the headline figure, which
includes pending. History starts at the latest of each account's earliest
posted feed row less one day (before that the feed may not cover it), and never
earlier than 29 days before the as-of day. A card payment moves checking and
the card by the same amount, so net cash does not move, except for the days
between the two legs posting, if they post on different days.

Reads go through `household_of()` (Plaid items) and `scoped()` (ledger rows),
the ADR 0045 choke point. Every finance figure is visible to every member (ADR
0073), so nothing here reads through `visible()`.
"""

from __future__ import annotations

import datetime as dt

from django.utils.dateparse import parse_datetime

WINDOW_DAYS = 30
CREDIT = "credit"
DEPOSITORY = "depository"
CHECKING = "checking"

BASIS_AVAILABLE = "bank_available"
BASIS_CURRENT_PLUS_PENDING = "current_plus_pending"


def _role(record: dict) -> str | None:
    kind = record.get("type")
    if kind == CREDIT:
        return "card"
    if kind == DEPOSITORY and record.get("subtype") == CHECKING:
        return "checking"
    return None


def _label(record: dict) -> str:
    return (record.get("name") or record.get("official_name") or "").strip() or (
        "Card" if record.get("type") == CREDIT else "Checking"
    )


def _mask(record: dict) -> str | None:
    mask = str(record.get("mask") or "")
    return mask if len(mask) == 4 and mask.isdigit() else None


def _as_of(record: dict) -> dt.datetime | None:
    stated = parse_datetime(record.get("balance_as_of") or "")
    if stated is not None and stated.tzinfo is None:
        stated = stated.replace(tzinfo=dt.UTC)
    return stated


def _missing_reason(record: dict, role: str) -> str | None:
    """Why this account's balance cannot go into net cash, in words, or None."""
    if role == "card" and record.get("balance_current_cents") is None:
        return "the bank sent no balance for this card"
    if (
        role == "checking"
        and record.get("balance_current_cents") is None
        and record.get("balance_available_cents") is None
    ):
        return "the bank sent no balance for this account"
    if not record.get("balance_currency"):
        return "the bank sent no currency with its balance"
    if _as_of(record) is None:
        return "no as-of time was stored with its balance"
    if _mask(record) is None:
        return "it has no four-digit number, so its pending charges cannot be matched"
    return None


def compute(records: list[dict], rows: list[dict], zone) -> dict:
    """Net cash from the stored Plaid account records and the bank feed's rows.

    `records`: `PlaidItem.accounts` entries (every item of the household).
    `rows`: `BANK_API` ledger rows as dicts with `account_last4`, `currency`,
    `txn_date`, `amount_cents` (money out negative) and `bank_pending`.
    `zone`: the household's zone, which decides an as-of time's day.

    Pure: no database, no clock. The figure is integer cents throughout.
    """
    parts: list[dict] = []
    missing: list[dict] = []
    seen: set[str] = set()
    for record in records:
        role = _role(record)
        if role is None:
            continue
        account_id = str(record.get("account_id") or "")
        if account_id and account_id in seen:
            continue
        seen.add(account_id)
        why = _missing_reason(record, role)
        if why is not None:
            missing.append(
                {"role": role, "label": _label(record), "mask": _mask(record), "reason": why}
            )
            continue
        mask = _mask(record)
        currency = record["balance_currency"]
        pending = [
            r
            for r in rows
            if r["account_last4"] == mask and r["currency"] == currency and r["bank_pending"]
        ]
        pending_cents = sum(r["amount_cents"] for r in pending)
        current = record.get("balance_current_cents")
        available = record.get("balance_available_cents")
        if role == "checking" and available is not None:
            balance, basis = available, BASIS_AVAILABLE
        elif role == "checking":
            balance, basis = current + pending_cents, BASIS_CURRENT_PLUS_PENDING
        else:
            balance, basis = -current + pending_cents, BASIS_CURRENT_PLUS_PENDING
        parts.append(
            {
                "role": role,
                "label": _label(record),
                "mask": mask,
                "currency": currency,
                "balance_cents": balance,
                "basis": basis,
                "bank_current_cents": current,
                "bank_available_cents": available,
                "pending_cents": pending_cents,
                "pending_count": len(pending),
                "as_of": _as_of(record),
                # Posted basis, for the line: the bank's current in the ledger's sign.
                "_posted": current if role == "checking" else -current,
            }
        )
    parts.sort(key=lambda p: (p["role"] != "checking", p["label"], p["mask"]))
    missing.sort(key=lambda m: (m["role"] != "checking", m["label"]))

    reason = None
    checking = [p for p in parts if p["role"] == "checking"]
    currencies = {p["currency"] for p in parts}
    if missing:
        named = "; ".join(
            f"{m['label']}{' ending ' + m['mask'] if m['mask'] else ''}: {m['reason']}"
            for m in missing
        )
        reason = f"No net cash figure: {named}."
    elif not checking:
        reason = "No net cash figure: no checking account is linked to the bank feed."
    elif len(currencies) > 1:
        reason = (
            "No net cash figure: the linked accounts are in "
            + " and ".join(sorted(currencies))
            + ", and figures in two currencies are never added."
        )

    as_of = min((p["as_of"] for p in parts), default=None)
    body = {
        "available": reason is None,
        "reason": reason,
        "net_cents": None,
        "currency": None,
        "as_of": as_of,
        "pending_cents": None,
        "parts": [{k: v for k, v in p.items() if not k.startswith("_")} for p in parts],
        "missing": missing,
        "history": None,
    }
    if reason is not None:
        return body
    net = sum(p["balance_cents"] for p in parts)
    body["net_cents"] = net
    body["currency"] = checking[0]["currency"]
    body["pending_cents"] = sum(p["pending_cents"] for p in parts)
    body["history"] = _history(parts, rows, zone, net)
    return body


def _history(parts: list[dict], rows: list[dict], zone, net: int) -> dict:
    """The 30-day line, posted basis, ending at the headline figure."""

    def day_of(at: dt.datetime) -> dt.date:
        return at.astimezone(zone).date()

    end = min(day_of(p["as_of"]) for p in parts)
    start = end - dt.timedelta(days=WINDOW_DAYS - 1)
    posted_by_part = []
    for p in parts:
        own = [
            r
            for r in rows
            if r["account_last4"] == p["mask"]
            and r["currency"] == p["currency"]
            and not r["bank_pending"]
        ]
        first = min((r["txn_date"] for r in own), default=None)
        # With no posted row the feed's reach for this account is unknown: no walk back.
        start = max(start, (first - dt.timedelta(days=1)) if first else end)
        posted_by_part.append((p, own))
    start = min(start, end)
    points = []
    day = start
    while day < end:
        total = 0
        for p, own in posted_by_part:
            own_day = day_of(p["as_of"])
            after = sum(r["amount_cents"] for r in own if day < r["txn_date"] <= own_day)
            total += p["_posted"] - after
        points.append({"on": day, "net_cents": total})
        day += dt.timedelta(days=1)
    points.append({"on": end, "net_cents": net})
    return {
        "basis": "posted",
        "start": points[0]["on"],
        "end": end,
        "short": points[0]["on"] > end - dt.timedelta(days=WINDOW_DAYS - 1),
        "points": points,
    }


def net_cash(request) -> dict:
    """`compute` over this household's linked accounts and bank-feed rows."""
    from django.db.models import Min, Q

    from finance.figure_views import household_today
    from household.tenancy import household_of, scoped
    from ingest.models import PlaidItem
    from legacy.models.ledger import LedgerTransaction

    records: list[dict] = []
    for item in PlaidItem.objects.filter(household=household_of(request)).order_by("item_id"):
        records.extend(item.accounts or [])
    _, zone = household_today(request)
    feed = scoped(LedgerTransaction, request).filter(provenance="BANK_API")
    fields = ("account_last4", "currency", "txn_date", "amount_cents", "bank_pending")
    stated = [_as_of(r) for r in records if _role(r) and _as_of(r) is not None]
    if not stated:
        return compute(records, [], zone)
    # The line walks back at most 30 days from the oldest as-of day: every
    # pending row, the posted rows in reach, and per account its earliest posted
    # day (where the feed's reach starts), as a row with no amount.
    oldest = min(stated).astimezone(zone).date() - dt.timedelta(days=WINDOW_DAYS + 1)
    rows = list(feed.filter(Q(bank_pending=True) | Q(txn_date__gte=oldest)).values(*fields))
    for first in (
        feed.filter(bank_pending=False)
        .order_by()
        .values("account_last4", "currency")
        .annotate(first=Min("txn_date"))
    ):
        rows.append(
            {
                "account_last4": first["account_last4"],
                "currency": first["currency"],
                "txn_date": first["first"],
                "amount_cents": 0,
                "bank_pending": False,
            }
        )
    return compute(records, rows, zone)
