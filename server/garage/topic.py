"""The words that make speech about front-end hardware, for the torque guard.

`agents.legion.speech_rules.states_unbacked_torque` drops a model sentence holding a number code
did not speak from the checked table this turn, when the sentence is about the front end
(`SENTENCE`) or the whole turn is (`TURN`, matched on what he said and on the reply before it).
Built from the manifest's own aliases, so a part added there is covered here the same day.

`SENTENCE` is wide on purpose (when in doubt, drop). `TURN` leaves out the common English words
some aliases are ("arm", "frame", "spring", "wheel"): a question about the weather this spring
must not silence every number in its answer.
"""

from __future__ import annotations

import re

from garage import xj_front as m

_HARDWARE = (
    "torque", "torqued", "torquing", "retorque", "tighten", "tightened", "tightening", "snug",
    "crank", "ft", "lb", "lbs", "lbf", "pound", "pounds", "newton", "newtons", "nm", "socket",
    "wrench", "spanner", "bolt", "nut", "stud", "screw", "fastener", "thread", "cotter", "castle",
    "spec", "specs", "foot-pound", "foot-pounds",
)  # fmt: skip
# Aliases that are ordinary English words: in SENTENCE, never in TURN.
_COMMON = frozenset(
    {"arm", "frame", "spring", "wheel", "tire", "tyre", "coil", "spacer", "axle", "diff",
     "differential", "hub", "shock", "disconnect", "sleeve", "adjuster", "spindle", "unibody",
     "crossmember", "wheel bearing"}
)  # fmt: skip


def _phrases(include_common: bool) -> list[str]:
    said = {a.lower() for p in m.PARTS for a in p.aliases}
    said |= {a.lower() for f in m.FASTENERS for a in f.aliases}
    if not include_common:
        said -= _COMMON
    said |= set(_HARDWARE)
    # Longest first, so "tie rod" is tried before "tie"; any space or dash between words.
    return sorted(said, key=len, reverse=True)


def _pattern(phrases: list[str]) -> re.Pattern:
    body = "|".join(r"[\s-]+".join(map(re.escape, re.split(r"[\s-]+", p))) for p in phrases)
    return re.compile(rf"\b(?:{body})(?:e?s)?\b", re.I)


SENTENCE = _pattern(_phrases(include_common=True))
TURN = _pattern(_phrases(include_common=False))


def about_front_end(*texts: str) -> bool:
    """Whether any of these (his words, the reply before) is about front-end hardware."""
    return any(TURN.search(t or "") for t in texts)
