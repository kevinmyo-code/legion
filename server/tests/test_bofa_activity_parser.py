"""backend-etl ticket 09: BofA's activity-CSV readers, with no database.

Fixtures in `tests/bofa_fixtures/` are copies of the phone's
`app/src/test/resources/ledger_fixtures/` CSVs. The two checking files were
written SYNTHETIC (commit 2d188e7); the card file came in through the scrub
commit 942ce15. Its signs (payment negative, purchase positive) are not the
real export's: ticket 12 read the real file as 38 negative debits and 2
positive credits, which is why nothing here is negated (see the module doc of
`ingest/parsers/bofa_activity.py`).
"""
from __future__ import annotations

from pathlib import Path

import pytest

from ingest import statements
from ingest.parsers import bofa_activity
from ingest.parsers.bofa_activity import CARD, CHECKING

FIXTURES = Path(__file__).parent / "bofa_fixtures"
CARD_NAME = "currentTransaction_7823.csv"
CHECKING_NAME = "bofa_1000_activity_2026-08-03.csv"
CARD_HEADER = bofa_activity.CARD_HEADER


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def card(*rows: str, eol: str = "\r\n") -> bytes:
    return eol.join([CARD_HEADER, *rows, ""]).encode()


def checking(
    *,
    beginning='"-6.31"',
    credits='"2,430.00"',
    debits='"-203.08"',
    ending='"2,220.61"',
    between=("",),
    rows=None,
    after=(),
) -> bytes:
    table = rows if rows is not None else [
        '07/01/2026,Beginning balance as of 07/01/2026,,"-6.31"',
        '07/02/2026,"EMPLOYER PAYROLL","2,430.00","2,423.69"',
        '07/21/2026,"CHECKCARD 0721 GROCER","-203.08","2,220.61"',
    ]
    lines = [
        "Description,,Summary Amt.",
        f"Beginning balance as of 07/01/2026,,{beginning}",
        f"Total credits,,{credits}",
        f"Total debits,,{debits}",
        f"Ending balance as of 08/03/2026,,{ending}",
        *between,
        "Date,Description,Amount,Running Bal.",
        *table,
        *after,
        "",
    ]
    return "\r\n".join(lines).encode()


def refused(parser, content: bytes, name: str) -> str:
    with pytest.raises(statements.ParserRefused) as caught:
        parser.parse(content, file_name=name)
    reason = str(caught.value)
    assert reason.endswith("Nothing was written.")
    return reason


# =============================================================================
# Card: provisional
# =============================================================================


def test_the_real_card_fixture_reads_as_provisional_rows_signs_as_printed():
    parsed = CARD.parse(fixture(CARD_NAME), file_name=CARD_NAME)
    assert parsed is not None and parsed.provisional is True
    payload = parsed.payload
    assert payload["account_last4"] == "7823"
    assert payload["account_nickname"] == "BofA card"
    assert payload["currency"] == "USD"
    # Rule 7: nothing to anchor on, and nothing is invented.
    for anchor in ("stated_total_cents", "opening_balance_cents", "closing_balance_cents"):
        assert anchor not in payload
    assert payload["lines"] == [
        {"txn_date": "2026-06-09", "description": "PAYMENT - THANK YOU", "amount_cents": -150000},
        {"txn_date": "2026-06-13", "description": "NORTHWIND OUTFITTERS", "amount_cents": 60000},
    ]


def test_lf_crlf_and_a_byte_order_mark_read_the_same():
    rows = ("09/01/2026,1,COFFEE,,-4.50", '09/02/2026,2,"SHOP, INC",,-12.00')
    crlf = CARD.parse(card(*rows), file_name=CARD_NAME).payload
    lf = CARD.parse(card(*rows, eol="\n"), file_name=CARD_NAME).payload
    bom = CARD.parse(b"\xef\xbb\xbf" + card(*rows), file_name=CARD_NAME).payload
    assert crlf == lf == bom
    assert crlf["lines"][1]["description"] == "SHOP, INC"


