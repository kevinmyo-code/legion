"""Kevin's profile, spoken by code (2026-11-18: "code speaks the profile; the model only chooses").

Asked "Tell me about Kevin.", the model reworded the profile it was handed: a graduation date
moved, a degree level appeared that the profile never states. A check of the model's sentences
against the profile failed review both ways, so the model no longer writes them at all: it picks
SECTIONS by their titles (`about_kevin(sections=[...])`) and this module turns the picked sections
into the exact sentences that are spoken and shown. The model is never handed a sentence of the
profile, so it has nothing to reword.

- `split_sentences`: a section's text as sentences, cut where a sentence ends and nowhere else
  (a line break ends one; "B.S.", "U.S.", "Ph.D.", "e.g." and a single initial do not). When in
  doubt it does NOT cut: two sentences spoken as one are still his words.
- `speak`: the picked sections' sentences, capped at `CAP`, whole sections only, plus at most one
  closing line from a fixed list.

Every example in tests is invented; nothing of the real profile is in this repo.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

NOT_SHARED = "Kevin hasn't shared that here; ask him directly"
NOT_SHARED_LINE = f"{NOT_SHARED}."

# Sentences spoken per answer. Five sentences plus one closing line is six speech requests, what
# one turn's speech reserve covers (`demo.budget`, one cent): a profile answer never needs more.
CAP = 5

# The only lines the model may add, by key. A closing line that names a thing this visit cannot
# show is never said (`offer_line`).
OFFERS = {
    "resume": "Want to see his résumé?",
    "contact": "Want to know how to reach him?",
}
OFFER_KEYS = ("none", *OFFERS)

# Words that end in a full stop without ending a sentence. Lower-cased, without the stop.
_ABBREVIATIONS = frozenset(
    {
        "mr", "mrs", "ms", "dr", "jr", "sr", "st", "prof", "inc", "ltd", "co", "corp",
        "vs", "etc", "approx", "no", "mt", "dept", "univ", "jan", "feb", "mar", "apr",
        "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    }
)  # fmt: skip
# "B.S.", "U.S.", "Ph.D.", "e.g.", "M.Sc.": letters and stops, two stops or more.
_DOTTED = re.compile(r"^(?:[A-Za-z]{1,3}\.){2,}$")
_INITIAL = re.compile(r"^[A-Z]\.$")
# A possible end: . ! or ? (and any closing quote or bracket), then space, then what may open a
# sentence.
_END = re.compile(r"[.!?][\"'”’)\]]*\s+(?=[A-Z0-9\"'“‘(\[])")
_BULLET = re.compile(r"^\s*(?:[-*•·]|\d+[.)])\s+")


def _ends_here(text: str, stop_at: int) -> bool:
    """Whether the stop at `stop_at` ends a sentence: not when the word it closes is an
    abbreviation, a dotted acronym or a single initial."""
    if text[stop_at] != ".":
        return True
    start = text.rfind(" ", 0, stop_at) + 1
    word = text[start : stop_at + 1].lstrip("(\"'“‘[")
    if _DOTTED.match(word) or _INITIAL.match(word):
        return False
    return word[:-1].lower() not in _ABBREVIATIONS


def split_sentences(text: str) -> list[str]:
    """`text` as sentences, in order, every character of each kept (but a list bullet)."""
    out: list[str] = []
    for raw in (text or "").splitlines():
        line = _BULLET.sub("", raw).strip()
        if not line:
            continue
        start = 0
        for match in _END.finditer(line):
            if _ends_here(line, match.start()):
                piece = line[start : match.end()].strip()
                if piece:
                    out.append(piece)
                start = match.end()
        rest = line[start:].strip()
        if rest:
            out.append(rest)
    return out


@dataclass(frozen=True)
class Section:
    id: str
    title: str
    sentences: tuple[str, ...]


def _slug(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_") or "section"


def sections_of(profile: dict | None) -> list[Section]:
    """The profile's sections in its own order, each with a stable id from its title. A section
    with no text is left out (there is nothing to say)."""
    out: list[Section] = []
    taken: set[str] = set()
    for title, text in (profile or {}).items():
        sentences = tuple(split_sentences(text if isinstance(text, str) else ""))
        if not sentences:
            continue
        base = _slug(str(title))
        sid, n = base, 2
        while sid in taken:
            sid, n = f"{base}_{n}", n + 1
        taken.add(sid)
        out.append(Section(sid, str(title).strip(), sentences))
    return out


def _titles(sections: list[Section]) -> str:
    names = [s.title for s in sections]
    if len(names) <= 1:
        return "".join(names)
    return ", ".join(names[:-1]) + " and " + names[-1]


@dataclass(frozen=True)
class Spoken:
    lines: tuple[str, ...]
    # The sections read (titles), for the record's history marker; empty for "not shared".
    read: tuple[str, ...]
    # The closing line that names what was left out, if anything was.
    more: str | None


def speak(sections: list[Section], chosen: list[str], rest_of: str | None = None) -> Spoken:
    """What is said for the picked section ids. Unknown ids are ignored; none left is "not shared".

    The cap: when the picked sections hold more than `CAP` sentences, whole sections are taken
    shortest first while they fit (so a pointed question about a short section is never crowded
    out by a long one) and spoken in the order the model picked them; the ones left out are named
    in one line. A single section longer than `CAP` gives its first `CAP` sentences, and
    `rest_of=<its id>` gives the next `CAP`. No sentence is ever cut or picked from inside a
    section otherwise."""
    by_id = {s.id: s for s in sections}
    if rest_of in by_id:
        section = by_id[rest_of]
        rest = section.sentences[CAP:] or section.sentences
        more = None
        if len(rest) > CAP:
            more = f"There's more on {section.title} than I can read out here; ask him directly."
        return Spoken(tuple(rest[:CAP]), (section.title,), more)
    picked = [by_id[i] for i in dict.fromkeys(chosen or []) if i in by_id]
    if not picked:
        return Spoken((NOT_SHARED_LINE,), (), None)
    if sum(len(s.sentences) for s in picked) <= CAP:
        kept = picked
    else:
        order = sorted(picked, key=lambda s: (len(s.sentences), picked.index(s)))
        kept, used = [], 0
        for section in order:
            if used + len(section.sentences) <= CAP:
                kept.append(section)
                used += len(section.sentences)
        if not kept:
            first = order[0]
            left = [s for s in picked if s is not first]
            more = f"There's more on {_titles([first, *left])}; just ask."
            return Spoken(first.sentences[:CAP], (first.title,), more)
    left = [s for s in picked if s not in kept]
    lines = tuple(line for s in picked if s in kept for line in s.sentences)
    more = f"There's more on {_titles(left)}; just ask." if left else None
    return Spoken(lines, tuple(s.title for s in picked if s in kept), more)


def offer_line(offer: str | None, *, has_resume: bool, has_contact: bool) -> str | None:
    """The model's chosen closing line, only when this visit can show what it offers."""
    if offer == "resume" and has_resume:
        return OFFERS["resume"]
    if offer == "contact" and has_contact:
        return OFFERS["contact"]
    return None
