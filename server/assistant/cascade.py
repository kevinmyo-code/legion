"""The web assistant's cascade turn (ADR 0102): speech-to-text, the text model's tool loop over
the web assistant's own tool door, text-to-speech, streamed back as events.

Kevin, 2026-11-17: *"recruiter demo > can it [go] to our current cloud pipeline too"*, then *"the
exact same legion cloud pipeline, par the wakeword"* and *"same voice behavior across
everything"*. The phone's cloud voice (`app/.../service/cascade/`) on the engine, for the
browser, following the behaviour spec taken from the phone's code
(`.legion/reports/voice-behaviour-spec-1117.md`, items cited as "spec N"). The browser posts one
utterance (16 kHz PCM16 mono), one typed message or a greeting request; nothing it holds is a
credential to Google.

## What it is built from

- **The loop is the engine runtime's** (`agents.runtime.loop.run_loop`, `GeminiClient`), the same
  generic loop the dark agent turn uses: rounds, the tool-free last round with the phone's nudge,
  streamed text cut into whole sentences, per-round token counts (spec 6, 7, 8). Nothing of
  `agents/legion` (its router, specs, confirm gate or turn tables) is used, and
  `/api/agents/turn` is untouched. No runtime file is changed.
- **The prompt prefix is cached** (`assistant/prompt_cache.py`, spec 9): the stable body plus the
  phone's voice clause (spec 11) and the declarations; the clock and who is speaking ride at the
  head of each turn as the phone's "(Current context ...)" note (spec 10).
- **The tools are the web tool door's**, called the way `POST /api/assistant/tool` calls them
  (`WebDoor` below): `tools_for(user)` declares them (a recruiter's `RECRUITER_TOOLS`, narrowed
  tables, its own procedures and diagrams), `refusal_for` fences each call (default deny for a
  recruiter), `recruiter_only_tool` / `run_engine_tool` runs it under the caller's own
  `visible()`, every call is audited (`AssistantCall`) and written to the conversation record, and
  a card goes to the screen, never to the model. So a recruiter's fence on this path is the Live
  path's fence, the same code. **One addition, for every role: `end_conversation`** (spec 18), a
  control the page acts on; it reaches no data and no engine tool.
- **ADR 0090** holds by the tool door's own guard: one HTTP request is one turn, a third-party
  tool marks the request before it runs (`read_through.before_tool`) and every write after it in
  the same request is refused in words by `run_engine_tool`. The turn is also marked on the
  record (`read_through.set_turn` with this turn's number, never None), as the Live door does.
- **History** is the conversation record's own engine-written `user` / `assistant` lines
  (`web_conversation.history`), last `HISTORY_TURNS` exchanges: no new table, and a browser upload
  can never put words in the assistant's mouth.
- **Speech**: each sentence to speech as soon as it is whole, at most two at once, played in order
  (spec 5). A filler line from memory when the model goes to a slow tool before saying anything
  (spec 22); a reply that opens with exactly a filler line loses it (spec 23, first half).
- **The greeting on open** (spec 25 to 29) is `assistant/web_greeting.py`.

## Money is spoken by code (ADR 0094, spec 30)

A tool in `VERBATIM_TOOLS` whose result carries `structured["speak_verbatim"]` (today:
`account_balances`' net cash sentence) has that sentence spoken and shown as it is, once per
turn, never through the model; after it, any sentence of the model's that carries a money amount
is dropped (`has_money`, the phone's `MoneyFilter`), counted, never logged as text. Every other
figure on this path is still the model's wording of a code-written tool result, as on Live.

## Cost guard

The caller checks the budget before this runs (`assistant/views.py`): a recruiter's turn reserves
its worst case (`demo.budget.reserve_cascade_turn`), a member's cap (`llm/cap.py`) must not be
spent. Here: at most `MAX_ROUNDS` model calls, `MAX_TOOL_CALLS` tool calls, `MAX_SPOKEN_SENTENCES`
spoken sentences, and the runtime's wall-clock limits. Each stage's timing and token counts go in
one `cascade_turn` line on the record and in the log, numbers only, never words.
"""

from __future__ import annotations

import base64
import dataclasses
import functools
import logging
import queue
import re
import threading
import time
from collections import deque
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime

from django.db import DatabaseError, transaction

from agents.legion.gateway import SLOW_TOOLS, rest_declarations
from agents.legion.guards import claims_unbacked_outcome
from agents.runtime import events as ev
from agents.runtime.budget import BudgetUsed, LoopLimits
from agents.runtime.loop import (
    BUDGET_WORDS,
    MODEL_WORDS,
    NO_ANSWER_WORDS,
    TIMEOUT_WORDS,
    LoopHooks,
    SentenceSplitter,
    run_loop,
)
from agents.runtime.model import (
    Finish,
    ModelClient,
    ModelFailure,
    ModelRequest,
    PartEvent,
    TextDelta,
    ToolCallEvent,
    Usage,
    Waiting,
)
from agents.runtime.spec import AgentSpec
from agents.runtime.tools import ToolCall, ToolOutcome
from assistant import echo, fillers, read_through, speech_tags, spoken_figures, tour, web_greeting
from assistant.models import AssistantCall
from assistant.speech import OUTPUT_RATE, GeminiSpeech, SpeechFailure, Spoken
from assistant.spoken_words import pronounced, speakable_words
from assistant.surface import recruiter_only_tool, refusal_for, tools_for
from demo import suggestions
from demo.tools import spoken_for as about_kevin_spoken_for
from devicelogs import web_conversation
from engine_mcp import cards
from engine_mcp.models import Outcome
from engine_mcp.tools import (
    ENDS_TURN,
    FOR_MODEL,
    REPLAY_AS,
    SPEAK_EXACT,
    SPEAK_VERBATIM,
    ToolResult,
    run_engine_tool,
    run_tool,
)
from household.models import is_recruiter
from household.tenancy import household_of
from legion import deadline
from llm import cap as member_cap
from llm import client as llm_client

log = logging.getLogger(__name__)

FEATURE = "web_cascade"
MAX_ROUNDS = 4
MAX_TOOL_CALLS = 8
MAX_SPOKEN_SENTENCES = 12
HISTORY_TURNS = 8
LIMITS = LoopLimits(
    max_rounds=MAX_ROUNDS,
    round_timeout_s=10.0,
    tool_timeout_s=8.0,
    turn_timeout_s=30.0,
    max_input_tokens=120_000,
)
# `about_kevin`: his profile, spoken by code and never by the model (2026-11-18, `demo/tools.py`).
VERBATIM_TOOLS: frozenset[str] = frozenset({"account_balances", "about_kevin"})
# Cached input, in hundredths of a micro-dollar per token: the rate the agent turn quotes for
# flash-lite (`agents/legion/turn.py`, $0.03 / 1M). An ESTIMATE, like every cost here.
CACHED_MICRO_USD_PER_TOKEN_X100 = 3
# How long the turn waits for the next sentence's speech before it gives up on it.
TTS_WAIT_S = 20.0
# How often a turn waiting on speech asks whether it was cancelled (round 3 N4): well inside the
# page's 6 s of `busy` re-sends.
CANCEL_POLL_S = 0.25
# The longest a whole turn may hold its server thread, speech included (finding 1): speech-to-text
# (12 s), the rounds (`LIMITS`), and what is left of the speech is cut here.
TURN_WALL_S = 60.0

# The phone's `CascadePrompt.VOICE_CLAUSE` (spec 11), added to the cached prefix, with its figure
# rule changed for the web (round 2 speech review B): the model writes every figure exactly, in
# digits, and code alone decides how it is said (`spoken_figures`). One rule, never two.
VOICE_CLAUSE = (
    "Spoken output: what you write is read aloud by a separate voice, as you write it. Write plain "
    "spoken sentences only: no markdown, lists, headings, emoji, links or symbols. Write every "
    "number, amount, time and unit exactly, in digits. Keep replies short unless asked for "
    "detail. When a tool will take a moment, a few words first are fine."
)

# "That sounded robotic" (voice-persona section 8): the turn before it is marked for rework.
_ROBOTIC = re.compile(
    r"\b(that|this|you|it)\s+(sounded|sounds|sound|was|is)\s+(so\s+|really\s+|very\s+)?"
    r"(robotic|like a robot|like an ai|like a machine|fake)\b",
    re.I,
)
ROBOTIC_ACK = "Noted. I'll get that one looked at."
ACK_TOOL = fillers.ACK_TOOL
_SMALL_TALK = re.compile(
    r"^\W*(hi|hey|hello|thanks|thank you|cheers|ok|okay|cool|great|nice|bye|good ?bye|"
    r"good (morning|evening|night)|yes|no|yeah|nope|sure)\b",
    re.I,
)


def is_robotic_remark(words: str | None) -> bool:
    return bool(_ROBOTIC.search(words or ""))


def needs_work(words: str | None) -> bool:
    """A turn that is clearly a request needing a look (an acknowledgement goes first), not small
    talk or a sign-off."""
    text = (words or "").strip()
    if len(text.split()) < 4 or _SMALL_TALK.match(text) or is_stop_utterance(text):
        return False
    return True


def mark_robotic(request, conversation_id, turn: int, *, source: str) -> None:
    """The `robotic` marker: the conversation's own record gets the turn number, and the log the
    ids. No words."""
    now = datetime.now(UTC)
    line = web_conversation.line(
        now,
        web_conversation.TID_ENGINE,
        web_conversation.AUDIT,
        f"robotic turn={turn} source={source}",
    )
    web_conversation.record_lines(request, conversation_id, [(now, line)])
    log.warning(
        "assistant robotic conversation=%s turn=%d source=%s", conversation_id, turn, source
    )


# The phone's `ScriptedLines.FILLERS` (spec 22): what a reply that opens with only these is
# stripped of. What the web says while a tool runs is per tool (`assistant/fillers.py`).
FILLERS = ("One moment.", "Just a moment.", "On it.", "Checking.", "Let me look.")

# The phone's `end_conversation` (spec 18): the description and the sign-off instruction, with
# "he" made "they" (the web serves every member and a visitor).
# Knowing when it is being spoken to (voice-persona section 10, Kevin 2026-11-17: "yes all four").
# A third outcome besides answering and calling a tool: the sentence was not for the assistant
# (a visitor turning to someone else). A no-data control like `end_conversation`.
NOT_FOR_ME_TOOL = "not_for_me"
NOT_FOR_ME_DECLARATION = {
    "name": NOT_FOR_ME_TOOL,
    "description": (
        "Call this, and say nothing, when what you just heard was clearly NOT said to you: someone "
        "talking to another person in the room, a remark about you or your voice to someone else, "
        "a phone call, the TV. Judge from the sentence and the last exchange. Never call it when "
        "they ask you something, answer your question, or say your name."
    ),
    "parameters": {"type": "object", "properties": {}},
}
NOT_FOR_ME_RESPONSE = "Not for you: say nothing."
CHANGED_SO_FOR_YOU = (
    "A change already went through in this turn, so it was for you: say what was done."
)
NOT_FOR_ME_NO_WRITE = "Nothing was changed: that sentence was not for the assistant."
NAMED_RESPONSE = "They said your name: it was for you. Answer them."
# What the page shows (never says) when it is not sure the sentence was for it (section 10.4).
UNSURE_CUE = "Were you talking to me? Say my name or tap to go on."
_SECOND_PERSON = re.compile(
    r"\b(you|your|can you|could you|would you|will you|tell me|show me|what's|what is|how much|"
    r"when is|when's|where is|is there|do i|did i|have i|please)\b",
    re.I,
)


