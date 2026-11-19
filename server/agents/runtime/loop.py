"""The one agent loop (plan section 13.2): model, tools, repeat.

`run_loop` is a generator: it yields `Event`s as they happen (sentences while the model is still
streaming) and returns a `LoopResult`. A generator rather than a callback so a Django
`StreamingHttpResponse` can drive it on the request's own thread, where the ORM's connection and
the request's tenancy live.

Each round:
1. stop if the turn was cancelled, ran out of time or out of budget;
2. call the model with the spec's prompt and the gateway's declarations (the last round keeps the
   declarations but turns calling off, and carries the phone's nudge "Answer now with what you
   have.", `GeminiTextLoop.kt`); a turn that still ends with no words speaks `NO_ANSWER_WORDS`;
3. stream text out sentence by sentence;
4. run the round's calls through the gateway, in order (engine tools share the request's database
   connection, so they are not run in parallel threads; the plan's "in parallel" is changed to
   this);
5. append the model turn verbatim and the function responses, and go again.

Nothing here knows what a tool does or which guard stopped it: the gateway says so in the
`ToolOutcome`.
"""

from __future__ import annotations

import re
import time
from collections.abc import Callable, Generator
from dataclasses import dataclass, field

from agents.runtime import events as ev
from agents.runtime.budget import LoopLimits, over
from agents.runtime.model import (
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
from agents.runtime.tools import ToolCall, ToolGateway

NUDGE = "Answer now with what you have."
CANCELLED_WORDS = "Stopped: the turn was cancelled."
TIMEOUT_WORDS = "That took too long, so I stopped. Nothing after this point was done."
BUDGET_WORDS = "This turn reached its spending limit, so I stopped before asking the model again."
MODEL_WORDS = "I couldn't reach the model just now, so I can't answer that."
# Spoken when a turn ends with no words at all: the last, tools-off round can come back empty (the
# model still reaching for a tool). The person always hears something; it claims nothing.
NO_ANSWER_WORDS = (
    "I didn't get to an answer that time, so I stopped there. Ask me again and I'll pick it up."
)

DONE = "done"
CANCELLED = "cancelled"
FAILED = "failed"

_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


@dataclass
class LoopHooks:
    cancelled: Callable[[], bool] = lambda: False
    cost_of: Callable[[Usage], int] = lambda usage: 0
    # Called once per model round: {"round", "model", "prompt_tokens", "cached_tokens",
    # "output_tokens", "first_token_ms", "total_ms", "cost_micro_usd", "finish_reason"}.
    on_model_call: Callable[[dict], None] = lambda row: None
    clock: Callable[[], float] = time.monotonic
    started: float | None = None


@dataclass
class LoopResult:
    status: str
    final_text: str
    words: str = ""
    rounds: int = 0
    tool_calls: list[dict] = field(default_factory=list)


class SentenceSplitter:
    """Text in as it streams, whole sentences out. Never less than a sentence: every check on an
    answer (the honesty guard, fillers, money, the sentence cap) reads whole sentences (blind
    review round 7). A voice's first-clause cut is speech only, after the checks
    (`assistant.cascade.Speaker.add`)."""

    def __init__(self):
        self._buf = ""

    def feed(self, text: str) -> list[str]:
        self._buf += text
        parts = _SENTENCE_END.split(self._buf)
        self._buf = parts.pop()
        return [p.strip() for p in parts if p.strip()]

    def flush(self) -> list[str]:
        rest, self._buf = self._buf.strip(), ""
        return [rest] if rest else []


def function_response_content(outcomes: list[tuple[ToolCall, object]], nudge: bool) -> dict:
    parts: list[dict] = []
    for call, outcome in outcomes:
        response = {"name": call.name, "response": outcome.response}
        if call.id:
            response["id"] = call.id
        parts.append({"functionResponse": response})
    if nudge:
        parts.append({"text": NUDGE})
    return {"role": "user", "parts": parts}


def run_loop(
    spec: AgentSpec,
    state,
    model: ModelClient,
    tools: ToolGateway,
    *,
    system: str,
    limits: LoopLimits | None = None,
    hooks: LoopHooks | None = None,
) -> Generator[ev.Event, None, LoopResult]:
    """`state` needs `messages` (model-format contents, this turn's user message last), `actions`
    (a list) and `budget` (a `BudgetUsed`)."""
    limits = limits or spec.limits
    hooks = hooks or LoopHooks()
    started = hooks.started if hooks.started is not None else hooks.clock()
    declarations = tools.declarations(spec)
    spoken: list[str] = []
    calls_made: list[dict] = []

    def result(status: str, words: str = "", rounds: int = 0) -> LoopResult:
        return LoopResult(status, " ".join(spoken).strip(), words, rounds, calls_made)

    for round_no in range(limits.max_rounds):
        if hooks.cancelled():
            return result(CANCELLED, CANCELLED_WORDS, round_no)
        if hooks.clock() - started > limits.turn_timeout_s:
            return result(FAILED, TIMEOUT_WORDS, round_no)
        if over(limits, state.budget):
            return result(FAILED, BUDGET_WORDS, round_no)
        last = round_no == limits.max_rounds - 1
        req = ModelRequest(
            model=spec.model,
            system=system,
            contents=list(state.messages),
            declarations=declarations,
            allow_tools=not last,
            timeout_s=limits.round_timeout_s,
        )
        parts: list[dict] = []
        calls: list[ToolCall] = []
        usage = Usage()
        finish = ""
        splitter = SentenceSplitter()
        t0 = hooks.clock()
        first_ms = None
        try:
            for item in model.stream(req):
                if hooks.clock() - t0 > limits.round_timeout_s:
                    raise ModelFailure("the model round ran past its time limit")
                if isinstance(item, PartEvent):
                    parts.append(item.part)
                    if first_ms is None:
                        first_ms = int((hooks.clock() - t0) * 1000)
                elif isinstance(item, TextDelta):
                    for sentence in splitter.feed(item.text):
                        spoken.append(sentence)
                        yield ev.sentence(sentence)
                elif isinstance(item, ToolCallEvent):
                    calls.append(ToolCall(item.id, item.name, dict(item.args)))
                elif isinstance(item, Usage):
                    usage = item
                elif isinstance(item, Waiting):
                    yield ev.waiting()
                else:
                    finish = item.reason
        except ModelFailure as exc:
            hooks.on_model_call(
                _model_row(round_no, spec, usage, first_ms, hooks, t0, 0, f"error: {exc}")
            )
            for sentence in splitter.flush():
                spoken.append(sentence)
                yield ev.sentence(sentence)
            return result(FAILED, MODEL_WORDS, round_no + 1)
        for sentence in splitter.flush():
            spoken.append(sentence)
            yield ev.sentence(sentence)
        cost = hooks.cost_of(usage)
        state.budget.rounds += 1
        state.budget.prompt_tokens += usage.prompt_tokens
        state.budget.cached_tokens += usage.cached_tokens
        state.budget.output_tokens += usage.output_tokens
        state.budget.cost_micro_usd += cost
        hooks.on_model_call(_model_row(round_no, spec, usage, first_ms, hooks, t0, cost, finish))
        if parts:
            state.messages.append({"role": "model", "parts": parts})
        if not calls or last:
            if not spoken:
                spoken.append(NO_ANSWER_WORDS)
                yield ev.sentence(NO_ANSWER_WORDS)
            return result(DONE, "", round_no + 1)
        outcomes = []
        for call in calls:
            if hooks.cancelled():
                return result(CANCELLED, CANCELLED_WORDS, round_no + 1)
            if tools.is_slow(call.name):
                yield ev.working(call.name)
            outcome = tools.call(state, spec, call, limits.tool_timeout_s)
            outcomes.append((call, outcome))
            calls_made.append(
                {"name": call.name, "args": call.args, "ok": outcome.ok, "guard": outcome.guard}
            )
            state.actions.append(
                {
                    "name": call.name,
                    "writes": outcome.writes,
                    "ok": outcome.ok,
                    "outcome": outcome.outcome,
                    "guard": outcome.guard,
                }
            )
            yield ev.tool_ended(call.name, outcome.ok, outcome.outcome, outcome.guard)
        if any(outcome.ends_turn for _call, outcome in outcomes):
            # Every call of the round has run (a resume opened, a write made); the answer is
            # code's, so no further round is asked for.
            return result(DONE, "", round_no + 1)
        nudge = round_no + 1 == limits.max_rounds - 1
        state.messages.append(function_response_content(outcomes, nudge))
    return result(DONE, "", limits.max_rounds)


def _model_row(round_no, spec, usage, first_ms, hooks, t0, cost, finish) -> dict:
    return {
        "round": round_no,
        "model": spec.model,
        "prompt_tokens": usage.prompt_tokens,
        "cached_tokens": usage.cached_tokens,
        "output_tokens": usage.output_tokens,
        "first_token_ms": first_ms,
        "total_ms": int((hooks.clock() - t0) * 1000),
        "cost_micro_usd": cost,
        "finish_reason": finish,
    }
