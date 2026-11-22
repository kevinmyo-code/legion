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
  `MAX_CHARS`, never a line stating a money or torque figure (the same detectors the model's
  sentences pass; review 1121 V2). Anything else is handed to the model as before.
- **The model is told.** It gets the line as already said, and the rest of the result without
  the line, so it has nothing to say again.
- **No restatement.** After such a line, a model sentence naming a trouble code or a module
  count, a bare verdict ("Clean bill.", "All good."), or a bare filler line is not said
  (`restates_result`, the phone's `DiagFilter.restatesResult`, narrowed), for the rest of the
  turn, across a pause. Review 1121 V1: a sentence with a safety cue (`SAFETY_CUES`) is ALWAYS
  said, and a verdict word inside other content ("I can clear them if you say yes", "it's clear
  tonight") is not a restatement. The clear offer may be said: clearing still needs his own yes in
  a later turn (the phone's clear-codes gate), so the offer acts on nothing.
"""

from __future__ import annotations

import json
import re

from agents.legion.relay import CLASSES_FILE, VERBATIM_TOOLS
from agents.legion.speech_rules import has_money, torque_figures

__all__ = [
    "CLASSES_FILE",
    "MAX_CHARS",
    "SAFETY_CUES",
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
# A sentence holding any of these is a warning about driving the car: never dropped as a
# restatement, whatever else it says (review 1121 V1). The one list.
SAFETY_CUES = (
    "pull over",
    "stop driving",
    "don't drive",
    "do not drive",
    "flashing",
    "overheating",
    "overheat",
    "smoke",
    "brakes",
    "oil pressure",
    "temperature warning",
)
_SAFETY = re.compile(r"\b(" + "|".join(re.escape(c) for c in SAFETY_CUES) + r")\b", re.IGNORECASE)
# Words a bare verdict about the read is made of ("The engine's clear.", "Clean bill of health."):
# a verdict-word sentence with any other word says something new and is said.
_BARE_VERDICT = frozenset(
    "a all an and are be bill car car's clean clear cleared code codes completely engine engine's "
    "everything everything's fine good health healthy here is issue issues it it's its look "
    "looking looks no nothing of ok okay pretty problem problems right seem seems set so stored "
    "that that's the there this totally very was wrong you're".split()
)
_WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")
# The phone's scripted filler lines (`ScriptedLines.FILLERS`), as `LeadingFiller.core` reads them.
_FILLER_CORES = frozenset({"one moment", "just a moment", "on it", "checking", "let me look"})
_ENDS = ".!?…"


def restates_result(sentence: str) -> bool:
    """True when `sentence` restates a diagnostics answer already said: a trouble code ("P0420",
    "P zero four two zero"), a module count, a bare verdict, or a bare filler line. Never a
    sentence with a safety cue."""
    text = (sentence or "").replace("\u2019", "'")
    if _SAFETY.search(text):
        return False
    core = text.strip().rstrip(_ENDS).lower()
    return bool(
        _CODE.search(text)
        or _COUNT.search(text)
        or (_VERDICT.search(text) and _only_verdict(core))
        or core in _FILLER_CORES
    )


def _only_verdict(core: str) -> bool:
    return all(word in _BARE_VERDICT for word in _WORD.findall(core))


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
    if has_money(line) or torque_figures(line):
        # Code's own line would skip the money and torque checks the model's sentences pass.
        return None
    return line.strip()


def for_model(line: str, result: dict) -> str:
    """What the model is handed instead: the line as said, and the rest without it."""
    rest = {k: v for k, v in result["result"].items() if k not in (SAID_KEY, FROM_KEY)}
    return f"{SAID_NOTE.format(line=line)} {json.dumps(rest, ensure_ascii=False)}"
