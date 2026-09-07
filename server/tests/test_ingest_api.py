"""`POST /api/ingest/statement` and `POST /api/ingest/receipt` - django-engine
ticket 03.

The corpus (`test_gate_corpus.py`) pins the arithmetic against the SQL the port
replaces. This file pins everything the corpus does not: that a refusal writes
NOTHING, that the refusal is a sentence a person can read, that the anchors are
retrievable from storage afterwards (section 4 rule 8), that the macro estimates
come back labelled (rule 5), and that a second post of the same file is a no-op.
"""
from __future__ import annotations

import uuid

import pytest
from django.db import DatabaseError, transaction

from ingest.gate import NO_STATED_TOTAL_REASON
from ingest.views import ESTIMATE_NOTE, PROVISIONAL_REFUSAL
from legacy.enums import IngestState, Provenance
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import LedgerTransaction, Statement
from legacy.models.pantry import Receipt, ReceiptLineItem

pytestmark = pytest.mark.django_db

RECEIPT_URL = "/api/ingest/receipt"
STATEMENT_URL = "/api/ingest/statement"


def a_receipt(**overrides):
    """A receipt that ties out: items 450 + 700 = 1150 subtotal, plus 92 tax =
    1242 total. Overrides are shallow, so a test that wants an off-by-one total
    changes one number and nothing else moves."""
    payload = {
        "content_sha256": "sha-receipt-ok",
        "store": "COLD STORAGE",
        "purchase_date": "2026-07-15",
        "currency": "SGD",
        "total_cents": 1242,
        "subtotal_cents": 1150,
        "tax_cents": 92,
        "items": [
            {
                "name": "MILK",
                "quantity": 1,
                "unit_price_cents": 450,
                "total_price_cents": 450,
                "estimated_calories_kcal": 620,
                "estimated_protein_g": 32,
            },
            {"name": "BREAD", "quantity": 2, "total_price_cents": 700},
        ],
    }
    payload.update(overrides)
    return payload


def a_statement(**overrides):
    """A three-anchor statement that ties out both ways: the lines sum to
    149550, which is also the stated total, and 649550 - 500000 is the same
    number."""
    payload = {
        "content_sha256": "sha-statement-ok",
        "account_last4": "1234",
        "account_nickname": "checking",
        "currency": "USD",
        "provenance": Provenance.DETERMINISTIC,
        "stated_total_cents": 149550,
        "opening_balance_cents": 500000,
        "closing_balance_cents": 649550,
        "lines": [
            {
                "txn_date": "2026-07-03",
                "description": "COFFEE",
                "amount_cents": -450,
                "line_ref": "l-0",
            },
            {
                "txn_date": "2026-07-11",
                "description": "SALARY",
                "amount_cents": 300000,
                "line_ref": "l-1",
            },
            {
                "txn_date": "2026-07-20",
                "description": "RENT",
                "amount_cents": -150000,
                "line_ref": "l-2",
            },
        ],
    }
    payload.update(overrides)
    return payload


# ---------------------------------------------------------------------------
# The gate commits
# ---------------------------------------------------------------------------


def test_a_receipt_that_ties_out_commits(auth_client):
    response = auth_client.post(RECEIPT_URL, a_receipt(), format="json")

    assert response.status_code == 201, response.data
    assert response.data["outcome"] == "COMMITTED"
    assert response.data["inserted"] == 2
    receipt = Receipt.objects.get(id=response.data["receipt_id"])
    assert ReceiptLineItem.objects.filter(receipt=receipt).count() == 2
    assert IngestedFile.objects.get(content_sha256="sha-receipt-ok").state == IngestState.INGESTED


def test_a_statement_that_ties_out_commits(auth_client):
    response = auth_client.post(STATEMENT_URL, a_statement(), format="json")

    assert response.status_code == 201, response.data
    assert response.data["outcome"] == "COMMITTED"
    assert response.data["inserted"] == 3
    statement = Statement.objects.get(id=response.data["statement_id"])
    assert LedgerTransaction.objects.filter(statement=statement).count() == 3


# ---------------------------------------------------------------------------
# The gate refuses, and refusing writes NOTHING
# ---------------------------------------------------------------------------


