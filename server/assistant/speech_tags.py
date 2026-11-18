"""Expression tags in spoken text (Kevin, 2026-11-17: "use as many tags as u can ... so we can make
it feel more natural"; `.legion/reports/voice-persona-1117.md` section 3).

Gemini 3.8 Flash TTS reads inline tags as sounds and never says them aloud in our request shape.
They live only on the text sent to speech:

- **Allowed:** the human sounds on Google's speech page that passed a heard test in our shape on
  2026-11-17 for both voices (`.legion/reports/tag-heard-test-1117.json`: synthesised, transcribed
  back, the tag word never spoken, the length sane). Any other tag is removed; `<long pause>` (about
  1.5 s) and the non-human or distressed sounds are never allowed.
- **Where:** casual sentences, liberally: code-written lines (the opening, the tour, sign-offs, a
  thinking sound on some fillers) and the model (`PROMPT_LINE`). Never on a sentence with a
  figure, date or time, a refusal or error, or a confirmation of a change (`casual`), and never on
  a code line that is not expressive (verbatim figures, failures, the recording notice).
- **How many:** at most one per sentence.
- **Never anywhere else:** captions, the conversation record, the history sent back to the model,
  logs, the funnel and the live strip, and the figure and honesty checks all see `untagged(text)`.
"""

from __future__ import annotations

import re

ALLOWED = (
    "<short pause>",
    "<chuckle>",
    "<chuckles>",
    "<laugh>",
    "<laughter>",
    "<giggle>",
    "<snicker>",
    "<sigh>",
    "<sighs>",
    "<phew>",
    "<gasp>",
    "<breath>",
    "<exhales>",
    "<pff>",
    "<tsk>",
    "<throat-clearing>",
)

PROMPT_LINE = (
    "In casual sentences you may add one sound tag, written exactly: <chuckle> or <laugh> for "
    "something funny, <sigh> or <phew> for relief or a long day, <short pause> before a point, "
    "<breath> before thinking aloud, <tsk> for a small annoyance, <gasp> for a surprise. Use "
    "them freely in light lines, one per sentence at most, never in a sentence with a figure, "
    "date or time, never in bad news, refusals or a question confirming a change, and no other "
    "tags."
)

# Any angle-bracket tag of words (`<long pause>`, `<breath>`, `</b>`), with the space around it.
# A tag is a `<word>` shape only: one word, or one of the few two-word sound tags. Text between a
# "<" and a ">" that holds other words or operators is real text ("if spending < income and
# savings > zero") and is never removed (round 2 speech review 8).
_TAG = re.compile(
    r"\s*<\s*/?\s*([A-Za-z][A-Za-z-]{0,30}(?:\s(?:pause|breath|clearing))?)\s*/?\s*>\s*"
)
# SSML a model may write (`<break time="500ms"/>`). Never spoken, never shown.
_MARKUP = re.compile(
    r"\s*<\s*/?\s*[A-Za-z][\w:-]*(?:\s+[\w:-]+\s*=\s*(?:\"[^\"]*\"|'[^']*'))+\s*/?\s*>\s*"
)
# Words in square brackets or between asterisks: a stage direction ("[laughs]", "*sighs*") is
# removed; anything else ("*really*", "[Reuters]") keeps its words and loses only the marks
# (round 3 speech, small b).
_BRACKETED = re.compile(r"\s*\[([^\[\]]{1,40})\]\s*|\s*\*([^*]{1,40})\*\s*")
_STAGE = re.compile(
    r"\s*(\w+\s+)?(laugh|laughs|laughing|chuckle|chuckles|chuckling|sigh|sighs|sighing|pause|"
    r"short pause|long pause|breath|breathes|breathing|giggle|giggles|giggling|clears throat|"
    r"clearing throat|smile|smiles|smiling|grin|grins|grinning|gasp|gasps|gasping|whisper|"
    r"whispers|whispering|exhale|exhales|inhale|inhales|cough|coughs|sniff|sniffs|tsk|hum|hums|"
    r"nod|nods|shrug|shrugs|phew)(\s+\w+){0,2}\s*",
    re.I,
)


