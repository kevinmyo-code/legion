#!/usr/bin/env python3
"""Generate the LLM-fallthrough ledger test fixture.

Why this exists
---------------
Every pre-existing fixture that reaches the LLM path is designed to FAIL the
reconciliation gate, so `IngestMethod.LLM_RECONCILED` had never been produced
and the ledger UI's "read by AI" provenance label had never rendered - on
device or anywhere else (MEMORY.md, 2026-08-02).

This produces a statement that:

1. **Falls through both deterministic parsers.** `DbsStatementParser` keys on
   `Account No. <digits>`; `BofaStatementParser` keys on `Account number`/
   `Account #` plus `Beginning balance on ...` / `Ending balance on ...`.
   None of those strings appear below - the account line is `A/C Reference`
   and the summary line is `Net movement for the period`.
2. **Reconciles.** `LedgerStatementAgent` checks that the transactions the
   model extracts sum EXACTLY to the `statedTotal` it also read off the same
   document. The printed net movement here is the arithmetic sum of the five
   signed amounts, so a faithful extraction passes the gate and a hallucinated
   or dropped line does not.

The earlier fixtures came from ReportLab, which is not installed here and is
not worth a dependency for one file. This writes an uncompressed PDF directly,
so the fixture is reproducible from source rather than an opaque blob.

Usage:  python tools/make_ledger_fixture.py
"""

from pathlib import Path

OUT = (
    Path(__file__).resolve().parent.parent
    / "app/src/test/resources/ledger_fixtures/unrecognized_reconciling.pdf"
)

# (date, description, signed amount in cents). The printed total is derived
# from these, never typed by hand - a fixture whose own total is a typo would
# test the opposite of what it is for.
ROWS = [
    ("02 Apr 2026", "DIRECT DEPOSIT - PAYROLL", 320000),
    ("05 Apr 2026", "RENT PAYMENT - OAKLINE PROPERTY", -145000),
    ("11 Apr 2026", "GROCERY MARKET 221", -12840),
    ("19 Apr 2026", "ELECTRIC UTILITY AUTOPAY", -9660),
    ("27 Apr 2026", "TRANSFER TO SAVINGS", -50000),
]


def money(cents: int) -> str:
    """Format signed cents the way `parseMoneyCents` can actually read them.

    Credits are printed UNSIGNED, not with a leading `+`. The first cut of this
    fixture printed `+3,200.00` / `+1,025.00`, which looks like a real
    statement and quarantined on device every time: `MONEY_RE` in
    `ledger/parsers/LedgerMoney.kt` is `^(-?)\\$?...`, so it accepts a leading
    minus and rejects a leading plus. The model faithfully echoed the `+` into
    `statedTotal`, `parseMoneyCents` threw, and the gate reported "doesn't
    print a clear total to verify against".

    That is a real limitation worth fixing in the parser (statements do print
    `+`), but this fixture exists to exercise a SUCCESSFUL reconciliation, so
    it must not also be a test of unsupported sign syntax.
    """
    sign = "-" if cents < 0 else ""
    whole, frac = divmod(abs(cents), 100)
    return f"{sign}{whole:,}.{frac:02d}"


def statement_lines() -> list[str]:
    net = sum(c for _, _, c in ROWS)
    lines = [
        "MERIDIAN TRUST BANK",
        "Personal Current Account Statement",
        "",
        "A/C Reference   7781-224-9",
        "Statement period   01 Apr 2026 to 30 Apr 2026",
        "All amounts in USD",
        "",
        "Date            Details                              Amount",
        "",
    ]
    for date, desc, cents in ROWS:
        lines.append(f"{date:<15} {desc:<36} {money(cents):>12}")
    lines += [
        "",
        f"{'Net movement for the period':<52} {money(net):>12}",
        "",
        "This statement is issued for the period shown above.",
    ]
    return lines


