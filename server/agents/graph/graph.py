"""`Graph`: nodes, conditional edges as code, a checkpoint after every node (plan section 13.3).

A node is a generator: it yields the events it produces as they happen and returns a
`NodeResult` naming the next node (None ends the turn). The edges are declared, so a node that
returns a next it may not take fails loudly, and `paths()` enumerates every reachable path for
the test that checks them all.

LangGraph's vocabulary (state, node, conditional edge, checkpointer) in a hundred lines, with
nothing hidden between a node and what it did (plan section 10, ADR 0100).
"""

from __future__ import annotations

from collections.abc import Callable, Generator
from dataclasses import dataclass
from typing import Any

from agents.graph.checkpoint import CheckpointStore
from agents.graph.state import TurnState
from agents.runtime.events import Event

MAX_STEPS = 16


@dataclass(frozen=True)
class NodeResult:
    state: TurnState
    next: str | None


NodeFn = Callable[[TurnState, Any], Generator[Event, None, NodeResult]]


class GraphError(RuntimeError):
    pass


class Graph:
    def __init__(self, nodes: dict[str, NodeFn], edges: dict[str, frozenset], start: str):
        if start not in nodes:
            raise GraphError(f"start node {start!r} is not a node")
        for name, targets in edges.items():
            if name not in nodes:
                raise GraphError(f"edge from unknown node {name!r}")
            for target in targets:
                if target is not None and target not in nodes:
                    raise GraphError(f"edge {name!r} -> unknown node {target!r}")
        missing = set(nodes) - set(edges)
        if missing:
            raise GraphError(f"nodes with no edges declared: {sorted(missing)}")
        self.nodes = nodes
        self.edges = edges
        self.start = start

    def run(
        self,
        state: TurnState,
        ctx: Any,
        store: CheckpointStore,
        from_node: str | None = None,
    ) -> Generator[Event, None, TurnState]:
        node = from_node or self.start
        step = 0
        while node is not None:
            if step >= MAX_STEPS:
                raise GraphError(f"the graph ran {MAX_STEPS} steps without ending")
            produced: list[Event] = []
            gen = self.nodes[node](state, ctx)
            try:
                while True:
                    event = next(gen)
                    produced.append(event)
                    yield event
            except StopIteration as stop:
                result: NodeResult = stop.value
            if result.next not in self.edges[node]:
                raise GraphError(f"node {node!r} went to {result.next!r}, which is not an edge")
            state = result.state
            store.save(state, node, step, produced)
            node = result.next
            step += 1
        return state

    def paths(self) -> list[tuple[str, ...]]:
        """Every path from the start to an end, each node at most once per path."""
        found: list[tuple[str, ...]] = []

        def walk(node: str, path: tuple[str, ...]) -> None:
            path = (*path, node)
            for target in sorted(self.edges[node], key=lambda t: (t is None, t or "")):
                if target is None:
                    found.append(path)
                elif target not in path:
                    walk(target, path)

        walk(self.start, ())
        return found
