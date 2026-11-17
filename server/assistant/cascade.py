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
import logging
import queue
import random
import re
import threading
import time
from collections import deque
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime

from agents.legion.gateway import SLOW_TOOLS, rest_declarations
from agents.runtime import events as ev
from agents.runtime.budget import BudgetUsed, LoopLimits
from agents.runtime.loop import (
    BUDGET_WORDS,
    MODEL_WORDS,
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
from assistant import read_through, web_greeting
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

# The phone's `ScriptedLines.FILLERS` (spec 22).
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
END_INSTRUCTION = (
    "Sign off now with one dry line in your own register, about this conversation, then "
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


def is_filler(sentence: str) -> bool:
    return sentence.strip().lower() in {f.lower() for f in FILLERS}


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

    def warm(self, speech, voice: str, lines=FILLERS) -> int:
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

    def pick(self, voice: str, choose=random.choice) -> tuple[str, bytes] | None:
        """A line in memory, never the last one said; None when none is in hand."""
        with self._lock:
            ready = [
                line for line in FILLERS if (voice, line) in self._audio and line != self._last
            ]
            if not ready:
                return None
            line = choose(ready)
            self._last = line
            return line, self._audio[(voice, line)]


FILLER_AUDIO = FillerAudio()


# ---- speech, two sentences at a time (spec 5) --------------------------------------------------

_END = object()


@dataclass
class _Job:
    text: str
    chunks: queue.Queue
    stats: Spoken
    cancelled: threading.Event


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

    def add(self, text: str) -> None:
        if not self.enabled:
            return
        job = _Job(text, queue.Queue(), Spoken(), threading.Event())
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

    def audio_event(self, chunk: bytes) -> ev.Event:
        if self.first_audio_ms is None:
            self.first_audio_ms = int((self.clock() - self.started_at) * 1000)
        return ev.Event(
            "audio", {"pcm": base64.b64encode(chunk).decode("ascii"), "rate": OUTPUT_RATE}
        )

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


class WebDoor:
    """`agents.runtime.tools.ToolGateway` over the web assistant's tool door: the same checks, in
    the same order, as `AssistantToolView.post`, plus the `end_conversation` control."""

    def __init__(self, request, conversation_id, turn: int):
        self.request = request
        self.user = request.user
        self.conversation_id = conversation_id
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
        return name in SLOW_TOOLS

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
        self.calls.append(DoorCall(name, ok, outcome, text, card, verbatim))
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


def cached_system(body: str) -> str:
    """What is cached: the prompt's stable body plus the phone's voice clause (spec 9, 11)."""
    return f"{body.strip()}\n\n{VOICE_CLAUSE}"


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
    fillers: FillerAudio | None = None,
) -> Iterator[ev.Event]:
    """Yields the turn's events. Fills `report` as it goes, so a caller that stops reading (the
    browser hung up) still knows what happened."""
    t0 = clock()
    timings = report.timings
    fillers = fillers or FILLER_AUDIO
    speak = turn_input.audio is not None or turn_input.greet
    system = cached_system(system)
    yield ev.Event("turn", {"conversation_id": str(conversation_id), "turn": turn})
    door = WebDoor(request, conversation_id, turn)
    speaker = Speaker(speech, voice, enabled=speak, clock=clock)
    if speak:
        # The filler lines' audio, fetched while the floor is theirs (spec 22).
        fillers.warm(speech, voice)
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
            )
            return
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
            fillers,
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
    fillers,
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
    report.heard = words
    web_conversation.record_lines(
        request,
        conversation_id_of(door),
        [web_conversation.engine_user_line(datetime.now(UTC), words, typed=not speak)],
    )
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
    money_dropped = 0
    sentences = 0

    def say(text: str):
        nonlocal sentences, first_sentence
        sentences += 1
        report.said.append(text)
        if first_sentence is None:
            first_sentence = int((clock() - t0) * 1000)
        speaker.add(text)
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
            if event.type == "working":
                yield event
                if speak and sentences == 0 and not filler_said:
                    picked = fillers.pick(voice)
                    if picked is not None:
                        filler_said = True
                        yield speaker.audio_event(picked[1])
                continue
            if event.type == "tool":
                done = door.calls[-1]
                payload = {"name": done.name, "ok": done.ok, "outcome": done.outcome}
                payload["text"] = done.text
                if done.card is not None:
                    payload["card"] = done.card
                yield ev.Event("tool", payload)
                if done.verbatim and not verbatim_said:
                    verbatim_said = True
                    yield say(done.verbatim)
                continue
            if event.type == "sentence":
                text = event.data["text"]
                if sentences == 0 and is_filler(text):
                    continue
                if verbatim_said and has_money(text):
                    money_dropped += 1
                    continue
                if sentences >= MAX_SPOKEN_SENTENCES:
                    continue
                yield say(text)
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
    if failure is not None:
        yield say(failure)
    elif sentences == 0 and not door.end_requested:
        yield say(NO_TEXT + ("" if door.wrote() else NOTHING_CHANGED))
    yield from speaker.drain()
    yield ev.done(
        report.status,
        " ".join(report.said).strip(),
        door.wrote(),
        end=door.end_requested,
    )


def conversation_id_of(door: WebDoor):
    return door.conversation_id


def _greet(request, turn_input, door, speaker, report, system, context, model_name, model, clock):
    """The greeting on open (spec 25 to 29): Spoken (a lead topic), Hello (nothing to raise) or
    Scripted (a recruiter's visit, or the model past its deadline or failing)."""
    t0 = clock()
    history = turn_input.greeting_history or {}
    kind, prompt, text = "scripted", None, ""
    if door.recruiter:
        later = bool(turn_input.preface)
        text = web_greeting.ack_line() if later else web_greeting.RECRUITER_HELLO
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
    speaker.add(text)
    yield ev.Event("sentence", {"text": text})
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
