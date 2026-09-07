"""Loader for the SHARED gate corpus, `app/src/test/resources/gate-corpus.json`.

Not a test module - `test_gate_corpus.py` is. This is here so the payload
mapping lives in exactly one place on the Python side.

## The corpus is shared, and that is the whole point

`20260825000700_commit_receipt_rpc.sql`'s own header: "ticket 03 ruling 2
accepted two implementations of the same gate on the condition that a shared
test corpus proves they agree; that corpus is the thing that makes this
duplication safe rather than merely duplicated." There are three readers of that
one file now:

    app/src/test/java/com/kevin/legion/ledger/GateCorpusTest.kt   the Kotlin side
    tools/gate_corpus_sql.py                                      emits SQL for the RPC side
    tests/gate_corpus.py + tests/test_gate_corpus.py              this port

A case can only pass all three by being right. Until django-engine ticket 10 the
SQL still runs against the live database, so **a divergence is a bug in this
port, never a corpus edit.**

## The payload mapping is copied from `tools/gate_corpus_sql.py` deliberately

Same constant account, same constant store, same synthetic `content_sha256`,
same `line_ref`, same `.get()`-not-`[...]` for `stated_total_cents` so a JSON
null passes straight through to exercise the null branch, and the same rule that
a case with no `provenance` key defaults to `DETERMINISTIC` (the two sides must
agree on what an ABSENT key means, not only on what an explicit one means).
If that tool's mapping changes, this one changes with it, or the two sides stop
sending the same request and the comparison stops meaning anything.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

# `server/tests/gate_corpus.py` -> repo root -> the Kotlin test resources. The
# corpus deliberately does NOT get a copy under `server/`: two copies of a file
# whose entire job is to be a single shared source would defeat it.
CORPUS_PATH = (
    Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources" / "gate-corpus.json"
)


def load_corpus() -> dict[str, Any]:
    """Reads the corpus, or fails loudly.

    **Never a `pytest.skip`.** A corpus that quietly loaded nothing and reported
    green would be section 4 rule 6's own failure shape - "a check that passes
    when nothing parsed is not a gate" - pointed at the test suite instead of at
    a bank statement. `test_gate_corpus.py` asserts the case counts for the same
    reason.
    """
    if not CORPUS_PATH.exists():
        raise FileNotFoundError(
            f"The shared gate corpus is missing at {CORPUS_PATH}. This port is checked against "
            f"it and against nothing else; a run without it proves nothing."
        )
    with CORPUS_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def ledger_payload(index: int, case: dict[str, Any]) -> dict[str, Any]:
    """One ledger case as the `commit_statement` payload, byte-for-byte the
    shape `tools/gate_corpus_sql.py` builds for the SQL side."""
    return {
        "content_sha256": f"corpus-ledger-{index}",
        "account_last4": "1234",
        "account_nickname": "corpus",
        "currency": "USD",
        "provenance": case.get("provenance", "DETERMINISTIC"),
        "stated_total_cents": case.get("stated_total_cents"),
        "opening_balance_cents": case["opening_balance_cents"],
        "closing_balance_cents": case["closing_balance_cents"],
        "lines": [
            {
                "txn_date": line["txn_date"],
                "description": line["description"],
                "amount_cents": line["amount_cents"],
                "line_ref": f"corpus-{index}-{n}",
            }
            for n, line in enumerate(case["lines"])
        ],
    }


def receipt_payload(index: int, case: dict[str, Any]) -> dict[str, Any]:
    """One pantry case as the `commit_receipt` payload. The `None`-dropping at
    the end is the SQL tool's own line, kept: every absent key is omitted except
    `subtotal_cents`, which is sent as an explicit null because "the receipt
    printed no subtotal" is the case that collapses the two anchors into one and
    has to reach the gate as a real state rather than a missing key."""
    payload = {
        "content_sha256": f"corpus-pantry-{index}",
        "store": "CORPUS",
        "purchase_date": "2026-07-15",
        "currency": "SGD",
        "provenance": "LLM_RECONCILED",
        "total_cents": case["total_cents"],
        "subtotal_cents": case.get("subtotal_cents"),
        "tax_cents": case.get("tax_cents"),
        "other_charges_cents": case.get("other_charges_cents"),
        "items": case["items"],
    }
    return {k: v for k, v in payload.items() if v is not None or k == "subtotal_cents"}


def case_id(index: int, case: dict[str, Any]) -> str:
    """A readable pytest parameter id, so a failure names the case the corpus
    names it rather than an index."""
    return f"{index}-{case['name']}"
