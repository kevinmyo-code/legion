"""Bank of America's "Download activity" CSV exports, read deterministically
(backend-etl ticket 09, CLAUDE.md section 4 rules 1, 6, 7 and 8).

A port of the phone's two deleted readers, last seen at `ad2d68f^`:
`ledger/parsers/BofaCardCsvStatementParser.kt` (card, ticket 12 of
ledger-drive-ingestion) and `ledger/parsers/BofaCsvStatementParser.kt`
(checking). Layout detection is theirs, byte for byte: an exact first line.

**No LLM anywhere in this module, and none downstream of it.** A CSV is never
sent to Gemini (`ingest/statements.py`); a CSV no reader here recognises is
quarantined by the watcher with `CSV_NOT_RECOGNISED`.

## Two layouts, and what each one lets the ledger claim

| Layout | First line | Anchors printed | Path |
|---|---|---|---|
| Card | `Posted Date,Reference Number,...` | none | provisional (rule 7) |
| Checking | `Description,,Summary Amt.` | balances, totals, running | gate (ruling 4) |

"balances, totals, running" is the beginning and ending balance, total credits,
total debits, and a running balance on every row.

**Card: provisional, always.** The export prints no balance, no total, nothing
to reconcile against (ticket 12's facts, read from the real file 2026-08-06).
Every row is `UNRECONCILED` with no statement header, and goes to the rule 7
writer (`ingest/provisional.py`), never to the gate.

**Checking: gated.** Ticket 09 ruling 4: a checking CSV that PRINTS its own
beginning and ending balance is a real anchor pair. It was confirmed on Kevin's
real export when the phone parser was written (commit 2d188e7, 2026-08-03: "all
three anchors hold to the cent"). Every check the Kotlin ran runs here first
(per-row running balance, the printed credit and debit totals, beginning + net
= ending), then the payload goes to `commit_statement` as `DETERMINISTIC` with
the two balances as its anchors and `stated_total_cents` NULL: the export prints
separate credit and debit totals, never one figure, so there is no single
stated total to store (section 4 rule 8: absent, never synthesised from the
lines). A checking export WITHOUT the summary block is not recognised at all,
so it is quarantined, never gated on a balance this module made up.

## Signs

Both exports print from the holder's side already: ticket 12 read the real card
file as 38 negative debits and 2 positive credits (a refund and a payment), and
the checking export prints withdrawals negative. So nothing is negated here,
unlike the card PDF (`ingest/parsers/bofa.py`). The card fixture copied from the
phone's tests (`currentTransaction_7823.csv`) prints its payment negative and
its purchase positive; that file is SYNTHETIC and its signs are not evidence
about the real export, which ticket 12's reading is.

## Which account

Neither export prints an account number anywhere in its body (both verified on
the real files). The file NAME is the only identity: BofA's own
`currentTransaction_<last4>.csv`, or the name ticket 09's login script writes,
`bofa_<last4>_activity_<YYYY-MM-DD>.csv`. Drive's " (1)" duplicate suffix is
tolerated. A recognised layout under any other name is refused with a sentence,
never filed under a guessed account.

## Rule 6

Once a layout is recognised, every line must be accounted for: a data row that
does not parse, a stray line between the summary and the table, anything after
the table's end other than blank lines. Any of those raises `ParserRefused` and
the whole file is quarantined. **A reason never quotes the file's text**
(`drive_statements` prints every result to the job log); it names the row by
its position.
"""
from __future__ import annotations

import csv
import datetime
import re
from collections.abc import Callable
from dataclasses import dataclass

from ingest import statements
from ingest.parsers.bofa import CARD_NICKNAME, CHECKING_NICKNAME, CURRENCY
from ingest.parsers.money import MoneyError, format_cents, parse_money_cents

CARD_HEADER = "Posted Date,Reference Number,Payee,Address,Amount"
CHECKING_SUMMARY_HEADER = "Description,,Summary Amt."
CHECKING_TABLE_HEADER = "Date,Description,Amount,Running Bal."

NOTHING_WRITTEN = "Nothing was written."