def test_a_receipt_off_by_one_cent_quarantines_and_writes_nothing(auth_client):
    """Section 4 rule 2: "Sum of line items equals the printed total, or the
    whole document quarantines. Nothing partial is ever written."

    One cent, because the gate is exact equality and a tolerance is the whole
    difference between a gate and a suggestion.
    """
    response = auth_client.post(RECEIPT_URL, a_receipt(total_cents=1243), format="json")

    assert response.status_code == 200
    assert response.data["outcome"] == "QUARANTINED"
    assert response.data["inserted"] == 0
    assert response.data["reason"] == (
        "The receipt's own figures do not tie out: subtotal 1150 plus tax 92 plus other 0 "
        "is 1242 cents, not the 1243 cents it prints as the total. Nothing was saved."
    )

    # Nothing partial. Not one row, not one line.
    assert Receipt.objects.count() == 0
    assert ReceiptLineItem.objects.count() == 0

    # But the refusal itself IS recorded - a quarantined document leaves a
    # reason and no data, or the file comes back on the next scan with no
    # memory of why it failed, forever.
    file_row = IngestedFile.objects.get(content_sha256="sha-receipt-ok")
    assert file_row.state == IngestState.QUARANTINED
    assert file_row.quarantine_reason == response.data["reason"]


def test_a_receipt_whose_items_miss_the_subtotal_quarantines(auth_client):
    response = auth_client.post(RECEIPT_URL, a_receipt(subtotal_cents=1300), format="json")

    assert response.data["outcome"] == "QUARANTINED"
    assert response.data["reason"] == (
        "The 2 extracted items come to 1150 cents but the receipt prints a subtotal of "
        "1300 cents. Nothing was saved."
    )
    assert Receipt.objects.count() == 0


def test_a_statement_whose_balances_disagree_quarantines_and_writes_nothing(auth_client):
    """Ticket 03's own verification list: "A payload whose lines sum to the total
    but whose opening and closing do not reconcile quarantines."

    Anchor 1 holds here and anchor 2 does not, which is the case a single-anchor
    gate would wave through.
    """
    response = auth_client.post(
        STATEMENT_URL, a_statement(closing_balance_cents=649551), format="json"
    )

    assert response.data["outcome"] == "QUARANTINED"
    assert response.data["reason"] == (
        "Closing balance minus opening balance is 149551 cents but the lines sum to "
        "149550 cents. Nothing was written."
    )
    assert Statement.objects.count() == 0
    assert LedgerTransaction.objects.count() == 0


def test_an_empty_extraction_quarantines_even_when_every_figure_is_zero(auth_client):
    """Section 4 rule 6. This is the BofA card statement that passed with four
    silently dropped interest rows, and only held because interest was zero that
    month."""
    response = auth_client.post(
        STATEMENT_URL,
        a_statement(
            lines=[],
            stated_total_cents=0,
            opening_balance_cents=0,
            closing_balance_cents=0,
        ),
        format="json",
    )

    assert response.data["outcome"] == "QUARANTINED"
    assert response.data["reason"] == (
        "No transactions were extracted from this file. An empty extraction can never "
        "satisfy the gate, whatever the stated totals are."
    )
    assert Statement.objects.count() == 0


def test_a_document_stating_no_anchor_is_quarantined_with_a_sentence(auth_client):
    """A source that states NO anchor cannot pass this endpoint, and the answer
    it gets is a quarantine that says why, in words.

    This test used to be specified as "stored UNRECONCILED and the response says
    so". It is not, and could not be: `statements_not_provisional` is
    `check (provenance <> 'UNRECONCILED')` and
    `ledger_txn_header_matches_provenance` requires a provisional row to have no
    statement at all, so a provisional import has no header and never comes
    through this path. Rule 7 ingestion is
    `.scratch/django-engine/issues/13-provisional-ingestion-has-no-endpoint.md`.
    What this path owes such a document is an honest refusal, which is what is
    asserted here.
    """
    response = auth_client.post(
        STATEMENT_URL,
        a_statement(provenance=Provenance.LLM_RECONCILED, stated_total_cents=None),
        format="json",
    )

    assert response.data["outcome"] == "QUARANTINED"
    assert response.data["reason"] == NO_STATED_TOTAL_REASON
    assert response.data["reason"] == (
        "This statement states no printed total, and only a deterministically "
        "parsed statement can qualify without one. Nothing was written."
    )
    assert Statement.objects.count() == 0
    assert IngestedFile.objects.get(content_sha256="sha-statement-ok").quarantine_reason


def test_a_deterministic_statement_with_no_printed_total_still_commits(auth_client):
    """The other half of the 2026-08-27 amendment, so the test above cannot be
    mistaken for "no stated total is always refused": a DETERMINISTIC statement
    is let off a number its bank never printed, and is held to the balance delta
    instead. Two anchors is a floor, not a discount."""
    response = auth_client.post(
        STATEMENT_URL,
        a_statement(provenance=Provenance.DETERMINISTIC, stated_total_cents=None),
        format="json",
    )

    assert response.status_code == 201, response.data
    assert response.data["outcome"] == "COMMITTED"


