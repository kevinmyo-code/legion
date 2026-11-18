"""What speech says for the bits of an answer that only read well on screen (2026-11-18, the demo's
speech wording). Speech only: the caption keeps every one of them as written.

- A URL is never read out: it becomes "the link on screen".
- A file path ("server/assistant/cascade.py", "docs/adr/0057-plaid.md", a bare "cascade.py") is
  never read out: it becomes "the file on screen".
- Backticks are never read out; what they hold is said, unless it is one of the above.
- "ADR 0057" is said "ADR 57": the leading zeros are a filing habit, not part of the number.
"""

from __future__ import annotations

import re

LINK = "the link on screen"
FILE = "the file on screen"

_URL = re.compile(r"\b(?:https?://|www\.)[^\s<>()\"'`]+[^\s<>()\"'`.,;:!?]", re.I)
_EXT = r"(?:py|ts|tsx|js|mjs|jsx|md|json|kt|kts|sh|ps1|yml|yaml|toml|txt|html|css|sql|cfg|ini)"
# A path: any name ending in a known file extension (with or without folders), or three or more
# segments joined by / or \. "and/or" and "3/4" are a word and a figure, not paths.
_PATH = re.compile(
    r"(?<![\w@./\\-])(?:\.{0,2}[/\\])?(?:[\w.-]+[/\\])*[\w-]+(?:\.[\w-]+)*\." + _EXT + r"\b"
    r"|(?<![\w@])(?:\.{0,2}[/\\])?(?:[\w.-]+[/\\]){2,}[\w.-]*[\w-]",
    re.I,
)
_TICKS = re.compile(r"`+([^`]*)`+")
_ADR = re.compile(r"\b(ADRs?)[\s-]*0+(\d+)\b")


def _paths_and_links(text: str) -> str:
    text = _URL.sub(LINK, text)
    return _PATH.sub(FILE, text)


def speakable_words(text: str) -> str:
    """`text` as it should be heard (see the module docstring); every other word kept."""
    if not text:
        return text
    text = _TICKS.sub(lambda m: _paths_and_links(m.group(1)), text)
    text = _paths_and_links(text)
    text = text.replace("`", "")
    return _ADR.sub(lambda m: f"{m.group(1)} {m.group(2)}", text)