# `currentTransaction_7823.csv`, `bofa_7823_activity_2026-09-27.csv`, and
# either with Drive's " (1)" suffix. Anchored at the end; case-insensitive
# because SAF and Drive listings disagree on the extension's case.
_FILE_LAST4_RE = re.compile(
    r"(?:^|[\\/])(?:currentTransaction_(\d{4})|bofa_(\d{4})_activity_\d{4}-\d{2}-\d{2})"
    r"(?: \(\d+\))?\.csv$",
    re.ASCII | re.IGNORECASE,
)
_AS_OF_RE = re.compile(r"as of (\d{2}/\d{2}/\d{4})$", re.ASCII)


def _text_lines(content: bytes) -> list[str] | None:
    """The file as lines, or None when it is not text this could be.

    `utf-8-sig` drops a byte-order mark. Bytes that are not UTF-8 (a PDF, an
    image) are simply not this layout, so None, never an exception."""
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        return None
    return re.split(r"\r\n|\n|\r", text)


def _strip_trailing_blanks(lines: list[str]) -> list[str]:
    end = len(lines)
    while end > 0 and not lines[end - 1].strip():
        end -= 1
    return lines[:end]


def _fields(line: str, where: str) -> list[str]:
    """One CSV row. `strict=True`: a malformed quote is a refusal, never a
    best guess at where the field ends."""
    try:
        rows = list(csv.reader([line], strict=True))
    except csv.Error:
        raise statements.ParserRefused(
            f"{where} is not a well-formed CSV row. {NOTHING_WRITTEN}"
        ) from None
    return rows[0] if rows else []


def _money(token: str, where: str) -> int:
    try:
        return parse_money_cents(token)
    except MoneyError:
        raise statements.ParserRefused(
            f"The amount on {where} is not an exact amount LEGION can read. {NOTHING_WRITTEN}"
        ) from None


def _date(token: str, where: str) -> datetime.date:
    try:
        return datetime.datetime.strptime(token.strip(), "%m/%d/%Y").date()
    except ValueError:
        raise statements.ParserRefused(
            f"The date on {where} is not a date LEGION recognises. {NOTHING_WRITTEN}"
        ) from None


def account_last4_from_name(file_name: str, layout: str) -> str:
    """The last four digits the file NAME states, or a refusal in words."""
    match = _FILE_LAST4_RE.search(file_name)
    if match is None:
        raise statements.ParserRefused(
            f"This is Bank of America's {layout} activity export, which prints no account "
            f"number of its own, and its file name does not say which account it is for "
            f"(expected currentTransaction_<last 4>.csv or "
            f"bofa_<last 4>_activity_<YYYY-MM-DD>.csv). {NOTHING_WRITTEN}"
        )
    return match.group(1) or match.group(2)


# =============================================================================
# Card: provisional
# =============================================================================


def parse_card(lines: list[str], file_name: str) -> dict | None:
    """The card export's payload, None when the first line is not its header.
    Raises `ParserRefused` once the layout is recognised."""
    if not lines or lines[0].strip() != CARD_HEADER:
        return None
    last4 = account_last4_from_name(file_name, "card")
    rows = _strip_trailing_blanks(lines[1:])
    if not rows:
        # Rule 6 for a file with no anchor at all: an empty extraction is never
        # a successful import of nothing.
        raise statements.ParserRefused(
            f"This card export has its header and no transaction rows. {NOTHING_WRITTEN}"
        )

    out = []
    for number, row in enumerate(rows, start=1):
        where = f"row {number} of this card export"
        fields = _fields(row, where.capitalize())
        if len(fields) != 5:
            raise statements.ParserRefused(
                f"Row {number} of this card export has {len(fields)} columns, not the 5 "
                f"(Posted Date, Reference Number, Payee, Address, Amount) LEGION reads. "
                f"{NOTHING_WRITTEN}"
            )
        posted, _reference, payee, _address, amount = fields
        description = payee.strip()
        if not description:
            raise statements.ParserRefused(
                f"Row {number} of this card export has no merchant name. {NOTHING_WRITTEN}"
            )
        if not amount.strip():
            raise statements.ParserRefused(
                f"Row {number} of this card export has no amount. {NOTHING_WRITTEN}"
            )
        out.append(
            {
                "txn_date": _date(posted, where).isoformat(),
                "description": description,
                # Holder's side as printed (see the module doc): never negated.
                "amount_cents": _money(amount, where),
            }
        )

    return {
        "account_last4": last4,
        "account_nickname": CARD_NICKNAME,
        "currency": CURRENCY,
        "lines": out,
    }


