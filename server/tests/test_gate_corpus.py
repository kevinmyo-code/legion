"""The shared gate corpus, run against this port - twice.

Once against the pure functions in `ingest/gate.py`, with no database anywhere
near it, and once end to end through `POST /api/ingest/statement` and
`POST /api/ingest/receipt`. Both matter and neither replaces the other: the pure
run pins the arithmetic, and the HTTP run pins that the view actually calls it
and turns its verdict into the right outcome.

`tools/gate_corpus_sql.py` emits the identical cases for the SQL side, which is
still live until django-engine ticket 10. **A divergence between the two is a
bug in this port, never a corpus edit.**

Only `outcome` is asserted per case here, matching what the SQL script asserts.
`inserted`, the dedup counters and the quarantine wording are pinned in
`test_ingest_api.py` and `test_dedup.py`, where the surrounding state is
controlled instead of accumulating across seventeen cases.
"""
from __future__ import annotations

import pytest

from ingest import gate
from tests.gate_corpus import case_id, ledger_payload, load_corpus, receipt_payload

CORPUS = load_corpus()
LEDGER_CASES = list(enumerate(CORPUS["ledger"]))
PANTRY_CASES = list(enumerate(CORPUS["pantry"]))


def test_the_corpus_actually_loaded_cases():
    """Section 4 rule 6's own shape, pointed at this file: a check that passes
    when nothing parsed is not a check. Every other test below is parametrized
    from these two lists, so an empty corpus would collect zero tests and report
    a green run that proved nothing.

    The counts are asserted as lower bounds, not equalities - a corpus that
    GROWS is the intended way to add a case, and pinning an exact number would
    make adding one a two-file edit for no benefit.
    """
    assert len(LEDGER_CASES) >= 10, "the ledger corpus lost cases"
    assert len(PANTRY_CASES) >= 7, "the pantry corpus lost cases"


@pytest.mark.parametrize(
    ("index", "case"), LEDGER_CASES, ids=[case_id(i, c) for i, c in LEDGER_CASES]
)
def test_ledger_case_against_the_pure_gate(index, case):
    payload = ledger_payload(index, case)
    verdict = gate.check_statement(
        lines=payload["lines"],
        stated_total_cents=payload["stated_total_cents"],
        opening_balance_cents=payload["opening_balance_cents"],
        closing_balance_cents=payload["closing_balance_cents"],
        provenance=payload["provenance"],
    )
    assert verdict.outcome == case["expect"], case["why"]
    # A quarantine without a sentence is a section 7 failure ("a failure says in
    # words what did not happen"), so the corpus checks the shape of every
    # refusal even where it does not pin the wording.
    if verdict.outcome == gate.QUARANTINED:
        assert verdict.reason


@pytest.mark.parametrize(
    ("index", "case"), PANTRY_CASES, ids=[case_id(i, c) for i, c in PANTRY_CASES]
)
def test_pantry_case_against_the_pure_gate(index, case):
    payload = receipt_payload(index, case)
    verdict = gate.check_receipt(
        items=payload["items"],
        total_cents=payload["total_cents"],
        subtotal_cents=payload.get("subtotal_cents"),
        tax_cents=payload.get("tax_cents"),
        other_charges_cents=payload.get("other_charges_cents"),
    )
    assert verdict.outcome == case["expect"], case["why"]
    if verdict.outcome == gate.QUARANTINED:
        assert verdict.reason


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("index", "case"), LEDGER_CASES, ids=[case_id(i, c) for i, c in LEDGER_CASES]
)
def test_ledger_case_end_to_end(auth_client, index, case):
    response = auth_client.post(
        "/api/ingest/statement", ledger_payload(index, case), format="json"
    )
    assert response.status_code in (200, 201), response.data
    assert response.data["outcome"] == case["expect"], case["why"]


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("index", "case"), PANTRY_CASES, ids=[case_id(i, c) for i, c in PANTRY_CASES]
)
def test_pantry_case_end_to_end(auth_client, index, case):
    response = auth_client.post("/api/ingest/receipt", receipt_payload(index, case), format="json")
    assert response.status_code in (200, 201), response.data
    assert response.data["outcome"] == case["expect"], case["why"]
