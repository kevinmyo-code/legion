"""Money amounts written in words, in cents (review 1121 F1, the second net behind the digits rule).

The generalist is told to write figures in digits (`agents/specs/generalist.md`), so the money
check can compare them with what the tools returned (`speech_rules.backed_cents`). A model that
still writes an amount in words ("six dollars and fifty cents", "twelve bucks") is checked here
the same way: by membership, never rounded. An amount in words that cannot be read is unbacked.
"""

from __future__ import annotations

import re

from agents.legion.speech_rules import _WORDS as NUMBER_WORDS

_SMALL = {
    w: i
    for i, w in enumerate(
        "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
        "fifteen sixteen seventeen eighteen nineteen".split()
    )
}
_TENS = {
    w: 10 * (i + 2)
    for i, w in enumerate("twenty thirty forty fifty sixty seventy eighty ninety".split())
}
_SCALES = {"thousand": 1_000, "million": 1_000_000}

_AMOUNT = re.compile(
    rf"\b(?P<dollars>{NUMBER_WORDS})[\s-]+(?:dollars?|bucks?)\b"
    rf"(?:\s+and\s+(?P<cents>{NUMBER_WORDS})[\s-]+cents?\b)?"
    rf"|\b(?P<only_cents>{NUMBER_WORDS})[\s-]+cents?\b",
    re.I,
)


def words_value(words: str) -> int | None:
    """"three hundred twelve" -> 312; None when a word is not a number word."""
    total = current = 0
    for word in re.split(r"[\s-]+", words.lower().strip()):
        if not word or word == "and":
            continue
        if word in _SMALL:
            current += _SMALL[word]
        elif word in _TENS:
            current += _TENS[word]
        elif word == "hundred":
            current = (current or 1) * 100
        elif word in _SCALES:
            total += (current or 1) * _SCALES[word]
            current = 0
        else:
            return None
    return total + current


def spelled_cents(sentence: str) -> list[int | None]:
    """Every amount in words in `sentence`, in cents (None for one that cannot be read)."""
    found: list[int | None] = []
    for match in _AMOUNT.finditer(sentence or ""):
        if match.group("only_cents") is not None:
            found.append(words_value(match.group("only_cents")))
            continue
        dollars = words_value(match.group("dollars"))
        cents = words_value(match.group("cents")) if match.group("cents") else 0
        found.append(None if dollars is None or cents is None else dollars * 100 + cents)
    return found


def states_unbacked_spelled(sentence: str, backed: set[int] | None) -> bool:
    """True when a money tool ran and `sentence` states an amount in words none of them returned."""
    if backed is None:
        return False
    return any(c is None or c not in backed for c in spelled_cents(sentence))
