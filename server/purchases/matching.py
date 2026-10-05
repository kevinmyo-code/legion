"""When is "shampoo" the same thing as "Head & Shoulders shampoo"?
(purchase-log ticket 02, Kevin 2026-10-04: "find close matches, say what matched").

Pure functions, no database: the REST lookup, the list route's text search and
the `/mcp` tools all call `match_entries`, so the hands path and the voice path
find the same entries (ticket 02's last bullet).

Two layers, in this order:

1. **The floor**: `TickMatch.kt`'s normalisation (lower-case, trim, collapse
   whitespace). "Shampoo " is "shampoo". An entry that equals the query here is
   an EXACT match.
2. **The loose layer**: every word of the query appears as a word of the
   entry, after simple plural folding on both sides. "shampoo" finds "Head &
   Shoulders shampoo"; "shampoos" finds "shampoo". Words, not substrings: "oat"
   does not find "goat milk".

**A loose match is never answered as if exact.** Every result carries the
entry's own text, and `say_last_bought` names it: "You logged Head & Shoulders
shampoo on Sep 20, 2026 (Mia)", never "you bought shampoo on Sep 20". Several
different items match: all of them, newest first, never one picked silently.
No match: "I have no record of buying shampoo", never "you have never bought
it" (ADR 0049's absent-record rule).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta

# Letters and digits in any script ("jalapeños" is one word), never "_".
_WORD = re.compile(r"[^\W_]+")
_EPOCH = date(1970, 1, 1)


def normalise(text: str) -> str:
    """`TickMatch.kt`'s `normalizeForTickMatch`, exactly."""
    return re.sub(r"\s+", " ", (text or "").strip().lower())


def fold(word: str) -> str:
    """Simple plural folding, applied to both sides so it only has to be
    consistent, not linguistically right: batteries -> battery, boxes -> box,
    shampoos -> shampoo. Short words are left alone (gas, bus)."""
    if len(word) > 4 and word.endswith("ies"):
        return word[:-3] + "y"
    if len(word) > 4 and word.endswith(("ches", "shes", "sses", "xes", "zes")):
        return word[:-2]
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def words(text: str) -> frozenset[str]:
    return frozenset(fold(w) for w in _WORD.findall(normalise(text)))


def day_to_date(epoch_day: int) -> date:
    return _EPOCH + timedelta(days=epoch_day)


def date_to_day(value: date) -> int:
    return (value - _EPOCH).days


def say_date(epoch_day: int) -> str:
    d = day_to_date(epoch_day)
    return f"{d:%b} {d.day}, {d.year}"


@dataclass(frozen=True)
class Match:
    """The newest entry for one distinct item text, and how it matched."""

    entry: object
    exact: bool
    times_logged: int


def is_searchable(query: str) -> bool:
    return bool(words(query))


def matches(query: str, item_text: str) -> tuple[bool, bool]:
    """(matched, exact) for one entry's text."""
    if normalise(item_text) == normalise(query):
        return True, True
    wanted = words(query)
    return bool(wanted) and wanted <= words(item_text), False


def match_entries(query: str, entries) -> list[Match]:
    """Every distinct item text that matches `query`, each represented by its
    newest entry, newest first. `entries` are live purchases the caller may
    see (already through `visible()`), in any order."""
    groups: dict[str, list] = {}
    exact_of: dict[str, bool] = {}
    for entry in entries:
        matched, exact = matches(query, entry.item)
        if not matched:
            continue
        key = normalise(entry.item)
        groups.setdefault(key, []).append(entry)
        exact_of[key] = exact
    out = []
    for key, group in groups.items():
        newest = max(group, key=lambda e: (e.bought_on, e.logged_at))
        out.append(Match(entry=newest, exact=exact_of[key], times_logged=len(group)))
    out.sort(key=lambda m: (m.entry.bought_on, m.entry.logged_at), reverse=True)
    return out


def logged_by_name(user) -> str | None:
    """The member's first name, else the part of their email before the @
    (`push/dispatch._display_name`'s rule). None when nobody was recorded."""
    if user is None:
        return None
    return (user.first_name or "").strip() or user.email.split("@")[0]


def say_who(entry) -> str:
    name = logged_by_name(entry.created_by)
    if name is not None:
        return name
    return "who logged it was not recorded"


def say_last_bought(query: str, found: list[Match]) -> str:
    """The sentence ticket 02 asks for. Always names the entry it matched."""
    shown = query.strip()
    if not found:
        return f"I have no record of buying {shown}."
    lines = []
    for match in found:
        entry = match.entry
        line = f"{entry.item} on {say_date(entry.bought_on)} ({say_who(entry)})"
        if entry.source != "MANUAL":
            line += ", ticked off the Groceries list"
        if match.times_logged > 1:
            line += f"; logged {match.times_logged} times in all"
        lines.append(line)
    if len(lines) == 1:
        # Ticket 02's own example, and the entry's text is in it either way.
        return f"You logged {lines[0]}."
    return (
        f"{len(lines)} different entries match {shown!r}, newest first: "
        + "; ".join(lines)
        + ". Say which one is meant rather than picking one."
    )
