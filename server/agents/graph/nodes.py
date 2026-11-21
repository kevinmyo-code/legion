"""The turn's nodes (plan section 13.3): route, then confirm_reply or agent, then finish.

Generic: what a route is, which spec runs, what counts as a yes and what the honesty check looks
for all come from the `NodeContext` the caller builds. `procedure` (router-direct procedures,
stage 9) and the specialists' router (stage 5) are not built here.

    route --(a write waits for a yes)--> confirm_reply --> agent --> finish --> end
    route --------------------------------------------> agent --> finish --> end
    route --(the turn may not run: cap, refusal)----------------> finish --> end
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from agents.graph.graph import Graph, NodeResult
from agents.graph.state import OUTSIDE_REPLY_STUB, TurnState
from agents.runtime import events as ev
from agents.runtime.loop import (
    AWAITING_LIMB,
    LoopHooks,
    Suspension,
    abandoned_round,
    run_loop,
)
from agents.runtime.model import ModelClient
from agents.runtime.spec import AgentSpec
from agents.runtime.tools import ToolGateway


@dataclass
class NodeContext:
    model: ModelClient
    gateway: ToolGateway
    # state -> {"agents": [...], "reason": "...", "refusal": words or None}
    route: Callable[[TurnState], dict]
    spec: Callable[[str], AgentSpec]
    system_for: Callable[[AgentSpec, TurnState], str]
    hooks: LoopHooks
    is_yes: Callable[[str], bool]
    # True when the final text claims an outcome no successful action in the turn backs.
    honesty: Callable[[TurnState], bool]
    extra: Any = None
    # The relay (ADR 0098). `suspend` saves a paused turn (`state.pending_relay` set) and says
    # whether it was saved; with none, or when it fails, the turn may not pause and ends in words.
    # `relay_results`: set when resuming, one `ToolOutcome` per limb call of the paused round.
    suspend: Callable[[TurnState], bool] | None = None
    relay_results: list | None = None


# Said when a pause on a limb could not be saved: the request never left.
RELAY_NOT_SAVED = (
    "I couldn't hand that to the device just now, so it did not run. Nothing was changed."
)
RELAY_ABANDONED = "Nothing was asked of the device: the turn could not wait for it."


def route_node(state: TurnState, ctx: NodeContext):
    decision = ctx.route(state)
    state.route = decision
    if decision.get("refusal"):
        state.words = decision["refusal"]
        state.status = "refused"
        return NodeResult(state, "finish")
        yield  # pragma: no cover - makes this a generator
    if state.pending_confirm:
        return NodeResult(state, "confirm_reply")
    return NodeResult(state, "agent")


def confirm_reply_node(state: TurnState, ctx: NodeContext):
    """A yes releases the one waiting write (its args hash); anything else drops it."""
    pending = state.pending_confirm or {}
    if ctx.is_yes(state.transcript) and pending.get("args_sha256"):
        state.confirmed = pending["args_sha256"]
    state.pending_confirm = None
    return NodeResult(state, "agent")
    yield  # pragma: no cover


def agent_node(state: TurnState, ctx: NodeContext):
    name = (state.route or {}).get("agents", ["generalist"])[0]
    spec = ctx.spec(name)
    state.spec_versions[name] = spec.ref
    resume = None
    if ctx.relay_results is not None and state.pending_relay:
        resume = Suspension.from_json(state.pending_relay["loop"])
        state.pending_relay = None
    result = yield from run_loop(
        spec,
        state,
        ctx.model,
        ctx.gateway,
        system=ctx.system_for(spec, state),
        hooks=ctx.hooks,
        resume=resume,
        relay_results=ctx.relay_results if resume is not None else None,
    )
    state.final_text = result.final_text
    state.status = result.status
    if result.words:
        state.words = result.words
    if result.status == AWAITING_LIMB:
        state.pending_relay = {"loop": result.suspended.to_json()}
        # Outside text is never saved with a pause (the gateway refuses the limb call first);
        # this is the second lock.
        saved = not state.outside and ctx.suspend is not None and ctx.suspend(state)
        if not saved:
            state.messages.append(abandoned_round(result.suspended, RELAY_ABANDONED))
            state.pending_relay = None
            state.status = "failed"
            state.words = RELAY_NOT_SAVED
    return NodeResult(state, "finish")


def finish_node(state: TurnState, ctx: NodeContext):
    if state.status == AWAITING_LIMB:
        # The paused turn's stream ends here: one `tool_request` per limb call, in order, then
        # `done` naming them. The rest of the turn is the `tool_result` post's stream.
        requests = [item["request"] for item in state.pending_relay["loop"]["relay"]]
        for request in requests:
            yield ev.Event("tool_request", request)
        yield ev.done(
            state.status,
            state.final_text,
            state.writes_ran,
            calls=[r["call_id"] for r in requests],
            honesty_flag=False,
            pending_confirm=bool(state.pending_confirm),
            tainted=state.tainted,
        )
        return NodeResult(state, None)
    if state.status == "running":
        state.status = "done"
    state.honesty_flag = bool(state.final_text) and ctx.honesty(state)
    if state.words and state.status != "done":
        yield ev.sentence(state.words)
    if state.outside:
        # Checked above on the real words; from here only the stub is sent or kept.
        state.final_text = OUTSIDE_REPLY_STUB
    extra = {"tainted": state.tainted}
    if state.after_turn:
        extra["device_actions"] = list(state.after_turn)
    yield ev.done(
        state.status,
        state.final_text or state.words,
        state.writes_ran,
        honesty_flag=state.honesty_flag,
        pending_confirm=bool(state.pending_confirm),
        **extra,
    )
    return NodeResult(state, None)


NODES = {
    "route": route_node,
    "confirm_reply": confirm_reply_node,
    "agent": agent_node,
    "finish": finish_node,
}

EDGES = {
    "route": frozenset({"confirm_reply", "agent", "finish"}),
    "confirm_reply": frozenset({"agent"}),
    "agent": frozenset({"finish"}),
    "finish": frozenset({None}),
}


def turn_graph() -> Graph:
    return Graph(NODES, EDGES, start="route")