def test_a_provisional_payload_is_refused_in_words_and_writes_nothing(auth_client):
    """The `if v_provenance <> 'UNRECONCILED'` guard in `commit_statement` reads
    like a live branch and is not one - an UNRECONCILED payload reaching the
    header insert would trip `statements_not_provisional` and raise, rolling
    back its own quarantine row. This port refuses it up front instead."""
    response = auth_client.post(
        STATEMENT_URL, a_statement(provenance=Provenance.UNRECONCILED), format="json"
    )

    assert response.status_code == 400
    assert response.data["detail"] == PROVISIONAL_REFUSAL
    assert "provisional" in response.data["detail"]
    assert Statement.objects.count() == 0
    assert IngestedFile.objects.count() == 0


# ---------------------------------------------------------------------------
# Rule 8: the anchors are readable back from storage
# ---------------------------------------------------------------------------


def test_the_receipt_anchors_are_readable_back_from_storage(auth_client):
    """Section 4 rule 8: "every ingestion path stores the numbers the gate
    checked against, in their own columns, alongside the rows they gated."

    This is the rule three pantry receipts were lost to - their legacy table only
    ever had `totalCents`, the photos are gone, and the check cannot be
    reproduced from storage. Read back from the database, not from the response,
    because storage is what rule 8 is about.
    """
    response = auth_client.post(RECEIPT_URL, a_receipt(), format="json")

    stored = Receipt.objects.get(id=response.data["receipt_id"])
    assert stored.total_cents == 1242
    assert stored.subtotal_cents == 1150
    assert stored.tax_cents == 92
    assert stored.provenance == Provenance.LLM_RECONCILED
    # The response echoes them, so a caller does not need a second round trip.
    assert response.data["anchors"]["total_cents"] == 1242
    assert response.data["anchors"]["subtotal_cents"] == 1150


def test_an_absent_tax_is_stored_null_and_never_synthesised(auth_client):
    """Rule 8's sharpest clause: "An anchor the source did not state is stored
    NULL and recorded as absent - never synthesised from sum(lines), which would
    make the check an identity."

    Here the receipt prints no subtotal and no tax, so items alone must account
    for the total. Both columns must come back NULL, not 0: a stored 0 would
    claim the receipt printed a zero tax line, which it did not.
    """
    response = auth_client.post(
        RECEIPT_URL,
        a_receipt(total_cents=1150, subtotal_cents=None, tax_cents=None),
        format="json",
    )

    assert response.data["outcome"] == "COMMITTED", response.data
    stored = Receipt.objects.get(id=response.data["receipt_id"])
    assert stored.subtotal_cents is None
    assert stored.tax_cents is None
    assert stored.other_charges_cents is None
    assert stored.total_cents == 1150


def test_the_statement_anchors_are_readable_back_from_storage(auth_client):
    response = auth_client.post(STATEMENT_URL, a_statement(), format="json")

    stored = Statement.objects.get(id=response.data["statement_id"])
    assert stored.stated_total_cents == 149550
    assert stored.opening_balance_cents == 500000
    assert stored.closing_balance_cents == 649550
    assert response.data["anchors"]["opening_balance_cents"] == 500000


def test_a_deterministic_statement_stores_a_null_total_not_a_derived_one(auth_client):
    """The stored total must be NULL, never `sum(lines)`. Deriving it would make
    anchor 1 an identity - true by construction, checking nothing - which is
    rule 6's failure shape wearing rule 8's clothes."""
    response = auth_client.post(
        STATEMENT_URL, a_statement(stated_total_cents=None), format="json"
    )

    stored = Statement.objects.get(id=response.data["statement_id"])
    assert stored.stated_total_cents is None
    assert stored.opening_balance_cents == 500000
    assert stored.closing_balance_cents == 649550


# ---------------------------------------------------------------------------
# Rule 5: estimates are labelled as estimates
# ---------------------------------------------------------------------------


def test_the_macro_estimates_are_stored_and_labelled_an_estimate(auth_client):
    """Section 4 rule 5. A receipt never prints calories, so the macros cannot be
    gated and must never read as fact. They are stored (they are useful) and the
    response says in words what they are."""
    response = auth_client.post(RECEIPT_URL, a_receipt(), format="json")

    assert "estimate" in response.data["estimates"]["note"]
    assert response.data["estimates"]["note"] == ESTIMATE_NOTE
    assert response.data["estimates"]["items_carrying_an_estimate"] == 1
    assert "estimated_calories_kcal" in response.data["estimates"]["fields"]

    milk = ReceiptLineItem.objects.get(name="MILK")
    assert milk.estimated_calories_kcal == 620


def test_absurd_macro_estimates_cannot_change_the_verdict(auth_client):
    """The negative half of rule 5, and the reason `check_receipt` cannot see the
    macros at all: a million calories and a negative one both commit, because
    neither was ever part of the arithmetic."""
    payload = a_receipt()
    payload["items"][0]["estimated_calories_kcal"] = 999999
    payload["items"][1]["estimated_calories_kcal"] = -1

    response = auth_client.post(RECEIPT_URL, payload, format="json")

    assert response.data["outcome"] == "COMMITTED", response.data


