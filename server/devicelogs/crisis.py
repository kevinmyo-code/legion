"""Does a stored conversation record hold a crisis turn? (ticket #460)

One predicate, for every engine path that turns a device conversation record into something
durable: member memories (`member_memory.consolidate`, `member_memory.review`) and open loops
(`open_loops.words`). A crisis exchange said to the phone or in the car must never become a
long-term memory on the engine and be given back to him later.

**The decision is the engine's own detector**, `agents.voice.crisis.detect`, which is already a
pinned copy of the phone's `app/.../ai/CrisisDetector.kt` (the head unit's file is byte-identical
to the phone's but for its package line). No second phrase list is written here, and nothing here
is a phrase. `agents/voice/crisis.py` imports nothing of the engine's, so reading it here crosses
no layer.

**What it reads, and why it is not the consolidation transcript.** The devices audit each
transcript chunk BEFORE they detect (head unit `GeminiLiveSession.kt:922` audits, `:923` detects),
and the crisis teardown kills the socket on the partial transcript, so the phrase can be spooled
with no `user final=true` line and no `turn_complete` after it. So:

- every `user` line, final or not, and the chunks of one turn joined as the device joins them
  (`userTurnText.append(chunk)`, no separator), because a phrase can be split across two chunks;
- every `assistant` line too, joined the same way, as the head unit's `CrisisMemoryGate
  .forgetsSession` does: a reply carrying the line is as much a memory of the distress as the
  line is;
- each line on its own as well as the join, because a join can only add words and an exclusion
  reads the word before ("has suicide doors" is car talk, "suicide, doors..." is not);
- no read-through, redaction or `can_mark_turns` filtering: a turn those rules drop still says the
  record was a crisis, and none of this text is ever shown to a model;
- the phone's own `session_end reason="crisis"` mark as well, because every audit line is truncated
  at 400 characters and the phone writes a whole turn on one line (see `CRISIS_REASON`).

The answer is for the whole record, never for one turn: the turns said BEFORE the phrase are the
same exchange, which is the rule the head unit settled on in #458.
"""

from __future__ import annotations

from agents.voice.crisis import detect
from devicelogs import voice_audit

TURN_ENDS = frozenset({"turn_complete", "session_start", "session_end"})

# What the phone's crisis teardown writes: `shutdown("crisis")` ends at
# `CascadeSession.kt:4019` `audit.sessionEnd(reason)`. The head unit leaves no such mark (its
# crisis branch calls `silentDestroy`, which writes reason "superseded"), so this is a second
# way in, never the only one. It earns its line because every audit line is truncated at 400
# characters (`LiveAudit.MAX_TEXT`), and the phone writes a whole turn on one line: a phrase said
# past character 400 is a crisis no re-reading of the text can see.
CRISIS_REASON = "crisis"


def has_crisis_turn(raw: str) -> bool:
    """True when anything said in one record's raw audit lines says a crisis phrase, or when the
    record carries a device's own crisis mark."""
    said: list[str] = []
    heard: list[str] = []

    def joined_trips() -> bool:
        return detect("".join(said)) or detect("".join(heard))

    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag != voice_audit.AUDIT:
            continue
        event, fields = voice_audit.parse_audit(msg)
        if event == "session_end" and fields.get("reason") == CRISIS_REASON:
            return True
        if event in TURN_ENDS:
            if joined_trips():
                return True
            said, heard = [], []
            continue
        if event not in ("user", "assistant"):
            continue
        text = fields.get("text")
        if not isinstance(text, str):
            continue
        if detect(text):
            return True
        (said if event == "user" else heard).append(text)
    return joined_trips()


def log_has_crisis_turn(log, raw: str | None = None) -> bool:
    """`has_crisis_turn` for one stored record. A batch that cannot be read is not a crisis: the
    callers already have their own answer for an unreadable record."""
    from devicelogs.render import batch_text

    try:
        return has_crisis_turn(batch_text(log) if raw is None else raw)
    except ValueError:
        return False
