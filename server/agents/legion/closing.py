"""The closing line code says at the end of a voice turn, decided from what happened (engine-brain
switch plan, slice E0; findings 2 and 3 of the web door's reviews).

Lifted unchanged from the web door's turn (`assistant/cascade.py`, `_answer`, ADR 0102), which
says the line; the agent turn picks it up in slice E3. The words are `agents/legion/lines.py`;
which held claims count as unbacked is `agents/legion/sentence_gate.py`.

`assistant.read_through` is the shared ADR 0090 module (`agents/legion/guards.py` reads it too),
not the web door.
"""

from __future__ import annotations

from collections.abc import Callable

from agents.legion.lines import (
    CHANGE_MADE,
    CHANGE_NOT_MADE,
    NO_TEXT,
    NOTHING_CHANGED,
    READ_THROUGH_WAIT,
    READ_THROUGH_WAIT_AFTER_WRITE,
)
from assistant import read_through


def guard_refused(calls) -> bool:
    """A change ADR 0090 held back: a write refused by the outside-text rule, or `remember`'s own
    refusal (round 2 finding 5: that one was missed, so "Noted." passed with nothing saved)."""
    return any(
        not c.ok
        and (
            (c.text.startswith("Nothing was") and "read outside text" in c.text)
            or c.text == read_through.REFUSAL
        )
        for c in calls
    )


def closing_line(
    calls,
    *,
    refused: bool,
    unbacked: int,
    wrote: bool,
    tainted: Callable[[], bool],
) -> str | None:
    """The line about a change, or None. `refused` is `guard_refused(calls)`; `unbacked` the held
    claims no write backed; `tainted` whether the turn read outside text, asked only when needed."""
    if refused:
        return READ_THROUGH_WAIT_AFTER_WRITE if wrote else READ_THROUGH_WAIT
    if unbacked:
        if wrote:
            return CHANGE_MADE
        if tainted():
            return READ_THROUGH_WAIT
        return CHANGE_NOT_MADE
    if wrote and any(c.ends_turn for c in calls):
        # The turn ended on code's own answer (his profile) in the round that also made a change:
        # the model never got to confirm it, so code does, in its own words.
        return CHANGE_MADE
    return None


def nothing_said_line(wrote: bool) -> str:
    """Said when the turn ends with no sentence, no failure line and no sign-off."""
    return NO_TEXT + ("" if wrote else NOTHING_CHANGED)
