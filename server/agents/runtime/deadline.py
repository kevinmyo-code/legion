"""The current thread's deadline, standard library only (round 3 design change D1), so the runtime's
own transport can cap its timeouts by it. `legion.deadline` adds the database half and is what the
engine's code uses; both share this one context variable."""

from __future__ import annotations

import contextvars
import time
from contextlib import contextmanager

_UNTIL: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "legion_deadline", default=None
)
# The shortest timeout handed out: a request that cannot finish in this is not worth starting.
FLOOR_S = 0.05


def remaining() -> float | None:
    until = _UNTIL.get()
    return None if until is None else until - time.monotonic()


def cap(seconds: float) -> float:
    """`seconds`, or the time left on the current deadline when that is shorter."""
    left = remaining()
    if left is None:
        return seconds
    return max(FLOOR_S, min(seconds, left))


@contextmanager
def within(seconds: float):
    token = _UNTIL.set(time.monotonic() + seconds)
    try:
        yield
    finally:
        _UNTIL.reset(token)