@pytest.mark.parametrize(
    "name",
    [
        "currentTransaction_7823.csv",
        "CURRENTTRANSACTION_7823.CSV",
        "currentTransaction_7823 (1).csv",
        "bofa_7823_activity_2026-09-27.csv",
    ],
)
def test_the_account_comes_from_the_file_name(name):
    parsed = CARD.parse(card("09/01/2026,1,COFFEE,,-4.50"), file_name=name)
    assert parsed.payload["account_last4"] == "7823"


@pytest.mark.parametrize("name", ["activity.csv", "currentTransaction.csv", "bofa_78_activity.csv"])
def test_a_name_that_states_no_account_is_refused_never_guessed(name):
    reason = refused(CARD, card("09/01/2026,1,COFFEE,,-4.50"), name)
    assert "prints no account number" in reason


def test_a_header_with_no_rows_is_refused():
    assert "no transaction rows" in refused(CARD, card(), CARD_NAME)


@pytest.mark.parametrize(
    ("row", "says"),
    [
        ("09/01/2026,1,COFFEE,-4.50", "has 4 columns"),
        ("09/01/2026,1,COFFEE,,,-4.50", "has 6 columns"),
        ("09/01/2026,1,  ,,-4.50", "no merchant name"),
        ("09/01/2026,1,COFFEE,,", "no amount"),
        ("09/01/2026,1,COFFEE,,-4.5", "not an exact amount"),
        ("09/01/2026,1,COFFEE,,4.50 CR", "not an exact amount"),
        ("2026-09-01,1,COFFEE,,-4.50", "not a date"),
        ("", "has 0 columns"),
    ],
)
def test_one_row_that_does_not_parse_refuses_the_whole_file(row, says):
    """Section 4 rule 6: never a skip. The bad row is second, after a good
    one, so a reader that skipped it would have returned one row."""
    reason = refused(CARD, card("09/01/2026,1,GOOD,,-1.00", row, "09/03/2026,3,GOOD,,-1.00"),
                     CARD_NAME)
    assert says in reason
    assert "row 2" in reason.lower()
    assert "COFFEE" not in reason  # a reason never quotes the file


def test_a_malformed_quote_is_refused():
    reason = refused(CARD, card('09/01/2026,1,"COFFEE,,-4.50'), CARD_NAME)
    assert "Row 1" in reason


# =============================================================================
# Checking: gated
# =============================================================================


def test_the_real_checking_fixture_reads_as_a_gated_statement_with_both_balances():
    parsed = CHECKING.parse(fixture("bofa_csv_happy_path.csv"), file_name=CHECKING_NAME)
    assert parsed is not None and parsed.provisional is False
    payload = parsed.payload
    assert payload["account_last4"] == "1000"
    assert payload["account_nickname"] == "BofA checking"
    # Rule 8: the two printed balances are the anchors; the export prints no
    # single total, so that one is absent (None), never sum(lines).
    assert payload["stated_total_cents"] is None
    assert payload["opening_balance_cents"] == -631
    assert payload["closing_balance_cents"] == 222061
    assert (payload["period_start"], payload["period_end"]) == ("2026-07-01", "2026-08-03")
    lines = payload["lines"]
    # The beginning-balance row is the running balance's start, not a row.
    assert len(lines) == 7
    assert [line["amount_cents"] for line in lines] == [
        3000, 240000, -899, -495, -4574, -12840, -1500
    ]
    assert lines[-1]["balance_cents"] == 222061
    assert sum(line["amount_cents"] for line in lines) == 222061 - (-631)


def test_the_balance_mismatch_fixture_is_refused_with_the_figures():
    reason = refused(CHECKING, fixture("bofa_csv_balance_mismatch.csv"), CHECKING_NAME)
    assert "do not tie out" in reason
    assert "$9,999.99" in reason and "$2,220.61" in reason


def test_a_well_formed_checking_export_passes():
    parsed = CHECKING.parse(checking(), file_name=CHECKING_NAME)
    assert [line["amount_cents"] for line in parsed.payload["lines"]] == [243000, -20308]