def escape(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def build_pdf(lines: list[str]) -> bytes:
    content = ["BT", "/F1 10 Tf", "14 TL", "1 0 0 1 48 744 Tm"]
    for line in lines:
        # Tj then T* rather than ' so an empty line still advances the cursor.
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


def main() -> None:
    lines = statement_lines()
    net = sum(c for _, _, c in ROWS)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(build_pdf(lines))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    print(f"net movement {money(net)} from {len(ROWS)} rows - this is what must reconcile")
    make_card_fixtures()


# ---------------------------------------------------------------------------
# BofaCardStatementParser fixtures (ticket: "BofaCardStatementParser + card
# CSV rejection", 2026-08-03).
#
# Every name, id, and amount below is INVENTED - none of it is copied from
# Kevin's real card statement, same discipline as
# `tools/make_bofa_multiline_fixture.py`'s doc comment explains for the
# checking-account fixtures. Only the LAYOUT (line order, label text,
# section markers, the "Transactions Continued"/YTD trap) is real, and only
# because it has to be for the fixture to exercise the actual parser.
# ---------------------------------------------------------------------------

CARD_FIXTURES_DIR = Path(__file__).resolve().parent.parent / "app/src/test/resources/ledger_fixtures"

CARD_ACCOUNT_DIGITS = "5500 7734 1182 7823"
CARD_ACCOUNT_LAST4 = CARD_ACCOUNT_DIGITS.replace(" ", "")[-4:]

CARD_PREVIOUS_BALANCE = 842150  # $8,421.50, invented

CARD_PAYMENTS_ROWS = [
    ("06/08", "06/09", "PAYMENT FROM CHK 5521 CONF#4mv2plq8h", "3312", CARD_ACCOUNT_LAST4, -150000),
    ("06/26", "06/27", "PAYMENT FROM SAV 5521 CONF#8pk0zrt5n", "3399", CARD_ACCOUNT_LAST4, -125550),
]
CARD_PURCHASES_ROWS = [
    ("06/14", "06/15", "NORTHWIND OUTFITTERS      Northwind.com/billWA", "5510", CARD_ACCOUNT_LAST4, 60000),
    ("06/19", "06/21", "CASCADE GROCERY 118", "6042", CARD_ACCOUNT_LAST4, 45025),
    ("07/02", "07/03", "RIVERBEND HARDWARE #4", "7788", CARD_ACCOUNT_LAST4, 90000),
]
CARD_INTEREST_ROWS = [
    ("06/30", "07/01", "INTEREST CHARGED ON PURCHASES", "9001", CARD_ACCOUNT_LAST4, 500),
]
CARD_FEES_ROWS: list[tuple] = []  # no Fees Charged section prints this cycle - see BofaCardStatementParser's KDoc

# The real bug (Kevin's July 2026 card statement): Interest Charged rows -
# and Fees Charged rows, whenever a fee actually posts - carry NO reference
# number and NO account number at all, `MM/DD MM/DD <description> <amount>`,
# a 4-tuple rather than the 6-tuple full form every other section above
# uses. NONZERO here on purpose: the real statement's interest happened to
# total $0.00 that month, which is exactly what let the four dropped rows
# reconcile vacuously instead of tripping the gate - a fixture that repeats
# a zero total would repeat the blind spot, not test the fix.
CARD_BARE_INTEREST_ROWS = [
    ("07/05", "07/05", "INTEREST CHARGED ON PURCHASES", 815),
    ("07/05", "07/05", "INTEREST CHARGED ON BALANCE TRANSFERS", 0),
    ("07/05", "07/05", "INTEREST CHARGED ON DIR DEP&CHK CASHADV", 0),
    ("07/05", "07/05", "INTEREST CHARGED ON BANK CASH ADVANCES", 0),
]
CARD_BARE_FEE_ROWS = [
    ("07/05", "07/05", "LATE FEE", 3500),
]


def format_row(row: tuple) -> str:
    """Formats one transaction row. A 6-tuple is the full form (txn date,
    posting date, description, reference number, last-4 account digits,
    signed cents) every Payments/Purchases row uses. A 4-tuple is the BARE
    form BofA prints for Interest/Fees Charged rows - (txn date, posting
    date, description, signed cents), with no reference number and no
    account number at all. See CARD_BARE_INTEREST_ROWS's comment for why
    this shape matters.
    """
    if len(row) == 6:
        txn_date, post_date, desc, ref, acct, cents = row
        return f"{txn_date} {post_date} {desc} {ref} {acct} {money(cents)}"
    if len(row) == 4:
        txn_date, post_date, desc, cents = row
        return f"{txn_date} {post_date} {desc} {money(cents)}"
    raise ValueError(f"unexpected row shape: {row}")


def card_statement_lines(
    period_line: str,
    previous_balance: int,
    payments_rows: list[tuple],
    purchases_rows: list[tuple],
    fees_rows: list[tuple],
    interest_rows: list[tuple],
    summary_overrides: dict | None = None,
    extra_section_lines: dict[str, list[str]] | None = None,
) -> tuple[list[str], dict[str, int]]:
    """Builds the full line list for one BofaCardStatementParser fixture.

    [summary_overrides] lets a mismatch variant corrupt exactly one number
    at a time (a summary-block figure, or one section's own printed TOTAL
    line) while every OTHER figure stays internally consistent and
    programmatically derived - the same "never type a sum twice" discipline
    as `make_bofa_multiline_fixture.py`. See the three `make_card_*_mismatch`
    functions below for which key breaks which gate layer.

    [extra_section_lines] injects raw, already-formatted text lines into a
    named section's body VERBATIM, after its normal rows - used by
    `make_card_unparseable_row` to plant a line that matches neither row
    form and prove the parser hard-fails on it instead of skipping it.

    A section with no rows AND no extra lines is omitted entirely, same as
    a real statement with nothing to report for that section (Fees Charged
    most commonly) - see BofaCardStatementParser's KDoc on why [SECTIONS]
    treats every section as optional.
    """
    payments_total = sum(row[-1] for row in payments_rows)
    purchases_total = sum(row[-1] for row in purchases_rows)
    fees_total = sum(row[-1] for row in fees_rows)
    interest_total = sum(row[-1] for row in interest_rows)
    true_new_balance = previous_balance + payments_total + purchases_total + fees_total + interest_total

    overrides = summary_overrides or {}
    summary_payments = overrides.get("payments", payments_total)
    summary_purchases = overrides.get("purchases", purchases_total)
    summary_fees = overrides.get("fees", fees_total)
    summary_interest = overrides.get("interest", interest_total)
    summary_new_balance = overrides.get("new_balance", true_new_balance)
    section_total_overrides: dict[str, int] = overrides.get("section_totals", {})
    extra_lines = extra_section_lines or {}

    lines = [
        f"Account# {CARD_ACCOUNT_DIGITS}",
        period_line,
        # Marketing header - always states the TRUE new balance, same as a
        # real statement would. BofaCardStatementParser must never read
        # this one; every mismatch variant below relies on that.
        f"New Balance Total {money(true_new_balance)}",
        "Account Summary/Payment Information",
        f"Previous Balance {money(previous_balance)}",
        f"Payments and Other Credits {money(summary_payments)}",
        f"Purchases and Adjustments {money(summary_purchases)}",
        f"Fees Charged {money(summary_fees)}",
        f"Interest Charged {money(summary_interest)}",
        f"New Balance Total {money(summary_new_balance)}",
        "",
        "Transactions",
        "Transaction / Date / Posting / Date Description / Reference / Number / Account / Number Amount  Total",
    ]

    def emit_section(name: str, rows: list[tuple], true_total: int) -> None:
        extra = extra_lines.get(name, [])
        if not rows and not extra:
            return
        stated = section_total_overrides.get(name, true_total)
        lines.append(name)
        for row in rows:
            lines.append(format_row(row))
        lines.extend(extra)
        lines.append(f"TOTAL {name.upper()} FOR THIS PERIOD {money(stated)}")
        lines.append("")

    emit_section("Payments and Other Credits", payments_rows, payments_total)
    emit_section("Purchases and Adjustments", purchases_rows, purchases_total)
    emit_section("Fees Charged", fees_rows, fees_total)
    emit_section("Interest Charged", interest_rows, interest_total)

    # The trap: a reprinted column-header block plus a YTD summary, and one
    # line shaped EXACTLY like a transaction row that must never be
    # ingested. If BofaCardStatementParser ever regressed into scanning
    # every date-led line in the whole document instead of staying inside
    # each section's own TOTAL-line boundary, this row's -999.99 would
    # break gate layer 3 and the "happy path" fixture would start throwing.
    lines += [
        "Transactions Continued",
        "Transaction / Date / Posting / Date Description / Reference / Number / Account / Number Amount  Total",
        "2026 Totals Year-to-Date",
        "Total fees charged in 2026 $0.00",
        f"Total interest charged in 2026 {money(interest_total)}",
        f"06/08 06/09 SAMPLE YTD ROW THAT MUST NOT COUNT 9999 {CARD_ACCOUNT_LAST4} -999.99",
    ]

    return lines, {
        "payments_total": payments_total,
        "purchases_total": purchases_total,
        "fees_total": fees_total,
        "interest_total": interest_total,
        "new_balance": true_new_balance,
    }


def _write_card_fixture(name: str, lines: list[str]) -> None:
    out = CARD_FIXTURES_DIR / name
    out.write_bytes(build_pdf(lines))
    print(f"wrote {out} ({out.stat().st_size} bytes)")


def make_card_happy_path() -> None:
    lines, totals = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        CARD_FEES_ROWS,
        CARD_INTEREST_ROWS,
    )
    _write_card_fixture("bofa_card_happy_path.pdf", lines)
    print(
        f"card happy path: payments {money(totals['payments_total'])}, "
        f"purchases {money(totals['purchases_total'])}, interest {money(totals['interest_total'])}, "
        f"new balance {money(totals['new_balance'])}"
    )