def addressed_signals(words: str, companion_name: str, last_reply: str) -> dict[str, bool]:
    """Section 10.3's signals that code can read from the words: the assistant's name, a reply to
    its own question, and a request or question put to it. (A second voice and speech over the
    assistant's own are the phone's; the web has no enrolled voice.)"""
    name = (companion_name or "").strip()
    return {
        "named": bool(name) and bool(re.search(rf"\b{re.escape(name)}\b", words or "", re.I)),
        "answers_question": (last_reply or "").rstrip().endswith("?"),
        "second_person": bool(_SECOND_PERSON.search(words or "")),
    }


def addressed_decision(signals: dict[str, bool], model_says_not: bool) -> str:
    """`addressed`, `unsure` or `not_addressed`, combined by code; the model's judgement is one
    input. The name always gets through."""
    if signals.get("named") or not model_says_not:
        return "addressed"
    if signals.get("answers_question") or signals.get("second_person"):
        return "unsure"
    return "not_addressed"


END_TOOL = "end_conversation"
END_DECLARATION = {
    "name": END_TOOL,
    "description": (
        "End the conversation when they are DONE with you: 'never mind', 'nothing', "
        "'I'm good', 'that's all', 'go away'. 'That will be all' ALWAYS means this, "
        "however politely. 'Go to sleep', 'sleep', 'go dormant', 'stand down' mean this "
        "too: about YOU going quiet, not their bedtime; no goodnight. Say one dry sign-off "
        "line in your own register in the same turn; it closes when you finish. Do NOT "
        "call this when they are answering 'no' to something you offered, declining a "
        "suggestion, or pausing."
    ),
    "parameters": {"type": "object", "properties": {}},
}
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


END_INSTRUCTION = (
    "Sign off now with one dry line in your own register (a <chuckle> before it is fine), "
    "about this conversation, then "
    "stop. The conversation ends when you finish speaking."
)

# The phone's failure leads (`TextChatEngine.TextFailure`, spec 31), less "Check it in Setup".
NOT_ANSWERED_MODEL = "Not answered: Gemini returned an error for that message."
NOT_ANSWERED_TIMEOUT = "Not answered: that took too long and was stopped."
NOT_ANSWERED_BUDGET = "Not answered: this turn reached its spending limit."
NO_TEXT = "No written reply came back for that."
NOTHING_CHANGED = " Nothing was changed."
NOTHING_HEARD = "Nothing heard, so nothing was asked."
STT_FAILED = "Not answered: the words could not be made out just now."
MISSED_LINE = "Didn't catch that, say it again?"
# A turn that broke is said too, from memory, when its line is in hand (round 2 turn review 7):
# the voice cannot be asked for new speech once the turn has failed.
FAILED_SPOKEN = "Sorry, something broke on my end. Nothing was changed."
FAILED_AFTER_WRITE_SPOKEN = (
    "Sorry, something broke on my end, but the change you asked for went through."
)
SPOKEN_FAILURES = (MISSED_LINE, FAILED_SPOKEN, FAILED_AFTER_WRITE_SPOKEN)


def failure_audio(voice: str, wrote: bool) -> ev.Event | None:
    """The spoken failure line's audio for `voice`, if it has been made; else None."""
    line = FAILED_AFTER_WRITE_SPOKEN if wrote else FAILED_SPOKEN
    clip = FILLER_AUDIO.get(voice, line)
    if not clip:
        return None
    return ev.Event("audio", {"pcm": base64.b64encode(clip).decode("ascii"), "rate": OUTPUT_RATE})


# ADR 0090's reason in code, spoken whenever a turn that read outside text does not make a change
# it was asked for (measured 2026-11-17: the model gave a false reason, "that only works in the
# phone app", or claimed the change). The person's next message may make it.
READ_THROUGH_WAIT = (
    "I haven't changed anything: this turn read text from the web, so changes wait for your next "
    "message. Ask me again and I'll do it."
)
# Said after a claimed change that no write backed and none went through by the end of the turn.
CHANGE_NOT_MADE = "Nothing was changed: that change did not go through, so ask me again for it."
# The same, when a write did go through in this turn (a claim made before its own call).
CHANGE_MADE = "That change went through."
# ADR 0090 after a write in this turn was refused because the turn read outside text, but an
# earlier write in the same turn went through (finding 3): what happened, not "nothing".
READ_THROUGH_WAIT_AFTER_WRITE = (
    "The first change went through. The next one waits for your next message, because this turn "
    "read text from the web. Ask me again and I'll do it."
)
TOO_MANY_TOOLS = (
    "Nothing was read or written: this turn already ran as many tool calls as one turn may."
)


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


# ---- fillers kept in memory (spec 22) ----------------------------------------------------------


# How long a filler line whose speech failed waits before it is asked for again.
FILLER_RETRY_S = 600.0


class FillerAudio:
    """Each filler line's audio per voice, kept in this process once made. A line is said only from
    memory; a missing one is fetched in the background for next time (the phone's rule: a filler
    that waits for speech holds the answer up behind it)."""

    def __init__(self, spawn: Callable[[Callable[[], None]], None] | None = None):
        self._audio: dict[tuple[str, str], bytes] = {}
        self._fetching: set[tuple[str, str]] = set()
        # A line whose speech failed is not asked for again for a while (finding 6: with speech
        # failing, every spoken turn re-sent the whole set).
        self._failed: dict[tuple[str, str], float] = {}
        self._lock = threading.Lock()
        self._last: str | None = None
        self._spawn = spawn or (lambda work: threading.Thread(target=work, daemon=True).start())

    def warm(self, speech, voice: str, lines=None) -> int:
        lines = (*fillers.all_lines(), *SPOKEN_FAILURES) if lines is None else lines
        missing = []
        with self._lock:
            now = time.monotonic()
            for line in lines:
                key = (voice, line)
                if now - self._failed.get(key, -FILLER_RETRY_S) < FILLER_RETRY_S:
                    continue
                if key not in self._audio and key not in self._fetching:
                    self._fetching.add(key)
                    missing.append(line)
        if not missing:
            return 0

        def fetch() -> None:
            for line in missing:
                stats = Spoken()
                try:
                    # Through the same tag rules as an answer (round 2 speech review 6).
                    said = speech_tags.for_speech(pronounced(line), expressive=True)
                    audio = b"".join(speech.speak(said, voice, stats))
                except (SpeechFailure, OSError):
                    audio = b""
                if stats.voice not in (None, voice):
                    # Spoken in the fallback voice: never kept under this voice (round 3 S3); it
                    # is asked for again later, when the voice may be back.
                    audio = b""
                with self._lock:
                    self._fetching.discard((voice, line))
                    if audio:
                        self._audio[(voice, line)] = audio
                    else:
                        self._failed[(voice, line)] = time.monotonic()

        self._spawn(fetch)
        return len(missing)

    def missing(self, voice: str, lines=None) -> int:
        """How many lines `warm` would fetch now: what a turn reserves for them first."""
        lines = (*fillers.all_lines(), *SPOKEN_FAILURES) if lines is None else lines
        now = time.monotonic()
        with self._lock:
            return sum(
                1
                for line in lines
                if (voice, line) not in self._audio
                and (voice, line) not in self._fetching
                and now - self._failed.get((voice, line), -FILLER_RETRY_S) >= FILLER_RETRY_S
            )

    def get(self, voice: str, line: str) -> bytes | None:
        with self._lock:
            return self._audio.get((voice, line))

    def pick(self, voice: str, tool: str) -> tuple[str, bytes] | None:
        """One of `tool`'s lines that is in memory, never the last line said; None when the tool
        is silent or none of its lines is in hand yet."""
        with self._lock:
            ready = [line for line in fillers.lines_for(tool) if (voice, line) in self._audio]
        line = LINES.pick(ready)
        if line is None:
            return None
        return line, self._audio[(voice, line)]


FILLER_AUDIO = FillerAudio()
LINES = fillers.LinePicker()


# ---- speech, two sentences at a time (spec 5) --------------------------------------------------

_END = object()


@dataclass
class _Job:
    text: str
    chunks: queue.Queue
    stats: Spoken
    cancelled: threading.Event


# How a reply goes to speech (`Speaker`): "breath" (the first clause alone, the rest of each round
# as one request) or "sentence" (one request per sentence, the phone's old shape). Kept as a
# constant while the two are measured against each other (section 8.3); the loser goes.
SPEECH_GROUPING = "breath"
# Speech requests reserved before a turn starts (finding 6). A longer answer reserves more as it
# goes, one request at a time, up to `TTS_REQUEST_CEILING` (round 3 speech S4: the 12-sentence
# limit spoken one sentence a request, plus the closing lines); one is always kept back for a
# closing honesty line.
MAX_TTS_REQUESTS = 6
TTS_REQUEST_CEILING = 16
GROUP_SENTENCES = 3
GROUP_CHARS = 400
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
# Speech only (2026-11-18, speed; blind review round 7 moved it here from the runtime, after the
# checks): a first sentence past this many words is spoken in two requests, cut at its first
# , ; or : that leaves at least FIRST_CLAUSE_MIN_WORDS in front.
FIRST_CLAUSE_PAST_WORDS = 8
FIRST_CLAUSE_MIN_WORDS = 6
_CLAUSE_BREAK = re.compile(r"(?<=[,;:])\s+")


def _first_sentence(text: str) -> tuple[str, str]:
    parts = _SENTENCE_END.split(text, maxsplit=1)
    return parts[0], parts[1] if len(parts) > 1 else ""


def first_clause(text: str) -> tuple[str, str]:
    """(what is spoken at once, the rest) for an answer's first sentence; a short one goes whole."""
    if len(text.split()) <= FIRST_CLAUSE_PAST_WORDS:
        return text, ""
    for match in _CLAUSE_BREAK.finditer(text):
        head, rest = text[: match.start()].strip(), text[match.end() :].strip()
        if len(head.split()) >= FIRST_CLAUSE_MIN_WORDS and rest:
            return head, rest
    return text, ""


def _transcribe(speech, pcm: bytes, timings: dict):
    """`speech.transcribe(pcm)`, noting how many requests it sent beyond the first (a retry),
    which the turn's reservation holds one call for (round 3, finding 8 leftovers)."""
    try:
        return speech.transcribe(pcm)
    finally:
        timings["stt_retries"] = max(getattr(speech, "stt_sent", 1) - 1, 0)


def _whole(value) -> int:
    return value if isinstance(value, int) and value > 0 else 0


class _Cancelled(Exception):
    """The turn was cancelled while it waited on speech."""


