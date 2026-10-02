"""backend-etl ticket 10: the BofA PDF parsers, golden against the Kotlin suite.

`tests/bofa_fixtures/*.pdf` are the phone's own fixtures
(`app/src/test/resources/ledger_fixtures/bofa_*.pdf`), copied byte for byte. The
`.txt` beside each is what `ingest.parsers.pdf_text.extract_text` makes of it;
the parser tests read the `.txt`, and `test_extraction_*` pins that the PDF still
extracts to exactly that text, so the parser is exercised on identical input
and an extractor change shows up as a text diff.

Every expected figure below is the Kotlin test's own assertion
(`BofaStatementParserTest.kt`, `BofaCardStatementParserTest.kt`, last seen at
`ad2d68f^`), except where a comment says the port differs and why.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ingest import statements
from ingest.parsers import bofa
from ingest.parsers.money import MoneyError, find_money_tokens, parse_money_cents
from ingest.parsers.pdf_text import UnreadablePdf, extract_text

FIXTURES = Path(__file__).parent / "bofa_fixtures"
BOFA_PDFS = sorted(p.name for p in FIXTURES.glob("bofa_*.pdf"))


def text_of(name: str) -> str:
    return (FIXTURES / name).with_suffix(".txt").read_text(encoding="utf-8")


def checking(name: str) -> dict | None:
    return bofa.parse_checking_text(text_of(name), name)


def card(name: str) -> dict | None:
    return bofa.parse_card_text(text_of(name), name)


def amounts(payload: dict) -> list[int]:
    return [line["amount_cents"] for line in payload["lines"]]


def refusal(parse, name_or_text: str, *, text: bool = False) -> str:
    with pytest.raises(statements.ParserRefused) as caught:
        if text:
            parse(name_or_text, "statement.pdf")
        else:
            parse(text_of(name_or_text), name_or_text)
    reason = str(caught.value)
    assert reason.endswith("Nothing was written."), reason
    return reason


# =============================================================================
# The extraction layer
# =============================================================================


def test_every_kotlin_bofa_fixture_is_here():
    assert len(BOFA_PDFS) == 13


@pytest.mark.parametrize("name", BOFA_PDFS)
def test_extraction_gives_the_pinned_text(name):
    extracted = extract_text((FIXTURES / name).read_bytes())
    assert extracted.replace("\r\n", "\n") == text_of(name)


def test_bytes_that_are_not_a_pdf_are_unreadable_not_a_crash():
    with pytest.raises(UnreadablePdf):
        extract_text(b"%PDF-1.7 a bank statement")


def test_extraction_keeps_the_runs_of_spaces_the_kotlin_test_pins():
    # pdfplumber collapses these; PdfBox kept them, and so does pypdf.
    assert "NORTHWIND OUTFITTERS      Northwind.com/billWA" in text_of("bofa_card_happy_path.pdf")


# =============================================================================
# Money
# =============================================================================


@pytest.mark.parametrize(
    ("token", "cents"),
    [("1,234.56", 123456), ("-$1,234.56", -123456), ("+1,025.00", 102500), ("0.00", 0),
     ("$12.00", 1200), ("1234.00", 123400)],
)
def test_money_is_exact_integer_cents(token, cents):
    assert parse_money_cents(token) == cents


@pytest.mark.parametrize("token", ["1,23.45", "12.3", "12,345", "1.234", "", "١.00"])
def test_money_refuses_what_is_not_printed_exactly(token):
    with pytest.raises(MoneyError):
        parse_money_cents(token)


def test_money_tokens_are_located_left_to_right():
    assert find_money_tokens("Total -$45.67 and 1,200.00") == ["-$45.67", "1,200.00"]


# =============================================================================
# Checking: the Kotlin suite
# =============================================================================


def test_checking_happy_path_across_all_four_sections():
    payload = checking("bofa_happy_path.pdf")
    assert amounts(payload) == [200000, -10000, -5000, -1200]
    assert payload["lines"][0]["description"] == "PAYROLL DEPOSIT"
    assert [line["txn_date"] for line in payload["lines"]] == [
        "2026-06-03", "2026-06-05", "2026-06-10", "2026-06-15",
    ]
    assert payload["currency"] == "USD"
    # Kotlin: accountId "123456789012". Only the last four leave the parser.
    assert payload["account_last4"] == "9012"
    assert payload["account_nickname"] == "BofA checking"
    assert payload["lines"][0]["line_ref"] == (
        "bofa_happy_path.pdf:'06/03/26 PAYROLL DEPOSIT 2,000.00'"
    )


def test_checking_anchors_are_the_printed_balances_and_no_stated_total():
    payload = checking("bofa_happy_path.pdf")
    # Section 4 rule 8: no single total is printed, so none is synthesised.
    assert payload["stated_total_cents"] is None
    assert payload["opening_balance_cents"] == 100000
    assert payload["closing_balance_cents"] == 283800
    assert payload["opening_balance_cents"] + 183800 == payload["closing_balance_cents"]


def test_checking_corrupted_section_total_is_refused():
    reason = refusal(bofa.parse_checking_text, "bofa_section_mismatch.pdf")
    assert "does not add up" in reason


def test_checking_joins_a_multi_line_wire_and_still_reconciles():
    payload = checking("bofa_multiline_wire.pdf")
    assert amounts(payload) == [50000, 120000, -4567, -2500, -1200]
    assert payload["lines"][0]["description"] == "Zelle payment from JANE DOE Conf# ab12cd34e"
    wire = payload["lines"][1]["description"]
    assert wire == (
        "WIRE TYPE:WIRE IN DATE: 260617 TIME:0802 ET TRN:2026061700277191 "
        "SEQ:1002233445JS/100200 ORIG:ACME WIDGETS LLC ID:0099887766 SND BK:FIRST "
        "NATIONAL BANK, N.A. ID:0002 PMT DET:MB60617135571826"
    )
    assert "Page 2 of 6" not in wire
    assert payload["account_last4"] == "1000"  # Kotlin: "987654321000"
    assert payload["opening_balance_cents"] == 500000
    assert payload["closing_balance_cents"] == 661733


def test_checking_skips_the_summary_block_and_stitches_a_page_split_section():
    payload = checking("bofa_summary_and_split_section.pdf")
    assert amounts(payload) == [300000, 120000, -8000, -3000, -4500, -1500]
    for line in payload["lines"]:
        assert "Account #" not in line["description"]
        assert "Page 4 of 6" not in line["description"]


PAGE_BREAK = "06/10/26 Overdraft Protection Transfer -30.00\nTAYLOR J RIVERA"


def split_section_text() -> str:
    # Line endings follow the checkout (autocrlf), so normalise before splicing.
    return text_of("bofa_summary_and_split_section.pdf").replace("\r\n", "\n")


def test_checking_skips_a_promo_paragraph_between_continued_and_the_reprinted_header():
    # Kevin's 2026-09 checking statement: after "continued on the next page" BofA
    # printed a promotional paragraph and ANOTHER section's "- continued" heading
    # before this section's own reprinted header. The same shape, synthetic.
    text = split_section_text()
    assert PAGE_BREAK in text
    text = text.replace(
        PAGE_BREAK,
        "06/10/26 Overdraft Protection Transfer -30.00\n"
        "continued on the next page\n"
        "Pay with your phone at checkout. It is fast and easy!\n"
        "Learn more at bankofamerica.com/mobile, where 2 offers wait.\n"
        "TAYLOR J RIVERA",
    ).replace(
        "Page 4 of 6\nOther subtractions\n",
        "Page 4 of 6\nWithdrawals and other subtractions - continued\n"
        "Other subtractions - continued\n",
    )
    payload = bofa.parse_checking_text(text, "statement.pdf")
    assert amounts(payload) == [300000, 120000, -8000, -3000, -4500, -1500]
    for line in payload["lines"]:
        assert "phone" not in line["description"]
        assert "continued" not in line["description"]


def test_checking_continued_with_no_reprinted_header_is_refused():
    # The skip is bounded by two printed markers. With the second missing, nothing
    # says where the table resumes, so the file refuses rather than guessing.
    text = split_section_text().replace(
        PAGE_BREAK,
        "06/10/26 Overdraft Protection Transfer -30.00\n"
        "continued on the next page\n"
        "TAYLOR J RIVERA",
    )
    assert "Page 4 of 6\nOther subtractions\nDate" in text
    text = text.replace("Page 4 of 6\nOther subtractions\nDate", "Page 4 of 6\nDate")
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert "never reprints it" in reason


def test_checking_row_with_no_amount_is_refused():
    reason = refusal(bofa.parse_checking_text, "bofa_missing_amount.pdf")
    assert "missing an amount" in reason


def test_a_card_statement_is_not_claimed_by_the_checking_parser():
    assert checking("bofa_card_happy_path.pdf") is None


# =============================================================================
# Card: the Kotlin suite
# =============================================================================


def test_card_happy_path_three_sections_and_no_ytd_trap_row():
    payload = card("bofa_card_happy_path.pdf")
    # Signs flipped from the paper: a payment is positive, a purchase negative.
    assert amounts(payload) == [150000, 125550, -60000, -45025, -90000, -500]
    assert 99999 not in amounts(payload) and -99999 not in amounts(payload)
    assert payload["account_last4"] == "7823"  # Kotlin: "5555555555557823"
    assert payload["account_nickname"] == "BofA card"
    assert payload["lines"][0]["description"] == "PAYMENT FROM CHK 5521 CONF#4mv2plq8h"
    assert payload["lines"][2]["description"] == "NORTHWIND OUTFITTERS      Northwind.com/billWA"
    assert payload["period_start"] == "2026-06-06"
    assert payload["period_end"] == "2026-07-05"


def test_card_payments_store_positive_and_purchases_negative():
    lines = card("bofa_card_happy_path.pdf")["lines"]
    payments = [ln for ln in lines if ln["description"].startswith("PAYMENT FROM")]
    others = [ln for ln in lines if not ln["description"].startswith("PAYMENT FROM")]
    assert payments and all(ln["amount_cents"] > 0 for ln in payments)
    assert others and all(ln["amount_cents"] < 0 for ln in others)


def test_card_anchors_are_the_printed_balances_on_the_holders_side():
    payload = card("bofa_card_happy_path.pdf")
    assert payload["stated_total_cents"] is None
    # Kotlin returned these as printed (842150, 762125). The port negates them
    # with the rows, so the server gate's closing - opening = sum(lines) holds.
    assert payload["opening_balance_cents"] == -842150
    assert payload["closing_balance_cents"] == -762125
    assert (
        payload["closing_balance_cents"] - payload["opening_balance_cents"]
        == sum(amounts(payload))
    )


def test_card_corrupted_section_total_is_refused():
    reason = refusal(bofa.parse_card_text, "bofa_card_section_mismatch.pdf")
    assert '"Payments and Other Credits" section does not add up' in reason


def test_card_summary_that_does_not_add_up_is_refused():
    reason = refusal(bofa.parse_card_text, "bofa_card_summary_mismatch.pdf")
    assert "own summary does not add up" in reason


def test_card_that_passes_per_section_and_summary_but_fails_the_cross_check_is_refused():
    reason = refusal(bofa.parse_card_text, "bofa_card_crosscheck_mismatch.pdf")
    assert "net movement for the period" in reason


def test_card_year_across_a_december_to_january_cycle():
    payload = card("bofa_card_dec_jan_boundary.pdf")
    by_amount = {ln["amount_cents"]: ln["txn_date"] for ln in payload["lines"]}
    assert len(payload["lines"]) == 3
    assert by_amount[80000].startswith("2026-12")
    assert by_amount[-15000].startswith("2026-12")
    assert by_amount[-22500].startswith("2027-01")
    assert payload["period_start"] == "2026-12-27"
    assert payload["period_end"] == "2027-01-26"


def test_card_bare_interest_rows_with_no_reference_or_account_number():
    payload = card("bofa_card_bare_interest_rows.pdf")
    assert len(payload["lines"]) == 9
    interest = [ln for ln in payload["lines"] if ln["description"].startswith("INTEREST CHARGED")]
    assert len(interest) == 4
    assert sum(ln["amount_cents"] for ln in interest) == -815
    assert interest[0]["description"] == "INTEREST CHARGED ON PURCHASES"
    assert interest[0]["amount_cents"] == -815
    assert interest[1]["description"] == "INTEREST CHARGED ON BALANCE TRANSFERS"
    assert interest[1]["amount_cents"] == 0


def test_card_bare_fee_row():
    payload = card("bofa_card_bare_fee_rows.pdf")
    assert len(payload["lines"]) == 6
    fee = next(ln for ln in payload["lines"] if ln["description"] == "LATE FEE")
    assert fee["amount_cents"] == -3500


def test_card_unparseable_line_inside_a_section_is_refused_not_skipped():
    # The fixture's Interest Charged section totals $0.00, so a skip would have
    # reconciled zero rows against zero: section 4 rule 6's vacuous pass.
    reason = refusal(bofa.parse_card_text, "bofa_card_unparseable_row.pdf")
    assert '"Interest Charged" section does not look like a transaction' in reason


def test_a_checking_statement_is_not_claimed_by_the_card_parser():
    assert card("bofa_happy_path.pdf") is None


# =============================================================================
# Section 4 rule 6, beyond the Kotlin suite
# =============================================================================


def test_checking_stray_line_inside_a_section_is_refused_where_kotlin_dropped_it():
    text = text_of("bofa_happy_path.pdf").replace(
        "06/10/26 ONLINE TRANSFER -50.00\n",
        "06/10/26 ONLINE TRANSFER -50.00\nINTEREST EARNED THIS PERIOD\n",
    )
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert '"Other subtractions" section after row 1 is not a transaction' in reason


def test_checking_stray_line_before_the_first_row_is_refused():
    text = text_of("bofa_happy_path.pdf").replace(
        "06/15/26 MONTHLY MAINTENANCE FEE -12.00\n",
        "Waived this cycle: see page 3\n06/15/26 MONTHLY MAINTENANCE FEE -12.00\n",
    )
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert '"Service fees" section after row 0' in reason


def test_card_interest_row_in_a_shape_neither_form_reads_is_refused_at_zero_total():
    """The rule 6 story itself: interest rows in a shape the parser does not
    know, in a month whose interest totals $0.00, so dropping them would
    reconcile. Here the rows carry one date instead of two."""
    text = text_of("bofa_card_bare_interest_rows.pdf")
    text = text.replace("Interest Charged 8.15", "Interest Charged 0.00")
    text = text.replace("New Balance Total 7,624.40", "New Balance Total 7,616.25")
    text = text.replace(
        "07/05 07/05 INTEREST CHARGED ON PURCHASES 8.15",
        "07/05 INTEREST CHARGED ON PURCHASES 0.00",
    )
    for kind in ("BALANCE TRANSFERS", "DIR DEP&CHK CASHADV", "BANK CASH ADVANCES"):
        text = text.replace(
            f"07/05 07/05 INTEREST CHARGED ON {kind} 0.00",
            f"07/05 INTEREST CHARGED ON {kind} 0.00",
        )
    text = text.replace(
        "TOTAL INTEREST CHARGED FOR THIS PERIOD 8.15",
        "TOTAL INTEREST CHARGED FOR THIS PERIOD 0.00",
    )
    reason = refusal(bofa.parse_card_text, text, text=True)
    assert '"Interest Charged" section does not look like a transaction' in reason


def test_checking_row_signed_against_its_section_is_refused():
    text = text_of("bofa_happy_path.pdf").replace(
        "06/05/26 ATM WITHDRAWAL -100.00", "06/05/26 ATM WITHDRAWAL 100.00"
    )
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert "signed the wrong way" in reason


def test_checking_balances_that_do_not_tie_out_are_refused():
    text = text_of("bofa_happy_path.pdf").replace("$2,838.00", "$2,839.00")
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert "balances do not tie out" in reason


def test_a_quarantine_reason_never_quotes_the_document():
    text = text_of("bofa_happy_path.pdf").replace(
        "06/10/26 ONLINE TRANSFER -50.00\n",
        "06/10/26 ONLINE TRANSFER -50.00\nSECRET PAYEE NAME\n",
    )
    reason = refusal(bofa.parse_checking_text, text, text=True)
    assert "SECRET" not in reason and "ONLINE TRANSFER" not in reason


# =============================================================================
# The registry
# =============================================================================


def test_both_layouts_are_registered_as_pdf_parsers():
    names = {p.name: p.kinds for p in statements.PARSERS}
    assert names["bofa-checking-pdf"] == frozenset({"pdf"})
    assert names["bofa-card-pdf"] == frozenset({"pdf"})


def test_registering_twice_adds_nothing(monkeypatch):
    monkeypatch.setattr(statements, "PARSERS", [])
    bofa.register()
    bofa.register()
    assert [p.name for p in statements.PARSERS] == ["bofa-checking-pdf", "bofa-card-pdf"]


@pytest.mark.parametrize(
    ("parser", "name"),
    [(bofa.CHECKING, "bofa_multiline_wire.pdf"), (bofa.CARD, "bofa_card_happy_path.pdf")],
)
def test_the_parser_reads_its_pdf_bytes_as_a_deterministic_result(parser, name):
    parsed = parser.parse((FIXTURES / name).read_bytes(), file_name=name)
    assert parsed is not None and parsed.provisional is False
    assert parsed.payload == parser.parse_text(text_of(name), name)


@pytest.mark.parametrize("name", ["dbs_happy_path.pdf", "unrecognized_reconciling.pdf"])
def test_a_non_bofa_pdf_is_not_claimed(name):
    content = (FIXTURES / name).read_bytes()
    assert bofa.CHECKING.parse(content, file_name=name) is None
    assert bofa.CARD.parse(content, file_name=name) is None


def test_garbage_bytes_are_not_claimed():
    assert bofa.CHECKING.parse(b"%PDF-1.7 a bank statement", file_name="x.pdf") is None


def test_a_non_bofa_pdf_falls_through_the_registry_to_gemini(monkeypatch):
    monkeypatch.setattr(statements, "PARSERS", [])
    bofa.register()
    calls = []

    def gemini(content, *, key):
        calls.append(key)
        raise statements.GeminiRefusedDocument("stub")

    content = (FIXTURES / "unrecognized_reconciling.pdf").read_bytes()
    routed = statements.route(content, kind="pdf", name="x.pdf", key="k", gemini=gemini)
    assert calls == ["k"]
    assert routed.reason == "stub"


def test_a_bofa_pdf_routes_to_the_gate_without_gemini(monkeypatch):
    monkeypatch.setattr(statements, "PARSERS", [])
    bofa.register()

    def gemini(content, *, key):
        raise AssertionError("Gemini must not be asked")

    content = (FIXTURES / "bofa_card_happy_path.pdf").read_bytes()
    routed = statements.route(content, kind="pdf", name="c.pdf", key="k", gemini=gemini)
    assert routed.provisional is False
    assert routed.payload["provenance"] == "DETERMINISTIC"
    assert routed.payload["stated_total_cents"] is None


def test_a_refused_bofa_pdf_quarantines_without_gemini(monkeypatch):
    monkeypatch.setattr(statements, "PARSERS", [])
    bofa.register()

    def gemini(content, *, key):
        raise AssertionError("Gemini must not be asked")

    content = (FIXTURES / "bofa_card_unparseable_row.pdf").read_bytes()
    routed = statements.route(content, kind="pdf", name="c.pdf", key="k", gemini=gemini)
    assert routed.reason.startswith("bofa-card-pdf: ")
