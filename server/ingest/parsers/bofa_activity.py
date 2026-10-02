"""Bank of America's "Download activity" CSV exports, read deterministically
(backend-etl ticket 09, CLAUDE.md section 4 rules 1, 6, 7 and 8).

A port of the phone's two deleted readers, last seen at `ad2d68f^`:
`ledger/parsers/BofaCardCsvStatementParser.kt` (card, ticket 12 of
ledger-drive-ingestion) and `ledger/parsers/BofaCsvStatementParser.kt`
(checking). Layout detection is theirs, byte for byte: an exact first line.

**No LLM anywhere in this module, and none downstream of it.** A CSV is never
sent to Gemini (`ingest/statements.py`); a CSV no reader here recognises is
quarantined by the watcher with `CSV_NOT_RECOGNISED`.

## Two layouts, two windows, and what each lets the ledger claim

Kevin, 2026-09-28: *"lets not use statements. only the transaction history. i
just need to know what im spending on."* The laptop script (`tools/bofa_pull.py`)
downloads only transaction CSVs, two kinds per account, and says which in the
file NAME:

- `bofa_<last4>_activity_<YYYY-MM-DD>.csv`: "Current transactions", pulled
  daily. Its window is still OPEN (since the last statement, to today), so
  day N+1's file repeats day N's rows and adds more.
- `bofa_<last4>_period_<YYYY-MM-DD>.csv`: one CLOSED statement period, dated by
  the period's end as BofA lists it. It never changes again.

The content cannot tell the two apart (a checking current file prints the same
summary block a closed one does), so the name decides:

| Layout | Name | Anchors printed | Path |
|---|---|---|---|
| Card | any of the three below | none | provisional (rule 7) |
| Checking | `_period_` | balances, totals, running | gate (ruling 4) |
| Checking | `_activity_` | balances, totals, running | provisional (rule 7) |
| Checking | `currentTransaction_` or anything else | - | refused, in words |

"balances, totals, running" is the beginning and ending balance, total credits,
total debits, and a running balance on every row.

**Card: provisional, always.** The export prints no balance, no total, nothing
to reconcile against (ticket 12's facts, read from the real file 2026-08-06).
Every row is `UNRECONCILED` with no statement header, and goes to the rule 7
writer (`ingest/provisional.py`), never to the gate. BofA's own
`currentTransaction_<last4>.csv` name is still accepted, as a current file.

**Checking, closed period: gated.** Ticket 09 ruling 4: a checking CSV that
PRINTS its own beginning and ending balance is a real anchor pair. It was
confirmed on Kevin's real export when the phone parser was written (commit
2d188e7, 2026-08-03: "all three anchors hold to the cent"). Every check the
Kotlin ran runs here first (per-row running balance, the printed credit and
debit totals, beginning + net = ending), then the payload goes to
`commit_statement` as `DETERMINISTIC` with the two balances as its anchors and
`stated_total_cents` NULL: the export prints separate credit and debit totals,
never one figure, so there is no single stated total to store (section 4 rule
8: absent, never synthesised from the lines). The summary's "Ending balance as
of" date must be the date the name states, or the file is refused: a name that
claims a closed period the content does not end on is not believed. A checking
export WITHOUT the summary block is not recognised at all, so it is
quarantined, never gated on a balance this module made up.

**Checking, current window: provisional, although it prints balances.** This
is not a loosening of the gate, and the reason is the window, not the
arithmetic. Every check above still runs on a current file and a failure still
refuses it (rule 6 does not relax). What changes is what a pass is allowed to
become. A current file describes a window that is still open and will be
restated by the closed period that contains it; committing it as a verified
statement would put a new, overlapping "statement" on record every day, each
claiming to have listed its dates completely, and the days between the last
pull and the period's close would never be listed by any of them. So its rows
are stored as rule 7 provisional rows, `UNRECONCILED` and said in words, its
balances are NOT stored as statement anchors (there is no statement), and the
closed `_period_` file is what gets verified: when it commits,
`commit_statement` deletes the provisional rows in its window (rule 7 condition
4) and writes the verified ones. Nothing a current file says is ever read as
fact.

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
the real files). The file NAME is the only identity: one of the two names ticket
09's login script writes, or (card only) BofA's own
`currentTransaction_<last4>.csv`. Drive's " (1)" duplicate suffix is tolerated.
A recognised layout under any other name is refused with a sentence, never filed
under a guessed account or a guessed window.

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
# What BofA prints in the reference column of an undated, not-yet-posted
# temporary credit (seen on a real export 2026-09-28).
PENDING_TEMPORARY_CREDIT = "TEMPRET"
CHECKING_SUMMARY_HEADER = "Description,,Summary Amt."
CHECKING_TABLE_HEADER = "Date,Description,Amount,Running Bal."

NOTHING_WRITTEN = "Nothing was written."

# `currentTransaction_7823.csv`, `bofa_7823_activity_2026-09-27.csv`,
# `bofa_7823_period_2026-09-04.csv`, and any of them with Drive's " (1)" suffix.
# Anchored at the end; case-insensitive because SAF and Drive listings disagree
# on the extension's case.
_FILE_NAME_RE = re.compile(
    r"(?:^|[\\/])(?:"
    r"(?P<legacy>currentTransaction)_(?P<legacy4>\d{4})"
    r"|bofa_(?P<last4>\d{4})_(?P<kind>activity|period)_(?P<date>\d{4}-\d{2}-\d{2})"
    r")(?: \(\d+\))?\.csv$",
    re.ASCII | re.IGNORECASE,
)

# The two windows a name can state.
CURRENT = "current"
PERIOD = "period"


@dataclass(frozen=True)
class FileName:
    """What a BofA CSV's NAME states: the account, and whether the window is
    still open (`CURRENT`) or one closed period (`PERIOD`, ending `period_end`).
    `legacy` is BofA's own `currentTransaction_<last4>.csv`."""

    last4: str
    window: str
    period_end: datetime.date | None = None
    legacy: bool = False
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


