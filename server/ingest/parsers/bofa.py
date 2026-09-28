"""Bank of America statement PDFs, read deterministically (backend-etl ticket 10,
CLAUDE.md section 4 rule 1: deterministic first where a deterministic path
exists).

A port of the phone's two deleted parsers, last seen at `ad2d68f^`:
`ledger/parsers/BofaStatementParser.kt` (checking) and
`ledger/parsers/BofaCardStatementParser.kt` (credit card). Their doc comments
record where each rule came from (Kevin's real statements, 2026-08-02/03); the
logic here follows them line for line, with ONE deliberate change, below.

## Two layouts, and the anchors each one prints

| Layout | Opening | Closing | Printed total |
|---|---|---|---|
| Checking | "Beginning balance on ..." | "Ending balance on ..." | none |
| Card | "Previous Balance" (negated) | "New Balance Total" (negated) | none |

Checked inside the parser and not persisted (the statements table has no
column for them): checking, each of the four section totals and opening + net
= closing; card, each printed section total, the summary identity, and all
rows against the summary's net movement.

Neither prints ONE total for the whole statement: checking prints four section
totals, the card prints four summary figures (payments and credits, purchases,
fees, interest) and no net. So `stated_total_cents` is None for both and the
gate stores it NULL (section 4 rule 8: an anchor the source did not state is
recorded as absent, never synthesised from `sum(lines)`). The gate's
DETERMINISTIC two-anchor branch then still requires closing - opening =
sum(lines).

## Signs: the holder's side, both layouts

Money leaving Kevin is negative on every account (Kevin, 2026-08-07). Checking
already prints that way. The card prints the opposite (a purchase positive, a
payment negative), so every card check runs in the DOCUMENT's convention and the
rows are negated once, after every check has passed, exactly as the Kotlin did.

**The card's two balances are negated too, and that is new.** The Kotlin
returned them as printed because nothing downstream compared them to the flipped
rows; the server gate does (closing - opening = sum(lines)), so as printed they
would quarantine every card statement. Negated, a card balance is what the
holder owes as a negative number, which is the convention the Gemini prompt in
`ingest/statements.py` already states for a card.

## The one deliberate change: a stray line inside a section quarantines

The Kotlin checking parser silently DROPPED a non-date line inside a section
that no open row could claim ("trailing boilerplate"). Section 4 rule 6 says a
line the parser does not recognise inside a recognised section is a hard
failure, never a skip, and its own story is this bank's interest rows vanishing
that way. So here every line inside a section's bounds is a transaction row, a
wrapped continuation of the row before it, page furniture, a column header or a
reprinted section header, or the document is refused. The card parser already
worked that way.

## "Not mine" versus "refused"

A layout this module does not recognise returns None, so the registry offers the
file to the next parser and then Gemini: no account line, no balance summary, a
checking section header missing, no card summary block or period line (each an
`UnrecognizedLayoutException` in the Kotlin). Once a layout is recognised, every
failure raises `ParserRefused` and the file is quarantined without Gemini.

**A quarantine reason never quotes the document's text**: `drive_statements`
prints every result to the job's log, and a statement's content is never logged.
Reasons name the section and the line's position, and carry amounts in cents
the way the gate's own reasons do.
"""
from __future__ import annotations

import datetime
import re
from collections.abc import Callable
from dataclasses import dataclass

from ingest import statements
from ingest.parsers.money import MoneyError, find_money_tokens, format_cents, parse_money_cents
from ingest.parsers.pdf_text import UnreadablePdf, extract_text

CHECKING_NICKNAME = "BofA checking"
CARD_NICKNAME = "BofA card"
CURRENCY = "USD"

_A = re.ASCII  # Java's \d, \s and \p{Alpha} are ASCII; Python's are not.

NOTHING_WRITTEN = "Nothing was written."


def _lines(text: str) -> list[str]:
    """Kotlin's `text.lines().map { it.trim() }`: split on \\r\\n, \\n or \\r only
    (`str.splitlines` also splits on form feeds and Unicode separators)."""
    return [line.strip() for line in re.split(r"\r\n|\n|\r", text)]