def make_card_section_mismatch() -> None:
    """Gate layer 1 (per-section) fails: the Payments section's own printed
    TOTAL line is corrupted while every summary-block figure stays true and
    self-consistent - so this must throw BEFORE layer 2 or layer 3 ever
    gets a chance to fire, proving layer 1 runs and is checked per section.
    """
    corrupted_payments_total = sum(row[-1] for row in CARD_PAYMENTS_ROWS) - 100  # off by $1.00
    lines, _ = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        CARD_FEES_ROWS,
        CARD_INTEREST_ROWS,
        summary_overrides={"section_totals": {"Payments and Other Credits": corrupted_payments_total}},
    )
    _write_card_fixture("bofa_card_section_mismatch.pdf", lines)


def make_card_summary_mismatch() -> None:
    """Gate layer 2 (summary identity) fails: the summary block's own
    "New Balance Total" is corrupted while every section (and its total
    line) stays true - so this must throw before the section loop even
    starts.
    """
    lines, totals = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        CARD_FEES_ROWS,
        CARD_INTEREST_ROWS,
        summary_overrides={"new_balance": totals_new_balance_offset()},
    )
    _write_card_fixture("bofa_card_summary_mismatch.pdf", lines)


def totals_new_balance_offset() -> int:
    true_total = (
        CARD_PREVIOUS_BALANCE
        + sum(row[-1] for row in CARD_PAYMENTS_ROWS)
        + sum(row[-1] for row in CARD_PURCHASES_ROWS)
        + sum(row[-1] for row in CARD_FEES_ROWS)
        + sum(row[-1] for row in CARD_INTEREST_ROWS)
    )
    return true_total + 1000  # off by $10.00