def _bracketed(match: re.Match) -> str:
    inner = match.group(1) if match.group(1) is not None else match.group(2)
    if _STAGE.fullmatch(inner):
        return " "
    lead = " " if match.group(0)[:1].isspace() else ""
    tail = " " if match.group(0)[-1:].isspace() else ""
    return f"{lead}{inner.strip()}{tail}"


def _unmarked(text: str) -> str:
    return _BRACKETED.sub(_bracketed, _MARKUP.sub(" ", text))
# An amount, a date, a code, a figure: any digit, or a currency or percent sign.
_FIGURE = re.compile(r"[0-9$£€%]")
_SPACE_BEFORE = re.compile(r"\s+([,.!?;:])")
_DATE = re.compile(
    r"\b(january|february|march|april|may|june|july|august|september|october|november|december|"
    r"monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|yesterday|"
    r"o'clock|a\.m\.|p\.m\.|noon|midnight)\b",
    re.I,
)
_REFUSAL = re.compile(
    r"\b(can't|cannot|couldn't|won't|unable|not allowed|nothing was|wasn't|isn't possible|"
    r"sorry|error|failed|refus)",
    re.I,
)
_CHANGE = re.compile(
    r"\b(add|log|delete|remove|change|save|set|move|cancel|send|book|update)\b", re.I
)


def _tidy(text: str) -> str:
    return _SPACE_BEFORE.sub(r"\1", re.sub(r"\s{2,}", " ", text)).strip()


def untagged(text: str) -> str:
    """`text` with every tag, markup and stage direction removed: what is shown, stored, logged
    and checked."""
    text = text or ""
    if not any(c in text for c in "<[*"):
        return text
    return _tidy(_TAG.sub(" ", _unmarked(text)))


def has_tag(text: str) -> bool:
    text = text or ""
    return bool(
        _TAG.search(text)
        or _MARKUP.search(text)
        or any(_STAGE.fullmatch(m.group(1) or m.group(2)) for m in _BRACKETED.finditer(text))
    )


# Section 9 (measured): a tag on a short line stretched a five-word sign-off from 2.3 s to 4-5 s.
MIN_TAGGED_WORDS = 8
# A reply that reads out a list gets no tag (Kevin's one "too much" vote, section 11).
_LIST = re.compile(r"(,[^,]+){2,}\band\b|\b(first|second|third)\b.*\b(then|and)\b", re.I)


def casual(plain: str) -> bool:
    """A sentence a tag may go on: at least eight words, no figure, date or time, not a list, not
    a refusal, not a confirmation of a change (`voice-persona-1117.md` sections 3, 9 and 11)."""
    return not (
        len(plain.split()) < MIN_TAGGED_WORDS
        or _LIST.search(plain)
        or _FIGURE.search(plain)
        or _DATE.search(plain)
        or _REFUSAL.search(plain)
        or (plain.rstrip().endswith("?") and _CHANGE.search(plain))
    )


def for_speech(text: str, *, expressive: bool = True) -> str:
    """What is sent to speech. Each sentence is judged on its own (round 4 speech E2: the word
    count was taken over the whole line, so a tag rode on a six-word sentence): a casual sentence
    of an expressive line keeps its first allowed tag; every other tag is removed. A sentence with
    a figure, date or time, a refusal or a confirmation keeps none."""
    text = text or ""
    if not any(c in text for c in "<[*"):
        return text
    if not expressive:
        return untagged(text)
    return _tidy(" ".join(_one_sentence(part) for part in _SENTENCE_END.split(_unmarked(text))))


# Where one sentence ends and the next begins (a tag at the start of the next stays with it).
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _one_sentence(sentence: str) -> str:
    plain = untagged(sentence)
    if not casual(plain):
        return plain
    kept = False

    def one(match: re.Match) -> str:
        nonlocal kept
        tag = f"<{' '.join(match.group(1).lower().replace('_', ' ').split())}>"
        if tag in ALLOWED and not kept:
            kept = True
            return f" {tag} "
        return " "

    return _tidy(_TAG.sub(one, sentence))
