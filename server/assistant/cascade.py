"""The web assistant's cascade turn (ADR 0102): speech-to-text, the text model's tool loop over
the web assistant's own tool door, text-to-speech, streamed back as events.

Kevin, 2026-11-17: *"recruiter demo > can it [go] to our current cloud pipeline too"*. The phone's
cloud voice (`app/.../service/cascade/`) on the engine, for the browser: the browser posts one
utterance (16 kHz PCM16 mono) or one typed message; nothing it holds is a credential to Google.

## What it is built from

- **The loop is the engine runtime's** (`agents.runtime.loop.run_loop`, `GeminiClient`), the same
  generic loop the dark agent turn uses: rounds, the tool-free last round with the phone's nudge,
  sentence-by-sentence output, per-round token counts. Nothing of `agents/legion` (its router,
  specs, confirm gate or turn tables) is used, and `/api/agents/turn` is untouched.
- **The tools are the web tool door's**, called the way `POST /api/assistant/tool` calls them
  (`WebDoor` below): `assistant.surface.tools_for(user)` declares them (a recruiter's
  `RECRUITER_TOOLS`, narrowed tables, its own procedures and diagrams), `refusal_for` fences each
  call (default deny for a recruiter), `recruiter_only_tool` / `run_engine_tool` runs it under the
  caller's own `visible()`, every call is audited (`AssistantCall`) and written to the
  conversation record, and a card goes to the screen, never to the model. So a recruiter's fence
  on this path is the Live path's fence, the same code.
- **ADR 0090** holds by the tool door's own guard: one HTTP request is one turn, a third-party
  tool marks the request before it runs (`read_through.before_tool`) and every write after it in
  the same request is refused in words by `run_engine_tool`. The turn is also marked on the
  record (`read_through.set_turn` with this turn's number, never None), as the Live door does.
- **The prompt is the Live path's** (`assistant.session.system_prompt_for`), so a recruiter gets
  the same frame, greeting rules and register.
- **History** is the conversation record's own engine-written `user` / `assistant` lines
  (`web_conversation.history`), last `HISTORY_TURNS` exchanges: no new table, and a browser upload
  can never put words in the assistant's mouth.

## Money is spoken by code (ADR 0094, the phone's `speak_verbatim`)

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
import re
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime

from agents.legion.gateway import rest_declarations
from agents.runtime import events as ev
from agents.runtime.budget import BudgetUsed, LoopLimits
from agents.runtime.loop import LoopHooks, run_loop
from agents.runtime.model import ModelClient
from agents.runtime.spec import AgentSpec
from agents.runtime.tools import ToolCall, ToolOutcome
from assistant import read_through
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

NOTHING_HEARD = "I didn't catch anything there. Tap the mic and try again, or type it."
STT_FAILED = "I couldn't hear that clearly just now, so nothing was asked. Try again, or type it."
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
    the same order, as `AssistantToolView.post`."""

    def __init__(self, request, conversation_id, turn: int):
        self.request = request
        self.user = request.user
        self.conversation_id = conversation_id
        self.recruiter = is_recruiter(self.user)
        self.calls: list[DoorCall] = []
        self._tools = None
        read_through.set_turn(request, conversation_id, turn)

    def tools(self) -> dict:
        if self._tools is None:
            self._tools = {t.name: t for t in tools_for(self.user)}
        return self._tools

    def declarations(self, spec) -> list[dict]:
        return rest_declarations(list(self.tools().values()))

    def is_slow(self, name: str) -> bool:
        from agents.legion.gateway import SLOW_TOOLS

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


# ---- one turn --------------------------------------------------------------------------------


@dataclass
class TurnInput:
    audio: bytes | None = None
    text: str | None = None
    # A hidden line ahead of the person's words (the demo page's opener or "another conversation"
    # line, `frontend/src/screens/try.tsx`); never shown, never recorded as theirs.
    preface: str | None = None


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


def _sentences_out(text: str, speech, voice, spoken_stats, timings, speak: bool):
    """`sentence`, then its audio, for one sentence."""
    yield ev.Event("sentence", {"text": text})
    if not speak:
        return
    stats = Spoken()
    started = time.monotonic()
    try:
        for chunk in speech.speak(text, voice, stats):
            yield ev.Event(
                "audio", {"pcm": base64.b64encode(chunk).decode("ascii"), "rate": OUTPUT_RATE}
            )
    except SpeechFailure as exc:
        stats.failed = stats.failed or str(exc)
        yield ev.Event("speech_failed", {"reason": "tts"})
    spoken_stats.append(stats)
    timings["tts_ms"] = timings.get("tts_ms", 0) + int((time.monotonic() - started) * 1000)
    if timings.get("tts_first_audio_ms") is None and stats.first_audio_ms is not None:
        timings["tts_first_audio_ms"] = stats.first_audio_ms


