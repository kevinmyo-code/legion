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
- `speak`: the picked sections' sentences in his own order, capped at `CAP`, whole sections only,
  plus at most one closing line from a fixed list.

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

# Where the loader keeps the profile's own section order, beside it in the content blob.
PROFILE_ORDER = "profile_order"

# The pick for a general question about him ("Tell me about Kevin."): every section from the top
# of his file, so what leads is his choice, by the order he wrote them, never the model's guess
# from titles (the 2026-11-18 replay: Location, Availability and Roles, never Experience).
OVERVIEW = "overview"

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


def sections_of(profile: dict | None, order: list | None = None) -> list[Section]:
    """The profile's sections in the order Kevin wrote them, each with a stable id from its title.

    Postgres keeps a JSON object's keys in its own order, so the loader stores the file's order
    beside the profile (`PROFILE_ORDER`, `demo.content.load`); a row loaded before that has none
    and keeps the stored order. A section with no text is left out (there is nothing to say)."""
    profile = profile or {}
    titles = [t for t in (order or []) if t in profile]
    titles += [t for t in profile if t not in titles]
    out: list[Section] = []
    taken: set[str] = set()
    for title in titles:
        text = profile[title]
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


@dataclass(frozen=True)
class Spoken:
    lines: tuple[str, ...]
    # The sections read (titles), for the record's history marker; empty for "not shared".
    read: tuple[str, ...]
    # The closing line that names what was left out, if anything was.
    more: str | None


MORE = "There's more; just ask."


def _more_on(sections: list[Section]) -> str:
    return f"There's more on {', '.join(s.title for s in sections)}; just ask."


def speak(sections: list[Section], chosen: list[str], rest_of: str | None = None) -> Spoken:
    """What is said for the picked section ids. Unknown ids are ignored; none left is "not shared".

    The cap: the picked sections are spoken WHOLE, in the order Kevin wrote them (his own order of
    importance), while they fit in `CAP` sentences; the first that does not fit and every one after
    it are named in one line, in the same order. When the first picked section alone is longer
    than `CAP`, its first `CAP` sentences are said and the line says there is more (naming the
    others too, when others were picked); `rest_of=<its id>` gives the next `CAP`. No sentence is
    ever cut or picked from inside a section otherwise."""
    by_id = {s.id: s for s in sections}
    if rest_of in by_id:
        section = by_id[rest_of]
        rest = section.sentences[CAP:] or section.sentences
        more = None
        if len(rest) > CAP:
            more = f"There's more on {section.title} than I can read out here; ask him directly."
        return Spoken(tuple(rest[:CAP]), (section.title,), more)
    wanted = set(chosen or [])
    # `overview` with named ids too is still the overview: his order decides.
    picked = sections if OVERVIEW in wanted else [s for s in sections if s.id in wanted]
    if not picked:
        return Spoken((NOT_SHARED_LINE,), (), None)
    first = picked[0]
    if len(first.sentences) > CAP:
        more = _more_on(picked) if len(picked) > 1 else MORE
        return Spoken(first.sentences[:CAP], (first.title,), more)
    kept: list[Section] = []
    used = 0
    for section in picked:
        if used + len(section.sentences) > CAP:
            break
        kept.append(section)
        used += len(section.sentences)
    left = picked[len(kept) :]
    lines = tuple(line for s in kept for line in s.sentences)
    return Spoken(lines, tuple(s.title for s in kept), _more_on(left) if left else None)


def offer_line(offer: str | None, *, has_resume: bool, has_contact: bool) -> str | None:
    """The model's chosen closing line, only when this visit can show what it offers."""
    if offer == "resume" and has_resume:
        return OFFERS["resume"]
    if offer == "contact" and has_contact:
        return OFFERS["contact"]
    return None
