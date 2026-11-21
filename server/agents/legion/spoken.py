"""What the engine's agent turn says (engine-brain switch plan 1119, slice E3): the web door's turn
rules (`assistant/cascade.py`, lifted in slice E0) applied to the agent turn's event stream.

`SpokenTurn.run` wraps the graph's events, on the first stream (`turn.py`) and on a relay's
continuation (`relay_resume.py`), so every sentence of the reply passes the same checks the web
door's do before the limb hears it. Only the generalist's turns: the late-answer runner's
background turns are passed through as they are.
"""

from __future__ import annotations

from collections.abc import Generator
from dataclasses import dataclass

from agents.runtime import events as ev


@dataclass(frozen=True)
class TurnCall:
    """One call of the stream, as the speech rules read it (`LegionGateway.calls`): the result's
    text and plain data (`ToolResult.structured`, the money figures' source), never sent on."""

    name: str
    ok: bool
    text: str
    writes: bool
    data: dict | None = None


class SpokenTurn:
    def __init__(self, state, gateway, *, on: bool):
        self.state = state
        self.gateway = gateway
        self.on = on

    def run(self, events) -> Generator[ev.Event, None, object]:
        """`events`: the graph's run (a generator returning the final state). Yields what the
        limb is sent; returns that state."""
        try:
            while True:
                try:
                    event = next(events)
                except StopIteration as stop:
                    return stop.value
                yield event
        finally:
            # A hang-up closes this generator: the graph's own is closed with it, at once.
            events.close()


# The spec whose turns are spoken by these rules; the late-answer runner's `background` is not.
SPOKEN_SPEC = "generalist"


def spoken_turn(state, ctx, agent: str) -> SpokenTurn:
    """The turn's speech, over the graph context `ctx` (`turn.context`)."""
    return SpokenTurn(state, ctx.gateway, on=agent == SPOKEN_SPEC)
