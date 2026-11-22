"""The unconfirmed write (engine-brain switch plan 1119, section 2), on the agent turn only.

A write that went through but that the reply never named is said by code, so a lone "Okay." never
hides a change: "Done: added made-up stamps to Todo." A write the turn cannot confirm (a relayed
write whose result never came back: `timed_out`) is said as not confirmed, in the phone's words
(`CascadeRules.doneLine` / `unconfirmedLine`). The web door does not say these: adding them there
would change its live answers (slice E3's rule: the web door stays as it is).

The verb comes from the tool's name only where the name makes it certain; otherwise the generic
line. Titles are the write's own (`gateway.write_fact`), never its arguments.
"""

from __future__ import annotations

from agents.legion.guards import claims_unbacked_outcome
from agents.legion.lines import CHANGE_MADE

# The phone's `GENERIC_DONE` is the web door's CHANGE_MADE: one sentence for both.
GENERIC_DONE = CHANGE_MADE
UNCONFIRMED_ONE = "I couldn't confirm that change went through."
MAX_CLAUSES = 3

# By the tool name's start: the phone's `PHRASES` for the verbs an engine tool name settles.
_VERBS = (
    ("add_", "added %s"),
    ("tick_", "ticked off %s"),
    ("log_", "logged %s"),
    ("create_", "created %s"),
    ("remove_", "removed %s"),
    ("delete_", "removed %s"),
    ("remember", "saved %s"),
)


def _clause(fact: dict) -> str | None:
    title = (fact.get("title") or "").strip()
    tool = fact.get("tool") or ""
    phrase = next((p for start, p in _VERBS if tool.startswith(start)), None)
    if not title or phrase is None:
        return None
    to = f" to {fact['list'].strip()}" if fact.get("list") and phrase.startswith("added") else ""
    return phrase % title + to


def done_line(facts: list[dict], said: str) -> str | None:
    """What code says for writes the reply did not name, or None. A write is named when its title
    is in what was said (case folded). A write with no title or no certain verb gets the generic
    line, only when the reply claimed no change at all."""
    heard = said.casefold()
    unnamed = []
    for fact in facts:
        title = (fact.get("title") or "").strip().casefold()
        if not title or title not in heard:
            unnamed.append(fact)
    if not unnamed:
        return None
    clauses = [c for c in map(_clause, unnamed) if c is not None]
    generic = len(clauses) < len(unnamed)
    parts = []
    if clauses:
        kept = clauses[:MAX_CLAUSES]
        more = len(clauses) - len(kept)
        parts.append("Done: " + "; ".join(kept + ([f"and {more} more"] if more else [])) + ".")
    if generic and not clauses and not claims_unbacked_outcome(said, []):
        parts.append(GENERIC_DONE)
    return " ".join(parts) or None


def unconfirmed_line(count: int) -> str | None:
    """The phone's line for writes whose result never came back."""
    if count == 1:
        return UNCONFIRMED_ONE
    if count > 1:
        return f"I couldn't confirm that {count} changes went through."
    return None