def make_card_crosscheck_mismatch() -> None:
    """Gate layer 3 (cross-check) fails while layers 1 and 2 both pass: the
    summary block's "Payments and Other Credits" figure is corrupted AND
    "New Balance Total" is recomputed around that corrupted figure so the
    summary identity (layer 2) still holds - but the Payments SECTION's own
    printed TOTAL line, and its rows, are untouched, so layer 1 passes too.
    Only layer 3 (all parsed rows vs. the summary's stated net movement)
    can catch this, which is the point of the fixture.
    """
    true_payments_total = sum(row[-1] for row in CARD_PAYMENTS_ROWS)
    corrupted_payments_summary = true_payments_total + 5000  # off by $50.00
    corrupted_new_balance = (
        CARD_PREVIOUS_BALANCE
        + corrupted_payments_summary
        + sum(row[-1] for row in CARD_PURCHASES_ROWS)
        + sum(row[-1] for row in CARD_FEES_ROWS)
        + sum(row[-1] for row in CARD_INTEREST_ROWS)
    )
    lines, _ = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        CARD_FEES_ROWS,
        CARD_INTEREST_ROWS,
        summary_overrides={"payments": corrupted_payments_summary, "new_balance": corrupted_new_balance},
    )
    _write_card_fixture("bofa_card_crosscheck_mismatch.pdf", lines)


