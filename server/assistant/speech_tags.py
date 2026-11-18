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
# Markup and stage directions a model may write: SSML (`<break time="500ms"/>`), "[laughs]",
# "*laughs*". Never spoken, never shown.
_MARKUP = re.compile(
    r"\s*<\s*/?\s*[A-Za-z][\w:-]*(?:\s+[\w:-]+\s*=\s*(?:\"[^\"]*\"|'[^']*'))+\s*/?\s*>\s*"
    r"|\s*\[[A-Za-z][A-Za-z -]{0,30}\]\s*"
    r"|\s*\*[A-Za-z][A-Za-z -]{0,30}\*\s*"
)
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
    return _tidy(_TAG.sub(" ", _MARKUP.sub(" ", text)))


def has_tag(text: str) -> bool:
    return bool(_TAG.search(text or "") or _MARKUP.search(text or ""))


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
    """What is sent to speech. Keeps the first allowed tag of an expressive sentence and removes
    every other tag. A sentence with a figure, date or time, a refusal or a confirmation keeps
    none."""
    text = text or ""
    if not any(c in text for c in "<[*"):
        return text
    plain = untagged(text)
    if not expressive or not casual(plain):
        return plain
    text = _MARKUP.sub(" ", text)
    kept = False

    def one(match: re.Match) -> str:
        nonlocal kept
        tag = f"<{' '.join(match.group(1).lower().replace('_', ' ').split())}>"
        if tag in ALLOWED and not kept:
            kept = True
            return f" {tag} "
        return " "

    return _tidy(_TAG.sub(one, text))