class Speaker:
    """Each sentence goes to speech the moment it is whole, at most two at once; audio comes out
    in sentence order. `ready()` hands over what has arrived without waiting; `drain()` waits."""

    def __init__(self, speech, voice: str, enabled: bool, clock=time.monotonic):
        self.speech = speech
        self.voice = voice
        self.enabled = enabled
        self.clock = clock
        self.jobs: deque[_Job] = deque()
        self.done: list[Spoken] = []
        self.started_at = clock()
        self.first_audio_ms: int | None = None
        self.total_ms = 0
        # The whole turn, speech included, ends by this time (finding 1).
        self.deadline = self.started_at + TURN_WALL_S
        self._pool = ThreadPoolExecutor(max_workers=2) if enabled else None
        # The person asked for exact figures this turn: nothing is rounded when spoken.
        self.exact = False
        # "One breath per reply" (voice-persona section 8.3): the first words go to speech alone
        # and at once; what follows in the same round is one request, so its intonation carries
        # across sentences. `SPEECH_GROUPING` "sentence" is the old one-request-per-sentence.
        self.grouping = SPEECH_GROUPING
        self._first_out = False
        self._pending: list[str] = []
        self.requests = 0
        self.skipped = 0
        self.cancelled = _never
        self.cancelled_often = _never
        # Speech requests this turn may make now, and how to reserve more (round 3 S4).
        self.allowed = MAX_TTS_REQUESTS
        self.more: Callable[[int], bool] = lambda n: True
        # Set for the turn's closing lines: they may use the request kept back for them.
        self.closing = False
        self.stopped = False
        self._notices: list[ev.Event] = []

    def spoken_text(self, text: str, expressive: bool = False, model: bool = False) -> str:
        """What goes to speech for `text`: for a `model` sentence only, no file span, link or
        backtick read out (`spoken_words`; a line code wrote, his profile or a figure, is never
        reworded); its figures rounded like a person says them (`spoken_figures`; the exact ones
        stay in the caption), and its allowed tags only when `expressive` (a model sentence or a
        code line written with one)."""
        said = speakable_words(text) if model else text
        said = spoken_figures.speakable(pronounced(said), exact=self.exact)
        return speech_tags.for_speech(said, expressive=expressive)

    def add(self, text: str, expressive: bool = False, model: bool = False) -> None:
        if not self.enabled:
            return
        spoken = self.spoken_text(text, expressive, model)
        if self.grouping == "sentence":
            self._submit(spoken)
            return
        if not self._first_out:
            # The answer's first clause is spoken as soon as it exists (section 8.2), and with no
            # tag: tags stay off an answer's first sentence (section 9). Only for words written to
            # be said (`expressive`: the model's, the greeting); code's own lines (a figure, his
            # profile) are never cut, which once put "2026." in a request of its own.
            self._first_out = True
            lead, more = _first_sentence(spoken)
            spoken = f"{speech_tags.untagged(lead)} {more}".strip()
            head, rest = first_clause(spoken) if expressive else (spoken, "")
            self._submit(head)
            if rest:
                self._pending.append(rest)
            return
        self._pending.append(spoken)
        if len(self._pending) >= GROUP_SENTENCES or sum(map(len, self._pending)) >= GROUP_CHARS:
            self.flush()

    def flush(self) -> None:
        """Speak what is held as one request (the round ended, or enough is held)."""
        if self._pending:
            self._submit(" ".join(self._pending))
            self._pending = []

    def _submit(self, spoken: str) -> None:
        if self.cancelled():
            return
        if not self._may_speak():
            # Past what could be reserved: the rest is shown, not said, and the page is told
            # once (round 3 S4). The closing lines still have the request kept back for them.
            self.skipped += 1
            if not self.stopped:
                self.stopped = True
                self._notices.append(ev.Event("speech_stopped", {"reason": "limit"}))
            return
        self.requests += 1
        job = _Job(spoken, queue.Queue(), Spoken(), threading.Event())
        self.jobs.append(job)
        self._pool.submit(self._run, job)

    def _may_speak(self) -> bool:
        def room() -> bool:
            return self.requests < (self.allowed if self.closing else self.allowed - 1)

        if room():
            return True
        if self.allowed < TTS_REQUEST_CEILING and self.more(1):
            self.allowed += 1
        return room()

    def _run(self, job: _Job) -> None:
        started = time.monotonic()
        try:
            for chunk in self.speech.speak(job.text, self.voice, job.stats):
                if job.cancelled.is_set():
                    break
                job.chunks.put(chunk)
        except SpeechFailure as exc:
            job.stats.failed = job.stats.failed or str(exc)
        except Exception as exc:  # noqa: BLE001 - a sentence's speech failing is not the turn's
            job.stats.failed = type(exc).__name__
        finally:
            self.total_ms += int((time.monotonic() - started) * 1000)
            job.chunks.put(_END)

    def audio_event(self, chunk: bytes, filler: bool = False) -> ev.Event:
        if self.first_audio_ms is None:
            self.first_audio_ms = int((self.clock() - self.started_at) * 1000)
        data = {"pcm": base64.b64encode(chunk).decode("ascii"), "rate": OUTPUT_RATE}
        if filler:
            # The page may drop a filler that has not started once the answer's audio is ready.
            data["filler"] = True
        return ev.Event("audio", data)

    def _take(self, block: bool) -> Iterator[ev.Event]:
        while self._notices:
            yield self._notices.pop(0)
        while self.jobs:
            if self.cancelled_often():
                # Stopped: what is queued or still speaking is dropped, nothing more is asked for.
                for job in self.jobs:
                    job.cancelled.set()
                self.jobs.clear()
                self._pending = []
                return
            head = self.jobs[0]
            # Never past the turn's own deadline, however many sentences are still queued.
            wait = min(TTS_WAIT_S, max(self.deadline - self.clock(), 0.05))
            try:
                item = self._next(head, wait) if block else head.chunks.get_nowait()
            except _Cancelled:
                for job in self.jobs:
                    job.cancelled.set()
                self.jobs.clear()
                self._pending = []
                return
            except queue.Empty:
                if block:
                    head.cancelled.set()
                    head.stats.failed = head.stats.failed or "tts_timeout"
                    self.done.append(head.stats)
                    self.jobs.popleft()
                    yield ev.Event("speech_failed", {"reason": "tts"})
                    continue
                return
            if item is _END:
                self.jobs.popleft()
                self.done.append(head.stats)
                if head.stats.failed:
                    yield ev.Event("speech_failed", {"reason": "tts"})
                continue
            yield self.audio_event(item)

    def _next(self, job: _Job, wait: float):
        """The job's next chunk within `wait` seconds, asking about a cancel every
        `CANCEL_POLL_S` meanwhile (round 3 N4: a 20 s wait must not hold a cancelled turn)."""
        until = time.monotonic() + wait
        while True:
            left = until - time.monotonic()
            try:
                return job.chunks.get(timeout=max(min(CANCEL_POLL_S, left), 0.001))
            except queue.Empty:
                if self.cancelled_often():
                    raise _Cancelled from None
                if left <= CANCEL_POLL_S:
                    raise

    def ready(self) -> Iterator[ev.Event]:
        yield from self._take(block=False)

    def drain(self) -> Iterator[ev.Event]:
        self.flush()
        yield from self._take(block=True)

    def close(self) -> None:
        for job in self.jobs:
            job.cancelled.set()
        if self._pool is not None:
            self._pool.shutdown(wait=False, cancel_futures=True)


# ---- the tool door, as a runtime gateway ------------------------------------------------------


@dataclass
class DoorCall:
    name: str
    ok: bool
    outcome: str
    text: str
    card: dict | None = None
    # Sentences code wrote for this result, said and shown as they are (`VERBATIM_TOOLS`).
    verbatim: tuple[str, ...] | None = None
    ms: int = 0
    # Said with every figure exact; the turn's whole answer; what history replays instead.
    exact: bool = False
    ends_turn: bool = False
    replay: str | None = None
    # The call's arguments (the follow-up chips, `demo.suggestions`) and its result's plain data
    # (`ToolResult.structured`: the chips and the money-figure check, `backed_cents`); never sent
    # to the model or the page from here.
    args: dict = field(default_factory=dict)
    data: dict | None = None


TURN_STOPPED = "Not run: the turn was stopped."
PROFILE_READ_ONCE = (
    "Not read: his profile was already read aloud in this turn, and that reading is the answer."
)
# A tool still running at its deadline (round 3 design change D1): the database cancelled its
# statement or its wait for a lock and rolled its transaction back, or its request timed out.
TOOL_TIMED_OUT = (
    "Not done: {name} took too long, so it was stopped and nothing it did was kept. Nothing was "
    "changed."
)


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


def _run_by_deadline(work, deadline_s: float):
    """`work()`'s result, or None when it ran past `deadline_s` and was stopped (round 3 design
    change D1, replacing a thread pool that could not stop a call). Inline, on the turn's own
    thread: the database cancels a slow statement or lock wait and rolls the tool's transaction
    back, and outbound requests are capped by the time left (`legion.deadline`). So a write either
    committed inside the deadline or did not happen."""
    guard = None
    try:
        with deadline.within_database(deadline_s) as guard:
            result = work()
    except DatabaseError:
        if guard is None or not guard.expired:
            raise
        return None
    return None if guard.expired else result


def _prepay(request, name: str):
    """A demo visit's web search is charged on its own committed transaction BEFORE the tool's
    deadline transaction opens (round 4 R1: charged inside it, the budget row stayed locked for
    the whole search, up to 8 s, and every other demo reservation queued behind it; and a rollback
    at the deadline took a billed search's charge with it). The reservation, a refusal, or None."""
    from demo import budget
    from engine_mcp.tools import PREPAID_SEARCH, refused

    if name != "web_search" or not budget.counts(request.user):
        return None
    reservation = budget.charge_web_search(user=request.user)
    if reservation is None:
        return refused(f"Nothing was searched. {budget.used_up_message(request.user, 'search')}")
    setattr(request, PREPAID_SEARCH, reservation)
    return reservation


def _settle_prepaid(request, reservation) -> None:
    """Gives back a prepaid search Google refused (nothing billed); otherwise it stays counted."""
    from demo import budget
    from engine_mcp.tools import PREPAID_RELEASE, PREPAID_SEARCH

    if reservation is None:
        return
    if getattr(request, PREPAID_RELEASE, False):
        budget.release(reservation)
    for attr in (PREPAID_SEARCH, PREPAID_RELEASE):
        if hasattr(request, attr):
            delattr(request, attr)