# ---------------------------------------------------------------------------
# Idempotency, immutability, auth
# ---------------------------------------------------------------------------


def test_posting_the_same_receipt_twice_writes_nothing_the_second_time(auth_client):
    """Ticket 03 ruling 8's reason, not just its mechanism: the offline write
    queue is gone, so a network death after the commit but before the ack leaves
    the phone unable to tell success from failure - and the honesty clause is
    binary, with no vocabulary for "unknown". A repeat call being a successful
    no-op lets the phone retry until it gets a definite answer."""
    first = auth_client.post(RECEIPT_URL, a_receipt(), format="json")
    assert first.data["outcome"] == "COMMITTED"

    second = auth_client.post(RECEIPT_URL, a_receipt(), format="json")

    assert second.status_code == 200
    assert second.data["outcome"] == "ALREADY_COMMITTED"
    assert second.data["inserted"] == 0
    assert Receipt.objects.count() == 1
    assert ReceiptLineItem.objects.count() == 2


def test_posting_the_same_statement_twice_writes_nothing_the_second_time(auth_client):
    auth_client.post(STATEMENT_URL, a_statement(), format="json")
    second = auth_client.post(STATEMENT_URL, a_statement(), format="json")

    assert second.data["outcome"] == "ALREADY_COMMITTED"
    assert Statement.objects.count() == 1
    assert LedgerTransaction.objects.count() == 3


def test_a_committed_row_cannot_be_edited(auth_client):
    """What makes a committed row immutable is `private.forbid_mutation_of_facts`,
    a trigger, not this view - ADR 0044 decision 1 keeps that rule in SQL
    precisely because it must hold even when Django has a bug. Asserted here so
    the claim is tested rather than believed.

    The `atomic()` is a SAVEPOINT, not decoration: Postgres aborts the whole
    transaction on any statement error, so without it every assertion after this
    one would fail with "current transaction is aborted".
    """
    response = auth_client.post(RECEIPT_URL, a_receipt(), format="json")

    with pytest.raises(DatabaseError) as caught:
        with transaction.atomic():
            Receipt.objects.filter(id=response.data["receipt_id"]).update(store="EDITED")

    assert "immutable" in str(caught.value)
    assert Receipt.objects.get(id=response.data["receipt_id"]).store == "COLD STORAGE"


def test_ingest_requires_a_household_token(client):
    """The endpoint's own guard. `security invoker` has no analogue here, so the
    household token is what stands in its place."""
    assert client.post(RECEIPT_URL, a_receipt(), content_type="application/json").status_code == 401


# ---------------------------------------------------------------------------
# Rule 7 supersession, and money that is not an integer
# ---------------------------------------------------------------------------


def test_a_gated_statement_supersedes_provisional_rows_in_its_window(auth_client):
    """Rule 7's fourth condition: provisional rows are transient. "When a file
    that DID pass the gate commits over the same account and dates, the
    provisional rows in that window are deleted, so an unverified row can never
    outlive or double-count against the verified one that supersedes it."

    The provisional row is built directly rather than posted, because no endpoint
    can create one - which is the finding that made rule 7 ingestion its own
    ticket.
    """
    LedgerTransaction.objects.create(
        id=uuid.uuid4(),
        statement=None,
        account_last4="1234",
        account_nickname="checking",
        currency="USD",
        txn_date="2026-07-11",
        description="SALARY (voice-logged, unverified)",
        amount_cents=300000,
        line_ref="provisional-0",
        category_pending=True,
        provenance=Provenance.UNRECONCILED,
        created_at="2026-07-11T00:00:00Z",
    )

    response = auth_client.post(STATEMENT_URL, a_statement(), format="json")

    assert response.data["provisional_superseded"] == 1
    assert not LedgerTransaction.objects.filter(provenance=Provenance.UNRECONCILED).exists()
    assert LedgerTransaction.objects.count() == 3


def test_a_float_amount_is_refused_rather_than_rounded(auth_client):
    """CLAUDE.md section 4 rule 3, and rule 2's exactness resting on it. A float
    cent is refused at the door: the alternative is an anchor whose own
    arithmetic is approximate, and 0.1 + 0.2 is the reason."""
    payload = a_statement()
    payload["lines"][0]["amount_cents"] = -450.0

    response = auth_client.post(STATEMENT_URL, payload, format="json")

    assert response.status_code == 400
    assert "integer number of cents" in response.data["detail"]
    assert Statement.objects.count() == 0
    assert IngestedFile.objects.count() == 0