def make_card_dec_jan_boundary() -> None:
    """The one case that silently corrupts a year of data if
    BofaCardStatementParser's year-derivation formula is wrong: a cycle
    that crosses the December -> January boundary, with real rows dated in
    both months. BofA prints the year once, at the tail of the period line,
    and that year belongs to the END month (January here).
    """
    dec_jan_payments = [
        ("12/27", "12/28", "PAYMENT FROM CHK 5521 CONF#dec27xyz", "4410", CARD_ACCOUNT_LAST4, -80000),
    ]
    dec_jan_purchases = [
        ("12/29", "12/30", "WINTER MARKET DOWNTOWN", "4501", CARD_ACCOUNT_LAST4, 15000),
        ("01/03", "01/04", "NEW YEAR HARDWARE CO", "4602", CARD_ACCOUNT_LAST4, 22500),
    ]
    lines, totals = card_statement_lines(
        "December 27 - January 26, 2027",
        CARD_PREVIOUS_BALANCE,
        dec_jan_payments,
        dec_jan_purchases,
        fees_rows=[],
        interest_rows=[],
    )
    _write_card_fixture("bofa_card_dec_jan_boundary.pdf", lines)
    print(f"dec-jan boundary: new balance {money(totals['new_balance'])}")


def make_card_bare_interest_rows() -> None:
    """Regression fixture for the real bug: Interest Charged rows in the
    BARE form (no reference number, no account number), with a NONZERO
    total - the shape and the value that vanished silently before
    BofaCardStatementParser tried [BARE_ROW_RE] and before
    `parseSectionBody` started hard-failing on unmatched lines instead of
    skipping them. See CARD_BARE_INTEREST_ROWS's comment for why zero was
    the wrong total to test with.
    """
    lines, totals = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        fees_rows=[],
        interest_rows=CARD_BARE_INTEREST_ROWS,
    )
    _write_card_fixture("bofa_card_bare_interest_rows.pdf", lines)
    print(f"bare interest rows: interest total {money(totals['interest_total'])}")


def make_card_bare_fee_rows() -> None:
    """Same shape, same reasoning as `make_card_bare_interest_rows`, but for
    Fees Charged - the ticket's "very likely the same shape" hypothesis for
    the OTHER section that only ever prints ref/acct-less rows.
    """
    lines, totals = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        fees_rows=CARD_BARE_FEE_ROWS,
        interest_rows=[],
    )
    _write_card_fixture("bofa_card_bare_fee_rows.pdf", lines)
    print(f"bare fee rows: fees total {money(totals['fees_total'])}")


def make_card_unparseable_row() -> None:
    """The gate-can't-pass-vacuously regression: a line inside the Interest
    Charged section that matches NEITHER row form. Before this ticket's
    fix, an unmatched line was silently skipped, which is precisely what
    let a $0.00 total "reconcile" against zero real rows. This must now
    quarantine instead - if it doesn't, the gate is decorative.
    """
    lines, _ = card_statement_lines(
        "June 6 - July 5, 2026",
        CARD_PREVIOUS_BALANCE,
        CARD_PAYMENTS_ROWS,
        CARD_PURCHASES_ROWS,
        fees_rows=[],
        interest_rows=[],
        extra_section_lines={
            "Interest Charged": ["This section intentionally left blank due to a print error"],
        },
    )
    _write_card_fixture("bofa_card_unparseable_row.pdf", lines)


