"""`TurnState`: everything one turn knows, JSON at every checkpoint (plan section 13.3).

`taint` is the ADR 0090 write guard's memory: the names of the third-party sources this turn read
(web search, the brief's news, GitHub, mail later). It is set BEFORE such a tool runs, and once set
every write in the turn refuses. `pending_confirm` is a write waiting for a yes; `confirmed` is
the args hash of the one write a yes in THIS turn released.

`outside` (engine-brain E2, plan 1119 section 5) is narrower than `taint`: the names of the calls
whose OUTSIDE TEXT is now in `messages` (an engine web search ran; a relayed read-through result
came back). Taint can be set with no such text present (before the call runs, or carried from the
last turn); `outside` cannot. Storage keys its redaction on it, and a turn with it set never pauses
for a limb (the text would be saved with the pause). `pending_relay` is the paused round
(`runtime.loop.Suspension` as JSON, plus the requests' clock) while the turn waits on a limb;
`after_turn` holds device actions a ruling lets run once the reply ends
(`relay.AFTER_OUTSIDE_TEXT`).
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field

from agents.runtime.budget import BudgetUsed

# What is kept of a reply once outside text was in the turn (plan 1119 section 5, the owner's
# "blank them" ruling for the phone's copies, 7328e9c7): the live answer is spoken; nothing of it
# is stored.
OUTSIDE_REPLY_STUB = "[reply after an outside read: not kept]"


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
    outside: list[str] = field(default_factory=list)
    pending_relay: dict | None = None
    after_turn: list[dict] = field(default_factory=list)

    @property
    def tainted(self) -> bool:
        return bool(self.taint)

    @property
    def writes_ran(self) -> bool:
        return any(a.get("writes") and a.get("ok") for a in self.actions)

    def add_taint(self, source: str) -> None:
        if source not in self.taint:
            self.taint.append(source)

    def add_outside(self, name: str) -> None:
        if name not in self.outside:
            self.outside.append(name)

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
