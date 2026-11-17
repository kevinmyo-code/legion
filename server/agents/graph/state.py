"""`TurnState`: everything one turn knows, JSON at every checkpoint (plan section 13.3).

`taint` is the ADR 0090 write guard's memory: the names of the third-party sources this turn read
(web search, the brief's news, GitHub, mail later). It is set BEFORE such a tool runs, and once set
every write in the turn refuses. `pending_confirm` is a write waiting for a yes; `confirmed` is
the args hash of the one write a yes in THIS turn released.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field

from agents.runtime.budget import BudgetUsed


@dataclass
class TurnState:
    turn_id: str
    conversation_id: str
    seq: int
    household_id: str
    member_id: str
    client: str
    transcript: str
    client_facts: dict = field(default_factory=dict)
    route: dict | None = None
    messages: list[dict] = field(default_factory=list)
    pending_confirm: dict | None = None
    confirmed: str | None = None
    taint: list[str] = field(default_factory=list)
    actions: list[dict] = field(default_factory=list)
    budget: BudgetUsed = field(default_factory=BudgetUsed)
    final_text: str = ""
    words: str = ""
    status: str = "running"
    honesty_flag: bool = False
    graph_version: str = ""
    spec_versions: dict[str, str] = field(default_factory=dict)

    @property
    def tainted(self) -> bool:
        return bool(self.taint)

    @property
    def writes_ran(self) -> bool:
        return any(a.get("writes") and a.get("ok") for a in self.actions)

    def add_taint(self, source: str) -> None:
        if source not in self.taint:
            self.taint.append(source)

    def to_json(self) -> dict:
        out = dataclasses.asdict(self)
        out["budget"] = self.budget.to_json()
        return out

    @classmethod
    def from_json(cls, raw: dict) -> TurnState:
        data = dict(raw)
        data["budget"] = BudgetUsed.from_json(data.get("budget"))
        names = {f.name for f in dataclasses.fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in names})