# =============================================================================
# Checking: gated
# =============================================================================


def _summary(line: str, label: str, number: int) -> tuple[str, int]:
    """One summary row, `<label...>,,"<amount>"`: (the label as printed, cents)."""
    where = f"summary row {number} of this checking export"
    fields = _fields(line, where.capitalize())
    if len(fields) != 3 or not fields[0].strip().startswith(label) or fields[1].strip():
        raise statements.ParserRefused(
            f"Summary row {number} of this checking export is not the '{label}' row LEGION "
            f"expects there. {NOTHING_WRITTEN}"
        )
    return fields[0].strip(), _money(fields[2], where)


def _as_of(label: str, number: int) -> datetime.date:
    match = _AS_OF_RE.search(label)
    if match is None:
        raise statements.ParserRefused(
            f"Summary row {number} of this checking export does not end in "
            f"'as of MM/DD/YYYY'. {NOTHING_WRITTEN}"
        )
    return _date(match.group(1), f"summary row {number} of this checking export")


def parse_checking(lines: list[str], file_name: str) -> dict | None:
    """The checking export's payload, None when the first line is not its
    summary header. Raises `ParserRefused` once the layout is recognised."""
    if not lines or lines[0].strip() != CHECKING_SUMMARY_HEADER:
        return None
    lines = _strip_trailing_blanks(lines)
    if len(lines) < 5:
        raise statements.ParserRefused(
            f"This checking export's summary block is incomplete. {NOTHING_WRITTEN}"
        )
    begin_label, beginning = _summary(lines[1], "Beginning balance as of", 1)
    _, total_credits = _summary(lines[2], "Total credits", 2)
    _, total_debits = _summary(lines[3], "Total debits", 3)
    end_label, ending = _summary(lines[4], "Ending balance as of", 4)
    period_start = _as_of(begin_label, 1)
    period_end = _as_of(end_label, 4)

    # Between the summary and the table: blank lines only. The Kotlin searched
    # past anything for the table header; rule 6 says a line nobody reads is a
    # refusal, not a skip.
    index = 5
    while index < len(lines) and not lines[index].strip():
        index += 1
    if index >= len(lines) or lines[index].strip() != CHECKING_TABLE_HEADER:
        raise statements.ParserRefused(
            f"This checking export has its summary block but not the transaction table "
            f"straight after it. {NOTHING_WRITTEN}"
        )
    table = lines[index + 1 :]
    if not table:
        raise statements.ParserRefused(
            f"This checking export's transaction table has no rows. {NOTHING_WRITTEN}"
        )

    # Row 1 repeats the beginning balance with an EMPTY amount. It is the
    # running balance's starting point, never a transaction, and it has to
    # agree with the summary's own beginning balance.
    first = _fields(table[0], "The first table row of this checking export")
    if (
        len(first) != 4
        or first[2].strip()
        or not first[1].strip().startswith("Beginning balance as of")
    ):
        raise statements.ParserRefused(
            f"The first table row of this checking export is not the beginning-balance row "
            f"LEGION expects. {NOTHING_WRITTEN}"
        )
    running = _money(first[3], "the beginning-balance row of this checking export")
    if running != beginning:
        raise statements.ParserRefused(
            f"The table starts at {format_cents(running)} but the summary's beginning balance "
            f"is {format_cents(beginning)}. {NOTHING_WRITTEN}"
        )

    out = []
    credits = debits = 0
    for number, row in enumerate(table[1:], start=1):
        where = f"transaction row {number} of this checking export"
        if not row.strip():
            # A blank line inside the table, with more rows after it (trailing
            # blanks were stripped above): the Kotlin stopped reading here and
            # silently dropped everything below. Refused instead.
            raise statements.ParserRefused(
                f"This checking export has a blank line inside its transaction table, before "
                f"row {number}. {NOTHING_WRITTEN}"
            )
        fields = _fields(row, where.capitalize())
        if len(fields) != 4:
            raise statements.ParserRefused(
                f"Transaction row {number} of this checking export has {len(fields)} columns, "
                f"not the 4 (Date, Description, Amount, Running Bal.) LEGION reads. "
                f"{NOTHING_WRITTEN}"
            )
        date_token, description, amount_token, balance_token = fields
        description = description.strip()
        if not description:
            raise statements.ParserRefused(
                f"Transaction row {number} of this checking export has no description. "
                f"{NOTHING_WRITTEN}"
            )
        if not amount_token.strip():
            raise statements.ParserRefused(
                f"Transaction row {number} of this checking export has no amount. "
                f"{NOTHING_WRITTEN}"
            )
        txn_date = _date(date_token, where)
        amount = _money(amount_token, where)
        stated_balance = _money(balance_token, where)
        if running + amount != stated_balance:
            raise statements.ParserRefused(
                f"Transaction row {number} of this checking export does not add up: after it "
                f"the running balance should be {format_cents(running + amount)}, but the "
                f"export shows {format_cents(stated_balance)}. {NOTHING_WRITTEN}"
            )
        running = stated_balance
        if amount > 0:
            credits += amount
        else:
            debits += amount
        out.append(
            {
                "txn_date": txn_date.isoformat(),
                "description": description,
                "amount_cents": amount,
                "balance_cents": stated_balance,
            }
        )

    if credits != total_credits or debits != total_debits:
        parts = []
        if credits != total_credits:
            parts.append(
                f"it states {format_cents(total_credits)} in credits but the rows add up to "
                f"{format_cents(credits)}"
            )
        if debits != total_debits:
            parts.append(
                f"it states {format_cents(total_debits)} in debits but the rows add up to "
                f"{format_cents(debits)}"
            )
        raise statements.ParserRefused(
            f"This checking export's own totals do not match its rows: {'; '.join(parts)}. "
            f"{NOTHING_WRITTEN}"
        )
    net = credits + debits
    if beginning + net != ending:
        raise statements.ParserRefused(
            f"This checking export's balances do not tie out: it opens at "
            f"{format_cents(beginning)} and moves {format_cents(net)}, which lands at "
            f"{format_cents(beginning + net)}, not the {format_cents(ending)} it states. "
            f"{NOTHING_WRITTEN}"
        )

    # Account last: a numbers problem is the likelier real error and is
    # reported first (the Kotlin's ordering, UnmappedAccountException).
    last4 = account_last4_from_name(file_name, "checking")
    return {
        "account_last4": last4,
        "account_nickname": CHECKING_NICKNAME,
        "currency": CURRENCY,
        # Section 4 rule 8: separate credit and debit totals, never one printed
        # figure, so NULL. Never sum(lines).
        "stated_total_cents": None,
        "opening_balance_cents": beginning,
        "closing_balance_cents": ending,
        "period_start": period_start.isoformat(),
        "period_end": period_end.isoformat(),
        "lines": out,
    }


# =============================================================================
# The registry's side
# =============================================================================


@dataclass(frozen=True)
class BofaActivityParser:
    """`ingest.statements.StatementParser` for one BofA activity-CSV layout."""

    name: str
    parse_lines: Callable[[list[str], str], dict | None]
    provisional: bool
    kinds: frozenset[str] = frozenset({statements.CSV})

    def parse(self, content: bytes, *, file_name: str) -> statements.Parsed | None:
        lines = _text_lines(content)
        if lines is None:
            return None
        payload = self.parse_lines(lines, file_name)
        if payload is None:
            return None
        return statements.Parsed(payload, provisional=self.provisional)


CARD = BofaActivityParser("bofa-card-activity-csv", parse_card, provisional=True)
CHECKING = BofaActivityParser("bofa-checking-activity-csv", parse_checking, provisional=False)


def register() -> None:
    """Both layouts join the registry once, and the rule 7 writer with them
    (a provisional parser with no writer would fail every run)."""
    from ingest import provisional

    present = {parser.name for parser in statements.PARSERS}
    for parser in (CARD, CHECKING):
        if parser.name not in present:
            statements.register_parser(parser)
    statements.register_provisional_writer(provisional.commit_provisional)
