#!/usr/bin/env python3
"""Generate BofA fixtures exercising `BofaStatementParser`'s multi-line
transaction-row accumulation (continuation lines with the amount alone on a
trailing line, and page furniture interleaved mid-row).

Why this exists
----------------
`StatementDispatcher.dispatchDeterministic` quarantined Kevin's real BofA
statement whole, at zero rows imported, because `BofaStatementParser` assumed
every transaction fit on one line. Wires do not: BofA wraps the description
over up to three lines and prints the amount alone on a trailing line. See
`app/src/main/java/com/kevin/legion/ledger/parsers/BofaStatementParser.kt`'s
`parseSectionBody` doc comment for the fix. This script produces the fixture
that exercises it - it is a SIBLING to `tools/make_ledger_fixture.py`, same
uncompressed-PDF technique (no reportlab dependency, not installed here),
different bank layout (BofA's `Account number` / `Beginning balance on ...` /
section total markers, not the earlier fixture's fall-through layout).

Every name, id, and amount below is INVENTED for this fixture. None of it is
copied from Kevin's real statement - CLAUDE.md forbids that file from ever
entering the repo, and this script exists precisely so a synthetic stand-in
does not have to.

Three fixtures:

1. `bofa_multiline_wire.pdf` - a full happy-path statement (four sections,
   beginning/ending balance) where the Deposits section contains one
   ordinary single-line row AND one multi-line wire whose description wraps
   three lines with a "Page 2 of 6" footer interleaved between two of them
   (page furniture landing mid-row, per the real statement's shape) and the
   amount alone on a fourth line. Every section total and the
   beginning+net=ending check are exact, by construction below - the numbers
   are never typed twice.
2. `bofa_missing_amount.pdf` - a single-section statement whose first
   transaction row never states an amount before the next date-led row
   starts, which must still quarantine (accumulation is bounded, not
   permissive).
3. `bofa_summary_and_split_section.pdf` - the SECOND real bug found on
   Kevin's statement (2026-08-02): a front-page summary block that repeats
   every section name followed by its own total (not a "Date ..." header),
   plus a section that spans a page break with its header and "Date ..."
   header reprinted and a reprinted account line / "Page N of M" footer
   interleaved between the two halves of its rows. See
   `make_summary_and_split_section_fixture`'s docstring for the full shape.

Usage:  python tools/make_bofa_multiline_fixture.py
"""

from pathlib import Path

FIXTURES_DIR = Path(__file__).resolve().parent.parent / "app/src/test/resources/ledger_fixtures"


def money(cents: int) -> str:
    """Formats signed cents the way `parseMoneyCents`/`findMoneyTokens` read
    them: no leading `+` on credits (see `tools/make_ledger_fixture.py`'s
    `money()` for why that specific sign convention matters), and the BofA
    layout prints negatives as `-N.NN` on the transaction row itself but
    `-$N.NN` on a section total line - both must actually appear in the
    fixture text, so this only formats the digits; callers choose the sign
    style per call site.
    """
    sign = "-" if cents < 0 else ""
    whole, frac = divmod(abs(cents), 100)
    return f"{sign}{whole:,}.{frac:02d}"


def escape(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def build_pdf(lines: list[str]) -> bytes:
    """Verbatim copy of `tools/make_ledger_fixture.py`'s uncompressed
    single-page PDF writer - kept identical rather than imported, since that
    script is itself a disposable one-off and this one must keep working even
    if that one is deleted later.
    """
    content = ["BT", "/F1 9 Tf", "12 TL", "1 0 0 1 40 760 Tm"]
    for line in lines:
        content.append(f"({escape(line)}) Tj" if line else "() Tj")
        content.append("T*")
    content.append("ET")
    stream = "\n".join(content).encode("latin-1")

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    ]

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"

    xref_at = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_at}\n%%EOF\n"
    ).encode()
    return bytes(out)


