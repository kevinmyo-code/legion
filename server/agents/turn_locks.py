"""The agents doors' locks (Postgres advisory locks, held while an answer streams and released
when its response closes, however it ends):

- the member's one open STREAM (`POST /api/agents/turn/stream`, review 1120 finding 4);
- one turn at a time per CONVERSATION on the form route (`POST /api/agents/turn`, E2b): two
  turns of one conversation at once would race on its history and turn numbers.

**Wait, then refuse** (E2b, the phone's barge-in): after a barge-in the phone cancels the old
turn, closes its stream and opens a new one at once; the old response may take a moment to
notice the hang-up. So a taker waits up to `WAIT_S` for the lock before it refuses
(`stream_busy` / `conversation_busy`). No advisory locks on a local SQLite run: nothing is
limited there.
"""

from __future__ import annotations

import time

from django.db import connection

from agents.voice import streaming
from assistant import turn_slots

WAIT_S = 1.5
POLL_S = 0.05


def _hold(key_text: str, wait_s: float) -> turn_slots.Held | None:
    if connection.vendor != "postgresql":
        return turn_slots.Held()
    key = turn_slots._key(key_text)
    deadline = time.monotonic() + wait_s
    while not turn_slots._try(streaming.STREAM_LOCK_NS, key):
        if time.monotonic() >= deadline:
            return None
        time.sleep(POLL_S)
    return turn_slots.Held(locks=[(streaming.STREAM_LOCK_NS, key)])


def hold_stream(user) -> turn_slots.Held | None:
    """The member's one open stream, or None while another stays open past the wait."""
    return _hold(f"stream:{user.pk}", WAIT_S)


def hold_conversation(conversation_id) -> turn_slots.Held | None:
    """One turn at a time in this conversation, or None while another stays open past the wait.
    A turn with no conversation id starts a new conversation: nothing to hold."""
    if not conversation_id:
        return turn_slots.Held()
    return _hold(f"conversation:{conversation_id}", WAIT_S)