def _money(token: str, where: str) -> int:
    try:
        return parse_money_cents(token)
    except MoneyError:
        raise statements.ParserRefused(
            f"An amount on {where} is not an exact amount LEGION can read. {NOTHING_WRITTEN}"
        ) from None


def _last4(account_id: str) -> str:
    if len(account_id) < 4:
        raise statements.ParserRefused(
            f"The account number on this statement has fewer than four digits. {NOTHING_WRITTEN}"
        )
    return account_id[-4:]


def _line_ref(file_name: str, line: str) -> str:
    """The Kotlin parsers' own `lineRef`: `<file>:'<first 60 chars of the row>'`."""
    return f"{file_name}:'{line[:60]}'"


# =============================================================================
# Checking
# =============================================================================

_CHK_DATE_RE = re.compile(r"\d{2}/\d{2}/\d{2}", _A)
_CHK_ACCOUNT_RE = re.compile(r"Account (?:number|#)\s*:?\s*([\d ]{4,})", _A)
_CHK_BEGIN_RE = re.compile(r"Beginning balance on [^$]*\$([\d,]+\.\d{2})", _A)
_CHK_END_RE = re.compile(r"Ending balance on [^$]*\$([\d,]+\.\d{2})", _A)
_PAGE_FOOTER_RE = re.compile(r"Page \d+ of \d+", _A)
# A SUBSTRING match: the account line reprinted after a page break carries a
# name before it and a date range after it on the same physical line.
_ACCOUNT_LINE_RE = re.compile(r"Account (?:number|#)\s*:?\s*[\d ]{4,}", _A)
_IGNORED_LINES = frozenset({"continued on the next page"})

# A wire wraps over at most 3 description lines plus an amount-alone line on a
# real statement; this caps the search at about double that.
MAX_CONTINUATION_LINES = 6


@dataclass(frozen=True)
class _ChkSection:
    start: str
    total: str
    positive: bool


_CHK_SECTIONS = (
    _ChkSection("Deposits and other additions", "Total deposits and other additions", True),
    _ChkSection(
        "ATM and debit card subtractions", "Total ATM and debit card subtractions", False
    ),
    _ChkSection("Other subtractions", "Total other subtractions", False),
    _ChkSection("Service fees", "Total service fees", False),
)


def _is_furniture(line: str) -> bool:
    return (
        line in _IGNORED_LINES
        or _PAGE_FOOTER_RE.fullmatch(line) is not None
        or _ACCOUNT_LINE_RE.search(line) is not None
    )


def _first_token(line: str) -> str:
    return line.split(" ", 1)[0]


def _checking_date(token: str, section: str) -> datetime.date:
    """`MM/dd/yy`. Java's `yy` is 2000-2099, not Python's `%y` pivot at 69."""
    month, day, year = (int(part) for part in token.split("/"))
    try:
        return datetime.date(2000 + year, month, day)
    except ValueError:
        raise statements.ParserRefused(
            f'A transaction date in "{section}" is not a real date. {NOTHING_WRITTEN}'
        ) from None


class _NotMine(Exception):
    """Internal: the checking layout is not this document's (a section header
    never appears). Becomes a None return."""


def _checking_section(lines: list[str], section: _ChkSection) -> tuple[list[str], str]:
    """The section's body and its total line.

    Every section name prints twice: once in the summary block, followed by its
    own figure, and once as the table header, followed by a "Date ..." column
    line. Only the second is a start. A page break inside a section reprints the
    header and its "Date" line; both are dropped, which stitches the pieces into
    one body in document order before the total is checked.
    """
    start = next(
        (
            idx
            for idx, line in enumerate(lines)
            if line.startswith(section.start)
            and idx + 1 < len(lines)
            and lines[idx + 1].startswith("Date")
        ),
        None,
    )
    if start is None:
        raise _NotMine
    end = next(
        (idx for idx in range(start + 1, len(lines)) if lines[idx].startswith(section.total)),
        None,
    )
    if end is None:
        raise statements.ParserRefused(
            f'The "{section.start}" section never states its own total. {NOTHING_WRITTEN}'
        )
    body = []
    for idx in range(start + 1, end):
        line = lines[idx]
        if not line or line.startswith("Date"):
            continue
        if line.startswith(section.start) and idx + 1 < end and lines[idx + 1].startswith("Date"):
            continue  # a reprinted header; its "Date" line goes on its own turn
        body.append(line)
    return body, lines[end]


