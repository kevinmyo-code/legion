"""Spoken words to a part or fastener id: a deterministic lookup, never the model's guess.

The model passes the person's own words; code finds the id. Longest alias wins ("tie rod sleeve"
beats "tie rod", "axle nut" beats "axle"); inside the part, the longest joint phrase picks the
fastener; side words narrow it. When two still match (driver or passenger, upper or lower, which
end), the result lists them and the model asks. The model only ever names an id a result
returned (`garage.tool`).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from garage import xj_front as m

PART = "part"
FASTENER = "fastener"
AMBIGUOUS = "ambiguous"
NONE = "none"

# Left and right as sat in the driver's seat (left-hand drive): the spoken line names the side,
# so a person who meant "right as I face the bumper" hears which one is shown.
_SIDE_WORDS = {
    "driver": m.DRIVER,
    "drivers": m.DRIVER,
    "left": m.DRIVER,
    "passenger": m.PASSENGER,
    "passengers": m.PASSENGER,
    "right": m.PASSENGER,
}


def _token(word: str) -> str:
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def tokens(text: str) -> list[str]:
    return [_token(w) for w in re.split(r"[^a-z0-9]+", (text or "").lower().replace("'", "")) if w]


def _contains(words: list[str], phrase: list[str]) -> bool:
    n = len(phrase)
    return n > 0 and any(words[i : i + n] == phrase for i in range(len(words) - n + 1))


def _best(words: list[str], phrases: tuple[str, ...]) -> int:
    """The token length of the longest phrase found in `words`, or 0."""
    return max((len(p) for p in (tokens(x) for x in phrases) if _contains(words, p)), default=0)


def side_of(words: list[str]) -> str | None:
    found = {_SIDE_WORDS[w] for w in words if w in _SIDE_WORDS}
    return found.pop() if len(found) == 1 else None


@dataclass
class Match:
    kind: str
    part: m.Part | None = None
    fastener: m.Fastener | None = None
    side: str | None = None
    candidates: list = field(default_factory=list)


def _side_ok(item_side: str | None, side: str | None) -> bool:
    return side is None or item_side is None or item_side == side


def _fastener_side(f: m.Fastener) -> str | None:
    return f.side or m.PARTS_BY_ID[f.part].side


def _pick_fastener(part: m.Part, words: list[str], side: str | None) -> Match:
    mine = m.fasteners_of(part.id)
    scored = [(f, _best(words, f.joint_words)) for f in mine]
    top = max((s for _, s in scored), default=0)
    if top == 0:
        if len(mine) == 1:
            return Match(FASTENER, part, mine[0], side)
        return Match(PART, part, side=side)
    hits = [f for f, s in scored if s == top]
    narrowed = [f for f in hits if _side_ok(_fastener_side(f), side)] or hits
    if len(narrowed) == 1:
        return Match(FASTENER, part, narrowed[0], side)
    return Match(AMBIGUOUS, part, side=side, candidates=narrowed)


def resolve(words: str, side: str | None = None) -> Match:
    """What `words` name on the model. `side` (an argument) wins over side words said."""
    said = tokens(words)
    side = side or side_of(said)
    part_scores = [(p, _best(said, p.aliases)) for p in m.PARTS]
    fast_scores = [(f, _best(said, f.aliases)) for f in m.FASTENERS]
    top = max([s for _, s in part_scores] + [s for _, s in fast_scores])
    if top == 0:
        return Match(NONE, side=side)
    fasts = [f for f, s in fast_scores if s == top]
    if fasts:
        narrowed = [f for f in fasts if _side_ok(_fastener_side(f), side)] or fasts
        if len(narrowed) == 1:
            f = narrowed[0]
            return Match(FASTENER, m.PARTS_BY_ID[f.part], f, side)
        return Match(AMBIGUOUS, side=side, candidates=narrowed)
    parts = [p for p, s in part_scores if s == top]
    if len(parts) > 1:
        # A tie between parts goes to the one whose joint words were said too ("the track bar's
        # ball joint end" names the track bar, not a ball joint).
        joint = {
            p.id: max((_best(said, f.joint_words) for f in m.fasteners_of(p.id)), default=0)
            for p in parts
        }
        best = max(joint.values())
        parts = [p for p in parts if joint[p.id] == best]
    narrowed = [p for p in parts if _side_ok(p.side, side)] or parts
    if len(narrowed) > 1:
        # One part per side with the same fastener words: ask "driver or passenger" for the
        # fastener said, when one was, so the next call lands on the nut itself.
        picked = [_pick_fastener(p, said, side) for p in narrowed]
        if all(x.kind == FASTENER for x in picked) and len({x.fastener.id for x in picked}) > 1:
            return Match(AMBIGUOUS, side=side, candidates=[x.fastener for x in picked])
        return Match(AMBIGUOUS, side=side, candidates=narrowed)
    return _pick_fastener(narrowed[0], said, side)


def by_id(part_id: str | None, fastener_id: str | None, words: str, side: str | None) -> Match:
    """A follow-up naming an id a result returned. An id not on the model is `none`."""
    if fastener_id:
        f = m.FASTENERS_BY_ID.get(fastener_id)
        if f is None:
            return Match(NONE, side=side)
        return Match(FASTENER, m.PARTS_BY_ID[f.part], f, side)
    part = m.PARTS_BY_ID.get(part_id or "")
    if part is None:
        return Match(NONE, side=side)
    return _pick_fastener(part, tokens(words), side or side_of(tokens(words)))
