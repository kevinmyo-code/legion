"""A relayed result the device asks to be said word for word (engine-brain plan 1119, inventory
row 18): the phone's `VerbatimSpeech` and `DiagFilter`, on the engine's agent turn.

2026-11-17 stage 7: flash-lite turned "No stored engine codes ... pending codes and the other
modules were not read" into "Clean bill". So the phone says its diagnostics read itself and drops
any model sentence that restates it. With the engine as the brain the read is relayed, and the
same two rules live here:

- **Said as written.** A limb tool on the allowlist (`relay.VERBATIM_TOOLS`, read from the limb
  contract file both sides' tests load) whose `ok` result carries `speak_verbatim`, made by that
  same tool (`verbatim_from`), has that line said by code before the model's words. Never a
  read-through tool (its words are outside text), never a late or timed-out result, never past
  `MAX_CHARS`. Anything else is handed to the model as before.
- **The model is told.** It gets the line as already said, and the rest of the result without
  the line, so it has nothing to say again.
- **No restatement.** After such a line, a model sentence naming a trouble code, a module count,
  a verdict word, or a bare filler line is not said (`restates_result`, the phone's
  `DiagFilter.restatesResult`), for the rest of the turn, across a pause.
"""

from __future__ import annotations

import json
import re

from agents.legion.relay import CLASSES_FILE, VERBATIM_TOOLS

__all__ = [
    "CLASSES_FILE",
    "MAX_CHARS",
    "SAID_NOTE",
    "TOOLS",
    "for_model",
    "line_of",
    "restates_result",
]

TOOLS = VERBATIM_TOOLS
# The phone's own diagnostics lines are under 200 characters; anything far longer is not one.
MAX_CHARS = 600
SAID_KEY = "speak_verbatim"
FROM_KEY = "verbatim_from"
SAID_NOTE = (
    'Already said to them, word for word, by code: "{line}". Do not repeat, reword or sum it '
    "up. Add only something new, such as an offer to look a code up, or nothing."
)

_DIGITS = "zero|oh|one|two|three|four|five|six|seven|eight|nine"
_CODE = re.compile(
    rf"\b[pcbu][\s-]?[0-3][0-9a-f]{{3}}\b|\b[pcbu]([\s-]+[0-9a-f]){{4}}\b|\b[pcbu][\s-]+({_DIGITS})\b",
    re.IGNORECASE,
)
_COUNT = re.compile(
    r"\b(\d+|no|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|several|all)\s+"
    r"(modules?|module\(s\)|computers?|controllers?|ecus?)\b",
    re.IGNORECASE,
)
_VERDICT = re.compile(
    r"\b(clear|cleared|clean|fine|healthy|ok|okay|all good|no problems?|no issues?"
    r"|nothing wrong)\b",
    re.IGNORECASE,
)
# The phone's scripted filler lines (`ScriptedLines.FILLERS`), as `LeadingFiller.core` reads them.
_FILLER_CORES = frozenset({"one moment", "just a moment", "on it", "checking", "let me look"})
_ENDS = ".!?…"


def restates_result(sentence: str) -> bool:
    """True when `sentence` restates a diagnostics answer already said: a trouble code ("P0420",
    "P zero four two zero"), a module count, a verdict word, or a bare filler line."""
    core = (sentence or "").strip().rstrip(_ENDS).lower()
    return bool(
        _CODE.search(sentence)
        or _COUNT.search(sentence)
        or _VERDICT.search(sentence)
        or core in _FILLER_CORES
    )


def line_of(req: dict, result: dict) -> str | None:
    """The device's line to say for one relayed call's recorded result, or None."""
    if req.get("name") not in TOOLS or req.get("read_through"):
        return None
    if result.get("outcome") != "ok" or result.get("late"):
        return None
    data = result.get("result")
    if not isinstance(data, dict) or data.get(FROM_KEY) != req["name"]:
        return None
    line = data.get(SAID_KEY)
    if not isinstance(line, str) or not line.strip() or len(line.strip()) > MAX_CHARS:
        return None
    return line.strip()


def for_model(line: str, result: dict) -> str:
    """What the model is handed instead: the line as said, and the rest without it."""
    rest = {k: v for k, v in result["result"].items() if k not in (SAID_KEY, FROM_KEY)}
    return f"{SAID_NOTE.format(line=line)} {json.dumps(rest, ensure_ascii=False)}"
