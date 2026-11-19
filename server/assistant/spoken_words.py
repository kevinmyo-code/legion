"""What speech says for the bits of a MODEL's answer that only read well on screen (2026-11-18,
the demo's speech wording; cut to two rules by blind review round 7). Speech only: the caption
keeps every one of them as written. Never applied to a line code wrote (his profile, a money
line, a fixed line): the Speaker calls it for model sentences only.

- A span the model put in backticks that holds a slash and a file extension, or a slash path of
  three or more segments, is said "the file on screen". Any other backtick span is said as it is.
  The backticks themselves are never said.
- A URL that starts with http:// or https:// is said "the link on screen".
- "ADR 0057" is said "ADR 57": the leading zeros are a filing habit, not part of the number.

Nothing else is replaced: "React, Node.js and Vue.js", "Python/SQL/Django", "11/18/2026" and
"github.com/pat/legion" outside backticks are said as written.

How a word is SAID (`SAY_AS`, `pronounced`) is a separate, smaller rule: a respelling the voice
reads right, for every spoken line, code's own included (a profile line is not reworded by it;
only its sound changes). Speech only: the caption and the record keep the word as written.
"""

from __future__ import annotations

import re

LINK = "the link on screen"
FILE = "the file on screen"

_URL = re.compile(r"\bhttps?://[^\s<>()\"'`]+[^\s<>()\"'`.,;:!?]", re.I)
_TICKS = re.compile(r"`+([^`]*)`+")
_EXT = re.compile(r"\.[A-Za-z][A-Za-z0-9]{0,4}$")
_ADR = re.compile(r"\b(ADRs?)[\s-]*0+(\d+)\b")


def _is_file(span: str) -> bool:
    span = span.strip()
    if not span or any(c.isspace() for c in span):
        return False
    parts = re.split(r"[/\\]", span)
    if len(parts) < 2:
        return False
    if _EXT.search(parts[-1]):
        return True
    return len([p for p in parts if p]) >= 3


def _tick(match: re.Match) -> str:
    span = match.group(1)
    return FILE if _is_file(span) else span


def speakable_words(text: str) -> str:
    """A model sentence as it should be heard (see the module docstring); every other word kept."""
    if not text:
        return text
    text = _URL.sub(LINK, text)
    text = _TICKS.sub(_tick, text).replace("`", "")
    return _ADR.sub(lambda m: f"{m.group(1)} {m.group(2)}", text)


# Speech-only respellings, whole word, any case (Kevin, 2026-11-18: "Quanex" was heard as
# "Quantex"). The respelling is Kevin's pick from the clips in his reports folder; until he picks,
# the word is said as written.
SAY_AS: dict[str, str] = {"Quanex": "Quanex"}


def pronounced(text: str) -> str:
    """`text` with every `SAY_AS` word respelled for the voice; nothing else changed."""
    for word, said in SAY_AS.items():
        if word != said:
            text = re.sub(rf"\b{re.escape(word)}\b", said, text, flags=re.IGNORECASE)
    return text