class WebDoor:
    """`agents.runtime.tools.ToolGateway` over the web assistant's tool door: the same checks, in
    the same order, as `AssistantToolView.post`, plus the `end_conversation` control."""

    def __init__(self, request, conversation_id, turn: int):
        self.request = request
        self.user = request.user
        self.conversation_id = conversation_id
        self.turn = turn
        self.recruiter = is_recruiter(self.user)
        self.calls: list[DoorCall] = []
        self.end_requested = False
        # The browser's cancel (`CancelCheck`), asked before each tool.
        self.cancelled = _never
        # Told when a write committed: the turn is then one the user could have received (N5).
        self.on_write = lambda: None
        # Section 10: the model said the sentence was not for it, and the code-read signals.
        self.not_for_me = False
        self.addressed: dict[str, bool] = {}
        self._tools = None
        self._declarations = None
        read_through.set_turn(request, conversation_id, turn)

    def tools(self) -> dict:
        if self._tools is None:
            self._tools = {t.name: t for t in tools_for(self.user)}
            if "about_kevin" in self._tools:
                # The one seam between the engines for his profile: on the cascade code speaks it
                # and the model only picks sections; Live keeps `demo.tools.ABOUT_KEVIN` as it was.
                self._tools["about_kevin"] = about_kevin_spoken_for(self.user)
        return self._tools

    def declarations(self, spec=None) -> list[dict]:
        if self._declarations is None:
            self._declarations = [
                *rest_declarations(list(self.tools().values())),
                END_DECLARATION,
                NOT_FOR_ME_DECLARATION,
            ]
        return self._declarations

    def would_accept(self, name: str) -> bool:
        """Whether a call to `name` gets past the door's own refusals that need no arguments: the
        per-turn cap, the role's fence and "not for me"."""
        if len(self.calls) >= MAX_TOOL_CALLS or name not in self.tools():
            return False
        if self.not_for_me and self._writes(name):
            return False
        return (
            recruiter_only_tool(name, self.user) is not None
            or refusal_for(name, self.user, {}) is None
        )

    def _writes(self, name: str) -> bool:
        tool = self.tools().get(name)
        return bool(tool is not None and tool.writes)

    def is_slow(self, name: str) -> bool:
        # The runtime says `working` before a slow tool; the web's slow list is the fillers'
        # (a tool whose line goes out at its start), which covers the runtime's own SLOW_TOOLS.
        # A write is "slow" too: the runtime then yields before running it, and a browser that has
        # hung up is noticed there, before the change is made (finding 5).
        tool = self.tools().get(name)
        return fillers.is_slow(name) or name in SLOW_TOOLS or bool(tool is not None and tool.writes)

    def _audit(self, outcome: str, name: str) -> None:
        try:
            with transaction.atomic():
                AssistantCall.objects.create(
                    household=household_of(self.request),
                    user=self.user,
                    kind=AssistantCall.TOOL,
                    tool=name[:200],
                    outcome=outcome,
                )
        except DatabaseError:
            # The tool has run; a failed audit row must not turn its result into an error.
            log.warning("assistant cascade: audit row failed", exc_info=True)

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome:
        started = datetime.now(UTC)
        clock = time.monotonic()
        name, args = call.name, dict(call.args or {})
        result = None
        if self.cancelled():
            ok, outcome, text, guard = False, Outcome.REFUSED, TURN_STOPPED, "cancelled"
        elif len(self.calls) >= MAX_TOOL_CALLS:
            ok, outcome, text, guard = False, Outcome.REFUSED, TOO_MANY_TOOLS, "limit"
        elif name == NOT_FOR_ME_TOOL:
            named = self.addressed.get("named", False)
            if self.wrote():
                # A turn in which a change went through was for the assistant, whatever the model
                # says now: it must say what was done (round 2 finding 4).
                ok, outcome, text, guard = True, Outcome.OK, CHANGED_SO_FOR_YOU, None
            else:
                self.not_for_me = not named
                ok, outcome, text, guard = (
                    True,
                    Outcome.OK,
                    (NAMED_RESPONSE if named else NOT_FOR_ME_RESPONSE),
                    None,
                )
        elif self.not_for_me and self._writes(name):
            # Nothing changes on a sentence that was not for the assistant.
            ok, outcome, text, guard = False, Outcome.REFUSED, NOT_FOR_ME_NO_WRITE, "not_for_me"
        elif name == END_TOOL:
            # A control, not a data tool: the page closes the conversation after the sign-off.
            self.end_requested = True
            ok, outcome, text, guard = True, Outcome.OK, END_INSTRUCTION, None
        elif name == "about_kevin" and any(
            c.name == name and c.ok and c.verbatim for c in self.calls
        ):
            # One reading of his profile per turn: the cap (five sentences and a closing line) is
            # what one turn's speech reserve covers, and the first call's lines are the answer.
            ok, outcome, text, guard = False, Outcome.REFUSED, PROFILE_READ_ONCE, "once"
        else:
            refusal = refusal_for(name, self.user, args)
            if refusal is not None:
                ok, outcome, text = False, Outcome.REFUSED, refusal
                guard = "recruiter" if self.recruiter else "not_allowed"
            else:
                only_here = recruiter_only_tool(name, self.user)
                if only_here is not None and name == "about_kevin":
                    only_here = self.tools()[name]
                if only_here is not None:
                    work = functools.partial(run_engine_tool, self.request, only_here, args)
                else:
                    work = functools.partial(run_tool, self.request, name, args)
                prepaid = _prepay(self.request, name)
                if isinstance(prepaid, ToolResult):
                    result = prepaid
                else:
                    try:
                        result = _run_by_deadline(work, deadline_s)
                    finally:
                        _settle_prepaid(self.request, prepaid)
                if result is None:
                    result = ToolResult(
                        text=TOOL_TIMED_OUT.format(name=name), is_error=True, outcome=Outcome.FAILED
                    )
                ok, outcome, text, guard = not result.is_error, result.outcome, result.text, None
        if ok and self._writes(name):
            self.on_write()
        call_id = call.id or f"c{len(self.calls) + 1}"
        # Known at once: whatever fails after this, the turn knows the write went through.
        self.calls.append(DoorCall(name, ok, outcome, text, None, None, 0))
        self._audit(outcome, name)
        web_conversation.record_tool(
            self.request,
            self.conversation_id,
            call_id=call_id,
            name=name,
            args=args,
            started=started,
            ended=datetime.now(UTC),
            success=ok,
            outcome=outcome,
            text=text,
        )
        card = cards.for_viewer(result.card, self.recruiter) if result is not None else None
        spoken = _verbatim(name, result) if ok else None
        if spoken is not None and spoken.for_model:
            # What the model and the page's strip see: never the code-spoken sentences themselves
            # (the profile: the model is never handed a sentence of it, `demo/tools.py`).
            text = spoken.for_model
        ms = int((time.monotonic() - clock) * 1000)
        self.calls[-1] = DoorCall(
            name,
            ok,
            outcome,
            text,
            card,
            spoken.lines if spoken else None,
            ms,
            exact=bool(spoken and spoken.exact),
            ends_turn=bool(spoken and spoken.ends_turn),
            replay=spoken.replay if spoken else None,
            args=args,
            data=result.structured if (ok and result is not None) else None,
        )
        tool = self.tools().get(name)
        return ToolOutcome(
            name=name,
            ok=ok,
            response={"success": ok, "message": text},
            writes=bool(tool is not None and tool.writes),
            outcome="ok" if ok else str(outcome),
            guard=guard,
            ms=int((time.monotonic() - clock) * 1000),
            ends_turn=bool(spoken and spoken.ends_turn),
        )

    def actions(self) -> list[dict]:
        tools = self.tools()
        return [
            {
                "name": c.name,
                "writes": bool(getattr(tools.get(c.name), "writes", False)),
                "ok": c.ok,
            }
            for c in self.calls
        ]

    def wrote(self) -> bool:
        tools = self.tools()
        return any(c.ok and getattr(tools.get(c.name), "writes", False) for c in self.calls)


# ---- one turn --------------------------------------------------------------------------------


@dataclass
class TurnInput:
    audio: bytes | None = None
    text: str | None = None
    # A hidden line ahead of the person's words (the demo page's "another conversation" line,
    # `frontend/src/screens/try.tsx`); never shown, never recorded as theirs.
    preface: str | None = None
    # The greeting on open (spec 25): no words of theirs; spoken.
    greet: bool = False
    # What the page remembers for the greeting's caps (spec 27): today's lead kinds, the last
    # kind, the last hellos.
    greeting_history: dict | None = None
    # A text turn the page transcribed early and sends as spoken (the phone's speculation, spec
    # 33 adapted): answered aloud like an utterance, recorded as spoken.
    spoken: bool = False
    # Utterances this conversation received before this one, answered or not (round 3 speech
    # S5): only the first may get the acknowledgement.
    heard_before: int = 0
    # The page marked this utterance a barge-in (it began while a reply played), and the reply
    # that was playing: only then is the transcript checked for an echo (round 4 ruling).
    barge_in: bool = False
    last_reply: str = ""
    # The one-minute tour (`assistant/tour.py`): the beat to play, or None.
    tour_beat: int | None = None
    # A tapped suggestion chip offered to this visitor (`demo.suggestions.chip_for`): its checked
    # lookup is the turn's first round, never the model's pick.
    chip: object | None = None


# A function call the model did not make carries this in place of a thought signature (Gemini's
# documented value for a call injected into the conversation).
INJECTED_SIGNATURE = "skip_thought_signature_validator"


class ChipFirst:
    """The model, with the turn's first round already decided: the tapped chip's own checked call
    (`demo.suggestions`), never the model's pick of tool or arguments. Sits above the counting
    layer, so that round is no model call and costs nothing; a later round, if the result needs
    wording, is the real model's. A chip whose tool ends the turn in code (his profile) is answered
    with no model call at all."""

    def __init__(self, inner, chip):
        self.inner = inner
        self.chip = chip
        self.scripted = False
        self._row_skipped = False

    def stream(self, req):
        if not self.scripted:
            self.scripted = True
            args = dict(self.chip.args)
            yield PartEvent(
                {
                    "functionCall": {"name": self.chip.tool, "args": args},
                    "thoughtSignature": INJECTED_SIGNATURE,
                }
            )
            yield ToolCallEvent("chip", self.chip.tool, args)
            yield Usage()
            yield Finish("STOP")
            return
        yield from self.inner.stream(req)

    def skip_row(self) -> bool:
        """True once, for the scripted round's row: it is not a model call."""
        if self._row_skipped:
            return False
        self._row_skipped = True
        return True


@dataclass
class _State:
    messages: list[dict]
    actions: list = field(default_factory=list)
    budget: BudgetUsed = field(default_factory=BudgetUsed)


@dataclass
class TurnReport:
    """What the caller settles afterwards: rounds used (for the budget) and the record."""

    rounds: int = 0
    status: str = "done"
    heard: str = ""
    door: object | None = None
    said: list[str] = field(default_factory=list)
    timings: dict = field(default_factory=dict)
    # What the conversation's history replays for some of `said` (by index): a marker in place of
    # a sentence the model must never see again (his profile, a tour line about him), or "" to
    # replay nothing for it. The record still keeps `said` as it was.
    replay: dict[int, str] = field(default_factory=dict)

    def say(self, text: str, replay: str | None = None) -> None:
        if replay is not None:
            self.replay[len(self.said)] = replay
        self.said.append(text)

    def history_text(self) -> str:
        """`said` as the next turn's history may hold it."""
        return " ".join(
            self.replay.get(i, line) for i, line in enumerate(self.said) if self.replay.get(i, line)
        ).strip()


class CancelCheck:
    """"Has the browser cancelled this turn?" (`POST /api/assistant/turn/cancel`, 2026-11-17):
    answered from the database, so a cancel sent to any worker or instance is seen. Called at each
    stage boundary (before a model round, a tool, a speech request); `often` is the same question
    for the places asked many times a second (between audio chunks), asked at most every
    `every_s`. Once true it stays true. No `turn_id`, never cancelled."""

    def __init__(self, user_id, turn_id, clock=time.monotonic, every_s: float = 0.2):
        self.user_id = user_id
        self.turn_id = turn_id
        self.clock = clock
        self.every_s = every_s
        self.hit = False
        self._asked = None

    def __call__(self) -> bool:
        if self.hit or self.turn_id is None:
            return self.hit
        self._asked = self.clock()
        from assistant.models import TurnCancel

        try:
            self.hit = TurnCancel.objects.filter(
                turn_id=self.turn_id, user_id=self.user_id
            ).exists()
        except DatabaseError:
            return False
        return self.hit

    def often(self) -> bool:
        if self.hit or self.turn_id is None:
            return self.hit
        if self._asked is not None and self.clock() - self._asked < self.every_s:
            return False
        return self()


