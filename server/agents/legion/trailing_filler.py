"""A wait line after the answer began is not said (inventory row 10): the phone's
`TrailingFiller.isFiller` (`app/.../service/cascade/TrailingFiller.kt`), ported.

2026-11-17 phone logs: twice the last thing he heard was "Just a second." / "Give me a second on
that." after a finished answer, and he waited for an answer that never came. The agent turn drops
such a sentence once anything was said this turn (`spoken.py`); a reply that OPENS with one is the
opening-filler rule's (`speech_rules.is_filler`, `SentenceGate.in_answer`).
"""

from __future__ import annotations

import re

from agents.legion.speech_rules import is_filler

_ENDS = ".!?…။。！？"
# "Just a second.", "Give me a second on that.", "One sec.", "Okay, just a moment."
_WAIT = re.compile(
    r"^(ok(ay)?,?\s+)?(just|give me|wait|one|hang on,?|hold on,?)?\s*(a|one)?\s*"
    r"(sec|second|moment|minute|tick)s?(\s+(with|on) (that|this|it))?(,?\s*please)?$"
)
_OTHER = frozenset({"bear with me", "hang on", "hold on", "stand by", "hang tight", "one sec"})


def is_wait_line(sentence: str) -> bool:
    """A sentence that only asks him to wait (an opening filler line, or a wait line)."""
    core = (sentence or "").strip().rstrip(_ENDS).lower()
    if not core:
        return False
    return is_filler(sentence) or core in _OTHER or bool(_WAIT.match(core))