def _checking_rows(body: list[str], section: _ChkSection, file_name: str) -> list[dict]:
    """One row per date-led line, gathering wrapped lines until one carries the
    amount. Section 4 rule 6: every other line is page furniture or a refusal."""
    rows: list[dict] = []
    i = 0
    row_number = 0
    while i < len(body):
        line = body[i]
        if _is_furniture(line):
            i += 1
            continue
        first = _first_token(line)
        if _CHK_DATE_RE.fullmatch(first) is None:
            # The Kotlin dropped this line. Rule 6 forbids that.
            raise statements.ParserRefused(
                f'A line in the "{section.start}" section after row {row_number} is not a '
                f"transaction, part of one, or page furniture, so the section cannot be "
                f"checked. {NOTHING_WRITTEN}"
            )
        row_number += 1

        raw = [line[len(first) :].strip()]
        amount_idx = 0 if find_money_tokens(raw[0]) else -1
        j = i + 1
        while amount_idx < 0 and j < len(body) and (j - i) <= MAX_CONTINUATION_LINES:
            following = body[j]
            if _CHK_DATE_RE.fullmatch(_first_token(following)) is not None:
                break  # the next row began; this one never stated an amount
            if _is_furniture(following):
                j += 1
                continue
            raw.append(following)
            if find_money_tokens(following):
                amount_idx = len(raw) - 1
            j += 1

        if amount_idx < 0:
            raise statements.ParserRefused(
                f'Row {row_number} of the "{section.start}" section is missing an amount. '
                f"{NOTHING_WRITTEN}"
            )

        amount_line = raw[amount_idx]
        token = find_money_tokens(amount_line)[-1]
        amount = _money(token, f'row {row_number} of "{section.start}"')
        if (section.positive and amount < 0) or (not section.positive and amount > 0):
            raise statements.ParserRefused(
                f'Row {row_number} of the "{section.start}" section is signed the wrong way '
                f"for that section. {NOTHING_WRITTEN}"
            )
        txn_date = _checking_date(first, section.start)

        cut = amount_line.rfind(token)
        raw[amount_idx] = (amount_line[:cut] + amount_line[cut + len(token) :]).strip()
        description = " ".join(part for part in raw if part.strip())
        if not description.strip():
            raise statements.ParserRefused(
                f'Row {row_number} of the "{section.start}" section has no description. '
                f"{NOTHING_WRITTEN}"
            )
        rows.append(
            {
                "txn_date": txn_date.isoformat(),
                "description": description,
                "amount_cents": amount,
                "line_ref": _line_ref(file_name, line),
            }
        )
        i = j
    return rows


def parse_checking_text(text: str, file_name: str) -> dict | None:
    """A BofA checking statement's text to the commit payload, or None when the
    layout is not this one. Raises `ParserRefused` once it is."""
    lines = _lines(text)
    account = _CHK_ACCOUNT_RE.search(text)
    if account is None:
        return None
    begin = _CHK_BEGIN_RE.search(text)
    end = _CHK_END_RE.search(text)
    if begin is None or end is None:
        return None
    account_id = re.sub(r"\s+", "", account.group(1))
    opening = _money(begin.group(1), "the beginning balance line")
    closing = _money(end.group(1), "the ending balance line")

    rows: list[dict] = []
    net = 0
    for section in _CHK_SECTIONS:
        try:
            body, total_line = _checking_section(lines, section)
        except _NotMine:
            return None
        section_rows = _checking_rows(body, section, file_name)
        tokens = find_money_tokens(total_line)
        if len(tokens) != 1:
            raise statements.ParserRefused(
                f'The "{section.total}" line does not show a single clear amount. '
                f"{NOTHING_WRITTEN}"
            )
        stated = _money(tokens[0], f'the "{section.total}" line')
        actual = sum(row["amount_cents"] for row in section_rows)
        if actual != stated:
            raise statements.ParserRefused(
                f'The "{section.start}" section does not add up: the statement says '
                f"{format_cents(stated)}, its own lines sum to {format_cents(actual)}. "
                f"{NOTHING_WRITTEN}"
            )
        rows.extend(section_rows)
        net += actual

    if opening + net != closing:
        raise statements.ParserRefused(
            f"This statement's balances do not tie out: it opens at {format_cents(opening)} "
            f"and moves {format_cents(net)}, which lands at {format_cents(opening + net)}, "
            f"not the {format_cents(closing)} it states. {NOTHING_WRITTEN}"
        )

    return {
        "account_last4": _last4(account_id),
        "account_nickname": CHECKING_NICKNAME,
        "currency": CURRENCY,
        # Section 4 rule 8: BofA checking prints no single total. Absent, never
        # sum(lines).
        "stated_total_cents": None,
        "opening_balance_cents": opening,
        "closing_balance_cents": closing,
        # The Kotlin never read a period; the gate falls back to the row dates.
        "period_start": None,
        "period_end": None,
        "lines": rows,
    }