def make_card_csv_rejection_fixture() -> None:
    """`currentTransaction_<last4>.csv` - BofA's mid-cycle card activity
    export. Header matches exactly; every row is invented and, critically,
    NOTHING in this file states a balance or total of any kind - verified
    against Kevin's real export 2026-08-03/06. BofaCardCsvStatementParser
    (ticket 12, 2026-08-06) parses this deterministically anyway and tags
    every row IngestMethod.UNRECONCILED rather than refusing the file - see
    CLAUDE.md §4 rule 7. Function name kept for history; the file it writes
    is no longer a rejection fixture.
    """
    csv_lines = [
        "Posted Date,Reference Number,Payee,Address,Amount",
        f"06/09/2026,24992915166300{CARD_ACCOUNT_LAST4}01,PAYMENT - THANK YOU,,-1500.00",
        f"06/13/2026,24992915167300{CARD_ACCOUNT_LAST4}02,NORTHWIND OUTFITTERS,123 MAIN ST SEATTLE WA,600.00",
    ]
    out = CARD_FIXTURES_DIR / "currentTransaction_7823.csv"
    out.write_text("\n".join(csv_lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size} bytes)")


def make_card_fixtures() -> None:
    CARD_FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    make_card_happy_path()
    make_card_section_mismatch()
    make_card_summary_mismatch()
    make_card_crosscheck_mismatch()
    make_card_dec_jan_boundary()
    make_card_bare_interest_rows()
    make_card_bare_fee_rows()
    make_card_unparseable_row()
    make_card_csv_rejection_fixture()
    make_dbs_description_artifact()


# ---------------------------------------------------------------------------
# DbsStatementParser description-pollution fixture (ticket: real DBS/POSB
# statement description artifact, 2026-08-03).
#
# Kevin's real consolidated DBS/POSB statement parsed correctly end to end -
# all 14 transactions found, sums tied exactly to the printed totals - EXCEPT
# the final transaction's description came out as
# "Interest Earned 4 4 4 4 4" instead of "Interest Earned". PdfBox emitted a
# rotated/sidebar watermark as its OWN text line, "4 4 4 4 4", sitting
# between the last transaction row and the "Total Balance Carried Forward:"
# line, and the multi-line description accumulator (the bare
# `currentTxnIndex >= 0` branch at the bottom of the parse loop) swallowed it
# because nothing distinguished it from a real continuation line.
#
# This fixture reproduces the SHAPE of that bug - not Kevin's real amounts,
# account number, or counterparty names, all of which are invented - using
# the exact column x-offsets already proven to classify correctly by
# `dbs_happy_path.pdf` (337/430/493 header x0s, 372.482/438.972/514.972
# amount columns; see that fixture's own content stream for the header-word
# widths this was checked against).
#
# It also exercises a case the real statement did NOT happen to hit but the
# bug report calls out as "luck, not safety": an artifact line SANDWICHED
# between two genuine continuation lines. The fix must skip the artifact
# line without resetting the accumulator, so the second genuine line is
# still captured - a fix that (wrongly) terminated accumulation on any
# non-matching line would pass the single-artifact case and fail this one.
# ---------------------------------------------------------------------------

DBS_ACCOUNT_ID = "5566778899"
DBS_OPENING_BALANCE = 1_000_000  # $10,000.00, invented

# (date, description-first-line, [continuation lines, some of which are pure
# PDF artifact and must NOT survive into the parsed description], signed
# cents). Artifact lines are marked with a leading "!" and stripped of it
# before being written - kept in-line here so the row and its artifact are
# defined together rather than threaded through separate line lists.
DBS_ROWS = [
    (
        "01/03/2026",
        "PAYMENT TO ACME SUPPLIES",
        [
            "INVOICE REF 20260145",  # genuine continuation
            "!1 4 8 A",  # artifact, sandwiched between two genuine lines
            "APPROVAL CODE 774411",  # genuine continuation - must still land
        ],
        -25000,
    ),
    ("05/03/2026", "SALARY", [], 300000),
    (
        "31/03/2026",
        "Interest Earned",
        [
            "!9 9 9",  # artifact immediately before the totals line, same
            # shape as the real bug: nothing else follows it before
            # "Total Balance Carried Forward:" closes the section.
        ],
        1250,
    ),
]


