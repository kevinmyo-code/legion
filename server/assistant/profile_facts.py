"""The facts a recruiter weighs are said in the profile's own sentences (live demo, 2026-11-18: the
profile said "graduating summer 2027"; the answer said he "graduated").

After `about_kevin` ran in a turn, `ProfileFacts.check(sentence)` decides what is said for a model
sentence about one of those topics (education and graduation, availability, the roles wanted,
experience and employers, work authorisation):

- it stands when the profile says it near word for word (`NEAR` of its words, numbers and tense
  included, are in one profile sentence of that topic);
- otherwise the profile's own sentence on that topic is said in its place, once per turn;
- a topic the profile has no section for is "not shared".

Any other sentence stands. The model's sentence is never edited, only kept or replaced whole, and
only on these topics: the failure mode is a true sentence from the profile, never a wrong one.
"""

from __future__ import annotations

import re

NOT_SHARED_LINE = "Kevin hasn't shared that here; ask him directly."
# The share of a sentence's words that must appear in one profile sentence for it to stand.
NEAR = 0.8

# (what marks a sentence as being about the topic, which profile sections hold that topic)
TOPICS: tuple[tuple[re.Pattern, re.Pattern | None], ...] = (
    (
        re.compile(
            r"\b(?:graduat\w*|GPA|grade point|four[- ]point|degree|universit\w*|college|"
            r"bachelor\w*|master's|majors?|majored|majoring|studying|studies)\b",
            re.I,
        ),
        re.compile(r"educat|availab", re.I),
    ),
    (re.compile(r"\b(?:available|availability|start date)\b", re.I), re.compile(r"availab", re.I)),
    (
        re.compile(r"\b(?:looking for|roles?|positions?)\b", re.I),
        re.compile(r"\brole", re.I),
    ),
    (
        re.compile(
            r"\b(?:intern|interns|interned|internship|employer|worked at|works at|years of "
            r"experience)\b",
            re.I,
        ),
        re.compile(r"experien", re.I),
    ),
    (
        re.compile(
            r"\b(?:visa|sponsor\w*|work authori[sz]ation|authori[sz]ed to work|H-1B\w*|"
            r"citizen\w*|green card|permanent resident)\b",
            re.I,
        ),
        re.compile(r"authori|visa|sponsor", re.I),
    ),
)

_STOP = frozenset(
    "a an and are as at be been but by for from had has have he her him his in is it its of "
    "on or she so that the their them they this to was were which who with kevin".split()
)
_WORD = re.compile(r"[a-z0-9][a-z0-9.+'-]*")
_SENTENCE = re.compile(r"(?<=[.!?])\s+")


def words(text: str) -> set[str]:
    out = set()
    for w in _WORD.findall((text or "").lower().replace("’", "'")):
        w = w.strip(".'-")
        if w and w not in _STOP:
            out.add(w)
    return out


def sentences(text: str) -> list[str]:
    flat = " ".join((text or "").split())
    return [s.strip() for s in _SENTENCE.split(flat) if s.strip()]


class ProfileFacts:
    def __init__(self, profile: dict[str, str]):
        self.sections = {name: sentences(text) for name, text in profile.items()}
        self.said: set[str] = set()

    def _topic_sentences(self, where: re.Pattern | None) -> list[str]:
        if where is None:
            return []
        return [s for name, ss in self.sections.items() if where.search(name) for s in ss]

    def check(self, sentence: str) -> str | None:
        """What to say for `sentence`: itself, a profile sentence in its place, or None (its
        replacement was already said this turn)."""
        topic = next((where for marker, where in TOPICS if marker.search(sentence)), "none")
        if topic == "none":
            return sentence
        candidates = self._topic_sentences(topic)
        if not candidates:
            return self._once(NOT_SHARED_LINE)
        mine = words(sentence)
        best, best_share = candidates[0], -1.0
        for candidate in candidates:
            share = len(mine & words(candidate)) / max(len(mine), 1)
            if share > best_share:
                best, best_share = candidate, share
        if best_share >= NEAR:
            return sentence
        return self._once(best)

    def _once(self, line: str) -> str | None:
        if line in self.said:
            return None
        self.said.add(line)
        return line
