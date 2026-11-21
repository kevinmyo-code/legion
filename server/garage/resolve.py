"""Spoken words to a part or fastener id: a deterministic lookup, never the model's guess.

The model passes the person's own words; code finds the id. Longest alias wins ("tie rod sleeve"
beats "tie rod", "axle nut" beats "axle"); inside the part, the longest joint phrase picks the
fastener; side words narrow it. Code never picks silently when more than one fastener fits:

- A bare "nut" or "bolt" picks nothing: joint phrases made only of such words are not evidence.
- Naming a part also names every other part's fastener that joins it (`xj_front.MEETS`): "the
  pitman arm nut" may be the arm's shaft nut or the drag link's castle nut at the arm, so both
  come back and the model asks.

When two or more still fit (driver or passenger, upper or lower, which end, which part), the
result lists them and the model asks. The model only ever names an id a result returned
(`garage.tool`).
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
# Words that say WHAT KIND of fastener, never WHICH one.
GENERIC = frozenset({"nut", "bolt", "stud", "screw", "fastener"})
# Words that say the person wants a fastener, not the part as a whole.
_FASTENER_INTENT = frozenset(
    {
        "nut",
        "bolt",
        "stud",
        "screw",
        "fastener",
        "castle",
        "torque",
        "socket",
        "size",
        "spec",
        "thread",
        "tighten",
    }
)


def _token(word: str) -> str:
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


# Small numbers said as words name the same joint as digits ("frame bolt two" is "frame bolt 2"):
# code says the candidates in words, so the answer comes back in words.
_COUNT_WORDS = {
    "one": "1", "two": "2", "three": "3", "first": "1", "second": "2", "third": "3",
}  # fmt: skip


def tokens(text: str) -> list[str]:
    words = re.split(r"[^a-z0-9]+", (text or "").lower().replace("'", ""))
    return [_COUNT_WORDS.get(w, _token(w)) for w in words if w]


def _contains(words: list[str], phrase: list[str]) -> bool:
    n = len(phrase)
    return n > 0 and any(words[i : i + n] == phrase for i in range(len(words) - n + 1))


def _best(words: list[str], phrases: tuple[str, ...]) -> int:
    """The token length of the longest phrase found in `words`, or 0."""
    return max((len(p) for p in (tokens(x) for x in phrases) if _contains(words, p)), default=0)


def _joint_score(words: list[str], f: m.Fastener) -> int:
    """How well the words name this fastener's joint; a phrase of only generic words (a bare
    "nut", "stud nut") counts for nothing."""
    specific = tuple(j for j in f.joint_words if not set(tokens(j)) <= GENERIC)
    return _best(words, specific)


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


def _narrow(items: list[m.Fastener], side: str | None) -> list[m.Fastener]:
    return [f for f in items if _side_ok(_fastener_side(f), side)] or items


def _pick_fastener(part: m.Part, words: list[str], side: str | None) -> Match:
    mine = m.fasteners_of(part.id)
    scored = [(f, _joint_score(words, f)) for f in mine]
    top = max((s for _, s in scored), default=0)
    if top:
        narrowed = _narrow([f for f, s in scored if s == top], side)
        if len(narrowed) == 1:
            return Match(FASTENER, part, narrowed[0], side)
        return Match(AMBIGUOUS, part, side=side, candidates=narrowed)
    joining = m.fasteners_meeting(part.id)
    if not set(words) & _FASTENER_INTENT:
        # "Where is the track bar": the part itself, its fasteners listed; a part held by one
        # fastener and joined by no other part's goes straight to it.
        if len(mine) == 1 and not joining:
            return Match(FASTENER, part, mine[0], side)
        return Match(PART, part, side=side)
    every = _narrow(mine + joining, side)
    if len(every) == 1:
        return Match(FASTENER, m.PARTS_BY_ID[every[0].part], every[0], side)
    if not every:
        return Match(PART, part, side=side)
    return Match(AMBIGUOUS, part, side=side, candidates=every)


def _flatten(matches: list[Match]) -> list:
    out, seen = [], set()
    for x in matches:
        items = (
            [x.fastener]
            if x.kind == FASTENER
            else x.candidates
            if x.kind == AMBIGUOUS
            else [x.part]
        )
        for item in items:
            if item.id not in seen:
                seen.add(item.id)
                out.append(item)
    return out


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
        narrowed = _narrow(fasts, side)
        if len(narrowed) == 1:
            f = narrowed[0]
            return Match(FASTENER, m.PARTS_BY_ID[f.part], f, side)
        return Match(AMBIGUOUS, side=side, candidates=narrowed)
    parts = [p for p, s in part_scores if s == top]
    if len(parts) > 1:
        # A tie between parts goes to the one whose joint words were said too ("the track bar's
        # ball joint end" names the track bar, not a ball joint).
        joint = {
            p.id: max((_joint_score(said, f) for f in m.fasteners_of(p.id)), default=0)
            for p in parts
        }
        best = max(joint.values())
        parts = [p for p in parts if joint[p.id] == best]
    narrowed = [p for p in parts if _side_ok(p.side, side)] or parts
    if len(narrowed) > 1:
        picked = _flatten([_pick_fastener(p, said, side) for p in narrowed])
        if len(picked) == 1:
            only = picked[0]
            if isinstance(only, m.Fastener):
                return Match(FASTENER, m.PARTS_BY_ID[only.part], only, side)
            return Match(PART, only, side=side)
        return Match(AMBIGUOUS, side=side, candidates=picked)
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
