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
  streamed text cut into whole sentences, per-round token counts (spec 6, 7, 8). Of
  `agents/legion` it uses the turn rules both voice doors share (engine-brain switch plan, slice
  E0: `speech_rules`, `sentence_gate`, `ask_again`, `closing`, `end_conversation`, `lines`), the
  gateway's declarations, `late` and the honesty check in `guards`; never its router, specs,
  confirm gate or turn tables, and `/api/agents/turn` is untouched.
- **The prompt prefix is cached** (`assistant/prompt_cache.py`, spec 9): the stable body plus the
  phone's voice clause (spec 11) and the declarations; the clock and who is speaking ride at the
  head of each turn as the phone's "(Current context ...)" note (spec 10).
- **The tools are the web tool door's**, called the way `POST /api/assistant/tool` calls them
  (`WebDoor` below): `tools_for(user)` declares them (a recruiter's `RECRUITER_TOOLS`, narrowed
  tables, its own procedures and diagrams), `refusal_for` fences each call (default deny for a
  recruiter), `off_registry_tool` / `run_engine_tool` runs it under the caller's own
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
import functools
import logging
import queue
import re
import threading
import time
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime

from django.db import DatabaseError, transaction
from django.db import connections as db_connections

from agents.legion import closing, late
from agents.legion import sentence_gate as gates
from agents.legion.ask_again import ask_again, asks_again
from agents.legion.end_conversation import END_DECLARATION, END_INSTRUCTION, END_TOOL
from agents.legion.gateway import SLOW_TOOLS, rest_declarations
from agents.legion.guards import claims_unbacked_outcome
from agents.legion.lines import (
    CHANGE_MADE,  # noqa: F401 - read as cascade.CHANGE_MADE
    CHANGE_NOT_MADE,  # noqa: F401 - read as cascade.CHANGE_NOT_MADE
    FAILED_AFTER_WRITE_SPOKEN,
    FAILED_SPOKEN,
    MISSED_LINE,
    NO_TEXT,  # noqa: F401 - read as cascade.NO_TEXT
    NOT_ANSWERED_BUDGET,
    NOT_ANSWERED_MODEL,
    NOT_ANSWERED_TIMEOUT,
    NOTHING_CHANGED,
    READ_THROUGH_NOTHING_CHANGED,  # noqa: F401 - read as cascade.READ_THROUGH_NOTHING_CHANGED
    READ_THROUGH_WAIT,  # noqa: F401 - read as cascade.READ_THROUGH_WAIT
    READ_THROUGH_WAIT_AFTER_WRITE,  # noqa: F401 - read as cascade.READ_THROUGH_WAIT_AFTER_WRITE
    READ_THROUGH_WAIT_CARRIED,  # noqa: F401 - read as cascade.READ_THROUGH_WAIT_CARRIED
    SPOKEN_FAILURES,  # noqa: F401 - read as cascade.SPOKEN_FAILURES
    STT_FAILED,
    TOO_MANY_TOOLS,
)
from agents.legion.speech_rules import (
    FILLER_NOTE,  # noqa: F401 - read as cascade.FILLER_NOTE
    VERBATIM_TOOLS,  # noqa: F401 - read as cascade.VERBATIM_TOOLS
    _verbatim,
    backed_cents,
    backed_torque,
    decimal_cents,  # noqa: F401 - read as cascade.decimal_cents
    has_money,  # noqa: F401 - read as cascade.has_money
    is_filler,  # noqa: F401 - read as cascade.is_filler
    is_stop_utterance,
    states_unbacked_figure,
)
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
from agents.voice import crisis, wait_lines
from agents.voice import filler_audio as voice_filler_audio
from agents.voice import speaker as voice_speaker
from agents.voice.speaker import (  # noqa: F401 - the web door's tuning, read and set as cascade.X
    MAX_TTS_REQUESTS,
    SPEECH_GROUPING,
    TTS_REQUEST_CEILING,
    TTS_WAIT_S,
    _never,
    first_clause,
)
from agents.voice.speech import OUTPUT_RATE, GeminiSpeech, SpeechFailure
from agents.voice.stage import (  # noqa: F401 - prices read as cascade.X
    PRICE_TEXT_CACHED,
    PRICE_TEXT_IN,
    PRICE_TEXT_OUT,
    meter_speech,
    record_speech,
    timing_values,
    transcribe,
    turn_cost_usd,
)
from assistant import echo, fillers, read_through, speech_tags, spoken_figures, web_greeting
from assistant.models import AssistantCall
from assistant.surface import off_registry_tool, refusal_for, tools_for
from demo import suggestions
from demo.budget import tester_visit
from demo.shop import guards as shop_guards
from demo.shop import scenario as shop_scenario
from demo.shop import tools as shop_tools
from demo.tools import spoken_for as about_kevin_spoken_for
from devicelogs import web_conversation
from engine_mcp import cards
from engine_mcp.models import Outcome
from engine_mcp.tools import (
    ToolResult,
    run_engine_tool,
    run_tool,
)
from garage import topic as garage_topic
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
# Cached input, in hundredths of a micro-dollar per token: the rate the agent turn quotes for
# flash-lite (`agents/legion/turn.py`, $0.03 / 1M). An ESTIMATE, like every cost here.
CACHED_MICRO_USD_PER_TOKEN_X100 = 3

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


