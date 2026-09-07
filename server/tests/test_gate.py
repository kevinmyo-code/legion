"""`ingest/gate.py`'s money handling and its two failure MODES.

The corpus covers the arithmetic. This covers the two things underneath it that
the corpus cannot see, because a corpus case is always well formed:

1. **Money is integer cents** (section 4 rule 3), and a float is refused rather
   than rounded.
2. **A malformed payload is not a quarantine.** A quarantine is a verdict about
   a document's own arithmetic; a payload the gate cannot evaluate is a caller
   error. Collapsing them would let a bad request read as a failed document.
"""
from __future__ import annotations

import pytest

from ingest import gate


def test_an_integer_and_a_digit_string_are_both_cents():
    """Mirrors `(payload ->> 'x')::bigint`, which takes both - the RPC receives
    everything as jsonb text and casts, so a client sending "1242" and one
    sending 1242 must not get different answers."""
    assert gate.cents(1242, "total_cents") == 1242
    assert gate.cents("1242", "total_cents") == 1242
    assert gate.cents("-450", "amount_cents") == -450


def test_a_float_is_refused_rather_than_truncated():
    with pytest.raises(gate.GateInputError) as caught:
        gate.cents(12.42, "total_cents")

    assert "integer number of cents" in str(caught.value)


def test_a_decimal_string_is_refused():
    """`'450.0'::bigint` is an error in Postgres, not a silent truncation, and
    this port keeps that."""
    with pytest.raises(gate.GateInputError):
        gate.cents("450.0", "total_cents")


def test_a_boolean_is_refused():
    """`bool` is an `int` subclass in Python and `True` is not one cent. Postgres
    would never take a boolean for a bigint column; neither does this."""
    with pytest.raises(gate.GateInputError):
        gate.cents(True, "total_cents")


def test_a_missing_required_figure_is_refused_by_name():
    with pytest.raises(gate.GateInputError) as caught:
        gate.cents(None, "opening_balance_cents")

    assert "opening_balance_cents" in str(caught.value)


def test_absent_and_blank_are_none_but_zero_is_zero():
    """Section 4 rule 8's distinction, in the one function that draws it: an
    anchor the source did not state is NULL, never a synthesised 0. A printed
    zero tax and an unprinted tax are different facts and must stay different."""
    assert gate.optional_cents(None, "tax_cents") is None
    assert gate.optional_cents("", "tax_cents") is None
    assert gate.optional_cents("   ", "tax_cents") is None
    assert gate.optional_cents(0, "tax_cents") == 0


def test_a_committed_verdict_carries_no_reason_and_a_refusal_always_does():
    committed = gate.check_receipt(
        items=[{"total_price_cents": 1150}],
        total_cents=1242,
        subtotal_cents=1150,
        tax_cents=92,
        other_charges_cents=None,
    )
    refused = gate.check_receipt(
        items=[],
        total_cents=1242,
        subtotal_cents=1150,
        tax_cents=92,
        other_charges_cents=None,
    )

    assert committed.committed and committed.reason is None
    assert not refused.committed and refused.reason == gate.EMPTY_RECEIPT_REASON
