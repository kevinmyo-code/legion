"""The section 4 reconciliation gate, in Python. Pure functions, no ORM, no
Django imports - so the arithmetic that decides whether money is written can be
read, tested and reasoned about without a database anywhere near it.

## Why this file exists rather than the plpgsql it mirrors

`public.commit_statement` and `public.commit_receipt` have run this exact
arithmetic in SQL since 2026-08-25. ADR 0044 decision 2: "The gate moves
language, not posture. Section 4 rules 1 to 8 bind the Python implementation
exactly as they bound the plpgsql. Same request, same response, same corpus."

The argument that decides it over and above the ADR: **a gate left in plpgsql
has no owner once `supabase/migrations/` stops being the migration directory**
(ADR 0044 decision 1 makes `server/*/migrations/` the only migration owner and
names the four integrity rules that stay in SQL - the gate is not one of them).
It is also untestable where it stands: `tests/legacy_test_schema.py` builds the
legacy tables into pytest's own database by hand, so leaving the gate in SQL
would mean mirroring four functions there too, kept in sync with a migration
directory that is being retired.

## The rule this file is a copy of, and how the copy is kept honest

Two implementations of one gate ran in parallel before this one - the Kotlin
pre-check on the phone and the SQL - and `20260825000700_commit_receipt_rpc.sql`
accepted that on one condition, in its own header: "ticket 03 ruling 2 accepted
two implementations of the same gate on the condition that a shared test corpus
proves they agree; that corpus is the thing that makes this duplication safe
rather than merely duplicated." This module is a THIRD reader of that same
corpus (`app/src/test/resources/gate-corpus.json`), never a fourth fixture set;
`tests/test_gate_corpus.py` is the reader. Until django-engine ticket 10 the SQL
still runs against the live database, so both sides are live: a divergence is a
bug in this port, never a corpus edit.

Every branch below quotes the SQL line it mirrors, as django-engine ticket 03
requires by name.

## What is NOT here, said so its absence is not mistaken for coverage

- **Rule 7 provisional ingestion.** Neither commit path can write an
  `UNRECONCILED` row and neither ever could: `statements_not_provisional` is
  `check (provenance <> 'UNRECONCILED')`, `ledger_txn_header_matches_provenance`
  requires `statement_id is null` on a provisional line (so a provisional import
  has no header at all, which is what `public.statements`' own table comment
  says), and `receipts_not_provisional` as narrowed by
  `20260826000500_receipts_allow_unreconciled.sql` permits `UNRECONCILED` only
  when `unaccounted_cents is not null`, which `commit_receipt` never writes. The
  honest behaviour of these two paths for a document that states no anchor is a
  QUARANTINE with wording, which is what the no-stated-total branch below does.
  A provisional ingestion path is its own ticket,
  `.scratch/django-engine/issues/13-provisional-ingestion-has-no-endpoint.md`.
- **Extraction.** The caller supplies the lines. Server-side extraction is the
  eventual shape and waits on ticket 05 (media) and a ruling on where a
  user-owned server-side LLM key lives; ticket 03's status-detail says so.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

COMMITTED = "COMMITTED"
QUARANTINED = "QUARANTINED"
ALREADY_COMMITTED = "ALREADY_COMMITTED"

# `public.provenance`'s four values (20260825000200_conventions.sql). Named here
# rather than imported from `legacy.enums` so this module stays free of Django.
DETERMINISTIC = "DETERMINISTIC"
LLM_RECONCILED = "LLM_RECONCILED"
UNRECONCILED = "UNRECONCILED"
USER = "USER"

# Rule 6's two refusals, verbatim from the plpgsql. Constants rather than inline
# literals because the tests assert the SENTENCE, not merely the outcome - a
# quarantine nobody can read is a section 7 failure ("a failure says in words
# what did not happen") waiting to happen on whatever surface renders it.
EMPTY_STATEMENT_REASON = (
    "No transactions were extracted from this file. An empty extraction can never "
    "satisfy the gate, whatever the stated totals are."
)
EMPTY_RECEIPT_REASON = (
    "No line items were extracted from this receipt. An empty extraction can never "
    "satisfy the gate, whatever the printed total says."
)
# The 2026-08-27 amendment's scope guard. This is also the sentence a document
# stating NO anchor comes back with, which is why it is worth reading twice: it
# says nothing was written, and it says why, in words.
NO_STATED_TOTAL_REASON = (
    "This statement states no printed total, and only a deterministically "
    "parsed statement can qualify without one. Nothing was written."
)


class GateInputError(ValueError):
    """A payload this gate cannot even evaluate - a missing required figure, or
    a money field that is not an integer number of cents.

    Deliberately NOT a quarantine. A quarantine is a VERDICT: the gate ran, the
    arithmetic did not tie, and the file is recorded as refused with a reason.
    This is the caller having sent something the gate cannot run on at all, and
    the SQL treats it the same way - `raise exception ... using errcode =
    'invalid_parameter_value'`, which rolls back rather than recording a
    verdict. Collapsing the two would let a malformed payload masquerade as a
    document that failed its own arithmetic.
    """


@dataclass(frozen=True)
class Verdict:
    """`COMMITTED` or `QUARANTINED`, plus the reason on a refusal.

    `reason` is None exactly when `outcome == COMMITTED`. The gate never returns
    a bare boolean: section 4 rule 2 refuses the whole document, and section 7
    says a failure states in words what did not happen, so the sentence travels
    with the verdict rather than being reconstructed by whatever renders it.
    """

    outcome: str
    reason: str | None = None

    @property
    def committed(self) -> bool:
        return self.outcome == COMMITTED


def cents(value: object, field: str) -> int:
    """Coerces one money field to an integer number of cents, or refuses.

    Mirrors the SQL's `(payload ->> 'x')::bigint`, which takes an integer and a
    string of digits and rejects anything with a decimal point - `'450.0'::bigint`
    is an error in Postgres, not a silent truncation.

    **A float is refused outright**, and that is CLAUDE.md section 4 rule 3
    rather than fussiness: the gate is exact equality, and binary floating point
    cannot represent every decimal cent, so admitting one figure as a float
    would make the anchor's own arithmetic approximate. `bool` is refused for
    the reason it usually is in Python - it is an `int` subclass, and `True` is
    not one cent.
    """
    if value is None:
        raise GateInputError(f"{field} is required and was not supplied.")
    if isinstance(value, bool):
        raise GateInputError(f"{field} must be an integer number of cents, not a boolean.")
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError as exc:
            raise GateInputError(
                f"{field} must be an integer number of cents; got {value!r}."
            ) from exc
    raise GateInputError(
        f"{field} must be an integer number of cents, never a float or a decimal; "
        f"got {type(value).__name__} {value!r}."
    )


def optional_cents(value: object, field: str) -> int | None:
    """The nullable sibling of [cents]. Returns None for an absent, JSON-null or
    blank field - the SQL's `nullif(payload ->> 'x', '')::bigint`.

    **None means the document did not print this figure. It never means zero**,
    which is section 4 rule 8's own sentence: "An anchor the source did not
    state is stored NULL and recorded as absent - never synthesised." The
    arithmetic coalesces an absent tax to 0 to add it up; the STORAGE keeps the
    null. Those are two different things and this function is where they part
    company.
    """
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None
    return cents(value, field)


def _sum_amounts(lines: Sequence[Mapping[str, object]]) -> int:
    """`select coalesce(sum((line ->> 'amount_cents')::bigint), 0) from
    jsonb_array_elements(v_lines)`."""
    return sum(cents(line.get("amount_cents"), "amount_cents") for line in lines)


def check_statement(
    *,
    lines: Sequence[Mapping[str, object]],
    stated_total_cents: int | None,
    opening_balance_cents: int,
    closing_balance_cents: int,
    provenance: str,
) -> Verdict:
    """Ledger's gate: rule 6, then anchor 1 (the stated total, branched on
    provenance), then anchor 2 (the balance delta, unconditional).

    Mirrors `commit_statement` as replaced by
    `20260827000300_commit_statement_deterministic_two_anchor.sql`, which is the
    live definition - not the original `20260825000600` three-anchor version.

    All three anchors are parameters and the caller persists every one of them
    (section 4 rule 8). `stated_total_cents=None` is a real state, not a missing
    argument: a bank that prints no combined total. It is stored NULL, never
    synthesised from `sum(lines)`, which would turn the check into an identity -
    rule 6's failure shape.
    """
    line_count = len(lines)

    # `if v_line_count = 0 then ... perform private.quarantine_file(...)`
    #
    # Rule 6, and it comes first on purpose. A zero-line extraction sails
    # through every anchor below whenever the statement's own figures happen to
    # be zero: sum() of no rows is 0, and a month with no movement has
    # closing = opening. That is not hypothetical - it is how BofA's card
    # statement passed with four silently dropped interest rows, and it only
    # held because interest was zero that month.
    if line_count == 0:
        return Verdict(QUARANTINED, EMPTY_STATEMENT_REASON)

    total = _sum_amounts(lines)
    opening = cents(opening_balance_cents, "opening_balance_cents")
    closing = cents(closing_balance_cents, "closing_balance_cents")

    # `if v_stated_total is null then / if v_provenance <> 'DETERMINISTIC' then`
    #
    # ANCHOR 1, branched three ways exactly as the SQL branches it:
    #  - no stated total AND not DETERMINISTIC: quarantine outright. This is the
    #    2026-08-27 amendment's scope guard. An LLM-produced payload missing its
    #    printed total is the unverifiable shape section 4 rule 1's amendment
    #    exists to refuse, and the two-anchor allowance never applies to it.
    #  - no stated total AND DETERMINISTIC: the anchor does not exist to check
    #    (no bank format Kevin owns prints one combined total), so it is skipped
    #    rather than faked, and anchor 2 below still has to hold. Two anchors is
    #    a floor, not a discount.
    #  - stated total present: checked for every provenance, DETERMINISTIC
    #    included. A statement that DOES print a total is still held to it.
    if stated_total_cents is None:
        if provenance != DETERMINISTIC:
            return Verdict(QUARANTINED, NO_STATED_TOTAL_REASON)
    else:
        stated = cents(stated_total_cents, "stated_total_cents")
        # `elsif v_sum <> v_stated_total then`
        if total != stated:
            return Verdict(
                QUARANTINED,
                f"Lines sum to {total} cents but the statement states a total of "
                f"{stated} cents. Nothing was written.",
            )

    # `if (v_closing - v_opening) <> v_sum then`
    #
    # ANCHOR 2, required unconditionally, for every provenance and whether or
    # not anchor 1 ran. A DETERMINISTIC statement with no printed total and no
    # real opening/closing pair has ZERO anchors and must still fail here.
    delta = closing - opening
    if delta != total:
        return Verdict(
            QUARANTINED,
            f"Closing balance minus opening balance is {delta} cents but the lines "
            f"sum to {total} cents. Nothing was written.",
        )

    return Verdict(COMMITTED)


def check_receipt(
    *,
    items: Sequence[Mapping[str, object]],
    total_cents: int,
    subtotal_cents: int | None,
    tax_cents: int | None,
    other_charges_cents: int | None,
) -> Verdict:
    """Pantry's gate: rule 6, then two anchors that collapse into one when the
    receipt prints no subtotal.

    Mirrors `commit_receipt` (`20260825000700_commit_receipt_rpc.sql`), which is
    itself a line-by-line mirror of `pantry/PantryReceiptAgent.kt`'s
    `reconciliationFailure`.

    **The macro estimates are not parameters of this function at all**, and that
    is section 4 rule 5 enforced by shape rather than by discipline: a receipt
    never prints calories, so `estimated_calories_kcal` and its three siblings
    cannot be gated and must never read as fact. A function that cannot see them
    cannot accidentally sum them.

    `tax_cents=None` and `other_charges_cents=None` mean the receipt printed no
    such line. They count as 0 in the arithmetic here (`coalesce(..., 0)` in the
    SQL) and the caller stores them NULL - see [optional_cents] for why those two
    facts must not be collapsed.
    """
    item_count = len(items)

    # `if v_item_count = 0 then` - rule 6, before any arithmetic, for the same
    # reason as in check_statement: with zero items the anchors below are
    # satisfiable by a receipt whose figures happen to be zero.
    if item_count == 0:
        return Verdict(QUARANTINED, EMPTY_RECEIPT_REASON)

    # `select coalesce(sum((item ->> 'total_price_cents')::bigint), 0)`
    items_total = sum(cents(item.get("total_price_cents"), "total_price_cents") for item in items)
    total = cents(total_cents, "total_cents")
    # `v_tax := coalesce(nullif(payload ->> 'tax_cents', '')::bigint, 0)`
    tax = tax_cents if tax_cents is not None else 0
    other = other_charges_cents if other_charges_cents is not None else 0

    if subtotal_cents is not None:
        subtotal = cents(subtotal_cents, "subtotal_cents")
        # `if v_items_total <> v_subtotal then`
        # Anchor 1: the items are all of, and only, what the subtotal covers.
        if items_total != subtotal:
            return Verdict(
                QUARANTINED,
                f"The {item_count} extracted items come to {items_total} cents but the "
                f"receipt prints a subtotal of {subtotal} cents. Nothing was saved.",
            )

        # `v_computed := v_subtotal + v_tax + v_other; if v_computed <> v_total then`
        # Anchor 2: subtotal, tax and any other printed charge account for the
        # grand total exactly.
        computed = subtotal + tax + other
        if computed != total:
            return Verdict(
                QUARANTINED,
                f"The receipt's own figures do not tie out: subtotal {subtotal} plus tax "
                f"{tax} plus other {other} is {computed} cents, not the {total} cents it "
                f"prints as the total. Nothing was saved.",
            )
        return Verdict(COMMITTED)

    # `else` - no printed subtotal to split the check on, so the two anchors
    # collapse into one. Still a real gate: items plus every printed non-item
    # charge must account for the total exactly.
    computed = items_total + tax + other
    if computed != total:
        return Verdict(
            QUARANTINED,
            f"The {item_count} extracted items plus tax {tax} plus other {other} come to "
            f"{computed} cents, not the {total} cents the receipt prints as the total. "
            f"Nothing was saved.",
        )
    return Verdict(COMMITTED)
