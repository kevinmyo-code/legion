"""The named Python functions a `compute` or `check` step may call (ruling 01).

Pure and deterministic: sums, date maths, sentence composition the way `brief/compose.py` does
it. No I/O beyond the household's own rows, no model, no network. A `check` step names one of
these too; its function returns True to pass, and a failing check ends the run with the step's
`fail_sentence`.

Empty until the first built-ins (ticket 15) add theirs. A definition naming a function that is
not here is refused at load, so a typo never reaches the runner.
"""

from __future__ import annotations

from collections.abc import Callable

FUNCTIONS: dict[str, Callable] = {}


def step_function(name: str):
    """Register `fn` under `name`, refusing a second registration of the same name."""

    def register(fn: Callable) -> Callable:
        if name in FUNCTIONS:
            raise ValueError(f"A step function named {name!r} is already registered.")
        FUNCTIONS[name] = fn
        return fn

    return register
