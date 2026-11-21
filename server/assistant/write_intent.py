"""Did HIS words in this turn ask for a change? (ADR 0090 amended 2026-11-21, re-check R1.)

The carry's one exception (`read_through.note_refused`, `agents/legion/carry.py`): a turn that
refused a write in its own outside read does not carry, so "ask me again" is true. That holds only
when the change was HIS: a page that tells the model to add or delete something must not lift the
carry. So the exception needs his own words in the turn to ask for a change, read here from the
transcript alone (never the model's text, a tool's output or the page).

Pure and deliberately narrow. A change verb counts where he says it as an instruction: at the start
of a clause ("add milk", "please delete the dentist", "could you rename it"), or joined to another
request ("search the fair and add it"). Not after "how to" or "about", not as "adding". FAILS
CLOSED: no words, words in a language it cannot read, or no match is "did not ask", so the next
turn is carried, which costs him one more ask at worst.

The verbs are the write tools' own (`engine_mcp.tools` `writes=True`, the engine twins and the
phone's lists): add, put, remember, save, note, delete, remove, cancel, move, reschedule, set,
change, update, edit, log, record, tick, untick, check off, uncheck, rename, create, make, mark,
schedule, skip, archive, forget, clear, start, finish, swap, lock, accept, dismiss, discard,
draft, write, book, remind. Known misses are pinned in `tests/test_write_intent.py`.
"""

from __future__ import annotations

import re

_VERBS = (
    "add|put|remember|save|note|delete|remove|cancel|move|reschedule|set|change|update|edit|log|"
    "record|tick|untick|check off|uncheck|rename|create|make|mark|schedule|skip|archive|forget|"
    "clear|start|finish|swap|lock|accept|dismiss|discard|draft|write|book|remind"
)
# Words that may stand before the verb at the start of a clause ("could you please add").
_LEAD = (
    r"(?:please|kindly|can you|could you|would you|will you|go ahead and|i want you to|"
    r"i'd like you to|i need you to|you can|ok|okay|now|just|also|then|and|lets|let's|alfred)"
)
# A clause starts at the beginning, after punctuation, or after "and" / "then" / "also" / "but".
_CLAUSE = r"(?:^|[,.;:!?]|\band\b|\bthen\b|\balso\b|\bbut\b)"
_ASK = re.compile(rf"{_CLAUSE}\s*(?:{_LEAD}\s+)*(?:{_VERBS})\b", re.I)


def asks_for_change(words: str | None) -> bool:
    """True when his words ask for a change in so many words; False otherwise (fail closed)."""
    text = (words or "").strip().lower().replace("’", "'")
    return bool(text) and bool(_ASK.search(text))
