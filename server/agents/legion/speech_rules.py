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
# `board_status`: the factory board, spoken by code; the model never sees a card (`board/tool.py`).
# `show_part`: where a Jeep fastener is and its checked torque (garage, 2026-11-20).
VERBATIM_TOOLS: frozenset[str] = frozenset(
    {"account_balances", "about_kevin", "board_status", "show_part"}
)

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
# A torque figure said wrong can loosen a steering joint. So while `show_part` is offered (the
# garage switch on, his account, the cascade), a model sentence may hold a number only when
# `show_part` spoke that very number from the owner's checked table in THIS turn, whenever:
#   - the sentence states a torque, socket or thread figure (`torque_figures`: any foot/pound
#     form, a bare "lbs" or "pounds", N·m, a number in a "torque/tighten" sentence, a size beside
#     a fastener word);
#   - or it names front-end hardware (`topic`, the manifest's aliases and fastener words: "the
#     pitman arm nut takes 185");
#   - or the whole turn is about the front end (`whole_turn`: his words or the reply before them
#     were, or `show_part` ran): then every number counts ("grab a 19 for that", "the other side
#     is also 185", a figure repeated from the history).
# Otherwise the sentence is not said. A web search, the history or the model's memory can never
# put a torque in his ear. Number words are never backed: code speaks digits. Membership only,
# nothing computed. Guard off: `backed_torque` answers None and nothing here runs.

_WORD_NUMBER = "|".join(sorted(_NUMBER_WORDS, key=len, reverse=True))
_WORDS = rf"(?:{_WORD_NUMBER})(?:[\s-]+(?:and[\s-]+)?(?:{_WORD_NUMBER}))*"
_NUM = rf"(?:\d+(?:\.\d+)?|{_WORDS})"
_RANGE = rf"{_NUM}(?:\s*(?:to|-|–|or|and)\s*{_NUM})?"
_SEP = r"[\s.\-·⋅/]*"
_FT = r"(?:ft|foot|feet)"
_LB = r"(?:lbf|lbs|lb|pounds|pound)"
# A bare "lbs" or "pounds" counts too: "run it up to 95 lbs" is a torque under a truck.
_TORQUE_UNIT = (
    rf"(?:{_FT}{_SEP}{_LB}|{_LB}{_SEP}{_FT}|newton{_SEP}met(?:er|re)s?|n{_SEP}m|"
    rf"in(?:ch)?(?:es)?{_SEP}{_LB}|kg{_SEP}m|{_LB})\b"
)
# A size (mm, an inch fraction, a metric thread, "a 13 socket") counts beside a fastener word,
# so "12 mm of rain" or "the M25" is ordinary speech outside a front-end turn.
_TORQUE = re.compile(rf"\b({_RANGE})\s*{_TORQUE_UNIT}", re.I)
_SIZES = (
    re.compile(rf"\b({_NUM})\s*(?:mm|millimet(?:er|re)s?|mil)\b", re.I),
    re.compile(r"\bm(\d{1,2}(?:\s*(?:x|by|×)\s*\d+(?:\.\d+)?)?)\b", re.I),
    re.compile(rf"\b({_NUM})\s*(?:socket|wrench|spanner)\b", re.I),
    re.compile(
        r"\b(\d+\s*/\s*\d+|\d+\s+\d+\s*/\s*\d+|(?:\w+[\s-])?(?:half|quarters?|eighths?|"
        r"sixteenths?))[\s-]*(?:inch|in\.|\")",
        re.I,
    ),
)
_FASTENER_WORD = re.compile(
    r"\b(?:sockets?|wrench(?:es)?|spanners?|bolts?|nuts?|threads?|studs?|hex|allen|castle|"
    r"screws?|fasteners?)\b",
    re.I,
)
_TORQUE_WORD = re.compile(
    r"\b(?:torque\w*|tighten\w*|retorque\w*|snug\w*|tight(?:er|ness)?)\b", re.I
)
# Digits wherever they stand ("27mm", "M7", "3rd" all hold one); number words and fraction words
# ("a half-inch socket", "three-eighths") as whole words.
_ANY_NUMBER = re.compile(
    rf"(?<![\d.])\d+(?:\.\d+)?|\b(?:{_WORDS})\b|"
    r"\b(?:half|halves|quarters?|thirds?|eighths?|sixteenths?)\b",
    re.I,
)


def plain_number(text: str) -> str:
    """'60.0' and '60' alike: the form code and the guard compare in."""
    return text.rstrip("0").rstrip(".") if "." in text else text


def torque_figures(sentence: str) -> list[str]:
    """Every torque, socket-size or thread figure in `sentence`, as said."""
    text = sentence or ""
    found = [m.group(0) for m in _TORQUE.finditer(text)]
    if _TORQUE_WORD.search(text):
        found += sentence_numbers(text)
    if _FASTENER_WORD.search(text):
        for pattern in _SIZES:
            found += [m.group(0) for m in pattern.finditer(text)]
    return found


def sentence_numbers(sentence: str) -> list[str]:
    """Every number in `sentence`: digits, number words, fraction words. A lone "one" is a
    pronoun ("which one"), not a figure; "one eighty-five" still counts through its other words."""
    return [
        m.group(0)
        for m in _ANY_NUMBER.finditer(sentence or "")
        if m.group(0).lower() != "one"
    ]


@dataclass(frozen=True)
class TorqueGuard:
    """What the guard knows in a turn: the numbers code spoke from the checked table, the words
    that make a sentence about the front end, and whether the whole turn is."""

    numbers: frozenset[str]
    topic: re.Pattern
    whole_turn: bool


def backed_torque(calls, topic: re.Pattern | None, about_front_end: bool) -> TorqueGuard | None:
    """The turn's guard, or None when it is off (`topic` None: show_part is not offered)."""
    if topic is None:
        return None
    numbers: set[str] = set()
    ran = False
    for c in calls:
        if c.ok and c.name == "show_part" and isinstance(c.data, dict):
            ran = True
            numbers |= {plain_number(str(n)) for n in c.data.get("spoken_figures") or []}
    return TorqueGuard(frozenset(numbers), topic, about_front_end or ran)


def states_unbacked_torque(sentence: str, guard: TorqueGuard | None) -> bool:
    """True when the guard is on and `sentence` holds a number code did not speak from the
    checked table this turn, in a sentence or a turn about front-end hardware."""
    if guard is None:
        return False
    numbers = sentence_numbers(sentence)
    if not numbers:
        return False
    concerned = guard.whole_turn or bool(torque_figures(sentence)) or bool(
        guard.topic.search(sentence or "")
    )
    if not concerned:
        return False
    return any(not n[0].isdigit() or plain_number(n) not in guard.numbers for n in numbers)


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