def _never() -> bool:
    return False


class CountingModel:
    """Counts every paid model call started: each `stream` and each prompt cache made, so a
    cancelled round, a resend after a broken cache (counted under the cache layer, round 2 turn
    review 8) and a cache creation are all billed to the budget like any other."""

    def __init__(self, inner):
        self.inner = inner
        self.calls = 0

    def stream(self, req):
        self.calls += 1
        return self.inner.stream(req)

    def create_cache(self, *args, **kwargs):
        self.calls += 1
        return self.inner.create_cache(*args, **kwargs)

    def __getattr__(self, name):
        return getattr(self.inner, name)


# A lookup's line is said only if the answer is still not in hand this long after the lookup
# started (the phone's `ToolFillers.FAST_DELAY_MS`, 700 ms). Measured on /try: a quick lookup's
# answer round takes about 0.9 to 1.1 s, a code-spoken one (his profile) a few milliseconds.
FILLER_WAIT_S = 0.7
# How often a round that has said nothing yet is reported as `waiting` (`Ticking`).
TICK_S = 0.1


class Ticking:
    """Reads the model on a thread of its own and, until a round's first item arrives, yields
    `Waiting` every `every` seconds: the turn's only moments to act while a round is silent (a
    lookup's line on time). Only the HTTP read is on that thread; the loop, the door and the
    database stay on the turn's own. Once the round has started, items pass straight through."""

    _END = object()

    def __init__(self, inner, every: float | None = None):
        self.inner = inner
        self.every = TICK_S if every is None else every

    def stream(self, req):
        items: queue.Queue = queue.Queue()
        stop = threading.Event()

        def read() -> None:
            stream = self.inner.stream(req)
            try:
                for item in stream:
                    items.put(item)
                    if stop.is_set():
                        break
            except Exception as exc:  # noqa: BLE001 - raised again on the turn's own thread
                items.put(exc)
            finally:
                close = getattr(stream, "close", None)
                if close is not None:
                    close()
                items.put(Ticking._END)

        threading.Thread(target=read, name="legion-model-read", daemon=True).start()
        started = False
        try:
            while True:
                try:
                    item = items.get(timeout=None if started else self.every)
                except queue.Empty:
                    yield Waiting()
                    continue
                if item is Ticking._END:
                    return
                if isinstance(item, Exception):
                    raise item
                started = True
                yield item
        finally:
            stop.set()

    def __getattr__(self, name):
        return getattr(self.inner, name)


def context_note(context: str) -> str:
    """The phone's `CascadePrompt.preamble` shape (spec 10)."""
    return f"(Current context, use naturally if relevant:\n{context.strip()})"


# How it talks (`.legion/reports/voice-persona-1117.md` sections 1 and 2, Kevin 2026-11-17: "a
# work tool but we still want it to feel like a real person"). Adds warmth to the persona's own
# register; it does not replace it.
PERSONA_NOTE = (
    "Talk like a friendly, quick, slightly dry colleague: one sentence unless asked for more, "
    'contractions, now and then a lead-in like "So," or "Right," (never the same one twice '
    "running). A short warm remark or a joke back is fine; it never delays or pads the answer. "
    "For a list, say three, then how many more. The voice rounds figures for speech; the exact "
    "one stays on screen."
)


# Taught by example, not adjectives (section 8.1). Invented content, for the shape only.
SPOKEN_EXAMPLES = (
    "How replies sound (invented examples, for the shape only; never use their facts): "
    '"What\'s due this week?" -> "3 things. The stats quiz Thursday night is the big one." '
    '"How much did we spend on groceries last month?" -> "$412.38, a bit under the month before." '
    '"Is the car due for anything?" -> "An oil change, in 612 miles. Want it on the '
    'calendar?" '
    '"How does the router work?" -> "Short version: it reads the question and hands it to one '
    'specialist. Want the long one?" '
    '"Thanks!" -> "Anytime." '
    "Answer first, short clauses, fragments are fine, never read labels or brackets aloud, never "
    "more than three items, never repeat the question."
)


def cached_system(body: str) -> str:
    """What is cached: the prompt's stable body plus the phone's voice clause (spec 9, 11), how
    it talks, and the sound tags it may use."""
    return (
        f"{body.strip()}\n\n{VOICE_CLAUSE} {PERSONA_NOTE} {SPOKEN_EXAMPLES} "
        f"{speech_tags.PROMPT_LINE}"
    )


def _failure_words(result, door: WebDoor) -> str | None:
    if result is None:
        return None
    lead = {
        MODEL_WORDS: NOT_ANSWERED_MODEL,
        TIMEOUT_WORDS: NOT_ANSWERED_TIMEOUT,
        BUDGET_WORDS: NOT_ANSWERED_BUDGET,
    }.get(result.words, result.words or None)
    if lead is None:
        return None
    return lead + ("" if door.wrote() else NOTHING_CHANGED)


def run_turn(
    request,
    turn_input: TurnInput,
    *,
    conversation_id,
    history: list[dict],
    turn: int,
    system: str,
    context: str = "",
    voice: str,
    model_name: str,
    model: ModelClient,
    speech: GeminiSpeech,
    report: TurnReport,
    clock: Callable[[], float] = time.monotonic,
    filler_audio: FillerAudio | None = None,
    companion_name: str = "",
    cancelled: Callable[[], bool] = _never,
    on_write: Callable[[], None] = lambda: None,
    more_speech: Callable[[int], bool] = lambda n: True,
) -> Iterator[ev.Event]:
    """Yields the turn's events. Fills `report` as it goes, so a caller that stops reading (the
    browser hung up) still knows what happened. `cancelled` is asked at every stage boundary: once
    true, no further paid call starts."""
    t0 = clock()
    timings = report.timings
    filler_audio = filler_audio or FILLER_AUDIO
    # Counted UNDER the prompt-cache layer when there is one, so its resend after a broken cache
    # and its cache creation are each a call.
    if hasattr(model, "inner") and hasattr(model, "store"):
        counted = CountingModel(model.inner)
        model.inner = counted
    else:
        counted = CountingModel(model)
        model = counted
    speak = (
        turn_input.audio is not None
        or turn_input.greet
        or turn_input.spoken
        # A tour beat is narrated; a typed question during it is a typed turn (round 3 N9).
        or (turn_input.tour_beat is not None and not turn_input.text)
    )
    system = cached_system(system)
    yield ev.Event("turn", {"conversation_id": str(conversation_id), "turn": turn})
    door = WebDoor(request, conversation_id, turn)
    report.door = door
    door.cancelled = cancelled
    door.on_write = on_write
    speaker = Speaker(speech, voice, enabled=speak, clock=clock)
    speaker.cancelled = cancelled
    speaker.cancelled_often = getattr(cancelled, "often", cancelled)
    speaker.more = more_speech
    if cancelled():
        # Cancelled before it started (a cancel sent for a `busy` turn that was then re-sent):
        # no transcription, greeting call or speech is paid for (round 3 N4).
        report.status = "cancelled"
        yield ev.done("cancelled", "", False)
        return
    if speak and turn_input.audio is None:
        # The filler lines' audio, fetched while the floor is theirs (spec 22). For audio, only
        # once speech was heard: room noise must cost nothing (round 2 cloud run, 2026-11-17).
        timings["speech_fetched"] = filler_audio.warm(speech, voice)
    try:
        if turn_input.greet:
            yield from _greet(
                request,
                turn_input,
                door,
                speaker,
                report,
                system,
                context,
                model_name,
                model,
                clock,
                filler_audio,
                companion_name,
            )
            return
        if turn_input.tour_beat is not None:
            words = None
            if turn_input.audio is not None:
                # Said during the tour: "skip" and "stop" steer it; anything else is a question.
                try:
                    heard = _transcribe(speech, turn_input.audio, timings)
                except SpeechFailure as exc:
                    timings["stt_failed"] = str(exc)
                    report.status = "stt_failed"
                    # Said, as on the answer path (round 2 turn review 7).
                    speaker.add(MISSED_LINE)
                    yield from speaker.drain()
                    yield ev.error(STT_FAILED)
                    return
                timings.update(stt_ms=heard.ms, audio_ms=heard.audio_ms)
                timings.update(
                    stt_prompt_tokens=heard.prompt_tokens, stt_output_tokens=heard.output_tokens
                )
                words = heard.text
                yield ev.Event("heard", {"text": words})
            elif turn_input.text:
                # Typed during the tour: the same as said (2026-11-17, the page sends the beat).
                words = turn_input.text.strip()
            if words is not None and not words.strip():
                # No speech during the tour: silence, as on the answer path.
                report.status = "nothing_heard"
                yield ev.done("nothing_heard", "", False, ignored="no_speech")
                return
            if turn_input.audio is not None:
                timings["speech_fetched"] = filler_audio.warm(speech, voice)
            if words is None or tour.is_skip(words) or tour.is_stop(words):
                yield from _tour(request, door, speaker, report, turn_input.tour_beat, words)
                return
            turn_input = dataclasses.replace(
                turn_input,
                audio=None,
                text=words,
                spoken=turn_input.audio is not None or turn_input.spoken,
                tour_beat=None,
            )
        yield from _answer(
            request,
            turn_input,
            door,
            speaker,
            report,
            history,
            system,
            context,
            voice,
            model_name,
            Ticking(model),
            speech,
            clock,
            filler_audio,
            companion_name,
        )
    finally:
        speaker.close()
        timings.update(
            tts_first_audio_ms=speaker.first_audio_ms,
            tts_ms=speaker.total_ms,
            tts_audio_ms=sum(s.audio_ms for s in speaker.done),
            tts_prompt_tokens=sum(s.prompt_tokens for s in speaker.done),
            tts_output_tokens=sum(s.output_tokens for s in speaker.done),
            tts_failed=sum(1 for s in speaker.done if s.failed),
            turn_ms=int((clock() - t0) * 1000),
        )
        # Every model call that was started counts, even one a hang-up cut short or a cache retry
        # sent twice (finding 6): billed rounds are never handed back to the budget.
        report.rounds = max(report.rounds, counted.calls)
        timings["tts_requests"] = speaker.requests
        _meter_speech(request, timings)


