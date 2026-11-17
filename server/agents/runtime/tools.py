"""The tool seam: the loop asks a `ToolGateway` for declarations and hands it every call.

The gateway owns everything the runtime must not know: which tools exist, the guards, tenancy,
and what a result looks like to the model. The loop only sees a `ToolOutcome`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    args: dict


@dataclass(frozen=True)
class ToolOutcome:
    name: str
    ok: bool
    # What the model is handed as the function response.
    response: dict
    writes: bool = False
    # ok / failed / refused / timed_out
    outcome: str = "ok"
    # The guard that stopped the call, if one did.
    guard: str | None = None
    ms: int = 0


class ToolGateway(Protocol):
    def declarations(self, spec) -> list[dict]: ...

    def is_slow(self, name: str) -> bool: ...

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome: ...
