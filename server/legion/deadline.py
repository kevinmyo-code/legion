"""A deadline for the work done on the current thread (round 3 design change D1).

A Python thread cannot be stopped, so a tool's deadline is enforced where its time is spent:

- **The database** (`within_database`): the work runs in one transaction whose
  `statement_timeout` and `lock_timeout` come from the time left, so Postgres cancels a slow
  statement or a wait on a lock and the whole transaction rolls back. A write either committed
  inside the deadline or did not happen.
- **Outbound requests** (`cap`): every transport a tool can reach passes
  `timeout=deadline.cap(ITS_OWN_TIMEOUT)`, the smaller of its own timeout and the time left
  (`tests/test_tool_deadline.py` walks the tool registry for any that does not).

Not covered: Python code that loops without a statement or a request (none in the tools today).
Outside a deadline (`/mcp`, a management command) `cap` returns the caller's own timeout.
"""

from __future__ import annotations

import contextvars
import time
from contextlib import contextmanager

from django.db import DatabaseError, connection, transaction

_UNTIL: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "legion_deadline", default=None
)
# The shortest timeout handed out: a request that cannot finish in this is not worth starting.
FLOOR_S = 0.05
# Statement timeout is lowered again as time runs out, in steps of at least this.
_RESET_STEP_MS = 500


class DeadlinePassed(DatabaseError):
    """A statement was refused, or cancelled by the database, because the deadline passed."""


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


class _Guard:
    """Django `execute_wrapper`: refuses a statement once the deadline has passed, keeps the
    transaction's statement timeout no longer than the time left, and notes a statement the
    database cancelled for time (Postgres SQLSTATE 57014 query_canceled, 55P03 lock_not_available),
    since a tool may catch the error and carry on."""

    def __init__(self, set_ms: int):
        self.set_ms = set_ms
        self.expired = False
        self._inside = False

    def __call__(self, execute, sql, params, many, context):
        if self._inside:
            return execute(sql, params, many, context)
        left = remaining()
        if left is not None and left <= 0:
            self.expired = True
            raise DeadlinePassed("deadline passed before this statement")
        if left is not None:
            left_ms = max(1, int(left * 1000))
            if left_ms < self.set_ms - _RESET_STEP_MS:
                self._inside = True
                try:
                    with context["cursor"].connection.cursor() as cur:
                        cur.execute(
                            "SELECT set_config('statement_timeout', %s, true)", [f"{left_ms}ms"]
                        )
                finally:
                    self._inside = False
                self.set_ms = left_ms
        try:
            return execute(sql, params, many, context)
        except DatabaseError as exc:
            if getattr(getattr(exc, "__cause__", None), "sqlstate", None) in ("57014", "55P03"):
                self.expired = True
            raise


@contextmanager
def within_database(seconds: float):
    """Runs the block inside `within(seconds)` and one transaction bounded by it (see the module).
    Yields the guard; when `guard.expired` is set after the block, the transaction was rolled back
    (whether or not the block saw an error). Postgres only; elsewhere just `within`."""
    with within(seconds):
        if connection.vendor != "postgresql":
            guard = _Guard(0)
            yield guard
            return
        set_ms = max(1, int(seconds * 1000))
        # A local setting lasts to the end of the OUTERMOST transaction. Nested (a test, a
        # caller's `atomic`), it is put back when this block's savepoint is released; a savepoint
        # rolled back undoes it on its own.
        nested = connection.in_atomic_block
        with transaction.atomic():
            with connection.cursor() as cur:
                cur.execute(
                    "SELECT current_setting('statement_timeout'), current_setting('lock_timeout')"
                )
                before = list(cur.fetchone())
                cur.execute(_SET, [f"{set_ms}ms", f"{set_ms}ms"])
            guard = _Guard(set_ms)
            with connection.execute_wrapper(guard):
                yield guard
            if guard.expired:
                transaction.set_rollback(True)
        if nested and not guard.expired:
            with connection.cursor() as cur:
                cur.execute(_SET, before)


_SET = "SELECT set_config('statement_timeout', %s, true), set_config('lock_timeout', %s, true)"