def make_multiline_wire_fixture() -> None:
    # (section start marker, total marker, positive, [row lines-per-transaction])
    # Each transaction is itself a list of physical lines exactly as they'd
    # come out of PdfText.extractText - a single-element list is an ordinary
    # single-line row, more than one is a wrapped row.
    deposits_rows = [
        ["06/09/26 Zelle payment from JANE DOE Conf# ab12cd34e " + money(50000)],
        [
            "06/17/26 WIRE TYPE:WIRE IN DATE: 260617 TIME:0802 ET TRN:2026061700277191",
            "SEQ:1002233445JS/100200 ORIG:ACME WIDGETS LLC ID:0099887766 SND BK:FIRST",
            "Page 2 of 6",  # page furniture landing mid-description - must be dropped, not joined in
            "NATIONAL BANK, N.A. ID:0002 PMT DET:MB60617135571826",
            money(120000),
        ],
    ]
    atm_rows = [["06/05/26 Debit Card Purchase GROCERY MART -" + money(4567)]]
    other_rows = [["06/12/26 Overdraft Protection Transfer -" + money(2500)]]
    fee_rows = [["06/20/26 Monthly Maintenance Fee -" + money(1200)]]

    deposits_total = 50000 + 120000
    atm_total = -4567
    other_total = -2500
    fee_total = -1200
    net_total = deposits_total + atm_total + other_total + fee_total
    beginning_balance = 500000
    ending_balance = beginning_balance + net_total

    lines = [
        "BANK OF AMERICA",
        "Personal Checking Account Statement",
        "Account number: 987654321000",
        "Statement Period 06/01/26 to 06/30/26",
        "",
        f"Beginning balance on 6/1/26 ${money(beginning_balance)}",
        f"Ending balance on 6/30/26 ${money(ending_balance)}",
        "",
        "Deposits and other additions",
        "Date        Description                                          Amount",
    ]
    for row in deposits_rows:
        lines.extend(row)
    lines.append(f"Total deposits and other additions ${money(deposits_total)}")
    lines.append("")

    lines.append("ATM and debit card subtractions")
    lines.append("Date        Description                                          Amount")
    for row in atm_rows:
        lines.extend(row)
    lines.append(f"Total ATM and debit card subtractions -${money(-atm_total)}")
    lines.append("")

    lines.append("Other subtractions")
    lines.append("Date        Description                                          Amount")
    for row in other_rows:
        lines.extend(row)
    lines.append(f"Total other subtractions -${money(-other_total)}")
    lines.append("")

    lines.append("Service fees")
    lines.append("Date        Description                                          Amount")
    for row in fee_rows:
        lines.extend(row)
    lines.append(f"Total service fees -${money(-fee_total)}")
    lines.append("")
    lines.append("This statement is issued for the period shown above.")

    out = FIXTURES_DIR / "bofa_multiline_wire.pdf"
    out.write_bytes(build_pdf(lines))
    print(f"wrote {out} ({out.stat().st_size} bytes)")
    print(f"deposits ${money(deposits_total)}, net ${money(net_total)}, "
          f"ending ${money(ending_balance)} - all derived, none hand-typed")


