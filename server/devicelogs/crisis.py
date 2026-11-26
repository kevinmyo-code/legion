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
transcript chunk BEFORE they detect (head unit `GeminiLiveSession.kt:920-922` appends the chunk to
`userTurnText` and audits it, `:923` detects), and the crisis teardown kills the socket on the
partial transcript, so the phrase can be spooled with no whole-turn line and no `turn_complete`
after it. So:

- every `user` line, final or not, rebuilt into the turn the way the device builds it
  (`userTurnText.append(chunk)`, no separator), because a phrase can be split across two chunks;
- the turn is judged **when it closes**, never per line and never on a growing prefix. A chunk
  boundary is not the start or the end of an utterance, and the detector keeps a hit when an
  excluded phrase has no car-talk word in front of it or runs off the end of the text: judged
  apart, "does the wagon have" + " suicide doors" reads as a crisis, and so does the prefix "does
  the wagon have suicide", while the whole sentence does not (RED review B2). The live detector on
  the car does read each prefix (`checkForCrisis()` on the accumulated `userTurnText`), and
  deliberately so: there, firing early on a half-said sentence costs one unneeded help line, and
  the next chunk settles it. Here it would cost the whole record, kept out of memory for good, so
  the completed turn is the only honest unit;
- every `assistant` line too, rebuilt the same way, as the head unit's
  `CrisisMemoryGate.forgetsSession` does: a reply carrying the line is as much a memory of the
  distress as the line is;
- **never the head unit's whole-turn repeat on top of the chunks it repeats** (see `_repeats`);
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
TRUNCATION_MARK = "..."

# What the phone's crisis teardown writes: `shutdown("crisis")` ends at
# `CascadeSession.kt:4019` `audit.sessionEnd(reason)`. The head unit leaves no such mark (its
# crisis branch calls `silentDestroy`, which writes reason "superseded"), so this is a second
# way in, never the only one. It earns its line because every audit line is truncated at 400
# characters (`LiveAudit.MAX_TEXT`), and the phone writes a whole turn on one line: a phrase said
# past character 400 is a crisis no re-reading of the text can see.
CRISIS_REASON = "crisis"


def _repeats(built: str, text: str) -> bool:
    """Is `text` the turn `built` already holds, written again?

    The head unit audits every transcript chunk and then, at `turn_complete`, the whole trimmed
    turn once more (`GeminiLiveSession.kt:1035`). Appending that line to the chunks it repeats
    would fuse its first word onto the turn's last one, and a fused word breaks the car-talk
    exclusion the detector exists to honour: "...suicide doors" + "does the wagon..." becomes the
    one word "doorsdoes", `suicide doors` stops matching and the `suicide` stem fires on ordinary
    car talk (RED review B1).

    The repeat is `userTurnText.toString().trim()`, so it equals what we have, except that an audit
    line over 400 characters is cut and marked (`ConvoAudit.MAX_TEXT`), which leaves a prefix.
    """
    if not built:
        return False
    said = text.strip()
    if not said:
        return False
    if said == built:
        return True
    return said.endswith(TRUNCATION_MARK) and built.startswith(said[: -len(TRUNCATION_MARK)])


def has_crisis_turn(raw: str) -> bool:
    """True when a turn in one record's raw audit lines says a crisis phrase, or when the record
    carries a device's own crisis mark."""
    turns: dict[str, list[str]] = {"user": [], "assistant": []}

    def closes() -> bool:
        return any(detect("".join(said)) for said in turns.values())

    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag != voice_audit.AUDIT:
            continue
        event, fields = voice_audit.parse_audit(msg)
        if event == "session_end" and fields.get("reason") == CRISIS_REASON:
            return True
        if event in TURN_ENDS:
            if closes():
                return True
            turns = {"user": [], "assistant": []}
            continue
        if event not in turns:
            continue
        text = fields.get("text")
        if not isinstance(text, str):
            continue
        turn = turns[event]
        if _repeats("".join(turn).strip(), text):
            continue
        turn.append(text)
    return closes()


def log_has_crisis_turn(log, raw: str | None = None) -> bool:
    """`has_crisis_turn` for one stored record. A batch that cannot be read is not a crisis: the
    callers already have their own answer for an unreadable record."""
    from devicelogs.render import batch_text

    try:
        return has_crisis_turn(batch_text(log) if raw is None else raw)
    except ValueError:
        return False
