"""The words that make speech about front-end hardware, for the torque guard.

`agents.legion.speech_rules.states_unbacked_torque` drops a model sentence holding a number code
did not speak from the checked table this turn, when the sentence is about the front end
(`SENTENCE`) or the whole turn is (`about_front_end`). Built from the manifest's own aliases and
their head nouns ("control arm" gives "arm" too), so a part added there is covered here the same
day. Wide on purpose: when in doubt, drop.

A turn is about the front end when HIS words name hardware or torque intent ("how tight is the
hub?"), or when they are a bare follow-up ("and the other side?", "what was that again?") to a
reply that was about it. A new topic after a front-end reply ("what time is it?") is not.
"""

from __future__ import annotations

import re

from garage import xj_front as m

_HARDWARE = (
    "torque", "torqued", "torquing", "retorque", "tighten", "tightened", "tightening", "tight",
    "tighter", "snug", "crank", "ft", "lb", "lbs", "lbf", "pound", "pounds", "newton", "newtons",
    "nm", "socket", "wrench", "spanner", "bolt", "nut", "stud", "screw", "fastener", "thread",
    "cotter", "castle", "spec", "specs", "foot-pound", "foot-pounds",
)  # fmt: skip
# Head nouns too common to stand alone as a part name.
_NOT_HEADS = frozenset({"end", "assembly", "stop"})

# A bare follow-up: words that lean on the reply before. Ends with any new topic.
_FOLLOW_UP = re.compile(
    r"\b(?:other side|both sides|again|same|that one|this one|the other one|which one|"
    r"(?:first|second|third|last) one|repeat|say that|the rest|what was that)\b",
    re.I,
)


def _phrases() -> list[str]:
    said = {a.lower() for p in m.PARTS for a in p.aliases}
    said |= {a.lower() for f in m.FASTENERS for a in f.aliases}
    heads = {re.split(r"[\s-]+", a)[-1] for a in said if re.search(r"[\s-]", a)}
    said |= heads - _NOT_HEADS
    said |= set(_HARDWARE)
    # Longest first, so "tie rod" is tried before "tie"; any space or dash between words.
    return sorted(said, key=len, reverse=True)


def _pattern(phrases: list[str]) -> re.Pattern:
    body = "|".join(r"[\s-]+".join(map(re.escape, re.split(r"[\s-]+", p))) for p in phrases)
    return re.compile(rf"\b(?:{body})(?:e?s)?\b", re.I)


SENTENCE = _pattern(_phrases())


def about_front_end(words: str, reply_before: str = "") -> bool:
    """Whether this turn is about front-end hardware: his words name it, or they are a bare
    follow-up to a reply that did."""
    if SENTENCE.search(words or ""):
        return True
    return bool(_FOLLOW_UP.search(words or "")) and bool(SENTENCE.search(reply_before or ""))
