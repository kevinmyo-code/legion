"""Exact statement amounts as integer cents (CLAUDE.md section 4 rule 3).

A port of the phone's deleted `ledger/parsers/LedgerMoney.kt` (itself a port of
Project Andromeda's `duo_ledger.bronze.parsers._money`), last seen at
`ad2d68f^`. Same two regexes, same semantics:

- `parse_money_cents` VALIDATES one token: `[-+]$?digits.cents` with correct
  thousands grouping, or it refuses. No float, no guessing at separators.
- `find_money_tokens` only LOCATES candidates in free text. It stays `-?`, not
  `[-+]?`: a leading `+` falls outside the match, and `+3,200.00` yields
  `3,200.00`, which parses to the same cents.

`re.ASCII` throughout: Kotlin's (Java's) `\\d` is ASCII by default and Python's
is not, and an Arabic-Indic digit must not become money here.
"""
from __future__ import annotations

import re

_MONEY_RE = re.compile(r"([-+]?)\$?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})", re.ASCII)
_MONEY_TOKEN_RE = re.compile(r"-?\$?\d[\d,]*\.\d{2}", re.ASCII)


class MoneyError(ValueError):
    """A token that is not an exact amount as printed."""


def parse_money_cents(token: str) -> int:
    """`-1,234.56` -> -123456. Raises `MoneyError` on anything else."""
    match = _MONEY_RE.fullmatch(token.strip())
    if match is None:
        raise MoneyError(f"cannot parse amount: {token!r}")
    sign, whole, cents = match.groups()
    value = int(whole.replace(",", "")) * 100 + int(cents)
    return -value if sign == "-" else value


def find_money_tokens(text: str) -> list[str]:
    """Candidate amount substrings, left to right, for `parse_money_cents`."""
    return _MONEY_TOKEN_RE.findall(text)


def format_cents(cents: int) -> str:
    """-123456 -> `-$1,234.56`, for quarantine sentences only."""
    sign = "-" if cents < 0 else ""
    whole, part = divmod(abs(cents), 100)
    return f"{sign}${whole:,}.{part:02d}"
