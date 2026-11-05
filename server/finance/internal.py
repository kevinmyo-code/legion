"""Household-internal money (household-finance ticket 02, map ruling 10).

Money moving between the household's own people and places is not spend. The
ported rules in `api/spend.py` already take out equal-and-opposite pairs across
the household's accounts, rows naming one of its own accounts, and
not-spending categories. They still count a Zelle to the spouse as spend,
because a person is not an account. This is the layer that fixes that, run
ABOVE the port, never inside it: `api/spend.py` stays a line-for-line port of
the phone's Kotlin and `tests/test_spend_parity.py` stays honest.

**The rules, in order.** A row is household-internal when:

1. **Member transfer** (`MEMBER_TRANSFER`): its description is shaped like a
   person-to-person transfer (`TRANSFER_WORDS`: Zelle, a transfer, a wire, a
   remittance, PayNow ...) AND names a household member's configured payer name
   (`FinanceMember.payer_names`, loaded from the private file, never written
   here). Both conditions, because a bank prints the account holder's own name
   on payroll lines too, and a paycheque is income, not internal money. When
   both legs are visible (equal and opposite, different accounts, same
   currency, within 5 days) each names the other in `paired_with`; when only
   one side is visible the row is internal on its own.
2. **Cross-border transfer** (`CROSS_BORDER_PAIR`): a transfer-shaped USD row
   and a transfer-shaped SGD row of opposite sign, within 10 days, whose amounts
   agree within 3% at the EARLIER day's stored rate (`finance.fx`; no rate on
   file, no pair). A wire's own fee and the spread mean the two legs are never
   equal, hence the tolerance.

**Never internal:** any row whose description says FEE or CHARGE. A wire fee is
spend (category Fees), whatever else the line says.

**Card payments are already netted** by the port (pass 1 pairs the checking
debit with the card credit; pass 2 catches a row naming the card's number).
This layer does not touch them.

Every internal row carries `reason` and, when paired, `paired_with`, so a
surface can say why each one was left out. Money is int cents.
"""

from __future__ import annotations

import datetime as dt
import re
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass

from finance.fx import apply_rate

MEMBER_TRANSFER = "MEMBER_TRANSFER"
CROSS_BORDER_PAIR = "CROSS_BORDER_PAIR"

REASON_WORDS = {
    MEMBER_TRANSFER: "money moved between members of the household",
    CROSS_BORDER_PAIR: "money moved between the household's accounts in two countries",
}

# What a person-to-person or account-to-account transfer line looks like,
# matched as whole words.
TRANSFER_WORDS = re.compile(
    r"\b(zelle|transfer\w*|xfer|wire|remit\w*|payment (?:to|from)|paynow|fast payment|giro|"
    r"telegraphic|wise|venmo)\b",
    re.I,
)
_NEVER = re.compile(r"\b(fee|fees|charge|charges)\b", re.I)
MEMBER_PAIR_DAYS = 5
CROSS_BORDER_DAYS = 10
# Of the USD leg.
CROSS_BORDER_TOLERANCE_PERCENT = 3


@dataclass(frozen=True)
class Row:
    id: str
    account: str
    currency: str
    txn_date: dt.date
    description: str
    amount_cents: int


@dataclass(frozen=True)
class Mark:
    reason: str
    paired_with: str | None = None


def _norm(text: str) -> str:
    return " ".join(re.sub(r"[^A-Za-z0-9]+", " ", text).upper().split())


def transfer_shaped(description: str) -> bool:
    return bool(TRANSFER_WORDS.search(description)) and not _NEVER.search(description)


def names_a_member(description: str, payer_names: Iterable[str]) -> bool:
    text = f" {_norm(description)} "
    return any(f" {_norm(name)} " in text for name in payer_names if _norm(name))


def _pair(rows: Sequence[Row], fits: Callable[[Row, Row], bool], key) -> dict[str, str]:
    """Greedy pairing, closest first, each row used once (the port's posture)."""
    used: set[str] = set()
    out: dict[str, str] = {}
    for row in sorted(rows, key=lambda r: (r.txn_date, r.id)):
        if row.id in used:
            continue
        options = [o for o in rows if o.id != row.id and o.id not in used and fits(row, o)]
        if not options:
            continue
        best = min(options, key=lambda o: key(row, o))
        used.update((row.id, best.id))
        out[row.id], out[best.id] = best.id, row.id
    return out


def household_internal(
    rows: Sequence[Row],
    payer_names: Iterable[str],
    rate_for: Callable[[dt.date], int | None],
) -> dict[str, Mark]:
    """{row id: why it is household-internal}, for the rows that are."""
    names = [n for n in payer_names if n and n.strip()]
    shaped = [r for r in rows if transfer_shaped(r.description)]
    marks: dict[str, Mark] = {}

    members = [r for r in shaped if names and names_a_member(r.description, names)]
    member_pairs = _pair(
        members,
        lambda a, b: (
            a.account != b.account
            and a.currency == b.currency
            and a.amount_cents == -b.amount_cents
            and abs((a.txn_date - b.txn_date).days) <= MEMBER_PAIR_DAYS
        ),
        lambda a, b: (abs((a.txn_date - b.txn_date).days), b.id),
    )
    for row in members:
        marks[row.id] = Mark(MEMBER_TRANSFER, member_pairs.get(row.id))

    def agrees(a: Row, b: Row) -> bool:
        if {a.currency, b.currency} != {"USD", "SGD"} or (a.amount_cents > 0) == (
            b.amount_cents > 0
        ):
            return False
        if abs((a.txn_date - b.txn_date).days) > CROSS_BORDER_DAYS:
            return False
        rate = rate_for(min(a.txn_date, b.txn_date))
        if rate is None:
            return False
        usd, sgd = (a, b) if a.currency == "USD" else (b, a)
        as_usd = apply_rate(abs(sgd.amount_cents), "SGD", "USD", rate)
        gap = abs(as_usd - abs(usd.amount_cents))
        return gap * 100 <= abs(usd.amount_cents) * CROSS_BORDER_TOLERANCE_PERCENT

    open_rows = [r for r in shaped if r.id not in marks or marks[r.id].paired_with is None]
    cross = _pair(
        open_rows, agrees, lambda a, b: (abs((a.txn_date - b.txn_date).days), b.id)
    )
    for row_id, other in cross.items():
        marks[row_id] = Mark(CROSS_BORDER_PAIR, other)
    return marks


def rows_from_ledger(values: Iterable[dict]) -> list[Row]:
    """`Row`s from `LedgerTransaction` value dicts (id, account_last4,
    currency, txn_date, description, amount_cents)."""
    return [
        Row(
            id=str(v["id"]),
            account=v["account_last4"],
            currency=v["currency"],
            txn_date=v["txn_date"],
            description=v["description"] or "",
            amount_cents=v["amount_cents"],
        )
        for v in values
    ]


def stored_rate(on_date: dt.date) -> int | None:
    """The stored USD/SGD rate on or before `on_date`, or None. Never fetches."""
    from finance.fx import NoRate, rate_on

    try:
        return rate_on(on_date).rate_micros
    except NoRate:
        return None


def payer_names_for(household) -> list[str]:
    from finance.models import FinanceMember

    names: list[str] = []
    for member in FinanceMember.objects.filter(household=household):
        names.extend(member.payer_names or [])
    return names


def marks_for(household, values: Sequence[dict]) -> dict[str, Mark]:
    """The household's internal marks over ledger value dicts."""
    return household_internal(rows_from_ledger(values), payer_names_for(household), stored_rate)
