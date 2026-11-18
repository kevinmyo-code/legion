"""Figures said like a person, shown exactly (Kevin, 2026-11-17: "round when spoken like a real
person. exact on screen for verification"; `.legion/reports/voice-persona-1117.md` section 1).

Code rounds, never the model. Every sentence keeps its exact figures for captions, the record and
every check; only the text sent to speech is rewritten here (`speakable`), so the exact figure is
on screen in the same turn as the rounded one is heard.

Money:
- under $20: dollars and cents, as written;
- $20 to under $1,000: the nearest dollar;
- $1,000 to under $1,000,000: three significant figures;
- $1,000,000 and up: three significant figures with a unit word, at most one decimal.
"about" goes in front whenever rounding changed the value; the sign is kept.
Percentages: whole numbers; one decimal kept under 10.

Never rounded: anything that is not a dollar amount or a percentage (ids, order numbers, codes,
dates, times, quantities), a sentence that asks to confirm a change (it ends in a question mark),
and every figure in a turn where the person asked for the exact number (`wants_exact`).
Counts are not rounded here: a bare number in free text cannot be told from an id or a year.
"""

from __future__ import annotations

import re
from decimal import ROUND_HALF_UP, Decimal

# "$2,333.61", "-$45", "$ 12.5", "$13.7 million"
_MONEY = re.compile(
    # A minus counts as the amount's sign only at the start or after a space or "(": between two
    # amounts it is a range ("$1,450-$1,500"), never a negative (round 2 speech review 3).
    r"(?P<sign>(?<![^\s(])[-\u2212])?"
    r"\$\s?(?P<num>\d{1,3}(?:,\d{3})+|\d+)(?P<dec>\.\d+)?"
    r"(?:\s?(?P<unit>thousand|million|billion|k\b|m\b|bn\b))?",
    re.I,
)
_PERCENT = re.compile(r"(?<![\w.])(?P<num>\d+\.\d+)\s?(?P<sym>%|percent\b)", re.I)
_EXACT = re.compile(
    r"\b(exact(ly)?|to the cent|precise(ly)?|the exact (number|figure|amount))\b", re.I
)
_UNITS = {"k": 1_000, "thousand": 1_000, "m": 1_000_000, "million": 1_000_000}
_UNITS.update({"bn": 1_000_000_000, "billion": 1_000_000_000})


def wants_exact(words: str | None) -> bool:
    """The person asked for the exact figure ("exactly", "to the cent"): nothing is rounded."""
    return bool(_EXACT.search(words or ""))


def _sig3(value: Decimal) -> Decimal:
    if value == 0:
        return value
    digits = len(str(int(value))) if value >= 1 else 1
    quantum = Decimal(10) ** (digits - 3)
    return (value / quantum).quantize(Decimal(1), rounding=ROUND_HALF_UP) * quantum


def spoken_money(amount: Decimal) -> str:
    """The rounded spoken form of a non-negative amount, digits kept for the voice to read."""
    if amount < 20:
        return f"${amount:,.2f}".replace(".00", "")
    if amount < 1_000:
        rounded = amount.quantize(Decimal(1), rounding=ROUND_HALF_UP)
        text = f"${rounded:,.0f}"
    elif amount < 1_000_000:
        rounded = _sig3(amount)
        text = f"${rounded:,.0f}"
    else:
        rounded = _sig3(amount)
        for word, size in (("billion", 1_000_000_000), ("million", 1_000_000)):
            if rounded >= size:
                scaled = (rounded / size).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
                text = f"${scaled.normalize():f} {word}"
                rounded = scaled * size
                break
    return text if rounded == amount else f"about {text}"


def rounded_cents(cents: int) -> int:
    """What `spoken_money` says, in cents (for the heard-back check)."""
    amount = Decimal(abs(cents)) / 100
    words = spoken_money(amount).removeprefix("about ")
    found = _MONEY.search(words)
    value = _value(found)
    return int((value * 100).to_integral_value()) * (1 if cents >= 0 else -1)


def _value(match: re.Match) -> Decimal:
    value = Decimal(match.group("num").replace(",", "") + (match.group("dec") or ""))
    unit = (match.group("unit") or "").lower()
    return value * _UNITS.get(unit, 1)


# Between two amounts: a range ("$10-$20", "$10 to $20", "$10 and $20"), said exactly.
_RANGE_AFTER = re.compile(r"\s*(?:[-\u2013\u2014]|to|and|or)\s*[-\u2212]?\$", re.I)
_RANGE_BEFORE = re.compile(r"(?:[-\u2013\u2014]|\bto|\band|\bor)\s*$", re.I)
# A sign word RIGHT BEFORE the amount already says it is negative ("overdrawn by -$45.20"): only
# then is "minus" not said a second time. Anywhere else the sign is always said (round 3 S1).
_SIGN_RIGHT_BEFORE = re.compile(
    r"\b(overdrawn|owe|owed|owing|short|negative|minus|deficit of|in the red)(\s+by)?\s*$", re.I
)
# Two amounts with only spaces and a hyphen or dash between them are a range ("$1,450 -$1,500").
_AMOUNT_RIGHT_BEFORE = re.compile(
    r"\$\s?[\d,]+(\.\d+)?(\s?(thousand|million|billion|k|m|bn))?\s*[-–—]?\s*$", re.I
)


def speakable(sentence: str, *, exact: bool = False) -> str:
    """`sentence` as it is said. Rounds only a plain, standalone dollar amount it fully
    understands; anything else is said exactly as written (round 2 speech review B: the failure
    mode is "spoken exactly", never "spoken wrong"). Nothing is rounded when the person asked for
    exact figures, in a sentence that asks to confirm a change, or (the caller's `exact`) in a turn
    in which a change was made."""
    if exact or sentence.rstrip().endswith("?"):
        return sentence

    def money(match: re.Match) -> str:
        before, after = sentence[: match.start()], sentence[match.end() :]
        if _RANGE_AFTER.match(after) or (_RANGE_BEFORE.search(before) and "$" in before):
            return match.group(0)
        if match.group("sign") and _AMOUNT_RIGHT_BEFORE.search(before):
            return match.group(0)
        said = spoken_money(_value(match))
        if match.group("sign") and not _SIGN_RIGHT_BEFORE.search(before):
            said = f"minus {said}"
        return said

    def percent(match: re.Match) -> str:
        value = Decimal(match.group("num"))
        if value < 10:
            kept = value.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
        else:
            kept = value.quantize(Decimal(1), rounding=ROUND_HALF_UP)
        text = f"{kept.normalize():f}{match.group('sym')}"
        return text if kept == value else f"about {text}"

    out = _MONEY.sub(money, sentence)
    out = _PERCENT.sub(percent, out)
    # "about about" when the sentence already hedged.
    return re.sub(r"\b(about|around|roughly|nearly|almost) about\b", r"\1", out, flags=re.I)
