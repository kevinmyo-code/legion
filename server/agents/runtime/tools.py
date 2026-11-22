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
    # The result IS the turn's answer (code says it): the loop finishes the round's calls and asks
    # the model nothing more.
    ends_turn: bool = False
    # Set when the call runs on a limb, not here (ADR 0098, the relay): the `tool_request` the
    # limb is sent (JSON). Nothing ran yet; the loop pauses the round on it (`loop.Suspension`)
    # and ignores the other fields. A gateway that never relays never sets it.
    relay: dict | None = None
    # A write that went through, in words a limb may say under a stop ("Done: ..."): `{tool,
    # title?, list?}` (plan 1119 row 15). Sent on the turn's `tool` event as `write_fact`.
    fact: dict | None = None


class ToolGateway(Protocol):
    def declarations(self, spec) -> list[dict]: ...

    def is_slow(self, name: str) -> bool: ...

    def call(self, state, spec, call: ToolCall, deadline_s: float) -> ToolOutcome: ...


# Optional on a gateway (read with `getattr`, so a gateway that never relays, such as the web
# door's, need not define it): `relay_kind(call) -> None | RELAY_BATCH | RELAY_ALONE`, whether a
# call would go to a limb, asked BEFORE it runs so the loop keeps the round's calls in order.
# `RELAY_ALONE`: its result is outside text, so it pauses alone (nothing else waits beside it).
RELAY_BATCH = "batch"
# Optional too: `working_kind(call) -> str | None`, the class of a call about to run (`slow`,
# `local`, `write`, `relay`), so a client can pick its filler; with it the loop sends `working`
# for every call, not only the slow ones.
RELAY_ALONE = "alone"
