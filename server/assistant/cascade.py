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
    ModelClient,
    ModelFailure,
    ModelRequest,
    TextDelta,
    Usage,
)
from agents.runtime.spec import AgentSpec
from agents.runtime.tools import ToolCall, ToolOutcome
from assistant import fillers, read_through, speech_tags, spoken_figures, tour, web_greeting
from assistant.models import AssistantCall
from assistant.speech import OUTPUT_RATE, GeminiSpeech, SpeechFailure, Spoken
from assistant.surface import recruiter_only_tool, refusal_for, tools_for
from devicelogs import web_conversation
from engine_mcp import cards
from engine_mcp.models import Outcome
from engine_mcp.tools import SPEAK_VERBATIM, run_engine_tool, run_tool
from household.models import is_recruiter
from household.tenancy import household_of
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
VERBATIM_TOOLS: frozenset[str] = frozenset({"account_balances"})
# Cached input, in hundredths of a micro-dollar per token: the rate the agent turn quotes for
# flash-lite (`agents/legion/turn.py`, $0.03 / 1M). An ESTIMATE, like every cost here.
CACHED_MICRO_USD_PER_TOKEN_X100 = 3
# How long the turn waits for the next sentence's speech before it gives up on it.
TTS_WAIT_S = 20.0

# The phone's `CascadePrompt.VOICE_CLAUSE` (spec 11), verbatim: added to the cached prefix.
VOICE_CLAUSE = (
    "Spoken output: what you write is read aloud by a separate voice, as you write it. Write plain "
    "spoken sentences only: no markdown, lists, headings, emoji, links or symbols. Write numbers, "
    "money, times and units the way you would say them. Keep replies short unless asked for "
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
# Said instead of a sentence claiming a change no tool made (measured 2026-11-17: after a web
# search, "I have added that to your grocery list", with the write refused or never tried).
CHANGE_NOT_MADE = "Nothing was changed: that change did not go through, so ask me again for it."
# ADR 0090's reason in code, spoken whenever a turn that read outside text does not make a change
# it was asked for (measured 2026-11-17: the model gave a false reason, "that only works in the
# phone app", or claimed the change). The person's next message may make it.
READ_THROUGH_WAIT = (
    "I haven't changed anything: this turn read text from the web, so changes wait for your next "
    "message. Ask me again and I'll do it."
)
# A sentence of the model's about not making a change: a can't-word and a change-word together.
_CANT = re.compile(
    r"\b(can['’]?t|cannot|can not|couldn['’]?t|unable|not able|won['’]?t be able|"
    r"don['’]?t have (a|the|any) (tool|way)|only (works )?(in|on|from) the "
    r"(phone|full|mobile|android) app|from here|held back)\b",
    re.I,
)
_CHANGE = re.compile(
    r"\b(add|added|adding|put|save|saved|change|changed|delete|remove|update|set|create|schedule|"
    r"log|write|list|calendar|remind|reminder)\b",
    re.I,
)
# The web's own tools change lists, checklists, the calendar and purchases (`assistant.surface`), so
# "that only works in the phone app" about one of them is never true here (measured 2026-11-17, in
# a turn with no outside read). Said by code in place of that sentence.
WEB_CAN = "That can be done from here: tell me exactly what to change and I'll do it."
_PHONE_APP = re.compile(r"\b(phone|android|mobile|full) app\b", re.I)
_WEB_TOOLS_WORD = re.compile(
    r"\b(list|lists|checklist|checklists|calendar|event|events|purchase|purchases)\b", re.I
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


class FillerAudio:
    """Each filler line's audio per voice, kept in this process once made. A line is said only from
    memory; a missing one is fetched in the background for next time (the phone's rule: a filler
    that waits for speech holds the answer up behind it)."""

    def __init__(self, spawn: Callable[[Callable[[], None]], None] | None = None):
        self._audio: dict[tuple[str, str], bytes] = {}
        self._fetching: set[tuple[str, str]] = set()
        self._lock = threading.Lock()
        self._last: str | None = None
        self._spawn = spawn or (lambda work: threading.Thread(target=work, daemon=True).start())

    def warm(self, speech, voice: str, lines=None) -> int:
        lines = fillers.all_lines() if lines is None else lines
        missing = []
        with self._lock:
            for line in lines:
                key = (voice, line)
                if key not in self._audio and key not in self._fetching:
                    self._fetching.add(key)
                    missing.append(line)
        if not missing:
            return 0

        def fetch() -> None:
            for line in missing:
                try:
                    audio = b"".join(speech.speak(line, voice, Spoken()))
                except (SpeechFailure, OSError):
                    audio = b""
                with self._lock:
                    self._fetching.discard((voice, line))
                    if audio:
                        self._audio[(voice, line)] = audio

        self._spawn(fetch)
        return len(missing)

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
GROUP_SENTENCES = 3
GROUP_CHARS = 400
# A first sentence longer than this many words has its first clause spoken on its own.
FIRST_CLAUSE_WORDS = 14
_CLAUSE_BREAK = re.compile(r"(?<=[,;:\u2014])\s+")


def first_clause(sentence: str) -> tuple[str, str]:
    """(what is spoken at once, the rest). A long first sentence is split after its first comma
    that leaves at least four words in front; a short one goes whole."""
    words = sentence.split()
    if len(words) <= FIRST_CLAUSE_WORDS:
        return sentence, ""
    for match in _CLAUSE_BREAK.finditer(sentence):
        head = sentence[: match.start()].strip()
        if len(head.split()) >= 4 and len(sentence[match.end() :].split()) >= 3:
            return head, sentence[match.end() :].strip()
    return sentence, ""


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
        self._pool = ThreadPoolExecutor(max_workers=2) if enabled else None
        # The person asked for exact figures this turn: nothing is rounded when spoken.
        self.exact = False
        # "One breath per reply" (voice-persona section 8.3): the first words go to speech alone
        # and at once; what follows in the same round is one request, so its intonation carries
        # across sentences. `SPEECH_GROUPING` "sentence" is the old one-request-per-sentence.
        self.grouping = SPEECH_GROUPING
        self._first_out = False
        self._pending: list[str] = []

    def spoken_text(self, text: str, expressive: bool = False) -> str:
        """What goes to speech for `text`: its figures rounded like a person says them
        (`spoken_figures`; the exact ones stay in the caption), and its allowed tags only when
        `expressive` (a model sentence or a code line written with one)."""
        said = spoken_figures.speakable(text, exact=self.exact)
        return speech_tags.for_speech(said, expressive=expressive)

    def add(self, text: str, expressive: bool = False) -> None:
        if not self.enabled:
            return
        spoken = self.spoken_text(text, expressive)
        if self.grouping == "sentence":
            self._submit(spoken)
            return
        if not self._first_out:
            # The answer's first clause is spoken as soon as it exists (section 8.2).
            self._first_out = True
            head, rest = first_clause(spoken)
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
        job = _Job(spoken, queue.Queue(), Spoken(), threading.Event())
        self.jobs.append(job)
        self._pool.submit(self._run, job)

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
        while self.jobs:
            head = self.jobs[0]
            try:
                item = head.chunks.get(timeout=TTS_WAIT_S) if block else head.chunks.get_nowait()
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
    verbatim: str | None = None
    ms: int = 0


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
        self._tools = None
        self._declarations = None
        read_through.set_turn(request, conversation_id, turn)

    def tools(self) -> dict:
        if self._tools is None:
            self._tools = {t.name: t for t in tools_for(self.user)}
        return self._tools

    def declarations(self, spec=None) -> list[dict]:
        if self._declarations is None:
            self._declarations = [*rest_declarations(list(self.tools().values())), END_DECLARATION]
        return self._declarations

    def is_slow(self, name: str) -> bool:
        # The runtime says `working` before a slow tool; the web's slow list is the fillers'
        # (a tool whose line goes out at its start), which covers the runtime's own SLOW_TOOLS.
        return fillers.is_slow(name) or name in SLOW_TOOLS

    def _audit(self, outcome: str, name: str) -> None:
        AssistantCall.objects.create(
            household=household_of(self.request),
            user=self.user,
            kind=AssistantCall.TOOL,
            tool=name[:200],
            outcome=outcome,
        )

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome:
        started = datetime.now(UTC)
        clock = time.monotonic()
        name, args = call.name, dict(call.args or {})
        result = None
        if len(self.calls) >= MAX_TOOL_CALLS:
            ok, outcome, text, guard = False, Outcome.REFUSED, TOO_MANY_TOOLS, "limit"
        elif name == END_TOOL:
            # A control, not a data tool: the page closes the conversation after the sign-off.
            self.end_requested = True
            ok, outcome, text, guard = True, Outcome.OK, END_INSTRUCTION, None
        else:
            refusal = refusal_for(name, self.user, args)
            if refusal is not None:
                ok, outcome, text = False, Outcome.REFUSED, refusal
                guard = "recruiter" if self.recruiter else "not_allowed"
            else:
                only_here = recruiter_only_tool(name, self.user)
                if only_here is not None:
                    result = run_engine_tool(self.request, only_here, args)
                else:
                    result = run_tool(self.request, name, args)
                ok, outcome, text, guard = not result.is_error, result.outcome, result.text, None
        self._audit(outcome, name)
        web_conversation.record_tool(
            self.request,
            self.conversation_id,
            call_id=call.id or f"c{len(self.calls) + 1}",
            name=name,
            args=args,
            started=started,
            ended=datetime.now(UTC),
            success=ok,
            outcome=outcome,
            text=text,
        )
        card = cards.for_viewer(result.card, self.recruiter) if result is not None else None
        verbatim = None
        if ok and result is not None and name in VERBATIM_TOOLS:
            said = (result.structured or {}).get(SPEAK_VERBATIM)
            verbatim = said.strip() if isinstance(said, str) and said.strip() else None
        ms = int((time.monotonic() - clock) * 1000)
        self.calls.append(DoorCall(name, ok, outcome, text, card, verbatim, ms))
        tool = self.tools().get(name)
        return ToolOutcome(
            name=name,
            ok=ok,
            response={"success": ok, "message": text},
            writes=bool(tool is not None and tool.writes),
            outcome="ok" if ok else str(outcome),
            guard=guard,
            ms=int((time.monotonic() - clock) * 1000),
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
    # The one-minute tour (`assistant/tour.py`): the beat to play, or None.
    tour_beat: int | None = None


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
    said: list[str] = field(default_factory=list)
    timings: dict = field(default_factory=dict)


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
    "For a list, say three, then how many more. Write every figure exactly as the tools gave it, "
    "to the cent: the voice rounds it for speech and the exact one stays on screen."
)


# Taught by example, not adjectives (section 8.1). Invented content, for the shape only.
SPOKEN_EXAMPLES = (
    "How replies sound (invented examples, for the shape only; never use their facts): "
    '"What\'s due this week?" -> "Three things. The stats quiz Thursday night is the big one." '
    '"How much did we spend on groceries last month?" -> "$412.38, a bit under the month before." '
    '"Is the car due for anything?" -> "An oil change, in about 600 miles. Want it on the '
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
) -> Iterator[ev.Event]:
    """Yields the turn's events. Fills `report` as it goes, so a caller that stops reading (the
    browser hung up) still knows what happened."""
    t0 = clock()
    timings = report.timings
    filler_audio = filler_audio or FILLER_AUDIO
    speak = (
        turn_input.audio is not None
        or turn_input.greet
        or turn_input.spoken
        or turn_input.tour_beat is not None
    )
    system = cached_system(system)
    yield ev.Event("turn", {"conversation_id": str(conversation_id), "turn": turn})
    door = WebDoor(request, conversation_id, turn)
    speaker = Speaker(speech, voice, enabled=speak, clock=clock)
    if speak:
        # The filler lines' audio, fetched while the floor is theirs (spec 22).
        filler_audio.warm(speech, voice)
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
                    heard = speech.transcribe(turn_input.audio)
                except SpeechFailure as exc:
                    timings["stt_failed"] = str(exc)
                    report.status = "stt_failed"
                    yield ev.error(STT_FAILED)
                    return
                timings.update(stt_ms=heard.ms, audio_ms=heard.audio_ms)
                timings.update(
                    stt_prompt_tokens=heard.prompt_tokens, stt_output_tokens=heard.output_tokens
                )
                words = heard.text
                yield ev.Event("heard", {"text": words})
            if words is None or tour.is_skip(words) or tour.is_stop(words):
                yield from _tour(request, door, speaker, report, turn_input.tour_beat, words)
                return
            turn_input = dataclasses.replace(
                turn_input, audio=None, text=words, spoken=True, tour_beat=None
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
            model,
            speech,
            clock,
            filler_audio,
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
):
    timings = report.timings
    t0 = clock()
    speak = speaker.enabled

    # 1. The words.
    if turn_input.audio is not None:
        try:
            heard = speech.transcribe(turn_input.audio)
        except SpeechFailure as exc:
            timings["stt_failed"] = str(exc)
            report.status = "stt_failed"
            yield ev.error(STT_FAILED)
            return
        timings.update(
            stt_ms=heard.ms,
            audio_ms=heard.audio_ms,
            stt_prompt_tokens=heard.prompt_tokens,
            stt_output_tokens=heard.output_tokens,
        )
        words = heard.text
        yield ev.Event("heard", {"text": words})
        if not words:
            report.status = "nothing_heard"
            yield ev.done("nothing_heard", "", False, words=NOTHING_HEARD)
            return
    else:
        words = (turn_input.text or "").strip()
        if turn_input.spoken:
            yield ev.Event("heard", {"text": words})
    report.heard = words
    speaker.exact = spoken_figures.wants_exact(words)
    web_conversation.record_lines(
        request,
        conversation_id_of(door),
        [web_conversation.engine_user_line(datetime.now(UTC), words, typed=not speak)],
    )
    if is_robotic_remark(words):
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
    if speak and needs_work(words):
        # First sound within about a second (section 8.2): a short acknowledgement from memory
        # while the model's first round runs. Never the same one twice running.
        picked = filler_audio.pick(voice, ACK_TOOL)
        if picked is not None:
            line, audio = picked
            timings["ack"] = 1
            yield ev.Event("filler", {"tool": "", "text": speech_tags.untagged(line)})
            yield speaker.audio_event(audio, filler=True)
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
        tools=(*door.tools(), END_TOOL),
        limits=LIMITS,
    )
    rounds: list[dict] = []
    first_sentence = None
    verbatim_said = False
    filler_said = False
    filler_dropped = 0
    no_answer = False
    money_dropped = 0
    sentences = 0

    def filler(tool: str):
        picked = filler_audio.pick(voice, tool)
        if picked is None:
            return None
        line, audio = picked
        timings["filler_line"] = line
        yield ev.Event("filler", {"tool": tool, "text": line})
        yield speaker.audio_event(audio, filler=True)
        return line

    unbacked = 0
    wait_reason = False
    web_can = False

    def tainted() -> bool:
        return read_through.read_third_party(door.request)

    def honest(text: str) -> bool:
        """False for a sentence that claims a change when no write went through in this turn
        (`agents.legion.guards.claims_unbacked_outcome`, the runtime's own check, here blocking
        rather than flagging; CLAUDE.md section 7). Measured 2026-11-17: after a web search, the
        model said it had added a date to a list with no write tried at all."""
        nonlocal unbacked, wait_reason, web_can
        if claims_unbacked_outcome(text, door.actions()):
            unbacked += 1
            return False
        if tainted() and _CANT.search(text) and _CHANGE.search(text):
            # The model's own reason for not changing something in a read-through turn: replaced
            # by the true one, in code (ADR 0090).
            wait_reason = True
            return False
        if _CANT.search(text) and _PHONE_APP.search(text) and _WEB_TOOLS_WORD.search(text):
            # A false "only in the phone app" about something the web's own tools do.
            web_can = True
            return False
        return True

    def say(text: str, tagged: str | None = None):
        """`text` is what is shown, stored and checked (no tags, ever); `tagged` is a model
        sentence as written, its allowed tags kept for speech only (`speech_tags`). Code lines
        (verbatim figures, refusals, failures) pass no `tagged` and are spoken plain."""
        nonlocal sentences, first_sentence
        text = speech_tags.untagged(text)
        sentences += 1
        report.said.append(text)
        if first_sentence is None:
            first_sentence = int((clock() - t0) * 1000)
        speaker.add(tagged if tagged is not None else text, expressive=tagged is not None)
        return ev.Event("sentence", {"text": text})

    loop = run_loop(
        spec,
        state,
        model,
        door,
        system=system,
        hooks=LoopHooks(on_model_call=rounds.append, clock=clock, started=t0),
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
            if event.type != "sentence":
                # A round ended (a tool is next, or the turn): what it said goes to speech now.
                speaker.flush()
            if event.type == "working":
                # A slow tool has started: its own line, from memory, before it runs.
                yield event
                if speak and sentences == 0 and not filler_said:
                    line = yield from filler(event.data["tool"])
                    filler_said = line is not None
                continue
            if event.type == "tool":
                done = door.calls[-1]
                payload = {"name": done.name, "ok": done.ok, "outcome": done.outcome}
                payload["text"] = done.text
                payload["ms"] = done.ms
                if done.card is not None:
                    payload["card"] = done.card
                yield ev.Event("tool", payload)
                waited = (clock() - t0) * 1000
                if (
                    speak
                    and sentences == 0
                    and not filler_said
                    and not fillers.is_slow(done.name)
                    and waited >= fillers.FAST_AFTER_MS
                ):
                    # A fast tool returned and nothing has been said yet: its line fills the
                    # model's next round. Never once an answer has started.
                    line = yield from filler(done.name)
                    filler_said = line is not None
                if done.verbatim and not verbatim_said:
                    verbatim_said = True
                    yield say(done.verbatim)
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
                if verbatim_said and has_money(text):
                    money_dropped += 1
                    continue
                if sentences >= MAX_SPOKEN_SENTENCES or not honest(text):
                    continue
                yield say(text, tagged)
                continue
            yield event
    finally:
        loop.close()
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
            filler=int(filler_said),
        )
        _meter(request, model_name, rounds)

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
            if not honest(text):
                continue
            yield say(text, tagged)
        report.rounds = len(rounds)
        timings["rounds"] = len(rounds)
        _meter(request, model_name, rounds[-1:])
    guard_refused = any(
        not c.ok and c.text.startswith("Nothing was") and "read outside text" in c.text
        for c in door.calls
    )
    if wait_reason or guard_refused or (unbacked and tainted()):
        yield say(READ_THROUGH_WAIT)
    elif unbacked:
        yield say(CHANGE_NOT_MADE)
    elif web_can:
        yield say(WEB_CAN)
    timings["wait_reason"] = int(wait_reason or guard_refused)
    timings["unbacked"] = unbacked
    if failure is not None:
        yield say(failure)
    elif sentences == 0 and not door.end_requested:
        yield say(NO_TEXT + ("" if door.wrote() else NOTHING_CHANGED))
    yield from speaker.drain()
    if not door.end_requested and is_stop_utterance(words):
        door.end_requested = True
        timings["stop_phrase"] = 1
    extra = {}
    if door.recruiter:
        # The demo's live strip (recruiter only): what this turn is estimated to have cost.
        extra["cost_usd"] = round(turn_cost_usd(timings, speaker.done), 5)
        extra["tools_ms"] = [[c.name, c.ms] for c in door.calls]
    yield ev.done(
        report.status,
        " ".join(report.said).strip(),
        door.wrote(),
        end=door.end_requested,
        **extra,
    )


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
    shown = speech_tags.untagged(said)
    report.said.append(shown)
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
    if prompt is not None:
        text = _one_call(model, model_name, system, context, prompt, door, rounds, clock)
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
        if filler_audio is not None and kind == "scripted" and speaker.enabled:
            filler_audio.warm(speaker.speech, speaker.voice, [spoken_as])
    yield from speaker.drain()
    yield ev.done("greeting", text, False)


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
    )
    values = " ".join(
        f"{k}={-1 if report.timings.get(k) is None else report.timings[k]}" for k in keys
    )
    status = web_conversation.word(report.status)
    return f"cascade_turn status={status} spoken={str(spoken).lower()} {values}"
