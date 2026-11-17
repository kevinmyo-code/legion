"""What a turn streams to its limb (plan section 13.1): one small typed event per thing that
happened, serialisable as one Server-Sent Event.

- `turn`: the turn's ids, first.
- `sentence`: one finished sentence of the reply, as soon as it is complete.
- `working`: a slow tool is about to run (the limb may play its filler).
- `tool`: a tool call ended: its name, whether it succeeded, and which guard stopped it, if one did.
  Never its arguments or result.
- `done`: the turn ended: status, final text, whether any write ran.
- `error`: the turn could not run; `words` says so.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Event:
    type: str
    data: dict = field(default_factory=dict)

    def sse(self) -> str:
        """One Server-Sent Event: `event: <type>` and one `data:` line of JSON."""
        return f"event: {self.type}\ndata: {json.dumps(self.data, ensure_ascii=False)}\n\n"

    def to_json(self) -> dict:
        return {"type": self.type, "data": self.data}


def sentence(text: str) -> Event:
    return Event("sentence", {"text": text})


def working(tool: str) -> Event:
    return Event("working", {"tool": tool})


def tool_ended(name: str, ok: bool, outcome: str, guard: str | None) -> Event:
    return Event("tool", {"name": name, "ok": ok, "outcome": outcome, "guard": guard})


def done(status: str, final_text: str, writes_ran: bool, **extra) -> Event:
    return Event(
        "done", {"status": status, "final_text": final_text, "writes_ran": writes_ran, **extra}
    )


def error(words: str) -> Event:
    return Event("error", {"words": words})