def _answer(
    request,
    turn_input,
    door,
    speaker,
    report,
    history,
    system,
    context,
    voice,
    model_name,
    model,
    speech,
    clock,
    filler_audio,
    companion_name="",
):
    timings = report.timings
    t0 = clock()
    speak = speaker.enabled

    # 1. The words.
    if turn_input.audio is not None:
        try:
            heard = _transcribe(speech, turn_input.audio, timings)
        except SpeechFailure as exc:
            timings["stt_failed"] = str(exc)
            report.status = "stt_failed"
            # The miss is said like a person (voice-persona section 5), and shown.
            speaker.add(MISSED_LINE)
            yield from speaker.drain()
            yield ev.error(STT_FAILED)
            return
        timings.update(
            stt_ms=heard.ms,
            audio_ms=heard.audio_ms,
            stt_prompt_tokens=heard.prompt_tokens,
            stt_output_tokens=heard.output_tokens,
        )
        words = heard.text
        if door.cancelled():
            # Stopped while being transcribed: no model round starts.
            report.status = "cancelled"
            yield ev.done("cancelled", "", False)
            return
        last = turn_input.last_reply
        if words and turn_input.barge_in and echo.is_echo(words, last):
            # The assistant's own last reply heard back through the speaker (2026-11-17: played
            # back at 5% it was transcribed and answered). No model round, nothing kept as their
            # line, not side talk; logged with lengths only.
            report.status = "echo"
            log.warning(
                "assistant cascade: echo heard_words=%d reply_words=%d",
                len(echo.words(words)),
                len(echo.words(last)),
            )
            yield ev.done("nothing_heard", "", False, ignored="no_speech")
            return
        if words:
            timings["speech_fetched"] = filler_audio.warm(speech, speaker.voice)
        if not words:
            # No speech (2026-11-17: room noise must never become a reply). Silence: no reply, no
            # filler, nothing on screen; the page listens again with a fresh window. Whether a paid
            # transcription was made is in the timings, for the budget (`views._turn_sse`).
            report.status = "nothing_heard"
            timings["stt_paid"] = int(heard.prompt_tokens > 0)
            yield ev.done("nothing_heard", "", False, ignored="no_speech")
            return
        yield ev.Event("heard", {"text": words})
    else:
        words = (turn_input.text or "").strip()
        if turn_input.spoken:
            yield ev.Event("heard", {"text": words})
    report.heard = words
    speaker.exact = spoken_figures.wants_exact(words)
    heard_at = datetime.now(UTC)
    user_recorded = False

    def record_user() -> None:
        """The person's words go into the record once it is clear they were for the assistant
        (section 10: a sentence that was not keeps nothing but a count)."""
        nonlocal user_recorded
        if user_recorded:
            return
        user_recorded = True
        web_conversation.record_lines(
            request,
            conversation_id_of(door),
            [web_conversation.engine_user_line(heard_at, words, typed=not speak)],
        )

    if is_robotic_remark(words):
        record_user()
        # "That sounded robotic": the turn before is marked for rework, by its number only
        # (voice-persona section 8). Nothing else is kept and no model is asked.
        mark_robotic(request, conversation_id_of(door), door.turn - 1, source="voice")
        report.status = "robotic"
        report.said.append(ROBOTIC_ACK)
        speaker.add(ROBOTIC_ACK)
        yield ev.Event("sentence", {"text": ROBOTIC_ACK})
        yield from speaker.drain()
        yield ev.done("robotic", ROBOTIC_ACK, False)
        return
    acked = False
    if speak and turn_input.heard_before == 0 and needs_work(words):
        # First sound within about a second (section 8.2): a short acknowledgement from memory
        # while the model's first round runs. Only on the first utterance after the tap, which is
        # for the assistant by construction (round 2 speech review 5): on any later turn nothing
        # is said until the model has started an answer or a lookup has started.
        picked = filler_audio.pick(voice, ACK_TOOL)
        if picked is not None:
            line, audio = picked
            acked = True
            timings["ack"] = 1
            timings["filler_ms"] = int((clock() - t0) * 1000)
            yield ev.Event("filler", {"tool": "", "text": speech_tags.untagged(line)})
            yield speaker.audio_event(audio, filler=True)
    last_reply = next(
        (c["parts"][0].get("text", "") for c in reversed(history) if c.get("role") == "model"), ""
    )
    door.addressed = addressed_signals(words, companion_name, last_reply)
    # 2. The loop.
    parts = []
    if context:
        parts.append({"text": context_note(context)})
    if turn_input.preface:
        parts.append({"text": turn_input.preface})
    parts.append({"text": words})
    state = _State(messages=[*history, {"role": "user", "parts": parts}])
    spec = AgentSpec(
        name="web_cascade",
        version=1,
        model=model_name,
        prompt="",
        tools=(*door.tools(), END_TOOL, NOT_FOR_ME_TOOL),
        limits=LIMITS,
    )
    rounds: list[dict] = []
    chip_first = None
    if turn_input.chip is not None:
        chip_first = model = ChipFirst(model, turn_input.chip)
        timings["chip"] = 1

    def on_model_call(row: dict) -> None:
        if chip_first is None or not chip_first.skip_row():
            rounds.append(row)

    first_sentence = None
    verbatim_said = False
    # The acknowledgement is this turn's one line: a lookup's line is never stacked on it.
    filler_said = acked
    filler_dropped = 0
    no_answer = False
    money_dropped = 0
    sentences = 0

    # The first lookup of the turn and when it started; its line waits for FILLER_WAIT_S.
    lookup: tuple[str, float] | None = None

    def lookup_line():
        """The lookup's line, once FILLER_WAIT_S has passed since it started with nothing said;
        never once an answer sentence is ready, never a second line in the turn."""
        if lookup is None or filler_said or sentences or not speak:
            return None
        if clock() - lookup[1] < FILLER_WAIT_S or door.cancelled():
            # Never after a cancel (asked only once the line is due: it reads the database).
            return None
        return (yield from filler(lookup[0]))

    def filler(tool: str):
        picked = filler_audio.pick(voice, tool)
        if picked is None:
            return None
        line, audio = picked
        timings["filler_line"] = line
        timings["filler_ms"] = int((clock() - t0) * 1000)
        yield ev.Event("filler", {"tool": tool, "text": line})
        yield speaker.audio_event(audio, filler=True)
        return line

    unbacked = 0
    # Claims of a change not yet backed when said: held until the turn ends, then said if a write
    # backed them by then, else dropped (finding 3: a claim streamed before its own call).
    held_claims: list[tuple[str, str]] = []

    def tainted() -> bool:
        return read_through.read_third_party(door.request)

    def honest(text: str) -> bool:
        """False for a sentence that claims a change when no write has gone through in this turn
        yet (`agents.legion.guards.claims_unbacked_outcome`, the runtime's own check; CLAUDE.md
        section 7). Such a sentence is held and decided at the end of the turn. Nothing else is
        rewritten from the model's words (findings 4 and 10): when in doubt its sentence stays."""
        return not claims_unbacked_outcome(text, door.actions())

    def say(
        text: str, tagged: str | None = None, replay: str | None = None, exact_text: bool = False
    ):
        """`text` is what is shown, stored and checked (no tags, ever); `tagged` is a model
        sentence as written, its allowed tags kept for speech only (`speech_tags`). Code lines
        (verbatim figures, refusals, failures) pass no `tagged` and are spoken plain. `replay` is
        what history keeps of it instead (`TurnReport.replay`). `exact_text`: code's verbatim
        sentences (his profile) are shown exactly as written, never put through the tag stripper,
        which would drop an asterisk or a bracketed word from them."""
        nonlocal sentences, first_sentence
        if not exact_text:
            text = speech_tags.untagged(text)
        sentences += 1
        report.say(text, replay)
        if first_sentence is None:
            first_sentence = int((clock() - t0) * 1000)
        speaker.add(
            tagged if tagged is not None else text,
            expressive=tagged is not None,
            model=tagged is not None,
        )
        return ev.Event("sentence", {"text": text})

    loop = run_loop(
        spec,
        state,
        model,
        door,
        system=system,
        hooks=LoopHooks(
            on_model_call=on_model_call,
            clock=clock,
            started=t0,
            cancelled=door.cancelled,
        ),
    )
    result = None
    try:
        while True:
            yield from speaker.ready()
            try:
                event = next(loop)
            except StopIteration as stop:
                result = stop.value
                break
            if event.type == "waiting":
                # A round has said nothing yet: the lookup's line, if it is due (`Ticking`).
                line = yield from lookup_line()
                filler_said = filler_said or line is not None
                continue
            if event.type != "sentence":
                # A round ended (a tool is next, or the turn): what it said goes to speech now.
                speaker.flush()
            if event.type == "working":
                # A slow tool has started: its own line, from memory, before it runs.
                yield event
                tool = event.data["tool"]
                # Only a network lookup is announced at its start (`fillers.is_slow`); the door's
                # wider slow list (writes, SLOW_TOOLS) still makes the `working` event.
                if (
                    speak
                    and fillers.is_slow(tool)
                    and sentences == 0
                    and not filler_said
                    and not door.cancelled()
                    and door.would_accept(tool)
                ):
                    # Never for a call the door is about to refuse (round 2 speech review 7).
                    line = yield from filler(tool)
                    filler_said = line is not None
                continue
            if event.type == "tool" and door.calls[-1].name == NOT_FOR_ME_TOOL:
                if door.not_for_me:
                    # Not for it (section 10): no reply, no tool, no filler. The loop stops here.
                    break
                continue
            record_user()
            if event.type == "tool":
                done = door.calls[-1]
                if done.ok and door._writes(done.name):
                    # A change was made: from here every figure is said exactly, as a
                    # confirmation is (round 2 speech review 9).
                    speaker.exact = True
                payload = {"name": done.name, "ok": done.ok, "outcome": done.outcome}
                payload["text"] = done.text
                payload["ms"] = done.ms
                if done.card is not None:
                    payload["card"] = done.card
                yield ev.Event("tool", payload)
                # No filler once its tool has returned (round 2 speech review 7): the result is in
                # hand, so a line would only delay what comes next, a code-written sentence first.
                # His profile (ends_turn) is always said; the money sentence once per turn, and
                # only it holds back the model's later money sentences (`verbatim_said`).
                if done.verbatim and (done.ends_turn or not verbatim_said):
                    if not done.ends_turn:
                        verbatim_said = True
                    if done.exact:
                        # Code's own sentences (his profile): no figure in them is rounded.
                        speaker.exact = True
                    for i, line in enumerate(done.verbatim):
                        marker = None
                        if done.replay is not None:
                            marker = done.replay if i == 0 else ""
                        yield say(line, replay=marker, exact_text=done.exact)
                elif speak and done.ok and lookup is None:
                    # A lookup returned and the answer is not in hand yet: its line is due if the
                    # answer still is not, FILLER_WAIT_S after the lookup started.
                    lookup = (done.name, clock() - done.ms / 1000)
                line = yield from lookup_line()
                filler_said = filler_said or line is not None
                continue
            if event.type == "sentence":
                tagged = event.data["text"]
                # Every check below reads the sentence without its tags (`speech_tags`).
                text = speech_tags.untagged(tagged)
                if not text:
                    continue
                if text == NO_ANSWER_WORDS:
                    # The runtime's line for a turn with no words. Held: after tools, the turn is
                    # asked once more (below); else the phone's own line is said (spec 31).
                    no_answer = True
                    continue
                if sentences == 0 and is_filler(text):
                    filler_dropped += 1
                    continue
                if (verbatim_said and has_money(text)) or states_unbacked_figure(
                    text, backed_cents(door.calls)
                ):
                    money_dropped += 1
                    continue
                if sentences >= MAX_SPOKEN_SENTENCES:
                    continue
                if not honest(text):
                    held_claims.append((text, tagged))
                    continue
                yield say(text, tagged)
                continue
            yield event
    finally:
        loop.close()
        if not door.not_for_me:
            # A turn cut short before its first event still keeps what was asked.
            record_user()
        report.rounds = len(rounds)
        timings.update(
            rounds=len(rounds),
            tools=len(door.calls),
            model_ms=sum(r.get("total_ms") or 0 for r in rounds),
            prompt_tokens=sum(r.get("prompt_tokens") or 0 for r in rounds),
            cached_tokens=sum(r.get("cached_tokens") or 0 for r in rounds),
            output_tokens=sum(r.get("output_tokens") or 0 for r in rounds),
            first_sentence_ms=first_sentence,
            sentences=sentences,
            money_dropped=money_dropped,
            verbatim=int(verbatim_said),
            filler=int(filler_said and not acked),
        )
        _meter(request, model_name, rounds)

    if door.not_for_me:
        yield from _not_for_me(request, door, speaker, report, timings)
        return
    record_user()
    if door.cancelled():
        # The browser stopped this turn: nothing more is asked for or said (2026-11-17, cancel).
        report.status = "cancelled"
        yield ev.done("cancelled", " ".join(report.said).strip(), door.wrote())
        return
    report.status = result.status if result is not None else "cancelled"
    failure = _failure_words(result, door)
    if (
        failure is None
        and result is not None
        and result.status == "done"
        and sentences == 0
        and (filler_dropped or no_answer)
        and door.calls
        and not door.end_requested
    ):
        # Spec 23: the tools ran and the answer was only filler, or nothing at all (measured
        # 2026-11-17: a write refused after a web search left the last round empty, and the person
        # heard neither the search's answer nor the refusal). Asked once more, tool-free, with the
        # phone's note; whatever it says is the answer.
        for tagged in _ask_again(model, model_name, system, state.messages, door, rounds):
            text = speech_tags.untagged(tagged)
            if not text or is_filler(text) or (verbatim_said and has_money(text)):
                continue
            if states_unbacked_figure(text, backed_cents(door.calls)):
                money_dropped += 1
                continue
            if not honest(text):
                held_claims.append((text, tagged))
                continue
            yield say(text, tagged)
        report.rounds = len(rounds)
        timings["rounds"] = len(rounds)
        _meter(request, model_name, rounds[-1:])
    # A change ADR 0090 held back: a write refused by the outside-text rule, or `remember`'s own
    # refusal (round 2 finding 5: that one was missed, so "Noted." passed with nothing saved).
    guard_refused = any(
        not c.ok
        and (
            (c.text.startswith("Nothing was") and "read outside text" in c.text)
            or c.text == read_through.REFUSAL
        )
        for c in door.calls
    )
    # The answer's own words go to speech first; what follows may use the request kept back.
    speaker.flush()
    speaker.closing = True
    # The closing lines are decided from what happened, at the END of the turn (findings 2, 3).
    for text, tagged in held_claims:
        if states_unbacked_figure(text, backed_cents(door.calls)):
            # Held before the money tool ran; its figures are checked now, like every sentence's
            # (blind review finding 3). It was still a claim of a change, so the closing line about
            # the change is said in its place (round 2, N1): "went through" or "did not".
            money_dropped += 1
            unbacked += 1
        elif honest(text):
            yield say(text, tagged)
        else:
            unbacked += 1
    # The final count: the ask-again round's and the held claims' drops included (finding 2).
    timings["money_dropped"] = money_dropped
    wrote = door.wrote()
    if guard_refused:
        yield say(READ_THROUGH_WAIT_AFTER_WRITE if wrote else READ_THROUGH_WAIT)
    elif unbacked:
        if wrote:
            yield say(CHANGE_MADE)
        elif tainted():
            yield say(READ_THROUGH_WAIT)
        else:
            yield say(CHANGE_NOT_MADE)
    elif wrote and any(c.ends_turn for c in door.calls):
        # The turn ended on code's own answer (his profile) in the round that also made a change:
        # the model never got to confirm it, so code does, in its own words.
        yield say(CHANGE_MADE)
    timings["wait_reason"] = int(guard_refused)
    timings["unbacked"] = unbacked
    if failure is not None:
        yield say(failure)
    elif sentences == 0 and not door.end_requested:
        yield say(NO_TEXT + ("" if door.wrote() else NOTHING_CHANGED))
    yield from speaker.drain()
    if door.cancelled():
        # Stopped while its speech played out (round 3 N4): the turn ends as cancelled.
        report.status = "cancelled"
    if not door.end_requested and is_stop_utterance(words):
        door.end_requested = True
        timings["stop_phrase"] = 1
    extra = {}
    if door.recruiter:
        # The demo's live strip (recruiter only): what this turn is estimated to have cost.
        extra["cost_usd"] = round(turn_cost_usd(timings, speaker.done), 5)
        extra["tools_ms"] = [[c.name, c.ms] for c in door.calls]
        # The follow-up chips: only what ran, signed for this visitor; the page asks for the
        # checked chips after the answer (`demo.suggestions`), never inside the turn.
        offer = (
            None
            if door.end_requested
            else suggestions.turn_offer(
                door.user, door.calls, " ".join(report.said), report.status
            )
        )
        if offer is not None:
            extra["offer"] = offer
    yield ev.done(
        report.status,
        " ".join(report.said).strip(),
        door.wrote(),
        end=door.end_requested,
        **extra,
    )


