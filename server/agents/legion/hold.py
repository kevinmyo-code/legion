"""The hold (engine-brain ticket 04, slice E2b; the phone client's gap G1): a turn whose words the
phone learns only at `heard` waits for the phone's word before it ACTS.

Why: with speech-to-text on the engine, the phone first sees his words at the `heard` event. Some
turns are the phone's to answer (a stop or goodbye, mail words, a turn in a mixed conversation,
plan 1119 section 4b). Without a hold, the engine could already have made a write the phone
then makes again ("add milk and check my mail").

**What waits, and what does not.** A turn opened with `go: false` (the stream route's metadata
frame, or the form route's field) is HELD. While held the engine thinks and reads at once (the
model round, engine read tools, sentences streamed as they come: the first sentence is not
delayed), but performs nothing that acts or leaves the engine until the phone's decision:
- no engine WRITE tool and no `run_procedure` (its steps may write);
- no relay request to a limb and no K1 device action;
- no late-answer start or owed-line claim (`late.py`), when that path is on;
- and the turn's record is not written until the decision (so a turn the phone takes leaves no
  trace of words as said).
A turn opened with `go: true` (the default; the phone did its own speech-to-text, or typed) is
never held: no extra hop.

**The decision** (`decide`): `POST /api/agents/turn/<id>/go` (the turn is the engine's) or
`POST /api/agents/turn/<id>/take` (the phone keeps it: the turn is cancelled, nothing acts,
nothing is recorded as said). Stored on the turn row (`pending_relay = {"go": decision}`; no
migration: the row is not paused while held, and a pause needs the go first) and read by polling
(`POLL_S`); one engine instance may stream while another takes the post.

**Silence is a cancel.** No decision `HOLD_TIMEOUT_S` (5 s) after the turn began: nothing acts; a
waiting write is refused in words and the turn is cancelled. Why 5 s: the phone sends its decision
right after `heard`, one small round trip (about 0.15-0.3 s measured from the laptop, unmeasured
on mobile), so 5 s is far past a live phone and short enough that a lost phone does not leave the
model waiting on a write.

**Added latency** [reasoned]: a read-only turn adds none (it never waits; only its record waits
for the decision, after `done` went out). A write turn waits only if the model asks for the write
before the go has arrived: the go leaves at `heard`, the model's first round takes about
0.55-0.65 s to its first token (stage 0 probe), so the go is usually there first; at worst one
round trip.
"""

from __future__ import annotations

import time

from django.db import transaction

from agents.models import TURN_AWAITING_LIMB, TURN_RUNNING, AgentTurn
from household.tenancy import household_of

GO = "go"
TAKE = "take"
TIMEOUT = "timeout"
HOLD_TIMEOUT_S = 5.0
POLL_S = 0.05

HELD_WORDS = (
    "Nothing was done: the device did not confirm this turn was mine to act on, so {name} did "
    "not run. Say nothing more about it."
)


def _decision(turn_id) -> str | None:
    row = AgentTurn.objects.filter(pk=turn_id).values_list("pending_relay", "cancel_requested")
    found = row.first()
    if found is None:
        return TAKE
    pending, cancelled = found
    decided = (pending or {}).get("go") if isinstance(pending, dict) else None
    if decided in (GO, TAKE):
        return decided
    if "loop" in (pending or {}):
        return GO  # a pause needs the go first
    return TAKE if cancelled else None


def _pause(seconds: float) -> None:
    """One poll's wait (a seam: a test stands in for the phone here)."""
    time.sleep(seconds)


class TurnHold:
    """One turn's hold. `needed` False: always go, no query."""

    def __init__(self, turn_id, needed: bool, clock=time.monotonic):
        self.turn_id = turn_id
        self.needed = needed
        self.decision = None if needed else GO
        self._clock = clock
        self._deadline = clock() + HOLD_TIMEOUT_S

    def peek(self) -> str | None:
        """The decision if one came, without waiting."""
        if self.decision is None:
            self.decision = _decision(self.turn_id)
        return self.decision

    def wait(self) -> str:
        """Blocks until the phone decides or the hold times out (then the turn is cancelled)."""
        while self.peek() is None:
            if self._clock() >= self._deadline:
                self.decision = TIMEOUT
                AgentTurn.objects.filter(pk=self.turn_id).update(cancel_requested=True)
                break
            _pause(POLL_S)
        return self.decision

    @property
    def go(self) -> bool:
        return self.decision == GO

    @property
    def taken(self) -> bool:
        return self.decision == TAKE


class HoldRefusal(Exception):
    def __init__(self, http: int, code: str, detail: str):
        super().__init__(detail)
        self.http, self.code, self.detail = http, code, detail


def decide(request, turn_id, decision: str) -> dict:
    """Records the phone's decision on its member's own turn (module docstring). Idempotent; a
    take after a go, or a go after a take, is refused (the first word stands)."""
    with transaction.atomic():
        turn = (
            AgentTurn.objects.select_for_update()
            .filter(pk=turn_id, household=household_of(request), owner_user=request.user)
            .first()
        )
        if turn is None:
            raise HoldRefusal(404, "no_turn", "No turn of yours with that id; nothing decided.")
        pending = turn.pending_relay if isinstance(turn.pending_relay, dict) else {}
        current = pending.get("go") or (GO if "loop" in pending else None)
        if turn.status not in (TURN_RUNNING, TURN_AWAITING_LIMB):
            if decision == GO:
                return {"decision": GO, "status": turn.status}
            raise HoldRefusal(
                409, "turn_over", "That turn already ended on the engine; it cannot be taken back."
            )
        if current == decision:
            return {"decision": decision, "status": turn.status}
        if current is not None:
            code = "already_go" if current == GO else "taken"
            raise HoldRefusal(409, code, f"That turn was already decided: {current}.")
        turn.pending_relay = {**pending, "go": decision}
        fields = ["pending_relay"]
        if decision == TAKE:
            turn.cancel_requested = True
            fields.append("cancel_requested")
        turn.save(update_fields=fields)
        return {"decision": decision, "status": turn.status}