# =============================================================================
# Card
# =============================================================================

_CARD_SUMMARY_MARKER = "Account Summary/Payment Information"
_CARD_TRANSACTIONS_MARKER = "Transactions"
# `Account#` with no space before the `#`, which the checking regex refuses.
_CARD_ACCOUNT_RE = re.compile(r"Account\s*#\s*([\d ]{4,})", _A)
_CARD_PERIOD_RE = re.compile(r"([A-Za-z]+) (\d{1,2}) - ([A-Za-z]+) (\d{1,2}), (\d{4})", _A)
# Greedy description, anchored both ends, so the only split is the rightmost one
# whose last three tokens really are reference, account and amount.
_CARD_ROW_RE = re.compile(
    r"(\d{2}/\d{2})\s+(\d{2}/\d{2})\s+(.+)\s+(\d+)\s+(\d{4})\s+(-?\$?[\d,]+\.\d{2})", _A
)
# Interest and fee rows carry no reference and no account number. Tried only
# after the full form, so a description ending in digits is never mis-split.
_CARD_BARE_ROW_RE = re.compile(r"(\d{2}/\d{2})\s+(\d{2}/\d{2})\s+(.+)\s+(-?\$?[\d,]+\.\d{2})", _A)

_MONTHS = {
    name: number
    for number, name in enumerate(
        (
            "JANUARY",
            "FEBRUARY",
            "MARCH",
            "APRIL",
            "MAY",
            "JUNE",
            "JULY",
            "AUGUST",
            "SEPTEMBER",
            "OCTOBER",
            "NOVEMBER",
            "DECEMBER",
        ),
        start=1,
    )
}


@dataclass(frozen=True)
class _CardSection:
    name: str
    total_prefix: str


_CARD_SECTIONS = (
    _CardSection("Payments and Other Credits", "TOTAL PAYMENTS AND OTHER CREDITS FOR THIS PERIOD"),
    _CardSection("Purchases and Adjustments", "TOTAL PURCHASES AND ADJUSTMENTS FOR THIS PERIOD"),
    _CardSection("Fees Charged", "TOTAL FEES CHARGED FOR THIS PERIOD"),
    _CardSection("Interest Charged", "TOTAL INTEREST CHARGED FOR THIS PERIOD"),
)


@dataclass(frozen=True)
class _YearBounds:
    """A month at or after the period's start month belongs to `start_year`,
    any other to `end_year` (the year printed). Only a December-to-January
    cycle makes the two differ."""

    start_month: int
    start_year: int
    end_year: int

    def year_for(self, month: int) -> int:
        return self.start_year if month >= self.start_month else self.end_year


def _month(name: str) -> int:
    number = _MONTHS.get(name.upper())
    if number is None:
        raise statements.ParserRefused(
            f"This statement's period line names a month LEGION does not recognise. "
            f"{NOTHING_WRITTEN}"
        )
    return number