def is_robotic_remark(words: str | None) -> bool:
    return bool(_ROBOTIC.search(words or ""))


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


def failure_audio(voice: str, wrote: bool) -> ev.Event | None:
    """The spoken failure line's audio for `voice`, if it has been made; else None."""
    line = FAILED_AFTER_WRITE_SPOKEN if wrote else FAILED_SPOKEN
    clip = FILLER_AUDIO.get(voice, line)
    if not clip:
        return None
    return ev.Event("audio", {"pcm": base64.b64encode(clip).decode("ascii"), "rate": OUTPUT_RATE})


def _clip_ms(pcm: bytes) -> int:
    """How long `pcm` plays for: 16-bit mono at `OUTPUT_RATE`. What the chain's gaps measure from
    (`agents/voice/wait_lines.py`: each gap runs from the END of the clip before it)."""
    return len(pcm) * 1000 // (OUTPUT_RATE * 2)


def _silence(ms: int) -> bytes:
    """`ms` of silence at `OUTPUT_RATE`, 16-bit mono: what leads a clip that still owes the one
    before it some spacing (`wait_lines.TurnFillers.lead_ms`)."""
    return bytes(2 * (ms * OUTPUT_RATE // 1000))


# ---- fillers kept in memory (spec 22): `agents/voice/filler_audio.py` --------------------------


class FillerAudio(voice_filler_audio.FillerAudio):
    """The web door's filler audio: every instance picks lines with this module's one `LINES`, so
    a line is never said twice running across the door's turns."""

    def picker(self) -> fillers.LinePicker:
        return LINES


FILLER_AUDIO = FillerAudio()
LINES = fillers.LinePicker()


# ---- speech, two sentences at a time (spec 5): `agents/voice/speaker.py` ----------------------


class Speaker(voice_speaker.Speaker):
    """The web door's speaker: its grouping and its wait for speech are this module's
    `SPEECH_GROUPING` and `TTS_WAIT_S`, read when the speaker is made."""

    def __init__(self, speech, voice: str, enabled: bool, clock=time.monotonic):
        super().__init__(
            speech, voice, enabled, clock, grouping=SPEECH_GROUPING, wait_s=TTS_WAIT_S
        )


def _whole(value) -> int:
    return value if isinstance(value, int) and value > 0 else 0


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
        # The demo's live strip (each tool's time, the turn's cost, the robotic marker) is Kevin's
        # tester visit's, never an ordinary recruiter's (Kevin, 2026-11-19: "B hide the whole
        # strip"). A member keeps each tool's time and the marker, as before; a turn's cost goes
        # to the tester visit only (`_answer`).
        self.strip = not self.recruiter or tester_visit(self.user)
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
        # The carry (ADR 0090 amended, 2026-11-21): the turn after one that read outside text
        # refuses writes too; members and, by the owner's word the same day, demo visits.
        read_through.set_turn(request, conversation_id, turn, carry=True)

    def tools(self) -> dict:
        if self._tools is None:
            self._tools = {t.name: t for t in tools_for(self.user)}
            if "about_kevin" in self._tools:
                # The one seam between the engines for his profile: on the cascade code speaks it
                # and the model only picks sections; Live keeps `demo.tools.ABOUT_KEVIN` as it was.
                self._tools["about_kevin"] = about_kevin_spoken_for(self.user)
            if self.recruiter:
                # The store-rep demo (`demo/shop/`): the rep's tools in its scenario, else the
                # guide's plus its switch. A member's door never asks.
                self._tools = shop_tools.door_tools(self.user, self._tools)
        return self._tools

    def shop(self) -> bool:
        """This turn's door is the store rep's (its tools were declared)."""
        return shop_tools.SHOP_FIND.name in self.tools()

    def visit(self):
        return shop_scenario.visit_of(self.user) if self.recruiter else None

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
        if self.recruiter and (name in shop_tools.ALL_TOOLS or self.shop()):
            return True
        return (
            off_registry_tool(name, self.user) is not None
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
        elif self.recruiter and (name in shop_tools.ALL_TOOLS or self.shop()):
            # The store-rep demo's tools run only where declared; in its scenario nothing else
            # does (`demo/shop/tools.py`). Same checks as any engine tool.
            tool = self.tools().get(name)
            if tool is None:
                ok, outcome, text, guard = False, Outcome.REFUSED, shop_tools.not_here(name), "shop"
            else:
                result = run_engine_tool(self.request, tool, args)
                ok, outcome, text, guard = not result.is_error, result.outcome, result.text, None
        else:
            refusal = refusal_for(name, self.user, args)
            if refusal is not None:
                ok, outcome, text = False, Outcome.REFUSED, refusal
                guard = "recruiter" if self.recruiter else "not_allowed"
            else:
                only_here = off_registry_tool(name, self.user)
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
    # The page marked this utterance a barge-in (it began while a reply played), and the reply
    # that was playing: only then is the transcript checked for an echo (round 4 ruling).
    barge_in: bool = False
    last_reply: str = ""
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
    # a sentence the model must never see again (his profile), or "" to
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
# The longest clip a delayed line may be (review round 2): an answer ready just after it starts
# waits at most this long. Measured clips (Puck): the generic lines 1.04 s, most tools' shortest
# 1.12 to 2.20 s.
DELAYED_LINE_MAX_MS = 1500
# How often a round that has said nothing yet is reported as `waiting` (`Ticking`).
TICK_S = 0.1


class Ticking:
    """Reads the model on a thread of its own and yields `Waiting` every `every` seconds whenever
    the round has nothing to hand over: the turn's moments to act while a round is silent (a
    lookup's line and the chained wait lines on time). Only the HTTP read is on that thread; the
    loop, the door and the database stay on the turn's own.

    It used to tick only until the round's FIRST item, which made the chain impossible: a model that
    sends a thought part and then thinks for seconds had one tick, at the very start, and
    `wait_lines` would never come due again (2026-11-24). `runtime/loop.py` takes `Waiting`
    anywhere in a stream, so ticking throughout costs nothing else."""

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
                # Nothing on the model's path uses the database today; if anything ever does, its
                # connection is this thread's own and must not outlive the read (a leaked session).
                db_connections.close_all()
                items.put(Ticking._END)

        threading.Thread(target=read, name="legion-model-read", daemon=True).start()
        try:
            while True:
                try:
                    item = items.get(timeout=self.every)
                except queue.Empty:
                    yield Waiting()
                    continue
                if item is Ticking._END:
                    return
                if isinstance(item, Exception):
                    raise item
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
        # `turn` counts the user turns already in the record: 0 is the first (or the greeting).
        # A cache is made from the second turn on, so a one-turn conversation never pays one.
        model.allow_create = turn >= 1
    else:
        counted = CountingModel(model)
        model = counted
    speak = turn_input.audio is not None or turn_input.greet or turn_input.spoken
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
        record_speech(timings, speaker)
        timings["turn_ms"] = int((clock() - t0) * 1000)
        # Every model call that was started counts, even one a hang-up cut short or a cache retry
        # sent twice (finding 6): billed rounds are never handed back to the budget.
        report.rounds = max(report.rounds, counted.calls)
        timings["tts_requests"] = speaker.requests
        meter_speech(request, timings, FEATURE)


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
            heard = transcribe(speech, turn_input.audio, timings)
        except SpeechFailure as exc:
            timings["stt_failed"] = str(exc)
            report.status = "stt_failed"
            # The miss is said like a person (voice-persona section 5), and shown.
            speaker.add(MISSED_LINE)
            yield from speaker.drain()
            yield ev.error(STT_FAILED)
            return
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
    # Whether HIS words ask for a change (the carry's exception, ADR 0090 amended 2026-11-21).
    read_through.heard(request, words)
    # A shop's prices are said exactly, never rounded for speech (`demo/shop/`).
    speaker.exact = spoken_figures.wants_exact(words) or door.shop()
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

    # An ordinary visitor's "that sounded robotic" is just words (the marker is the strip's).
    if door.strip and is_robotic_remark(words):
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
    # The late-answer path (`agents/legion/late.py`): None and nothing read or written unless
    # LEGION_LATE_ANSWERS=on, and never for a recruiter. Code answers "look into X and tell me
    # later", "anything pending?" and "cancel that" with no model round; otherwise the one line
    # owed from earlier is said first, kept out of this turn's model context.
    late_reply = late.intercept(
        request,
        words,
        "web",
        last_reply=next(
            (c["parts"][0].get("text", "") for c in reversed(history) if c.get("role") == "model"),
            "",
        ),
    )
    if late_reply is not None:
        record_user()
        if late_reply.owed is not None:
            _late_read_through(request, door, late_reply.owed)
        # An owed line's text is said and shown but never replayed as the model's own words.
        report.say(late_reply.text, replay=None if late_reply.history else "")
        speaker.add(late_reply.text)
        yield ev.Event("sentence", {"text": late_reply.text})
        yield from speaker.drain()
        yield ev.done("done", late_reply.text, False)
        return
    # This turn's fillers of every kind: the acknowledgement, the task line and the chained wait
    # lines, counted together against one cap (`agents/voice/wait_lines.py`, the phone's rule).
    held = wait_lines.TurnFillers(last_end=clock())
    # Why no acknowledgement was said, for the turn's log; None once one was.
    ack_skip = fillers.ack_skip(
        from_user=True,
        over_reply=turn_input.barge_in,
        words=words,
        # The phone says nothing light over words of distress (`Backchannel.kt` wordsSkip CRISIS),
        # and nothing here handles a crisis before this point, so the check belongs here.
        crisis=crisis.detect(words),
        goodbye=is_stop_utterance(words),
        # The phone takes the companion's name off either end before judging a goodbye, so
        # "Alfred, bye" is one (`Farewells.withoutCourtesy`).
        companion_name=companion_name,
    )
    # The phone's `fillerSkip` (`CascadeSession.kt:1366`): the decision binds the WHOLE chain, not
    # the acknowledgement alone, because `scheduleWaitFillers` opens `if (fillerSkip != null)
    # return`. So a crisis or a goodbye turn hears nothing light at all, where before the holding
    # lines chained over words of distress. A clip missing from memory is not one of those reasons,
    # so it is recorded in `ack_skip` below without closing the chain.
    filler_skip = ack_skip
    if speak and ack_skip is None:
        # The first sound, on EVERY turn of theirs (Kevin, 2026-11-24: the phone's behavior is the
        # one to match, and the phone plays one every turn). A short acknowledgement from memory
        # while the model's first round runs. It used to be the conversation's first utterance only
        # (round 2 speech review 5, round 3 S5), which left every later question silent until the
        # answer: `fillers.ack_skip` carries the phone's skip list instead.
        # Rotated, not bounded, and deliberately so. The phone bounds its backchannel to 120 to
        # 700 ms, but it measures a clip it has TRIMMED to its voice (`Backchannel.trimSilence`:
        # the cloud voice pads a one-word render with 80 to 520 ms of silence each side), and this
        # door plays what the engine returns untrimmed, so the phone's window cannot be applied
        # here as it stands. `shortest=True` would bound it at the cost of the phone's other rule,
        # rotation: it takes the shortest clip in hand every turn, so one word would be the
        # acknowledgement forever. Trimming, and the one measurement that sizes the problem (what
        # the TTS returns for a one-word line in a demo persona's voice), are owed.
        picked = filler_audio.pick(voice, ACK_TOOL)
        if picked is None:
            ack_skip = "not_in_memory"
        else:
            line, audio = picked
            timings["ack"] = 1
            # Its own key: `filler_ms` stays the TASK line's, so "no lookup line was heard" is
            # still readable off a turn now that an acknowledgement goes in front of every one.
            timings["ack_ms"] = int((clock() - t0) * 1000)
            held.note(line, _clip_ms(audio), clock(), wait=False)
            yield ev.Event("filler", {"tool": "", "text": speech_tags.untagged(line)})
            yield speaker.audio_event(audio, filler=True)
    if ack_skip is not None:
        timings["ack_skip"] = ack_skip
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
    # When the answer's speech was first asked of the voice, on the door's clock: the phone's
    # `answerAskedAtMs`, which is what `wait_lines.fits_before_answer` measures from.
    answer_asked_at: float | None = None
    verbatim_said = False
    # One TASK line a turn (the contract's rule): the wait lines chain on top of it, never a second
    # line naming a tool.
    task_said = False
    filler_dropped = 0
    no_answer = False
    money_dropped = 0
    # The torque guard (garage, `speech_rules.states_unbacked_torque`): on exactly when
    # `show_part` is offered to this caller (the switch on, his account, the cascade); otherwise
    # `backed_torque` answers None and no sentence is checked.
    torque_on = "show_part" in door.tools()
    torque_topic = garage_topic.SENTENCE if torque_on else None
    about_front_end = torque_on and garage_topic.about_front_end(words, last_reply)
    torque_dropped = 0
    # Which sentences are said, held or dropped (`agents/legion/sentence_gate.py`): asked per
    # sentence; the counts, speech and events stay here.
    # The store rep's sentence check (`demo.shop.guards`): on exactly while its tools are declared.
    shop_known = shop_guards.visit_cents(door.visit()) if door.shop() else None
    # Why each of the rep's sentences was dropped: rule names only, never the words.
    shop_drops: list[str] = []

    def shop_dropped(text: str) -> bool:
        reason = shop_guards.claim_reason(text, door.calls, shop_known)
        if reason is None and states_unbacked_figure(text, backed_cents(door.calls)):
            reason = "engine_figure"
        if reason is not None:
            shop_drops.append(reason)
        return reason is not None

    gate = gates.SentenceGate(
        backed=lambda: backed_cents(door.calls),
        torque=lambda: backed_torque(door.calls, torque_topic, about_front_end),
        actions=door.actions,
        shop=shop_dropped if shop_known is not None else None,
    )

    sentences = 0

    # The first lookup of the turn and when it started; its line waits for FILLER_WAIT_S.
    lookup: tuple[str, float] | None = None
    cancelled_often = getattr(door.cancelled, "often", door.cancelled)

    def lookup_line():
        """The lookup's line, once FILLER_WAIT_S has passed since it started with nothing said;
        never once an answer sentence is ready, never a second TASK line in the turn."""
        if lookup is None or task_said or sentences or not speak or held.capped:
            return None
        if clock() - lookup[1] < FILLER_WAIT_S or cancelled_often():
            # Never after a cancel. Asked only once the line is due, through the door's rate-limited
            # check: a due line with no clip in hand is asked again on every tick.
            return None
        return (yield from filler(lookup[0], shortest=True, ceiling_ms=DELAYED_LINE_MAX_MS))

    def filler(tool: str, shortest: bool = False, ceiling_ms: int | None = None):
        picked = filler_audio.pick(voice, tool, shortest=shortest, ceiling_ms=ceiling_ms)
        if picked is None:
            return None
        line, audio = picked
        # Never back to back with the acknowledgement: the clip is led by the remaining spacing
        # rather than held on a timer, because a tool's start is not a moment the door can wait at.
        lead = held.lead_ms(clock())
        timings["filler_line"] = line
        timings["filler_ms"] = int((clock() - t0) * 1000)
        held.note(line, lead + _clip_ms(audio), clock(), wait=False)
        yield ev.Event("filler", {"tool": tool, "text": line})
        yield speaker.audio_event(_silence(lead) + audio, filler=True)
        return line

    def wait_line():
        """The next chained holding line, if one is due (the phone's `WaitFillers`: 1.5 s after the
        last filler ended, then every 2.5 s, at most six fillers of all kinds in a turn).

        The acknowledgement's skip binds these too, as on the phone (`filler_skip` above): a turn
        that says nothing light says nothing light at all.

        The dead-air rule: the chain runs until the answer's AUDIO is in hand, which is the phone's
        own line (`CascadeSession.kt`: "until the answer's AUDIO is in hand ([DeadAir]: the first
        text alone no longer ends them)"); a sentence whose speech has not come back yet is still
        dead air to the person listening. What keeps that from putting a clip in FRONT of an answer
        already on its way is the phone's other half, `DeadAir.fits`
        (`wait_lines.fits_before_answer`): once the answer's speech has been asked for, a line
        starts only if it ends by the time that audio is expected, plus the phone's hold. A no
        there is not the end of the chain, because the estimate can go stale. Never after a cancel,
        because a clip already pushed cannot be recalled, and `wait_lines.MAX_CLIP_MS` bounds how
        long an answer ready just after a line starts has to wait behind it.
        """
        if not speak or filler_skip is not None or cancelled_often():
            return None
        if speaker.first_audio_ms is not None or not held.due_now(clock()):
            return None
        ready = held.ready(lambda line: filler_audio.get(voice, line) is not None)
        if not ready:
            return None
        # The door's own picker, so the same holding line is not heard twice running ACROSS turns
        # (the phone's `RecentLines` rotation): a per-turn index would open every turn with
        # "Still on it.". `held.words` is what stops a repeat inside one turn.
        line = filler_audio.picker().pick(ready)
        if line is None:
            return None
        audio = filler_audio.get(voice, line)
        clip_ms = _clip_ms(audio)
        if not wait_lines.clip_fits(clip_ms):
            # Outside the phone's 200 to 1,300 ms window: nothing, rather than a clip that could
            # still be playing when the answer is ready.
            held.words.add(line)
            return None
        if not wait_lines.fits_before_answer(
            now=clock(), asked_at=answer_asked_at, clip_ms=clip_ms
        ):
            # The answer's audio is expected too soon for this clip to end first. The line is NOT
            # marked said: the next tick asks again, and by then the estimate may be stale.
            return None
        timings["wait_lines"] = timings.get("wait_lines", 0) + 1
        timings["wait_ms"] = int((clock() - t0) * 1000)
        held.note(line, clip_ms, clock(), wait=True)
        # The same shape as any other filler, so nothing on the wire or in the generated API types
        # changes: a holding line is not a step the engine took, and `wait_lines.LINES` and
        # `fillers.ACK` share no words, so a reader can tell which kind a line is from its text.
        yield ev.Event("filler", {"tool": "", "text": line})
        yield speaker.audio_event(audio, filler=True)
        return line

    unbacked = 0
    # Claims of a change not yet backed when said: held until the turn ends, then said if a write
    # backed them by then, else dropped (finding 3: a claim streamed before its own call).
    held_claims: list[tuple[str, str]] = []

    def tainted() -> bool:
        return read_through.read_third_party(door.request)

    def say(
        text: str, tagged: str | None = None, replay: str | None = None, exact_text: bool = False
    ):
        """`text` is what is shown, stored and checked (no tags, ever); `tagged` is a model
        sentence as written, its allowed tags kept for speech only (`speech_tags`). Code lines
        (verbatim figures, refusals, failures) pass no `tagged` and are spoken plain. `replay` is
        what history keeps of it instead (`TurnReport.replay`). `exact_text`: code's verbatim
        sentences (his profile) are shown exactly as written, never put through the tag stripper,
        which would drop an asterisk or a bracketed word from them."""
        nonlocal sentences, first_sentence, answer_asked_at
        if not exact_text:
            text = speech_tags.untagged(text)
        sentences += 1
        report.say(text, replay)
        if first_sentence is None:
            first_sentence = int((clock() - t0) * 1000)
        if answer_asked_at is None:
            answer_asked_at = clock()
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
                # The round is silent (`Ticking`, every TICK_S): the lookup's line if it is due,
                # else the next chained wait line. The task line goes first: it says what is really
                # happening, a wait line only asks them to hold on.
                line = yield from lookup_line()
                task_said = task_said or line is not None
                if line is None:
                    yield from wait_line()
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
                    and not task_said
                    and not held.capped
                    and not door.cancelled()
                    and door.would_accept(tool)
                ):
                    # Never for a call the door is about to refuse (round 2 speech review 7). A line
                    # right behind the acknowledgement is led by the spacing it still owes
                    # (`filler`), so the tool's own start is never held up for it.
                    line = yield from filler(tool)
                    task_said = line is not None
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
                if door.strip:
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
                task_said = task_said or line is not None
                if line is None:
                    yield from wait_line()
                continue
            if event.type == "sentence":
                tagged = event.data["text"]
                # Every check below reads the sentence without its tags (`speech_tags`).
                text = speech_tags.untagged(tagged)
                verdict = gate.in_answer(
                    text,
                    sentences=sentences,
                    verbatim_said=verbatim_said,
                    max_spoken=MAX_SPOKEN_SENTENCES,
                )
                if verdict == gates.NO_ANSWER:
                    # Held: after tools, the turn is asked once more (below).
                    no_answer = True
                elif verdict == gates.FILLER:
                    filler_dropped += 1
                elif verdict == gates.MONEY:
                    money_dropped += 1
                elif verdict == gates.TORQUE:
                    torque_dropped += 1
                elif verdict == gates.HOLD:
                    held_claims.append((text, tagged))
                elif verdict == gates.SAY:
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
            filler=int(task_said),
        )
        _meter(request, model_name, rounds)

    if door.not_for_me:
        yield from _not_for_me(request, door, speaker, report, timings)
        return
    record_user()
    stopped = door.cancelled()
    # A turn under the carry alone gets its record line, and one cut off before its answer does not
    # spend the carry (ADR 0090 amended, 2026-11-21; `read_through.carried_turn`). Members only.
    read_through.carried_turn(request, cancelled=stopped)
    if stopped:
        # The browser stopped this turn: nothing more is asked for or said (2026-11-17, cancel).
        report.status = "cancelled"
        yield ev.done("cancelled", " ".join(report.said).strip(), door.wrote())
        return
    report.status = result.status if result is not None else "cancelled"
    failure = _failure_words(result, door)
    if result is not None and asks_again(
        failed=failure is not None,
        status=result.status,
        sentences=sentences,
        filler_only=bool(filler_dropped or no_answer),
        tools_ran=bool(door.calls),
        ending=door.end_requested,
    ):
        # Spec 23: the tools ran and the answer was only filler, or nothing at all (measured
        # 2026-11-17: a write refused after a web search left the last round empty, and the person
        # heard neither the search's answer nor the refusal). Asked once more, tool-free, with the
        # phone's note; whatever it says is the answer.
        for tagged in ask_again(
            model,
            model_name,
            system,
            state.messages,
            door.declarations(),
            LIMITS.round_timeout_s,
            rounds,
        ):
            text = speech_tags.untagged(tagged)
            verdict = gate.in_ask_again(text, verbatim_said=verbatim_said)
            if verdict == gates.MONEY:
                money_dropped += 1
            elif verdict == gates.TORQUE:
                torque_dropped += 1
            elif verdict == gates.HOLD:
                held_claims.append((text, tagged))
            elif verdict == gates.SAY:
                yield say(text, tagged)
        report.rounds = len(rounds)
        timings["rounds"] = len(rounds)
        _meter(request, model_name, rounds[-1:])
    # A change ADR 0090 held back (`agents/legion/closing.py`).
    guard_refused = closing.guard_refused(door.calls)
    # The answer's own words go to speech first; what follows may use the request kept back.
    speaker.flush()
    speaker.closing = True
    # The closing lines are decided from what happened, at the END of the turn (findings 2, 3).
    for text, tagged in held_claims:
        verdict = gate.held(text)
        if verdict == gates.MONEY:
            # Held before the money tool ran; its figures are checked now, like every sentence's
            # (blind review finding 3). It was still a claim of a change, so the closing line about
            # the change is said in its place (round 2, N1): "went through" or "did not".
            money_dropped += 1
            unbacked += 1
        elif verdict == gates.TORQUE:
            torque_dropped += 1
            unbacked += 1
        elif verdict == gates.SAY:
            yield say(text, tagged)
        else:
            unbacked += 1
    # The final count: the ask-again round's and the held claims' drops included (finding 2).
    timings["money_dropped"] = money_dropped
    if torque_on:
        timings["torque_dropped"] = torque_dropped
    wrote = door.wrote()
    closing_line = closing.closing_line(
        door.calls,
        refused=guard_refused,
        unbacked=unbacked,
        wrote=wrote,
        tainted=tainted,
        carried=closing.refused_by_carry(door.calls),
        promise=read_through.promised(request),
    )
    if closing_line is not None:
        yield say(closing_line)
    timings["wait_reason"] = int(guard_refused)
    timings["unbacked"] = unbacked
    if failure is not None:
        yield say(failure)
    elif sentences == 0 and not door.end_requested:
        # A store rep whose every sentence was an unbacked claim: code answers from this turn's
        # lookup (`demo.shop.tools.fallback_lines`).
        if door.shop() and money_dropped:
            for line in shop_tools.fallback_lines(door.calls, words):
                yield say(line)
        else:
            yield say(closing.nothing_said_line(door.wrote()))
    elif door.shop() and money_dropped:
        # Something survived, but not the option an objection needed (`demo.shop.tools`).
        extra = shop_tools.objection_addition(door.calls, words, report.said)
        if extra is not None:
            yield say(extra)
    if shop_drops:
        reasons = ",".join(shop_drops)
        log.warning("assistant store rep: sentences dropped reasons=%s", reasons)
        web_conversation.record_lines(
            request,
            conversation_id_of(door),
            [web_conversation.engine_latency_line(datetime.now(UTC), f"shop_dropped {reasons}")],
        )
    if late.enabled() and not door.cancelled() and not crisis.detect(words):
        # The late-answer path (`agents/legion/late.py`; switched off, not even the cancel check
        # runs here): the one owed line, claimed only now, after
        # the model ruled the words were for the assistant (`_not_for_me` returned before this)
        # and never on words of distress, so neither room talk nor a crisis spends it. After his
        # answer on this door; said and shown, never replayed as the model's own words.
        owed, more = late.claim(request, "web")
        if owed is not None:
            _late_read_through(request, door, owed)
            owed_line = late.late_event(owed, more)
            report.say(owed_line.data["text"], replay="")
            speaker.add(owed_line.data["text"], model=True)
            yield owed_line
    yield from speaker.drain()
    if door.cancelled():
        # Stopped while its speech played out (round 3 N4): the turn ends as cancelled.
        report.status = "cancelled"
    if not door.end_requested and is_stop_utterance(words):
        door.end_requested = True
        timings["stop_phrase"] = 1
    extra = {}
    if door.recruiter:
        # The demo's live strip (tools, their times, the turn's estimated cost): Kevin's tester
        # visit only, never a recruiter's (Kevin, 2026-11-19: "B hide the whole strip"). The page
        # shows the strip only once a turn has carried these.
        if door.strip:
            extra["cost_usd"] = round(turn_cost_usd(timings, speaker.done), 5)
            extra["tools_ms"] = [[c.name, c.ms] for c in door.calls]
        # The follow-up chips: only what ran, signed for this visitor; the page asks for the
        # checked chips after the answer (`demo.suggestions`), never inside the turn.
        # The guide's chips are not the store rep's (`demo/shop/`): none in its scenario.
        offer = (
            None
            if door.end_requested or door.shop()
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


def _late_read_through(request, door, owed) -> None:
    """An owed line built on web pages (`late.read_third_party`): this turn may not write
    (`read_through.mark_turns`), nor the next (the carry, ADR 0090 amended: it reads this turn's
    mark, so marking the next turn too would carry a second time), and memory keeps no assistant
    line from here on (the record's `read_through source=carry`)."""
    if not late.read_third_party(owed):
        return
    conversation_id = conversation_id_of(door)
    read_through.mark_turns(request, conversation_id, (door.turn,))
    web_conversation.record_lines(
        request,
        conversation_id,
        [web_conversation.engine_read_through_line(datetime.now(UTC), "carry")],
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


def conversation_id_of(door: WebDoor):
    return door.conversation_id


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
        if door.shop() and not later:
            # The visit is already the store rep's (`demo/shop/`): the shop's hello instead.
            text = shop_tools.SHOP_HELLO
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
    if door.recruiter and not turn_input.preface and not door.shop():
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


def timing_line(report: TurnReport, *, spoken: bool) -> str:
    """The record's and the log's `cascade_turn` line: names and numbers only."""
    status = web_conversation.word(report.status)
    values = timing_values(report.timings)
    return f"cascade_turn status={status} spoken={str(spoken).lower()} {values}"
