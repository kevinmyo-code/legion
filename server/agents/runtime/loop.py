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

**The relay (ADR 0098, engine-brain ticket 04 slice E2).** A call the gateway hands to a limb comes
back as `ToolOutcome(relay=...)`. The round then pauses: the loop returns `AWAITING_LIMB` with a
`Suspension` (the round's finished calls, the limb calls, and the calls after them, which wait so
the round's calls keep their order), and the caller saves it and ends the stream. No thread waits.
`run_loop(..., resume=suspension, relay_results=[...])` finishes that round with the limb's
results and goes on from the next round, so the round count spans the hops. A gateway that never
relays (the web door's) never sees any of this: with no `relay`, every line below runs as before.
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
from agents.runtime.tools import RELAY_ALONE, ToolCall, ToolGateway, ToolOutcome

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
AWAITING_LIMB = "awaiting_limb"

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


def _call_json(call: ToolCall) -> dict:
    return {"id": call.id, "name": call.name, "args": call.args}


def _call_of(raw: dict) -> ToolCall:
    return ToolCall(raw.get("id") or "", raw["name"], dict(raw.get("args") or {}))


@dataclass
class Suspension:
    """A round paused on limb calls, as JSON the caller saves (`to_json`) and hands back
    (`from_json`) with the limb's results. `done`: the round's calls that finished before the
    pause, with the response the model gets; `relay`: the limb calls, each with its request;
    `deferred`: the calls after them, not run yet. `spoken`: the turn's sentences so far, so the
    final text spans the hops; `elapsed_s`: the turn's running time so far (relay waits are not
    counted against `turn_timeout_s`)."""

    round: int
    spoken: list[str]
    elapsed_s: float
    done: list[dict]
    relay: list[dict]
    deferred: list[dict]

    def to_json(self) -> dict:
        return {
            "round": self.round,
            "spoken": list(self.spoken),
            "elapsed_s": self.elapsed_s,
            "done": self.done,
            "relay": self.relay,
            "deferred": self.deferred,
        }

    @classmethod
    def from_json(cls, raw: dict) -> Suspension:
        return cls(
            round=int(raw["round"]),
            spoken=list(raw.get("spoken") or []),
            elapsed_s=float(raw.get("elapsed_s") or 0.0),
            done=list(raw.get("done") or []),
            relay=list(raw.get("relay") or []),
            deferred=list(raw.get("deferred") or []),
        )

    @property
    def call_ids(self) -> list[str]:
        return [item["request"]["call_id"] for item in self.relay]


@dataclass
class LoopResult:
    status: str
    final_text: str
    words: str = ""
    rounds: int = 0
    tool_calls: list[dict] = field(default_factory=list)
    # Set with status AWAITING_LIMB only.
    suspended: Suspension | None = None


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
    parts = [_response_part(call, outcome.response) for call, outcome in outcomes]
    if nudge:
        parts.append({"text": NUDGE})
    return {"role": "user", "parts": parts}


def abandoned_round(suspension: Suspension, words: str, answered: dict | None = None) -> dict:
    """The function responses that close a paused round that will not resume (a stale turn, a
    cancel, a pause that could not be saved): the finished calls' own responses, a limb call's
    response from `answered` (call id to response) when its result came, and `words` for every
    other limb call and every call that waited, so the stored history stays a valid exchange
    (every functionCall answered) and claims nothing it did not see."""
    answered = answered or {}
    failed = {"success": False, "message": words}
    parts = [_response_part(_call_of(d["call"]), d["response"]) for d in suspension.done]
    for item in suspension.relay:
        # The limb call's id rides inside its response, so a result that comes after the close
        # can replace it on the record.
        cid = item["request"]["call_id"]
        response = {**answered.get(cid, failed), "call_id": cid}
        parts.append(_response_part(_call_of(item["call"]), response))
    parts += [_response_part(_call_of(raw), failed) for raw in suspension.deferred]
    return {"role": "user", "parts": parts}


def _response_part(call: ToolCall, response: dict) -> dict:
    part = {"name": call.name, "response": response}
    if call.id:
        part["id"] = call.id
    return {"functionResponse": part}


def run_loop(
    spec: AgentSpec,
    state,
    model: ModelClient,
    tools: ToolGateway,
    *,
    system: str,
    limits: LoopLimits | None = None,
    hooks: LoopHooks | None = None,
    resume: Suspension | None = None,
    relay_results: list[ToolOutcome] | None = None,
) -> Generator[ev.Event, None, LoopResult]:
    """`state` needs `messages` (model-format contents, this turn's user message last), `actions`
    (a list) and `budget` (a `BudgetUsed`). `resume` and `relay_results` (one per
    `resume.relay` item, in its order) finish a paused round (module docstring)."""
    limits = limits or spec.limits
    hooks = hooks or LoopHooks()
    started = hooks.started if hooks.started is not None else hooks.clock()
    declarations = tools.declarations(spec)
    spoken: list[str] = list(resume.spoken) if resume is not None else []
    calls_made: list[dict] = []
    relay_kind = getattr(tools, "relay_kind", None)

    def result(status: str, words: str = "", rounds: int = 0) -> LoopResult:
        return LoopResult(status, " ".join(spoken).strip(), words, rounds, calls_made)

    def account(call: ToolCall, outcome: ToolOutcome) -> ev.Event:
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
        return ev.tool_ended(call.name, outcome.ok, outcome.outcome, outcome.guard)

    def paused(round_no, outcomes, waiting, deferred) -> LoopResult:
        out = result(AWAITING_LIMB, "", round_no + 1)
        out.suspended = Suspension(
            round=round_no,
            spoken=list(spoken),
            elapsed_s=hooks.clock() - started,
            done=[
                {"call": _call_json(c), "response": o.response, "ends_turn": o.ends_turn}
                for c, o in outcomes
            ],
            relay=[{"call": _call_json(c), "request": o.relay} for c, o in waiting],
            deferred=[_call_json(c) for c in deferred],
        )
        return out

    def run_calls(round_no: int, calls: list[ToolCall], outcomes: list):
        """Runs `calls` in order into `outcomes`. Returns None when all ran, else the turn's
        result: cancelled, or paused on limb calls (those after a limb call wait, in order)."""
        waiting: list = []
        for i, call in enumerate(calls):
            if hooks.cancelled():
                return result(CANCELLED, CANCELLED_WORDS, round_no + 1)
            if waiting:
                kind = relay_kind(call) if relay_kind is not None else None
                if kind is None or kind == RELAY_ALONE:
                    return paused(round_no, outcomes, waiting, calls[i:])
            if tools.is_slow(call.name):
                yield ev.working(call.name)
            outcome = tools.call(state, spec, call, limits.tool_timeout_s)
            if outcome.relay is not None:
                waiting.append((call, outcome))
                if relay_kind is not None and relay_kind(call) == RELAY_ALONE:
                    return paused(round_no, outcomes, waiting, calls[i + 1 :])
                continue
            outcomes.append((call, outcome))
            yield account(call, outcome)
        if waiting:
            return paused(round_no, outcomes, waiting, [])
        return None

    first_round = 0
    if resume is not None:
        # The paused round's end: its finished calls, the limb's results, then the calls that
        # waited behind them; then the function responses, and on to the next round.
        outcomes = [
            (
                _call_of(d["call"]),
                ToolOutcome(
                    d["call"]["name"], True, d["response"], ends_turn=bool(d.get("ends_turn"))
                ),
            )
            for d in resume.done
        ]
        for item, outcome in zip(resume.relay, relay_results or [], strict=True):
            call = _call_of(item["call"])
            outcomes.append((call, outcome))
            yield account(call, outcome)
        deferred = [_call_of(c) for c in resume.deferred]
        stopped = yield from run_calls(resume.round, deferred, outcomes)
        if stopped is not None:
            return stopped
        if any(outcome.ends_turn for _call, outcome in outcomes):
            return result(DONE, "", resume.round + 1)
        nudge = resume.round + 1 == limits.max_rounds - 1
        state.messages.append(function_response_content(outcomes, nudge))
        first_round = resume.round + 1
        if first_round >= limits.max_rounds:
            return result(DONE, "", limits.max_rounds)

    for round_no in range(first_round, limits.max_rounds):
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
        outcomes: list = []
        stopped = yield from run_calls(round_no, calls, outcomes)
        if stopped is not None:
            return stopped
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