def run_turn(
    request,
    turn_input: TurnInput,
    *,
    conversation_id,
    history: list[dict],
    turn: int,
    system: str,
    voice: str,
    model_name: str,
    model: ModelClient,
    speech: GeminiSpeech,
    report: TurnReport,
    clock: Callable[[], float] = time.monotonic,
) -> Iterator[ev.Event]:
    """Yields the turn's events. Fills `report` as it goes, so a caller that stops reading (the
    browser hung up) still knows what happened."""
    t0 = clock()
    timings = report.timings
    speak = turn_input.audio is not None
    yield ev.Event("turn", {"conversation_id": str(conversation_id), "turn": turn})

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
        conversation_id,
        [web_conversation.engine_user_line(datetime.now(UTC), words, typed=not speak)],
    )

    # 2. The loop.
    parts = []
    if turn_input.preface:
        parts.append({"text": turn_input.preface})
    parts.append({"text": words})
    state = _State(messages=[*history, {"role": "user", "parts": parts}])
    door = WebDoor(request, conversation_id, turn)
    spec = AgentSpec(
        name="web_cascade",
        version=1,
        model=model_name,
        prompt="",
        tools=tuple(door.tools()),
        limits=LIMITS,
    )
    rounds: list[dict] = []
    first_sentence = None
    verbatim_said = False
    money_dropped = 0
    spoken_stats: list[Spoken] = []
    sentences = 0

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
            try:
                event = next(loop)
            except StopIteration as stop:
                result = stop.value
                break
            if event.type == "tool":
                done = door.calls[-1]
                payload = {"name": done.name, "ok": done.ok, "outcome": done.outcome}
                payload["text"] = done.text
                if done.card is not None:
                    payload["card"] = done.card
                yield ev.Event("tool", payload)
                if done.verbatim and not verbatim_said:
                    verbatim_said = True
                    report.said.append(done.verbatim)
                    sentences += 1
                    if first_sentence is None:
                        first_sentence = int((clock() - t0) * 1000)
                    yield from _sentences_out(
                        done.verbatim, speech, voice, spoken_stats, timings, speak
                    )
                continue
            if event.type == "sentence":
                text = event.data["text"]
                if verbatim_said and has_money(text):
                    money_dropped += 1
                    continue
                if sentences >= MAX_SPOKEN_SENTENCES:
                    continue
                sentences += 1
                report.said.append(text)
                if first_sentence is None:
                    first_sentence = int((clock() - t0) * 1000)
                yield from _sentences_out(text, speech, voice, spoken_stats, timings, speak)
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
            output_tokens=sum(r.get("output_tokens") or 0 for r in rounds),
            first_sentence_ms=first_sentence,
            sentences=sentences,
            money_dropped=money_dropped,
            verbatim=int(verbatim_said),
            tts_prompt_tokens=sum(s.prompt_tokens for s in spoken_stats),
            tts_output_tokens=sum(s.output_tokens for s in spoken_stats),
            tts_audio_ms=sum(s.audio_ms for s in spoken_stats),
            tts_failed=sum(1 for s in spoken_stats if s.failed),
            turn_ms=int((clock() - t0) * 1000),
        )
        _meter(request, model_name, rounds)

    report.status = result.status if result is not None else "cancelled"
    if result is not None and result.words:
        # The runtime's own words for a stop (time, budget, the model unreachable).
        report.said.append(result.words)
        yield from _sentences_out(result.words, speech, voice, spoken_stats, timings, speak)
    yield ev.done(report.status, " ".join(report.said).strip(), _wrote(door))


def _wrote(door: WebDoor) -> bool:
    tools = door.tools()
    return any(c.ok and getattr(tools.get(c.name), "writes", False) for c in door.calls)


def _meter(request, model_name: str, rounds: list[dict]) -> None:
    """A member's text rounds as `llm_usage` rows, so their daily cap (`llm/cap.py`) sees cascade
    spend. A recruiter is metered by the demo budget instead and writes none."""
    if not member_cap.applies_to(request.user):
        return
    for row in rounds:
        prompt, out = row.get("prompt_tokens") or 0, row.get("output_tokens") or 0
        cost = -(
            -(
                prompt * llm_client.INPUT_MICRO_USD_PER_TOKEN_X100
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
        "output_tokens",
        "first_sentence_ms",
        "sentences",
        "verbatim",
        "money_dropped",
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
