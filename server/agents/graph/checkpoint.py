"""The checkpointer: where the state goes after every node (plan section 13.3).

`CheckpointStore` is the seam; `MemoryCheckpointStore` keeps them in a list (tests, evals with no
database). LEGION's Postgres store is `agents/legion/store.py`, which redacts before it writes.
"""

from __future__ import annotations

from typing import Protocol

from agents.graph.state import TurnState
from agents.runtime.events import Event


class CheckpointStore(Protocol):
    def save(self, state: TurnState, node: str, step: int, events: list[Event]) -> None: ...


class MemoryCheckpointStore:
    def __init__(self):
        self.saved: list[dict] = []

    def save(self, state: TurnState, node: str, step: int, events: list[Event]) -> None:
        self.saved.append(
            {
                "step": step,
                "node": node,
                "state": state.to_json(),
                "events": [e.to_json() for e in events],
            }
        )

    def last(self, node: str | None = None) -> dict | None:
        rows = [r for r in self.saved if node is None or r["node"] == node]
        return rows[-1] if rows else None
