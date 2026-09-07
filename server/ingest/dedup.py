"""Ledger dedup, in Python. A port of `private.ledger_resolve_dedup` and
`private.ledger_dedup_description` (`20260825000800_ledger_dedup.sql`), which
were themselves a port of `ledger/LedgerDedup.kt`.

Pure, like `gate.py`: the credit pool and the enumerated windows arrive as
arguments and the ORM query that builds them lives in `views.py`. That is the
same split the SQL already had (the function "stays pure and testable on its
own", in its own words) and it is what lets the two-pass algorithm be tested
without a statement, an account or a database.

## The problem it solves, because the code is meaningless without it

Copied from the SQL's own header, because the reasoning is the thing that has to
survive the port. Kevin's July checking PDF covers 06/05 to 07/06. His mid-cycle
CSV covers 07/01 to 07/31. Six days are stated twice, and BofA words the same
transaction differently in the two exports:

    'PURCHASE   0706 VPN24.ME EDINBURGH    00'
    'VPN24.ME 07/06 PURCHASE EDINBURGH 00'

A description-sensitive key catches neither, so the row double-counts, every
month, by construction. The naive fix - drop description from the key - is
worse: it collapses two genuinely separate $4.50 coffees on the same day. So the
relaxation is NARROWED to dates some other committed statement has already
enumerated completely. Outside such a window nothing changes, because no prior
statement claims to have listed those rows.

## Two things the SQL is emphatic about, kept

1. **The key is `(account_last4, account_nickname)` together**, never last-four
   alone. `LedgerAccountIdentity.kt`: a checking account ending in the same four
   digits would absorb a card's rows, "a materially worse bug than the one this
   file exists to fix". That scoping happens in the caller, which is why neither
   field appears in this module - stated here so its absence reads as deliberate.
2. **Incoming is never deduplicated against itself.** Two identical lines in one
   statement are two genuine purchases, and collapsing them is the original bug.
   The pools below are built from EXISTING rows only.

## One divergence from the SQL, in this port's favour

The SQL normaliser's own header records a known, accepted divergence from the
Kotlin: `upper()` in Postgres is collation-driven and does not expand one-to-many
(Eszett stays one character), where Kotlin's `uppercase()` is Locale.ROOT with
full Unicode mapping and turns it into SS. Python's `str.upper()` expands the
same way Kotlin's does, so this port agrees with the Kotlin and differs from the
SQL on exactly that input. It cannot bite on ASCII bank descriptions, and the
failure direction is the safe one either way: a divergence causes a false
NON-match, which inserts a row rather than dropping one. Recorded rather than
silently fixed, because the SQL side is still live until ticket 10.
"""
from __future__ import annotations

import re
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date

_WHITESPACE_RUN = re.compile(r"\s+")


def normalize_description(description: str | None) -> str:
    """`upper(btrim(regexp_replace(coalesce(d, ''), '\\s+', ' ', 'g')))`.

    Collapse-then-trim, as the SQL does, rather than trim-then-collapse: they
    agree, because collapsing maps any run to a single space, and this way one
    regexp does the interior and the edges.
    """
    return _WHITESPACE_RUN.sub(" ", description or "").strip().upper()


@dataclass(frozen=True)
class ExistingRow:
    """One already-committed transaction, reduced to the three fields dedup
    keys on. The caller reads these out of `ledger_transactions` scoped to the
    account and the incoming date span, and with `reversal_of is null` - all
    three filters are in the SQL's own `for rec in select ...` and all three are
    load-bearing (an unscoped pool lets a far-future row with the same key
    absorb an incoming one)."""

    txn_date: date
    amount_cents: int
    description: str


@dataclass(frozen=True)
class DedupResult:
    """`insert_ordinals`, `duplicates_skipped`, `restatements_skipped` - the same
    three the SQL function returns, and the same three `commit_statement` puts
    in its response body."""

    insert_ordinals: list[int]
    duplicates_skipped: int
    restatements_skipped: int


def resolve_dedup(
    incoming: Sequence[Mapping[str, object]],
    existing: Iterable[ExistingRow],
    windows: Sequence[tuple[date, date]],
) -> DedupResult:
    """Two passes over a SHARED, DEPLETING credit pool.

    Transcribed as a loop rather than expressed as a set operation, for the
    reason the SQL gives: "A faithful loop that is obviously right beats a clever
    join that is probably right, for something that decides whether money gets
    counted twice."

    `incoming` items need `txn_date` (a `date`), `amount_cents` (an `int`) and
    `description`. The ordinal is the index, which fixes iteration order: order
    does not affect the COUNTS, but it decides WHICH concrete row survives when a
    loose key has a mix of in-window and out-of-window survivors, and two Kotlin
    tests assert on the survivor's identity.
    """
    strict_pool: Counter[tuple[date, int, str]] = Counter()
    loose_pool: Counter[tuple[date, int]] = Counter()
    for row in existing:
        normalized = normalize_description(row.description)
        strict_pool[(row.txn_date, row.amount_cents, normalized)] += 1
        loose_pool[(row.txn_date, row.amount_cents)] += 1

    # PASS ONE: exact matches, with NO window condition. Runs first and
    # completely, so a row that CAN be matched precisely never spends a loose
    # credit some other row needs. Fusing the passes changes behaviour; a Kotlin
    # test pins exactly that.
    survivors: list[int] = []
    for ordinal, row in enumerate(incoming):
        txn_date = row["txn_date"]
        amount = row["amount_cents"]
        strict_key = (txn_date, amount, normalize_description(row.get("description")))
        loose_key = (txn_date, amount)
        if strict_pool[strict_key] > 0:
            # Consume one strict credit AND one loose credit: both passes draw on
            # the same pool of existing rows, so one committed row absorbs
            # exactly one incoming row, never two.
            strict_pool[strict_key] -= 1
            loose_pool[loose_key] = max(loose_pool[loose_key] - 1, 0)
        else:
            survivors.append(ordinal)

    # PASS TWO: the loose relaxation, gated on the window. With no windows this
    # degenerates to "insert every survivor", which is the behaviour outside a
    # covered span.
    out: list[int] = []
    restatements = 0
    for ordinal in survivors:
        row = incoming[ordinal]
        txn_date = row["txn_date"]
        loose_key = (txn_date, row["amount_cents"])
        in_window = any(start <= txn_date <= end for start, end in windows)
        if in_window and loose_pool[loose_key] > 0:
            loose_pool[loose_key] -= 1
            restatements += 1
        else:
            out.append(ordinal)

    return DedupResult(
        insert_ordinals=out,
        # Computed by subtraction, exactly as the SQL and the Kotlin do, so every
        # non-inserted row counts once.
        duplicates_skipped=len(incoming) - len(out),
        restatements_skipped=restatements,
    )
