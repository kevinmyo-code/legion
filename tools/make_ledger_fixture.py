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


if __name__ == "__main__":
    main()