def _card_summary(summary: str, label: str) -> int:
    """`<label> <amount>` on one line of the summary block. The table header of
    the same name is a bare line and never followed by an amount."""
    match = re.search(re.escape(label) + r"\s+(-?\$?[\d,]+\.\d{2})", summary, _A)
    if match is None:
        raise statements.ParserRefused(
            f'This statement\'s summary has no "{label}" line. {NOTHING_WRITTEN}'
        )
    return _money(match.group(1), f'the summary\'s "{label}" line')


def _card_date(token: str, bounds: _YearBounds, section: str) -> datetime.date:
    month, day = (int(part) for part in token.split("/"))
    try:
        return datetime.date(bounds.year_for(month), month, day)
    except ValueError:
        raise statements.ParserRefused(
            f'A transaction date in "{section}" is not a real date. {NOTHING_WRITTEN}'
        ) from None


def _card_rows(
    body: list[str], section: _CardSection, bounds: _YearBounds, file_name: str
) -> list[dict]:
    """Every non-blank line inside the section is a row, full form or bare
    form, or the document is refused (section 4 rule 6)."""
    rows: list[dict] = []
    for line in body:
        if not line:
            continue
        full = _CARD_ROW_RE.fullmatch(line)
        bare = _CARD_BARE_ROW_RE.fullmatch(line) if full is None else None
        if full is not None:
            date_token, description, amount_token = full.group(1), full.group(3), full.group(6)
        elif bare is not None:
            date_token, description, amount_token = bare.group(1), bare.group(3), bare.group(4)
        else:
            raise statements.ParserRefused(
                f'Line {len(rows) + 1} of the "{section.name}" section does not look like a '
                f"transaction LEGION knows how to read, so the section cannot be checked. "
                f"{NOTHING_WRITTEN}"
            )
        amount = _money(amount_token, f'line {len(rows) + 1} of "{section.name}"')
        rows.append(
            {
                "txn_date": _card_date(date_token, bounds, section.name).isoformat(),
                "description": description.strip(),
                "amount_cents": amount,
                "line_ref": _line_ref(file_name, line),
            }
        )
    return rows