def _not_for_me(request, door, speaker, report, timings):
    """The sentence was not for the assistant, or it is not sure (section 10). Nothing is said and
    nothing is stored beyond a count; unsure shows a cue on screen. The page listens again with a
    fresh window. Logged with the turn number and the signals, never the words."""
    decision = addressed_decision(door.addressed, model_says_not=True)
    report.status = decision
    report.said.clear()
    report.replay.clear()
    timings["addressed"] = 0 if decision == "not_addressed" else 1
    signals = " ".join(f"{k}={int(v)}" for k, v in sorted(door.addressed.items()))
    now = datetime.now(UTC)
    line = web_conversation.line(
        now,
        web_conversation.TID_ENGINE,
        web_conversation.AUDIT,
        f"addressed decision={decision} turn={door.turn} {signals}",
    )
    web_conversation.record_lines(request, door.conversation_id, [(now, line)])
    log.warning("assistant addressed decision=%s turn=%d %s", decision, door.turn, signals)
    yield from speaker.drain()
    extra = {"words": UNSURE_CUE} if decision == "unsure" else {}
    yield ev.done(decision, "", False, ignored=decision, **extra)


def _ask_again(model, model_name, system, messages, door, rounds) -> list[str]:
    """One tool-free round after a filler-only answer (spec 23). [] on any failure."""
    req = ModelRequest(
        model=model_name,
        system=system,
        contents=[*messages, {"role": "user", "parts": [{"text": FILLER_NOTE}]}],
        declarations=door.declarations(),
        allow_tools=False,
        timeout_s=LIMITS.round_timeout_s,
    )
    splitter = SentenceSplitter()
    out: list[str] = []
    usage = Usage()
    try:
        for item in model.stream(req):
            if isinstance(item, TextDelta):
                out.extend(splitter.feed(item.text))
            elif isinstance(item, Usage):
                usage = item
    except ModelFailure:
        return []
    finally:
        rounds.append(
            {
                "prompt_tokens": usage.prompt_tokens,
                "cached_tokens": usage.cached_tokens,
                "output_tokens": usage.output_tokens,
            }
        )
    out.extend(splitter.flush())
    return out


def conversation_id_of(door: WebDoor):
    return door.conversation_id


def _tour(request, door, speaker, report, beat: int, words: str | None):
    """One beat of the one-minute tour (`assistant/tour.py`): its visual through the tool door,
    its sentence from code. Skips beats with nothing to show; "stop" ends the tour."""
    report.status = "tour"
    if words is not None and tour.is_stop(words):
        report.said.append(speech_tags.untagged(tour.STOPPED_LINE))
        speaker.add(tour.STOPPED_LINE, expressive=True)
        yield ev.Event("sentence", {"text": speech_tags.untagged(tour.STOPPED_LINE)})
        yield from speaker.drain()
        yield ev.done(
            "tour",
            speech_tags.untagged(tour.STOPPED_LINE),
            False,
            tour={"beat": beat, "next": None},
        )
        return
    beats = tour.beats_for(request.user) if door.recruiter else []
    index = beat
    said = ""
    while index < len(beats):
        said, ran = tour.run_beat(door, beats[index])
        done = door.calls[-1] if beats[index].tool and door.calls else None
        if done is not None and ran:
            payload = {"name": done.name, "ok": done.ok, "outcome": done.outcome, "ms": done.ms}
            payload["text"] = done.text
            if done.card is not None:
                payload["card"] = done.card
            yield ev.Event("tool", payload)
        if ran and said:
            break
        index += 1
    nxt = index + 1 if index + 1 < len(beats) else None
    if index >= len(beats):
        said, nxt = tour.END_LINE, None
    # Only the tour's own code lines may carry a tag; words from the content never do.
    about_him = index < len(beats) and beats[index].about_him
    # His profile's own sentences are shown and said exactly as written (no tag stripper, no figure
    # rounding), as `about_kevin` says them.
    shown = said if about_him else speech_tags.untagged(said)
    if about_him:
        speaker.exact = True
    # A beat that states facts about him (his profile): history replays which beat was read, never
    # its words, so no later turn can reword them (2026-11-18). A diagram's caption is about the
    # project and stays, so a follow-up about the diagram still has it.
    marker = f"(Tour line read aloud: {beats[index].key}.)" if about_him else None
    report.say(shown, marker)
    report.timings["sentences"] = 1
    speaker.add(said, expressive=said in tour.CODE_LINES)
    yield ev.Event("sentence", {"text": shown})
    yield from speaker.drain()
    if nxt is None and said != tour.END_LINE:
        report.said.append(speech_tags.untagged(tour.END_LINE))
        speaker.add(tour.END_LINE, expressive=True)
        yield ev.Event("sentence", {"text": speech_tags.untagged(tour.END_LINE)})
        yield from speaker.drain()
    yield ev.done(
        "tour", " ".join(report.said), False, tour={"beat": index, "next": nxt, "of": len(beats)}
    )