# A checking row as BofA writes it: date, quoted description, quoted amount,
# quoted running balance. Anchored at both ends on shapes a description cannot
# take (a date first, two exact amounts last), so the description is exactly
# what lies between, quotes included.
_CHECKING_ROW_RE = re.compile(
    r'^(\d{2}/\d{2}/\d{4}),"(.*)","(-?[\d,]+\.\d{2})","(-?[\d,]+\.\d{2})"$',
    re.ASCII | re.DOTALL,
)


def _checking_row_fields(line: str, where: str) -> list[str]:
    """One checking transaction row. BofA writes a Zelle memo's own quotation
    marks into the description unescaped (`...for "rent"; Conf# ...`), which is
    not valid CSV, and five of Kevin's 13 closed periods (2026-09-28) carried
    one. A row that is valid CSV reads as CSV. One that is not is read by its
    fixed shape, and only if that shape matches exactly; anything else is still
    a refusal. The running balance is checked on every row either way, so a
    misread row fails the gate rather than slipping through (section 4 rule 6)."""
    try:
        return _fields(line, where)
    except statements.ParserRefused:
        match = _CHECKING_ROW_RE.match(line)
        if match is None:
            raise
        return list(match.groups())


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


def read_file_name(file_name: str, layout: str) -> FileName:
    """What the file NAME states (account and window), or a refusal in words.

    The checking layout does not accept BofA's own `currentTransaction_` name:
    only the laptop script's two names say whether the window is closed, and
    for checking that decides between the gate and the provisional path."""
    match = _FILE_NAME_RE.search(file_name)
    if match is not None and match.group("legacy") and layout == "card":
        return FileName(match.group("legacy4"), CURRENT, legacy=True)
    if match is not None and match.group("last4"):
        if match.group("kind").lower() == "activity":
            return FileName(match.group("last4"), CURRENT)
        try:
            end = datetime.date.fromisoformat(match.group("date"))
        except ValueError:
            end = None
        if end is not None:
            return FileName(match.group("last4"), PERIOD, period_end=end)
    expected = (
        "bofa_<last 4>_activity_<YYYY-MM-DD>.csv for current transactions or "
        "bofa_<last 4>_period_<YYYY-MM-DD>.csv for one closed period"
    )
    if layout == "card":
        expected = f"currentTransaction_<last 4>.csv, {expected}"
    raise statements.ParserRefused(
        f"This is Bank of America's {layout} activity export, which prints no account "
        f"number of its own, and its file name does not say which account it is for or "
        f"which window it covers (expected {expected}). {NOTHING_WRITTEN}"
    )


def account_last4_from_name(file_name: str, layout: str) -> str:
    """The last four digits the file NAME states, or a refusal in words."""
    return read_file_name(file_name, layout).last4