def parse_card_text(text: str, file_name: str) -> dict | None:
    """A BofA credit card statement's text to the commit payload, or None when
    the layout is not this one. Raises `ParserRefused` once it is."""
    if _CARD_SUMMARY_MARKER not in text or "New Balance Total" not in text:
        return None
    account = _CARD_ACCOUNT_RE.search(text)
    if account is None:
        return None
    lines = _lines(text)
    period = next(
        (m for m in (_CARD_PERIOD_RE.fullmatch(line) for line in lines) if m is not None), None
    )
    if period is None:
        return None
    account_id = re.sub(r"\s+", "", account.group(1))

    # Recognised. From here every failure is a refusal.
    start_month = _month(period.group(1))
    end_month = _month(period.group(3))
    printed_year = int(period.group(5))
    bounds = _YearBounds(
        start_month=start_month,
        start_year=printed_year - 1 if start_month > end_month else printed_year,
        end_year=printed_year,
    )

    # The summary block starts at its marker, so the marketing "New Balance
    # Total" printed above it is excluded by position, not by a heuristic.
    summary = text[text.index(_CARD_SUMMARY_MARKER) :]
    previous = _card_summary(summary, "Previous Balance")
    payments = _card_summary(summary, "Payments and Other Credits")
    purchases = _card_summary(summary, "Purchases and Adjustments")
    fees = _card_summary(summary, "Fees Charged")
    interest = _card_summary(summary, "Interest Charged")
    new_balance = _card_summary(summary, "New Balance Total")

    # Layer 2: the summary identity, in the document's own signs.
    computed = previous + payments + purchases + fees + interest
    if computed != new_balance:
        raise statements.ParserRefused(
            f"This statement's own summary does not add up: previous balance "
            f"{format_cents(previous)}, payments {format_cents(payments)}, purchases "
            f"{format_cents(purchases)}, fees {format_cents(fees)} and interest "
            f"{format_cents(interest)} come to {format_cents(computed)}, not the "
            f"{format_cents(new_balance)} it states as the new balance. {NOTHING_WRITTEN}"
        )

    transactions_idx = next(
        (idx for idx, line in enumerate(lines) if line == _CARD_TRANSACTIONS_MARKER), None
    )
    if transactions_idx is None:
        raise statements.ParserRefused(
            f"This statement has no transactions section LEGION recognises. {NOTHING_WRITTEN}"
        )

    rows: list[dict] = []
    net = 0
    for section in _CARD_SECTIONS:
        # Exact equality: the summary line carries an amount, the header is
        # bare. A section that never prints (commonly Fees Charged) adds no
        # rows; layer 3 still catches money hiding behind it.
        start = next(
            (
                idx
                for idx in range(transactions_idx + 1, len(lines))
                if lines[idx] == section.name
            ),
            None,
        )
        if start is None:
            continue
        total_idx = next(
            (
                idx
                for idx in range(start + 1, len(lines))
                if lines[idx].startswith(section.total_prefix)
            ),
            None,
        )
        if total_idx is None:
            raise statements.ParserRefused(
                f'The "{section.name}" section never states its own total. {NOTHING_WRITTEN}'
            )
        tokens = find_money_tokens(lines[total_idx])
        if not tokens:
            raise statements.ParserRefused(
                f'The "{section.name}" section\'s total line shows no amount. {NOTHING_WRITTEN}'
            )
        stated = _money(tokens[-1], f'the "{section.name}" total line')
        section_rows = _card_rows(lines[start + 1 : total_idx], section, bounds, file_name)
        actual = sum(row["amount_cents"] for row in section_rows)
        # Layer 1: the section's rows against its own printed total.
        if actual != stated:
            raise statements.ParserRefused(
                f'The "{section.name}" section does not add up: the statement says '
                f"{format_cents(stated)}, its own lines sum to {format_cents(actual)}. "
                f"{NOTHING_WRITTEN}"
            )
        rows.extend(section_rows)
        net += actual

    # Layer 3: every row, across every section that printed, against the
    # summary's own net movement.
    expected = payments + purchases + fees + interest
    if net != expected:
        raise statements.ParserRefused(
            f"This statement's transactions sum to {format_cents(net)}, but its own summary "
            f"states {format_cents(expected)} in net movement for the period. {NOTHING_WRITTEN}"
        )

    try:
        period_start = datetime.date(bounds.start_year, start_month, int(period.group(2)))
        period_end = datetime.date(printed_year, end_month, int(period.group(4)))
    except ValueError:
        raise statements.ParserRefused(
            f"This statement's period line is not a real date range. {NOTHING_WRITTEN}"
        ) from None

    # Every check above ran in the document's signs and passed. Only now flip
    # to the holder's side: rows AND balances (see the module doc).
    for row in rows:
        row["amount_cents"] = -row["amount_cents"]
    return {
        "account_last4": _last4(account_id),
        "account_nickname": CARD_NICKNAME,
        "currency": CURRENCY,
        # Section 4 rule 8: the card prints four activity figures and no net.
        "stated_total_cents": None,
        "opening_balance_cents": -previous,
        "closing_balance_cents": -new_balance,
        "period_start": period_start.isoformat(),
        "period_end": period_end.isoformat(),
        "lines": rows,
    }


# =============================================================================
# The registry's side
# =============================================================================


@dataclass(frozen=True)
class BofaPdfParser:
    """`ingest.statements.StatementParser` for one BofA PDF layout."""

    name: str
    parse_text: Callable[[str, str], dict | None]
    kinds: frozenset[str] = frozenset({statements.PDF})

    def parse(self, content: bytes, *, file_name: str) -> statements.Parsed | None:
        try:
            text = extract_text(content)
        except UnreadablePdf:
            return None  # not a PDF this can read, so not this layout
        payload = self.parse_text(text, file_name)
        return None if payload is None else statements.Parsed(payload)


CHECKING = BofaPdfParser("bofa-checking-pdf", parse_checking_text)
CARD = BofaPdfParser("bofa-card-pdf", parse_card_text)


def register() -> None:
    """Add both layouts to the registry, once. `IngestConfig.ready` calls it."""
    present = {parser.name for parser in statements.PARSERS}
    for parser in (CHECKING, CARD):
        if parser.name not in present:
            statements.register_parser(parser)
