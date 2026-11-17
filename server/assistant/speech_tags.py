"""Expression tags in spoken text (Kevin, 2026-11-17: "plain. and with tags where possible").

Gemini 3.8 Flash TTS reads a few inline tags as sounds and never says them aloud in our request
shape (`.legion/reports/tts-styles-1117.md`). They live only on the text sent to speech:

- **Allowed:** `<chuckle>`, `<laugh>`, `<sigh>`, `<short pause>`. Any other tag is removed, and
  `<long pause>` is not allowed.
- **Where:** a few code-written lines carry them on purpose (the demo's opening, the tour's lighter
  lines, a sign-off), and the model may use one now and then (`PROMPT_LINE`). Never on a sentence
  with an amount, a date, a code or any figure, and never on a verbatim, refusal, error or filler
  line: those are spoken with every tag removed (`for_speech(..., expressive=False)`).
- **How many:** at most one per sentence and two per reply (`Budget`).
- **Never anywhere else:** captions, the conversation record, the history sent back to the model,
  logs, the funnel and the live strip, and the figure and honesty checks all see `strip(text)`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

ALLOWED = ("<chuckle>", "<laugh>", "<sigh>", "<short pause>")
MAX_PER_REPLY = 2

PROMPT_LINE = (
    "You may add one of these sound tags, written exactly, very rarely and only where it fits a "
    "light moment: <chuckle>, <laugh>, <sigh>, <short pause>. Never with figures, dates or bad "
    "news, and never any other tag."
)

# Any angle-bracket tag of words (`<long pause>`, `<breath>`, `</b>`), with the space around it.
_TAG = re.compile(r"\s*<\s*/?\s*([A-Za-z][A-Za-z _-]{0,30}?)\s*/?\s*>\s*")
# An amount, a date, a code, a figure: any digit, or a currency or percent sign.
_FIGURE = re.compile(r"[0-9$£€%]")
_SPACE_BEFORE = re.compile(r"\s+([,.!?;:])")


def _tidy(text: str) -> str:
    return _SPACE_BEFORE.sub(r"\1", re.sub(r"\s{2,}", " ", text)).strip()


def strip(text: str) -> str:
    """`text` with every tag removed: what is shown, stored, logged and checked."""
    if "<" not in (text or ""):
        return text or ""
    return _tidy(_TAG.sub(" ", text))


def has_tag(text: str) -> bool:
    return bool(_TAG.search(text or ""))


@dataclass
class Budget:
    """Tags left in one reply."""

    left: int = MAX_PER_REPLY


def for_speech(text: str, budget: Budget | None, *, expressive: bool = True) -> str:
    """What is sent to speech. Keeps the first allowed tag of an expressive sentence while the
    reply has any left; removes every other tag. A sentence with a figure keeps none."""
    if "<" not in (text or ""):
        return text or ""
    plain = strip(text)
    if not expressive or budget is None or budget.left <= 0 or _FIGURE.search(plain):
        return plain
    kept = False

    def one(match: re.Match) -> str:
        nonlocal kept
        tag = f"<{' '.join(match.group(1).lower().replace('_', ' ').split())}>"
        if tag in ALLOWED and not kept:
            kept = True
            return f" {tag} "
        return " "

    out = _tidy(_TAG.sub(one, text))
    if kept:
        budget.left -= 1
    return out