def test_a_running_balance_that_breaks_is_refused():
    rows = [
        '07/01/2026,Beginning balance as of 07/01/2026,,"-6.31"',
        '07/02/2026,"EMPLOYER PAYROLL","2,430.00","2,423.70"',
        '07/21/2026,"CHECKCARD 0721 GROCER","-203.08","2,220.61"',
    ]
    reason = refused(CHECKING, checking(rows=rows), CHECKING_NAME)
    assert "Transaction row 1" in reason and "running balance" in reason


def test_printed_credit_and_debit_totals_are_checked():
    reason = refused(CHECKING, checking(credits='"2,431.00"'), CHECKING_NAME)
    assert "credits" in reason and "debits" not in reason


def test_the_table_must_start_at_the_summary_beginning_balance():
    rows = [
        '07/01/2026,Beginning balance as of 07/01/2026,,"-6.30"',
        '07/02/2026,"EMPLOYER PAYROLL","2,430.00","2,423.70"',
        '07/21/2026,"CHECKCARD 0721 GROCER","-203.08","2,220.62"',
    ]
    assert "table starts at" in refused(CHECKING, checking(rows=rows), CHECKING_NAME)


@pytest.mark.parametrize(
    "kwargs",
    [
        {"between": ("", "some note")},
        {"after": ("", '07/30/2026,"LATE ROW","-1.00","2,219.61"')},
        {"after": ("Total,,,",)},
    ],
)
def test_a_line_nobody_reads_refuses_the_file(kwargs):
    """The Kotlin skipped anything between the summary and the table, and
    stopped reading at the first blank line in the table. Rule 6: refused."""
    refused(CHECKING, checking(**kwargs), CHECKING_NAME)


def test_a_checking_name_that_states_no_account_is_refused_after_the_numbers():
    assert "checking activity export" in refused(CHECKING, checking(), "stmt.csv")
    # Numbers first: a broken file says so even under a bad name.
    assert "tie out" not in refused(CHECKING, checking(), "stmt.csv")
    assert "credits" in refused(CHECKING, checking(credits='"1.00"'), "stmt.csv")


def test_a_summary_row_out_of_place_is_refused():
    content = checking().replace(b"Total credits", b"Total deposits")
    assert "Summary row 2" in refused(CHECKING, content, CHECKING_NAME)


# =============================================================================
# Recognition, and the registry
# =============================================================================


@pytest.mark.parametrize(
    "content",
    [
        b"Date,Description,Amount\n09/01/2026,COFFEE,-4.50\n",
        b"a,b\n1,2\n",
        b"",
        b"%PDF-1.7 \xff\xfe binary",
        # The checking table header on its own, no summary block: not a
        # layout this reads, so quarantined upstream, never gated on a
        # balance nobody printed.
        b"Date,Description,Amount,Running Bal.\n09/01/2026,COFFEE,-4.50,10.00\n",
    ],
)
def test_anything_else_is_not_recognised_by_either_reader(content):
    assert CARD.parse(content, file_name=CARD_NAME) is None
    assert CHECKING.parse(content, file_name=CHECKING_NAME) is None


def test_each_reader_leaves_the_other_layout_alone():
    assert CARD.parse(checking(), file_name=CHECKING_NAME) is None
    assert CHECKING.parse(card("09/01/2026,1,COFFEE,,-4.50"), file_name=CARD_NAME) is None


def test_both_readers_are_csv_only_and_the_writer_is_registered():
    names = {p.name: p.kinds for p in statements.PARSERS}
    assert names["bofa-card-activity-csv"] == frozenset({"csv"})
    assert names["bofa-checking-activity-csv"] == frozenset({"csv"})
    from ingest.provisional import commit_provisional

    assert statements.provisional_writer() is commit_provisional


def test_registering_twice_adds_nothing(monkeypatch):
    monkeypatch.setattr(statements, "PARSERS", [])
    monkeypatch.setattr(statements, "_PROVISIONAL_WRITER", [])
    bofa_activity.register()
    bofa_activity.register()
    assert [p.name for p in statements.PARSERS] == [
        "bofa-card-activity-csv",
        "bofa-checking-activity-csv",
    ]
