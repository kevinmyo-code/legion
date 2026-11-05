"""Vault identifiers never become memories (memory-and-vault map, decision E; Kevin's ruling 5).

Passport, NRIC, SSN, A-number and bank account numbers live in the ID vault, phone only, behind
a fingerprint, and are never sent to a model. A memory is read back to a model on every recall,
so one of those numbers in a memory would undo the vault. This is the one deterministic check,
applied at every door a memory comes through: REST create and update, the `remember` tool, the
one-time import and the consolidation job's output.

**Low-sensitivity identifiers pass** (ruling 5): a plate, a VIN, an insurance policy number are
ordinary data. A VIN is 17 letters and digits mixed, never a long run of digits alone, so it
never matches the digit-run rule below.

It is a filter on shape, so it is deliberately broad: a long run of digits is refused whatever
it is (an insurance policy number of nine plain digits included, the known false refusal), and
the refusal says where such a number belongs instead. A ten-digit phone number passes. It cannot
catch a number spelled out in words; the consolidation prompt asks the model never to keep one.
"""

from __future__ import annotations

import re

# Words that name a vault kind. Refused only when a digit is present too, so "renew my passport
# in March" is a fine memory and "passport E1234567" is not.
_KIND_WORDS = re.compile(
    r"\b(passport|nric|fin\s+number|ssn|social\s+security|a[-\s]?number|alien\s+(registration\s+)?"
    r"number|uscis\s+number|bank\s+account|account\s+number|routing\s+number|iban|swift)\b",
    re.IGNORECASE,
)
_ANY_DIGIT = re.compile(r"\d")
_SHAPES = (
    # US SSN, with or without separators.
    re.compile(r"\b\d{3}[-\s]\d{2}[-\s]\d{4}\b"),
    # Singapore NRIC / FIN: S, T, F, G or M, seven digits, a check letter.
    re.compile(r"\b[STFGM]\d{7}[A-Z]\b", re.IGNORECASE),
    # USCIS A-number: A and 8 or 9 digits.
    re.compile(r"\bA[-\s]?\d{8,9}\b", re.IGNORECASE),
    # Nine or more digits in one unbroken run (an SSN without separators, an account number).
    re.compile(r"\d{9,}"),
    # Eleven or more digits with spaces or dashes between groups (a spoken account or card
    # number). Ten is a phone number, which is ordinary data and stays allowed.
    re.compile(r"\d(?:[\s-]?\d){10,}"),
)

REFUSAL = (
    "Nothing was saved. That looks like an identity or bank number (a passport, NRIC, SSN, "
    "A-number or account number), and those are never kept in memory: they belong in the ID "
    "vault on the phone, behind the fingerprint."
)


def looks_sensitive(text: str) -> bool:
    """True when `text` carries a vault identifier's shape."""
    if any(shape.search(text) for shape in _SHAPES):
        return True
    return bool(_KIND_WORDS.search(text) and _ANY_DIGIT.search(text))