# =============================================================================
# Card: provisional
# =============================================================================


def parse_card(lines: list[str], file_name: str) -> statements.Parsed | None:
    """The card export, always provisional (current or closed period alike:
    it prints no anchor either way). None when the first line is not its
    header. Raises `ParserRefused` once the layout is recognised."""
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
    pending = 0
    for number, row in enumerate(rows, start=1):
        where = f"row {number} of this card export"
        fields = _fields(row, where.capitalize())
        if len(fields) != 5:
            raise statements.ParserRefused(
                f"Row {number} of this card export has {len(fields)} columns, not the 5 "
                f"(Posted Date, Reference Number, Payee, Address, Amount) LEGION reads. "
                f"{NOTHING_WRITTEN}"
            )
        posted, reference, payee, _address, amount = fields
        if not posted.strip() and reference.strip() == PENDING_TEMPORARY_CREDIT:
            # A temporary credit (a refund or dispute credit) that has not posted:
            # BofA prints it with no date and TEMPRET where the reference goes.
            # Kevin, 2026-09-28, option (a): left out BY NAME until it posts,
            # counted, and said in words by the writer and the job log. It comes
            # back as an ordinary dated row once it posts. Any OTHER undated row
            # still refuses the file below (rule 6).
            pending += 1
            continue
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

    if not out:
        raise statements.ParserRefused(
            f"This card export lists only {pending} pending temporary credit(s), which are "
            f"not stored until they post. {NOTHING_WRITTEN}"
        )
    payload = {
        "account_last4": last4,
        "account_nickname": CARD_NICKNAME,
        "currency": CURRENCY,
        "lines": out,
        "pending_left_out": pending,
    }
    return statements.Parsed(payload, provisional=True)


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


def parse_checking(lines: list[str], file_name: str) -> statements.Parsed | None:
    """The checking export: gated when its name says it is one closed period,
    provisional when it says it is the current window (module doc). None when
    the first line is not its summary header. Raises `ParserRefused` once the
    layout is recognised."""
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
        fields = _checking_row_fields(row, where.capitalize())
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
    name = read_file_name(file_name, "checking")

    if name.window == CURRENT:
        # Still-open window: provisional (module doc). The arithmetic above has
        # passed, but nothing of it is kept as an anchor: no balances, no
        # period, and no per-row running balance (the rule 7 writer stores
        # none). The closed period is what gets verified.
        payload = {
            "account_last4": name.last4,
            "account_nickname": CHECKING_NICKNAME,
            "currency": CURRENCY,
            "lines": [
                {
                    "txn_date": line["txn_date"],
                    "description": line["description"],
                    "amount_cents": line["amount_cents"],
                }
                for line in out
            ],
        }
        return statements.Parsed(payload, provisional=True)

    if period_end != name.period_end:
        raise statements.ParserRefused(
            f"This file is named as the checking period ending {name.period_end.isoformat()}, "
            f"but its own summary ends on {period_end.isoformat()}. A closed period is only "
            f"verified when the name and the content agree. {NOTHING_WRITTEN}"
        )
    payload = {
        "account_last4": name.last4,
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
    return statements.Parsed(payload, provisional=False)


# =============================================================================
# The registry's side
# =============================================================================


@dataclass(frozen=True)
class BofaActivityParser:
    """`ingest.statements.StatementParser` for one BofA activity-CSV layout.
    Whether a result is provisional is the layout's and the file name's call
    (module doc), so `parse_lines` returns the `Parsed` itself."""

    name: str
    parse_lines: Callable[[list[str], str], statements.Parsed | None]
    kinds: frozenset[str] = frozenset({statements.CSV})

    def parse(self, content: bytes, *, file_name: str) -> statements.Parsed | None:
        lines = _text_lines(content)
        if lines is None:
            return None
        return self.parse_lines(lines, file_name)


CARD = BofaActivityParser("bofa-card-activity-csv", parse_card)
CHECKING = BofaActivityParser("bofa-checking-activity-csv", parse_checking)


def register() -> None:
    """Both layouts join the registry once, and the rule 7 writer with them
    (a provisional parser with no writer would fail every run)."""
    from ingest import provisional

    present = {parser.name for parser in statements.PARSERS}
    for parser in (CARD, CHECKING):
        if parser.name not in present:
            statements.register_parser(parser)
    statements.register_provisional_writer(provisional.commit_provisional)
