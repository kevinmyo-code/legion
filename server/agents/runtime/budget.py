"""A turn's limits and what it has used (plan section 13.2 and 14, "Cost").

`LoopLimits` bounds one turn: rounds (the last one tool-free), one model call's wall time, one
tool's soft deadline, the whole turn's wall time, the input tokens and the estimated spend.
`BudgetUsed` is what the turn has spent so far, kept in the turn state.

The member's daily cap is not here: ADR 0100 keeps this package free of LEGION code, so the caller
checks `llm/cap.py` before the turn starts (`agents/legion/turn.py`) and hands in the price
function (`cost_of`). The plan's "budget.py wraps llm/cap.py" is changed to that, so the
boundary holds.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class LoopLimits:
    max_rounds: int = 4
    round_timeout_s: float = 8.0
    # Soft: engine tools run on the request's own thread (the ORM's connection), so a tool past its
    # deadline is not interrupted; the overrun is recorded and the turn deadline still binds.
    tool_timeout_s: float = 6.0
    turn_timeout_s: float = 20.0
    # Summed over the turn's rounds; 0 is no limit.
    max_input_tokens: int = 0
    # Estimated spend of the turn; 0 is no limit.
    max_cost_micro_usd: int = 0

    def __post_init__(self):
        if self.max_rounds < 1:
            raise ValueError("max_rounds must be at least 1")


@dataclass
class BudgetUsed:
    rounds: int = 0
    prompt_tokens: int = 0
    cached_tokens: int = 0
    output_tokens: int = 0
    cost_micro_usd: int = 0

    def to_json(self) -> dict:
        return asdict(self)

    @classmethod
    def from_json(cls, raw: dict | None) -> BudgetUsed:
        return cls(**(raw or {}))


def over(limits: LoopLimits, used: BudgetUsed) -> str | None:
    """Which limit the turn has reached, or None."""
    if limits.max_input_tokens and used.prompt_tokens >= limits.max_input_tokens:
        return "input_tokens"
    if limits.max_cost_micro_usd and used.cost_micro_usd >= limits.max_cost_micro_usd:
        return "cost"
    return None
