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

**Why this does not try to rebuild a turn.** The obvious shape, concatenating the lines of one
turn the way the device does (`userTurnText.append(chunk)`), was tried twice and was wrong twice,
because the audit stream does not say where a turn ends and the same event does not mean the same
thing on both clients:

- the phone writes `turn_complete` only through `afterReply()` (`CascadeSession.kt:2926`), and a
  barge-in or a cut-off reply cancels that path (`:2982-2997`, `:2044`), so two whole turns sit
  next to each other with only `interrupted` between them;
- `interrupted` is a turn end on the phone and MID-turn on the head unit (`ConvoAudit.kt:100`,
  written at `GeminiLiveSession.kt:509`, `:540`, `:992`, with that turn's own `turn_complete` still
  to come);
- the head unit writes the whole trimmed turn AGAIN at `turn_complete` (`:1035`), and a real chunk
  carries the same `final=true` flag when Gemini sets `finished` (`LiveTranscript.kt:15-19`), so
  neither the content nor the flag tells a repeat from a chunk;
- the conversation spool uploads on the periodic tick, not only at session end
  (`DriveLogLoop.kt:33`, `:75-78`), so a record can begin or end mid-turn.

Each of those made the same bug: joining two pieces of DIFFERENT provenance with nothing between
them turns two words into one ("...suicide doors" + "does the wagon..." = `doorsdoes`), which
destroys the `suicide doors` car-talk exclusion and lets the `suicide` stem fire on ordinary car
talk. The cost of that false positive is a whole conversation record dropped from memory for good.

Joining with a space instead does not work either, and the sweeps say so: a chunk boundary inside
a word makes `have` into `h ave`, which loses the car-talk word the exclusion needs in front of
`suicide doors`, so ordinary car talk fires again (6 of the 33 boundaries of one car-talk
sentence). An inserted separator is as much the engine's invention as a fused word.

**So nothing of different provenance is ever joined.** A run is the consecutive lines of one
speaker, joined exactly as the device joins them (no separator, `userTurnText.append(chunk)`), and
a run ENDS at:

- a `final=true` line, which every client writes only for a whole utterance it has finished (the
  phone's whole turn, the head unit's last chunk or its repeat). This is what makes a barge-in
  harmless: two phone turns can never share a run, whatever lies between them;
- a change of speaker, a turn end, or the end of the record.

**The one device fact this rests on, and it is pinned by a test:** the head unit's whole-turn
repeat is the last `user` line before `turn_complete` (`GeminiLiveSession.kt:1035`, then
`audit.turnComplete()`). At a `turn_complete` that line is judged on its own and left out of the
run it repeats, so the repeat can never fuse onto its own chunks and no content test is needed to
spot it (which is what a stutter defeated).

A chunk is therefore never judged alone, a whole utterance always is, and the text judged is
always text a device really held.

The rest of what it reads:

- every `user` line, final or not, and every `assistant` line, because a reply carrying the line is
  as much a memory of the distress as the line is (as the head unit's
  `CrisisMemoryGate.forgetsSession` decided in #458);
- no line is ever judged on its own, only a closed run: a chunk is not an utterance, and the
  detector keeps a hit when an excluded phrase has no car-talk word in front of it, so "does the
  wagon have" and " suicide doors" judged apart read as a crisis while the sentence does not;
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
ROLES = ("user", "assistant")

# What `ConvoAudit`/`LiveAudit` append to a line they cut at 400 characters.
TRUNCATION_MARK = "..."

# What the phone's crisis teardown writes: `shutdown("crisis")` ends at
# `CascadeSession.kt:4019` `audit.sessionEnd(reason)`. The head unit leaves no such mark (its
# crisis branch calls `silentDestroy`, which writes reason "superseded"), so this is a second
# way in, never the only one. It earns its line because every audit line is truncated at 400
# characters (`LiveAudit.MAX_TEXT`), and the phone writes a whole turn on one line: a phrase said
# past character 400 is a crisis no re-reading of the text can see.
CRISIS_REASON = "crisis"


def has_crisis_turn(raw: str) -> bool:
    """True when what one speaker said in one run of one record says a crisis phrase, or when the
    record carries a device's own crisis mark."""
    # Per speaker, the lines said since their last run ended, each with its `final` flag.
    runs: dict[str, list[tuple[str, bool]]] = {role: [] for role in ROLES}

    def closes(role: str) -> bool:
        """Judge what that speaker said in this run, and start them over.

        A run that ends on a `final=true` line ends on a whole utterance, and the lines before it
        are either the chunks that utterance repeats or an earlier fragment. Either way the two
        are judged APART, never joined, which is what stops a repeat fusing onto its own chunks
        and needs no test of the line's content to spot one.
        """
        said, runs[role] = runs[role], []
        if not said:
            return False
        whole, final = said[-1]
        if not final or len(said) == 1:
            return detect("".join(text for text, _ in said))
        chunks = "".join(text for text, _ in said[:-1])
        if whole.rstrip().endswith(TRUNCATION_MARK):
            # Cut at 400 characters (`ConvoAudit.MAX_TEXT`), so this line is not a whole utterance
            # and a phrase can be cut in half by it. The chunks are the text the device held.
            return detect(chunks)
        if chunks.strip() in whole:
            return detect(whole)  # the chunks are inside it; judging them again says nothing new
        return detect(whole) or detect(chunks)

    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag != voice_audit.AUDIT:
            continue
        event, fields = voice_audit.parse_audit(msg)
        if event == "session_end" and fields.get("reason") == CRISIS_REASON:
            return True
        if event in TURN_ENDS:
            if any(closes(role) for role in ROLES):
                return True
            continue
        if event not in runs:
            continue
        text = fields.get("text")
        if not isinstance(text, str):
            continue
        # The other speaker starting means this one has stopped.
        if any(closes(role) for role in ROLES if role != event):
            return True
        # A line the device calls final is a whole utterance it has finished, so nothing said
        # after it belongs to it. This is what keeps two phone turns out of one run, whatever
        # lies between them. The run is closed when the NEXT line arrives rather than at once,
        # because only the following event says whether this line was a whole-turn repeat.
        if runs[event] and runs[event][-1][1] and closes(event):
            return True
        runs[event].append((text, fields.get("final") == "true"))
    return any(closes(role) for role in ROLES)


def log_has_crisis_turn(log, raw: str | None = None) -> bool:
    """`has_crisis_turn` for one stored record. A batch that cannot be read is not a crisis: the
    callers already have their own answer for an unreadable record."""
    from devicelogs.render import batch_text

    try:
        return has_crisis_turn(batch_text(log) if raw is None else raw)
    except ValueError:
        return False