def make_summary_and_split_section_fixture() -> None:
    """Reproduces the SECOND real bug found on Kevin's statement
    (2026-08-02), after the multi-line wire fix above: every section name
    on a real BofA statement appears TWICE - once in a front-page SUMMARY
    block, immediately followed by that section's own total rather than a
    "Date ..." column header, and once as the real transaction-table
    header. `extractSection` used to lock onto whichever came first with
    `indexOfFirst`, which was always the summary line, and its body would
    then run straight through to the WRONG section's real header.

    This also reproduces "Other subtractions" spanning a page break: BofA
    reprints both the section title and the "Date ..." header right before
    the second piece's rows, with a reprinted account/name/date-range line
    ("KEVIN MYO NAING WIN ! Account # ... ! June 5, 2026 to July 8, 2026" on
    the real statement) and a "Page N of M" footer interleaved in between.
    Both pieces' rows must land in one section, and the total check must run
    once over all of them combined.

    The Deposits section keeps the existing single-line row and multi-line
    wire from `make_multiline_wire_fixture`, so this fixture also re-proves
    that regression still holds alongside the two new ones. Every amount
    below is invented; none of it is copied from Kevin's real statement.
    """
    deposits_rows = [
        ["06/03/26 PAYROLL DEPOSIT " + money(300000)],
        [
            "06/17/26 WIRE TYPE:WIRE IN DATE: 260617 TIME:0802 ET TRN:2026061700277191",
            "SEQ:1002233445JS/100200 ORIG:ACME WIDGETS LLC ID:0099887766 SND BK:FIRST",
            "Page 2 of 6",  # page furniture landing mid-description - must be dropped, not joined in
            "NATIONAL BANK, N.A. ID:0002 PMT DET:MB60617135571826",
            money(120000),
        ],
    ]
    atm_rows = [["06/05/26 Debit Card Purchase GROCERY MART -" + money(8000)]]
    other_rows_piece1 = [["06/10/26 Overdraft Protection Transfer -" + money(3000)]]
    other_rows_piece2 = [["06/22/26 Online Bill Pay UTILITY CO -" + money(4500)]]
    fee_rows = [["06/20/26 Monthly Maintenance Fee -" + money(1500)]]

    deposits_total = 300000 + 120000
    atm_total = -8000
    other_total = -3000 + -4500
    fee_total = -1500
    net_total = deposits_total + atm_total + other_total + fee_total
    beginning_balance = 750000
    ending_balance = beginning_balance + net_total

    # Invented stand-in for the real reprinted header line - same shape
    # (name ! Account # digits ! date range), fictional everything.
    reprinted_account_line = (
        "TAYLOR J RIVERA   !   Account # 4881 3004 3119   !   June 5, 2026 to July 8, 2026"
    )

    lines = [
        "BANK OF AMERICA",
        "Personal Checking Account Statement",
        "Account number: 987654321000",
        "Statement Period 06/01/26 to 06/30/26",
        "",
        # SUMMARY block - repeats every section name, each one NOT followed
        # by a "Date ..." column header, which is the only thing that tells
        # it apart from that section's real start further down the page.
        "Your checking account summary",
        f"Deposits and other additions {money(deposits_total)}",
        f"ATM and debit card subtractions -{money(-atm_total)}",
        f"Other subtractions -{money(-other_total)}",
        f"Service fees -{money(-fee_total)}",
        "",
        f"Beginning balance on 6/1/26 ${money(beginning_balance)}",
        f"Ending balance on 6/30/26 ${money(ending_balance)}",
        "",
    ]

    lines.append("Deposits and other additions")
    lines.append("Date        Description                                          Amount")
    for row in deposits_rows:
        lines.extend(row)
    lines.append(f"Total deposits and other additions ${money(deposits_total)}")
    lines.append("")

    lines.append("ATM and debit card subtractions")
    lines.append("Date        Description                                          Amount")
    for row in atm_rows:
        lines.extend(row)
    lines.append(f"Total ATM and debit card subtractions -${money(-atm_total)}")
    lines.append("")

    # Other subtractions spans a page break: header and "Date ..." header
    # both reprint before the second piece's rows, with the reprinted
    # account line and a "Page N of M" footer interleaved between the two
    # pieces - the exact shape found on Kevin's real statement.
    lines.append("Other subtractions")
    lines.append("Date        Description                                          Amount")
    for row in other_rows_piece1:
        lines.extend(row)
    lines.append(reprinted_account_line)
    lines.append("Page 4 of 6")
    lines.append("Other subtractions")
    lines.append("Date        Description                                          Amount")
    for row in other_rows_piece2:
        lines.extend(row)
    lines.append(f"Total other subtractions -${money(-other_total)}")
    lines.append("")

    lines.append("Service fees")
    lines.append("Date        Description                                          Amount")
    for row in fee_rows:
        lines.extend(row)
    lines.append(f"Total service fees -${money(-fee_total)}")
    lines.append("")
    lines.append("This statement is issued for the period shown above.")

    out = FIXTURES_DIR / "bofa_summary_and_split_section.pdf"
    out.write_bytes(build_pdf(lines))
    print(f"wrote {out} ({out.stat().st_size} bytes)")
    print(f"other subtractions ${money(other_total)} across two page-split pieces, "
          f"net ${money(net_total)}, ending ${money(ending_balance)} - all derived, none hand-typed")


def make_missing_amount_fixture() -> None:
    # First transaction never states an amount before the next date-led row
    # starts - must still quarantine. The section total and begin/end
    # balance lines are present but their VALUES are irrelevant: the
    # exception fires while parsing the Deposits section body, before any
    # total is ever compared.
    lines = [
        "BANK OF AMERICA",
        "Personal Checking Account Statement",
        "Account number: 987654321000",
        "Statement Period 06/01/26 to 06/30/26",
        "",
        "Beginning balance on 6/1/26 $500.00",
        "Ending balance on 6/30/26 $500.00",
        "",
        "Deposits and other additions",
        "Date        Description                                          Amount",
        "06/09/26 Zelle payment from JANE DOE Conf# ab12cd34e",
        "06/17/26 Wire transfer, amount never printed for this row",
        "Total deposits and other additions $0.00",
        "",
        "ATM and debit card subtractions",
        "Date        Description                                          Amount",
        "Total ATM and debit card subtractions $0.00",
        "",
        "Other subtractions",
        "Date        Description                                          Amount",
        "Total other subtractions $0.00",
        "",
        "Service fees",
        "Date        Description                                          Amount",
        "Total service fees $0.00",
    ]
    out = FIXTURES_DIR / "bofa_missing_amount.pdf"
    out.write_bytes(build_pdf(lines))
    print(f"wrote {out} ({out.stat().st_size} bytes)")


def main() -> None:
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    make_multiline_wire_fixture()
    make_missing_amount_fixture()
    make_summary_and_split_section_fixture()


if __name__ == "__main__":
    main()
