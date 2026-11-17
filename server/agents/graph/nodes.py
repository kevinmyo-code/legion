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
from agents.graph.state import TurnState
from agents.runtime import events as ev
from agents.runtime.loop import LoopHooks, run_loop
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
    result = yield from run_loop(
        spec, state, ctx.model, ctx.gateway, system=ctx.system_for(spec, state), hooks=ctx.hooks
    )
    state.final_text = result.final_text
    state.status = result.status
    if result.words:
        state.words = result.words
    return NodeResult(state, "finish")


def finish_node(state: TurnState, ctx: NodeContext):
    if state.status == "running":
        state.status = "done"
    state.honesty_flag = bool(state.final_text) and ctx.honesty(state)
    if state.words and state.status != "done":
        yield ev.sentence(state.words)
    yield ev.done(
        state.status,
        state.final_text or state.words,
        state.writes_ran,
        honesty_flag=state.honesty_flag,
        pending_confirm=bool(state.pending_confirm),
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
