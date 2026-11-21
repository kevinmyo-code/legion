"""The turn-level speech rules both voice doors use (engine-brain switch plan, slice E0): what a
reply's sentence may say and when code speaks instead of the model.

Lifted unchanged from the web door (`assistant/cascade.py`, ADR 0102), which imports them from
here; the agent turn (`agents/legion/turn.py`) picks them up in slice E3. Pure functions and tables:
no model, no database, no request.

- **Stop phrase**: an utterance that is nothing but "that will be all" (and its kin) closes the
  conversation by itself (`is_stop_utterance`).
- **Filler**: a reply line that is only "Checking." and its kin is not an answer (`is_filler`);
  a turn whose answer was only filler is asked once more with `FILLER_NOTE`.
- **Money spoken by code** (ADR 0094): a `VERBATIM_TOOLS` result's `speak_verbatim` sentences are
  said as they are (`_verbatim`); after them a model sentence with money in it is dropped
  (`has_money`), and in any turn where a money tool ran, a model sentence stating a decimal figure
  no tool returned is not said (`backed_cents`, `states_unbacked_figure`).

The unbacked-claim block's check is `agents.legion.guards.claims_unbacked_outcome`; the lines code
says are `agents/legion/lines.py`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from engine_mcp.tools import ENDS_TURN, FOR_MODEL, REPLAY_AS, SPEAK_EXACT, SPEAK_VERBATIM

# `about_kevin`: his profile, spoken by code and never by the model (2026-11-18, `demo/tools.py`).
# `show_part`: where a Jeep fastener is and its checked torque (garage, 2026-11-20).
VERBATIM_TOOLS: frozenset[str] = frozenset({"account_balances", "about_kevin", "show_part"})

# The phone's `ScriptedLines.FILLERS` (spec 22): what a reply that opens with only these is
# stripped of. What the web says while a tool runs is per tool (`assistant/fillers.py`).
FILLERS = ("One moment.", "Just a moment.", "On it.", "Checking.", "Let me look.")

# The stop phrases `end_conversation` names, as whole utterances (Kevin, 2026-11-17: "keep listening
# unless user says stop"). Measured in a real browser: the model signed off ("Catch you later") on
# "That will be all, thanks" without calling the tool, so the page kept listening. Only an
# utterance that is nothing but one of these closes by itself; a plain "no" never does.
_STOP_UTTERANCE = re.compile(
    r"^\W*(ok(ay)?[, ]+)?(that('?s| is| will be) all|never ?mind|i'?m good|nothing( else)?|go away|"
    r"go to sleep|stand down|that will do|we'?re done|i'?m done)"
    r"([,.!]*\s+(thanks?( you)?|thank you|for now|alfred))*\W*$",
    re.I,
)


def is_stop_utterance(words: str) -> bool:
    return bool(_STOP_UTTERANCE.match((words or "").strip()))


# ---- money in a sentence (the phone's `MoneyFilter.hasMoney`) ---------------------------------

_SYMBOL = re.compile(r"[$€£]\s?\d|\d+\.\d{2}\b|\d[\d,]*\s*(dollars?|bucks?|cents?)\b", re.I)
_MONEY_WORD = re.compile(r"\b(dollars?|bucks?|cents?)\b", re.I)
_NUMBER_WORDS = frozenset(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
    "fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty "
    "ninety hundred thousand million".split()
)


def _number_word(word: str) -> bool:
    return bool(word) and all(part in _NUMBER_WORDS for part in word.split("-"))


def has_money(sentence: str) -> bool:
    if _SYMBOL.search(sentence) or _MONEY_WORD.search(sentence):
        return True
    words = re.split(r"[^a-z\-]+", sentence.lower())
    return any(_number_word(a) and _number_word(b) for a, b in zip(words, words[1:], strict=False))


# ---- money figures a tool returned (2026-11-18, the chips ship's live gate) ------------------
# A model reply said a category's spend a few cents off the tool's figure, as a bare decimal, and
# nothing compared the two: the money guard above runs only after code has spoken a money line
# itself. Now, in a turn where a money tool ran (one whose result holds an amount in cents: the
# engine's `*_cents` convention, so no list of tools to keep), every decimal figure a model sentence
# states must be one of the amounts the tools returned, or the sentence is not said. Membership
# only: nothing computed, no guess at what the model meant.

_DECIMAL = re.compile(r"(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(?![\d])")
_CENTS_KEY = "_cents"


def decimal_cents(text: str) -> set[int]:
    """Every decimal figure with two places in `text` ("1,962.48", "$84.27", "84.27 USD"), in
    cents, without its sign."""
    return {
        int(whole.replace(",", "")) * 100 + int(cents)
        for whole, cents in _DECIMAL.findall(text or "")
    }


def _cents_in(data, out: set[int]) -> bool:
    found = False
    if isinstance(data, dict):
        for key, value in data.items():
            if key.endswith(_CENTS_KEY) and isinstance(value, int) and not isinstance(value, bool):
                out.add(abs(value))
                found = True
            else:
                found = _cents_in(value, out) or found
    elif isinstance(data, list):
        for item in data:
            found = _cents_in(item, out) or found
    return found


def backed_cents(calls) -> set[int] | None:
    """The amounts this turn's money tools returned, in cents, or None when none ran. A money tool
    is one whose result holds a `*_cents` amount; its backed amounts are those values plus the
    decimal figures ITS OWN text handed the model (code's words, e.g. a total the handler adds up
    and states without storing). Another tool's text (a web page, a record dump) never backs a
    figure (blind review, 2026-11-18)."""
    backed: set[int] = set()
    money = False
    for c in calls:
        if c.ok and _cents_in(c.data, backed):
            money = True
            backed |= decimal_cents(c.text)
    return backed if money else None


def states_unbacked_figure(sentence: str, backed: set[int] | None) -> bool:
    """True when a money tool ran and `sentence` states a decimal figure none of them returned."""
    return backed is not None and not decimal_cents(sentence) <= backed


# ---- torque and fastener sizes (garage, 2026-11-20) -------------------------------------------
# A torque figure said wrong can loosen a steering joint. So while the garage switch is on
# (`garage.flags`), on every member turn, a model sentence may state a torque, a socket size or a
# thread only when `show_part` spoke that very number from the owner's checked table in this
# turn; otherwise the sentence is not said. A web search or the model's own memory can therefore
# never put a torque in his ear. Number words are never backed: code speaks digits. Membership
# only, nothing computed. With the switch off nothing here runs (`backed_torque` answers None).

_WORD_NUMBER = "|".join(sorted(_NUMBER_WORDS, key=len, reverse=True))
_WORDS = rf"(?:{_WORD_NUMBER})(?:[\s-]+(?:and[\s-]+)?(?:{_WORD_NUMBER}))*"
_NUM = rf"(?:\d+(?:\.\d+)?|{_WORDS})"
_RANGE = rf"{_NUM}(?:\s*(?:to|-|–|or|and)\s*{_NUM})?"
_TORQUE_UNIT = (
    r"(?:ft\.?[\s-]*lbs?\b\.?|foot[\s-]*pounds?\b|feet[\s-]*pounds?\b|lbs?\.?[\s-]*f(?:ee)?t\b|"
    r"pounds?[\s-]*(?:feet|foot)\b|newton[\s-]*met(?:er|re)s?\b|n\s?[·.\-]?\s?m\b|"
    r"in(?:ch)?[\s.-]*(?:lbs?|pounds?)\b)"
)
_FIGURES = (
    re.compile(rf"\b({_RANGE})\s*{_TORQUE_UNIT}", re.I),
    re.compile(rf"\b({_NUM})\s*(?:mm|millimet(?:er|re)s?|mil)\b", re.I),
    re.compile(r"\bm(\d{1,2}(?:\s*(?:x|by|×)\s*\d+(?:\.\d+)?)?)\b", re.I),
    re.compile(rf"\b({_NUM})\s*(?:socket|wrench|spanner)\b", re.I),
    re.compile(
        r"\b(\d+\s*/\s*\d+|\d+\s+\d+\s*/\s*\d+|(?:\w+[\s-])?(?:half|quarters?|eighths?|"
        r"sixteenths?))[\s-]*(?:inch|in\.|\")",
        re.I,
    ),
)
_DIGITS = re.compile(r"\d+(?:\.\d+)?")
_HAS_WORD_NUMBER = re.compile(rf"\b(?:{_WORD_NUMBER}|half|quarters?|eighths?|sixteenths?)\b", re.I)


def plain_number(text: str) -> str:
    """'60.0' and '60' alike: the form code and the guard compare in."""
    return text.rstrip("0").rstrip(".") if "." in text else text


def torque_figures(sentence: str) -> list[str]:
    """Every torque, socket-size or thread figure in `sentence`, as said."""
    found = []
    for pattern in _FIGURES:
        found += [m.group(0) for m in pattern.finditer(sentence or "")]
    return found


def backed_torque(calls, on: bool) -> set[str] | None:
    """The numbers `show_part` spoke from checked rows in this turn, or None when the guard is
    off (the switch off, or a recruiter's turn)."""
    if not on:
        return None
    backed: set[str] = set()
    for c in calls:
        if c.ok and c.name == "show_part" and isinstance(c.data, dict):
            backed |= {plain_number(str(n)) for n in c.data.get("spoken_figures") or []}
    return backed


def states_unbacked_torque(sentence: str, backed: set[str] | None) -> bool:
    """True when the guard is on and `sentence` states a torque or size figure code did not say
    from the checked table this turn."""
    if backed is None:
        return False
    for figure in torque_figures(sentence):
        if _HAS_WORD_NUMBER.search(figure):
            return True
        if not {plain_number(n) for n in _DIGITS.findall(figure)} <= backed:
            return True
    return False


# The phone's `FillerTurn` (spec 23): a line of at most four words that opens with a filler opener
# and, after it, only a word that keeps it filler ("Checking now.", "Looking into it.").
FILLER_OPENERS = (
    "checking",
    "looking",
    "let me look",
    "let me check",
    "one moment",
    "just a moment",
    "on it",
    "hold on",
)
FILLER_TAIL_START = frozenset(
    "now into at up for the that this it your those on over through please again".split()
)
FILLER_MAX_WORDS = 4
# The phone's `FillerTurn.NOTE`, with "him" made "them".
FILLER_NOTE = (
    "(System note: the lookup is finished. Give them the answer now, from the tool results "
    "above, in plain words. If it could not be done or the results do not say, tell them that "
    "plainly. Do not say you are checking or looking.)"
)


def is_filler(sentence: str) -> bool:
    """True when `sentence` is nothing but filler. Blank is not filler."""
    core = re.sub(r"[.!?…]+$", "", sentence.strip().lower()).strip()
    if not core:
        return False
    if any(c in ",;:" or c.isdigit() for c in core):
        return False
    words = core.split()
    if len(words) > FILLER_MAX_WORDS:
        return False
    opener = next((o for o in FILLER_OPENERS if core == o or core.startswith(o + " ")), None)
    if opener is None:
        return False
    tail = core[len(opener) :].strip()
    return not tail or tail.split()[0] in FILLER_TAIL_START


@dataclass(frozen=True)
class _Verbatim:
    lines: tuple[str, ...]
    exact: bool
    ends_turn: bool
    for_model: str | None
    replay: str | None


def _verbatim(name: str, result) -> _Verbatim | None:
    """The code-written sentences a `VERBATIM_TOOLS` result asks to be said (`SPEAK_VERBATIM`, one
    sentence or a list) and how (`engine_mcp.tools`), or None."""
    if result is None or name not in VERBATIM_TOOLS:
        return None
    data = result.structured or {}
    said = data.get(SPEAK_VERBATIM)
    raw = [said] if isinstance(said, str) else said if isinstance(said, list) else []
    lines = tuple(line.strip() for line in raw if isinstance(line, str) and line.strip())
    if not lines:
        return None
    for_model = data.get(FOR_MODEL)
    replay = data.get(REPLAY_AS)
    return _Verbatim(
        lines,
        exact=bool(data.get(SPEAK_EXACT)),
        ends_turn=bool(data.get(ENDS_TURN)),
        for_model=for_model if isinstance(for_model, str) and for_model else None,
        replay=replay if isinstance(replay, str) and replay else None,
    )