def _greet(
    request,
    turn_input,
    door,
    speaker,
    report,
    system,
    context,
    model_name,
    model,
    clock,
    filler_audio=None,
    companion_name="",
):
    """The greeting on open (spec 25 to 29): Spoken (a lead topic), Hello (nothing to raise) or
    Scripted (a recruiter's visit, or the model past its deadline or failing)."""
    t0 = clock()
    history = turn_input.greeting_history or {}
    kind, prompt, text = "scripted", None, ""
    block = ""
    if door.recruiter:
        later = bool(turn_input.preface)
        text = web_greeting.ack_line() if later else web_greeting.recruiter_hello(companion_name)
    else:
        from greeting import facts

        try:
            pack = facts.pack(request)
        except Exception:  # noqa: BLE001 - no pack is the scripted line, never a failed open
            log.warning("assistant greeting: facts pack failed", exc_info=True)
            pack = None
        if pack is not None:
            local = datetime.fromisoformat(pack["generated_at"])
            chosen = web_greeting.lead(pack, local.hour, history)
            if chosen is not None:
                kind = web_greeting.kind_of_source(chosen[0])
                block = web_greeting.render(pack, f"{local:%H:%M}", chosen)
                prompt = web_greeting.spoken_prompt(block, *chosen)
            else:
                kind = web_greeting.HELLO
                prompt = web_greeting.hello_prompt(history.get("hellos") or [])
    rounds: list[dict] = []
    if door.cancelled():
        report.status = "cancelled"
        yield ev.done("cancelled", "", False)
        return
    if prompt is not None:
        text = _one_call(model, model_name, system, context, prompt, door, rounds, clock)
        if text and not greeting_is_honest(text):
            # The greeting goes through the answers' checks (finding 7): a figure the facts do not
            # hold, or a claimed change, and the scripted line is said instead.
            report.timings["greeting_refused"] = 1
            text = ""
        if not text:
            kind, text = "scripted", web_greeting.ack_line()
    report.rounds = len(rounds)
    report.status = "greeting"
    tagged, text = text, speech_tags.untagged(text)
    report.said.append(text)
    report.timings.update(
        rounds=len(rounds),
        prompt_tokens=sum(r["prompt_tokens"] for r in rounds),
        cached_tokens=sum(r["cached_tokens"] for r in rounds),
        output_tokens=sum(r["output_tokens"] for r in rounds),
        first_sentence_ms=int((clock() - t0) * 1000),
        sentences=1,
    )
    _meter(request, model_name, rounds)
    yield ev.Event("greeting", {"kind": kind, "text": text})
    yield ev.Event("sentence", {"text": text})
    # What is spoken, tags and all; also the line's key in memory, with the voice (`FillerAudio`).
    spoken_as = speaker.spoken_text(tagged, expressive=True)
    cached = (
        filler_audio.get(speaker.voice, spoken_as) if filler_audio and kind == "scripted" else None
    )
    if cached and speaker.enabled:
        # A scripted line made before: it starts at once (the demo's opening, in the tap).
        report.timings["greeting_cached"] = 1
        yield speaker.audio_event(cached)
    else:
        if speaker.enabled:
            speaker.add(spoken_as, expressive=True)
        if (
            filler_audio is not None
            and kind == "scripted"
            and speaker.enabled
            and not door.cancelled()
        ):
            fetched = filler_audio.warm(speaker.speech, speaker.voice, [spoken_as])
            report.timings["speech_fetched"] = (
                _whole(report.timings.get("speech_fetched")) + fetched
            )
    yield from speaker.drain()
    extra = {}
    if door.recruiter and not turn_input.preface:
        # The first greeting carries the examples' offer (`demo.suggestions.EXAMPLES`).
        extra["offer"] = suggestions.examples_offer(door.user)
    yield ev.done("greeting", text, False, **extra)


# A figure in any form (round 3 speech S2, ruling A: NO figure at all in a model-worded greeting):
# digits, a currency or percent sign, any number word but "one", any ordinal, a fraction word, or
# a clock time in words. "one" is allowed alone: it is a pronoun or an article far more often
# ("one thing", "a quiet one"); "one hundred" is still caught by "hundred".
_FIGURE_WORDS = re.compile(
    r"\b(zero|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|"
    r"fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|"
    r"eighty|ninety|hundreds?|thousands?|millions?|billions?|dozens?|couple|"
    r"first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|"
    r"\w+teenth|\w+tieth|hundredth|thousandth|"
    r"half|halves|quarters?|thirds|fourths|fifths|tenths|twice|thrice|double|triple|"
    r"noon|midnight|o'clock|oclock)\b",
    re.I,
)


# Round 4 speech N2: "one" as a clock time ("at one", "one tonight"), "both" (a count), and a span
# of time ("a week from now", "in a month").
_FIGURE_PHRASES = re.compile(
    r"\bat one\b"
    r"|\bone (?:tonight|today|tomorrow|this|o'clock|am|pm|a\.m\.|p\.m\.|fifteen|thirty|forty)\b"
    r"|\bboth\b"
    r"|\b(?:a|an) (?:minute|hour|day|week|fortnight|month|year)s?\b"
    r"(?= (?:from now|ago|away|out|left|or so|to go|later))"
    r"|\bin (?:a|an) (?:minute|hour|day|week|fortnight|month|year)\b",
    re.I,
)


def greeting_states_a_figure(text: str) -> bool:
    """True when a model-worded greeting states any figure (round 2 speech review A: checking or
    rewriting figures in free model text never converged, so a greeting carries none). The
    scripted line is said instead; a figure that belongs in a greeting is written by code."""
    return bool(
        re.search(r"[0-9$%\u00a3\u20ac]", text)
        or _FIGURE_WORDS.search(text)
        or _FIGURE_PHRASES.search(text)
    )


def greeting_is_honest(text: str) -> bool:
    """A model-worded greeting may state no figure, claim no change, and run no longer than
    `web_greeting.MAX_GREETING_WORDS` spoken words (2026-11-17: about five seconds)."""
    if len(speech_tags.untagged(text).split()) > web_greeting.MAX_GREETING_WORDS:
        return False
    return not greeting_states_a_figure(text) and not claims_unbacked_outcome(text, [])


def _one_call(model, model_name, system, context, prompt, door, rounds, clock) -> str:
    """One text call, no tools, against the cache, inside the greeting's deadline. "" on any
    failure or past the deadline (the caller says the scripted line)."""
    parts = [{"text": context_note(context)}] if context else []
    parts.append({"text": prompt})
    req = ModelRequest(
        model=model_name,
        system=system,
        contents=[{"role": "user", "parts": parts}],
        declarations=door.declarations(),
        allow_tools=True,
        timeout_s=web_greeting.DEADLINE_S,
        max_output_tokens=120,
    )
    started = clock()
    splitter = SentenceSplitter()
    out: list[str] = []
    usage = Usage()
    try:
        for item in model.stream(req):
            if clock() - started > web_greeting.DEADLINE_S:
                return ""
            if isinstance(item, TextDelta):
                out.extend(splitter.feed(item.text))
            elif isinstance(item, Usage):
                usage = item
    except ModelFailure:
        return ""
    finally:
        rounds.append(
            {
                "prompt_tokens": usage.prompt_tokens,
                "cached_tokens": usage.cached_tokens,
                "output_tokens": usage.output_tokens,
            }
        )
    out.extend(splitter.flush())
    return " ".join(out).strip()


def _meter(request, model_name: str, rounds: list[dict]) -> None:
    """A member's text rounds as `llm_usage` rows, so their daily cap (`llm/cap.py`) sees cascade
    spend. A recruiter is metered by the demo budget instead and writes none."""
    if not member_cap.applies_to(request.user):
        return
    try:
        with transaction.atomic():
            _meter_rows(request, model_name, rounds)
    except DatabaseError:
        # The turn has happened (a write may have gone through); a failed meter row must not turn
        # it into "nothing was changed" (finding 2).
        log.warning("assistant cascade: meter rows failed", exc_info=True)


def _meter_rows(request, model_name: str, rounds: list[dict]) -> None:
    for row in rounds:
        prompt, out = row.get("prompt_tokens") or 0, row.get("output_tokens") or 0
        cached = min(row.get("cached_tokens") or 0, prompt)
        cost = -(
            -(
                (prompt - cached) * llm_client.INPUT_MICRO_USD_PER_TOKEN_X100
                + cached * CACHED_MICRO_USD_PER_TOKEN_X100
                + out * llm_client.OUTPUT_MICRO_USD_PER_TOKEN_X100
            )
            // 100
        )
        llm_client.record(
            household=household_of(request),
            feature=FEATURE,
            model=model_name,
            user=request.user,
            usage={"prompt_tokens": prompt, "output_tokens": out, "total_tokens": prompt + out},
            cost_estimate_micro_usd=cost,
        )


def _meter_speech(request, timings: dict) -> None:
    """A member's speech-to-text and text-to-speech as `llm_usage` rows too (finding 12), so their
    daily cap sees all of a spoken turn. Estimates, at `tools/voice_prices.json`'s prices."""
    if not member_cap.applies_to(request.user):
        return

    def g(key: str) -> int:
        value = timings.get(key)
        return value if isinstance(value, int) and value > 0 else 0

    parts = (
        ("stt", g("stt_prompt_tokens"), g("stt_output_tokens"), PRICE_TEXT_IN, PRICE_TEXT_OUT),
        ("tts", g("tts_prompt_tokens"), g("tts_output_tokens"), PRICE_TTS_IN, PRICE_TTS_OUT),
    )
    try:
        with transaction.atomic():
            for model, prompt, out, price_in, price_out in parts:
                if not (prompt or out):
                    continue
                llm_client.record(
                    household=household_of(request),
                    feature=FEATURE,
                    model=model,
                    user=request.user,
                    usage={
                        "prompt_tokens": prompt,
                        "output_tokens": out,
                        "total_tokens": prompt + out,
                    },
                    cost_estimate_micro_usd=int(prompt * price_in + out * price_out) + 1,
                )
    except DatabaseError:
        log.warning("assistant cascade: speech meter rows failed", exc_info=True)


# USD per million tokens, from `tools/voice_prices.json` (read 2026-11-15). ESTIMATES: what the
# live strip shows and what the reports quote, never a bill.
PRICE_TEXT_IN = 0.30
PRICE_TEXT_CACHED = 0.03
PRICE_TEXT_OUT = 2.50
PRICE_TTS_IN = 0.50
PRICE_TTS_OUT = 9.00


def turn_cost_usd(timings: dict, spoken: list | None = None) -> float:
    """A turn's estimated cost: speech-to-text, the text rounds (cached input at the cached
    rate) and the speech made. `spoken` is the speaker's per-sentence stats, when the turn has not
    yet folded them into `timings`."""

    def g(key: str) -> int:
        value = timings.get(key)
        return value if isinstance(value, int) and value > 0 else 0

    tts_in = sum(s.prompt_tokens for s in spoken) if spoken is not None else g("tts_prompt_tokens")
    tts_out = sum(s.output_tokens for s in spoken) if spoken is not None else g("tts_output_tokens")
    cached = min(g("cached_tokens"), g("prompt_tokens"))
    usd = (
        (g("prompt_tokens") - cached + g("stt_prompt_tokens")) * PRICE_TEXT_IN
        + cached * PRICE_TEXT_CACHED
        + (g("output_tokens") + g("stt_output_tokens")) * PRICE_TEXT_OUT
        + tts_in * PRICE_TTS_IN
        + tts_out * PRICE_TTS_OUT
    )
    return usd / 1_000_000


def timing_line(report: TurnReport, *, spoken: bool) -> str:
    """The record's and the log's `cascade_turn` line: names and numbers only."""
    keys = (
        "audio_ms",
        "stt_ms",
        "stt_prompt_tokens",
        "stt_output_tokens",
        "rounds",
        "tools",
        "model_ms",
        "prompt_tokens",
        "cached_tokens",
        "output_tokens",
        "first_sentence_ms",
        "sentences",
        "verbatim",
        "money_dropped",
        "unbacked",
        "filler",
        "tts_first_audio_ms",
        "tts_ms",
        "tts_audio_ms",
        "tts_prompt_tokens",
        "tts_output_tokens",
        "tts_failed",
        "turn_ms",
        "ack",
        "filler_ms",
    )
    values = " ".join(
        f"{k}={-1 if report.timings.get(k) is None else report.timings[k]}" for k in keys
    )
    status = web_conversation.word(report.status)
    return f"cascade_turn status={status} spoken={str(spoken).lower()} {values}"