def dbs_amount_x(cents: int) -> tuple[float, bool]:
    """Returns (x, is_deposit) for a signed-cents amount, using the same
    withdrawal/deposit column x-offsets `dbs_happy_path.pdf` already proves
    classify correctly against `DbsStatementParser.findBoundaries`."""
    return (438.972, True) if cents > 0 else (372.482, False)


def dbs_content_entries() -> tuple[list[tuple[float, float, str]], dict[str, int]]:
    """Builds the (x, y, text) placement list for the DBS artifact fixture,
    one entry per `Tj`, mirroring `dbs_happy_path.pdf`'s own content stream
    (each word/phrase is its own positioned show-text call, not a single
    line of auto-advancing text) so `PdfWords.extractWords` classifies
    columns by x0 exactly the way it does against the real parser today.
    """
    entries: list[tuple[float, float, str]] = []
    y = 700.0
    entries.append((45.0, y, f"DBS Savings Account Account No. {DBS_ACCOUNT_ID}"))
    y -= 15
    entries += [
        (45.0, y, "Date"),
        (113.0, y, "Description"),
        (337.0, y, "Withdrawal"),
        (430.0, y, "Deposit"),
        (493.0, y, "Balance"),
    ]
    y -= 15
    entries += [
        (113.0, y, "Balance Brought Forward"),
        (514.972, y, money(DBS_OPENING_BALANCE)),
    ]

    running = DBS_OPENING_BALANCE
    for date, desc, continuations, cents in DBS_ROWS:
        running += cents
        amt_x, _ = dbs_amount_x(cents)
        y -= 15
        entries += [
            (45.0, y, date),
            (113.0, y, desc),
            (amt_x, y, money(abs(cents))),
            (514.972, y, money(running)),
        ]
        for line in continuations:
            y -= 15
            text = line[1:] if line.startswith("!") else line
            entries.append((113.0, y, text))

    withdrawal_total = -sum(c for _, _, _, c in DBS_ROWS if c < 0)
    deposit_total = sum(c for _, _, _, c in DBS_ROWS if c > 0)
    y -= 15
    entries.append((
        113.0,
        y,
        f"Total Balance Carried Forward: {money(withdrawal_total)} "
        f"{money(deposit_total)} {money(running)}",
    ))

    return entries, {
        "withdrawal_total": withdrawal_total,
        "deposit_total": deposit_total,
        "closing_balance": running,
    }


def build_positioned_pdf(entries: list[tuple[float, float, str]]) -> bytes:
    """Same uncompressed PDF object skeleton as `build_pdf`, but each entry
    is its own explicitly-positioned `Tm`/`Tj` rather than sequentially
    auto-advancing text - required for `DbsStatementParser`, which classifies
    every word into date/description/withdrawal/deposit/balance by x0, not
    by column order.
    """
    # Font selection is text STATE, not text POSITIONING - it persists across
    # BT/ET boundaries, so it is set once up front. Every entry after that is
    # its own self-contained `BT ... Tm (text) Tj T* ET`, same shape as
    # `dbs_happy_path.pdf`'s own content stream.
    content = ["BT /F1 9 Tf ET"]
    for x, y, text in entries:
        content.append(f"BT 1 0 0 1 {x} {y} Tm ({escape(text)}) Tj T* ET")
    stream = "\n".join(content).encode("latin-1")

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 594 792] "
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


def make_dbs_description_artifact() -> None:
    entries, totals = dbs_content_entries()
    out = CARD_FIXTURES_DIR / "dbs_description_artifact.pdf"
    out.write_bytes(build_positioned_pdf(entries))
    print(
        f"wrote {out} ({out.stat().st_size} bytes) - withdrawal "
        f"{money(totals['withdrawal_total'])}, deposit {money(totals['deposit_total'])}, "
        f"closing {money(totals['closing_balance'])}"
    )


if __name__ == "__main__":
    main()
