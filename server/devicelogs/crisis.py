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

**The text judged is the text a device really held, never a joined-up guess.** Joining two
pieces of different provenance with nothing between them makes one word of two ("...suicide
doors" + "does the wagon..." = `doorsdoes`), which destroys the `suicide doors` car-talk
exclusion; joining with a space makes `have` into `h ave`, which loses the car-talk word the
exclusion needs in front. And judging a piece of an utterance alone loses the same word: "does the
wagon have " and "suicide doors" judged apart read as a crisis while the sentence does not, and
"some days I just want" and " to die" judged apart miss one. A false positive costs a whole
record dropped from memory for good; a miss lets a crisis become a memory.

So the gate rebuilds what the device itself held, and the two kinds of voice session the apps run
write their turns in two different shapes:

- **The cascade** (the phone's `CascadeSession`) writes each turn WHOLE, on one `final=true` line
  (`CascadeSession.kt:1837`, typed `:1070`), and never a `final=false` line. It writes
  `turn_complete` only through `afterReply()` (`:2926`), so after a barge-in two whole turns sit
  next to each other with only `interrupted`, or nothing, between them. Every `user` line is
  therefore judged on its own, and two of them are never joined.
- **A Live session** (the head unit's only voice session, and the phone's `GeminiLiveSession`)
  writes every transcript delta as it arrives (`audit.user(chunk, final = finished)`, head unit
  `GeminiLiveSession.kt:922`, phone `:1808`), where `final` is Gemini's own
  `inputTranscription.finished`. That flag lands on whichever delta ends an utterance, so a
  `final=true` line here is sometimes only the last few words of one, and it says nothing about
  where a turn ends. The device appends every delta to one `userTurnText` and its reply deltas to
  one `companionTurnText`, and clears both only at the turn's completion or a typed line (head
  unit `:1079-1080`, phone `:1091-1093`, `:1995-1996`). The gate does the same: his deltas are
  joined with nothing between them across the whole turn, across `interrupted` and across the
  reply, and the reply's deltas are joined apart from his. **The `final` flag never ends anything.**

How a session is told apart, by what its own lines say: a `final=false` line exists only in a
Live session; a `session_start` names its model, and the cascade's is `<backend>+<model>`
(`CascadeSession.kt:728`) where a Live session's is the bare model name (phone
`GeminiLiveSession.kt:747`, head unit `:458`); and a record the head unit uploaded is Live
throughout, because the head unit writes `user` lines from no other session. **The one residue:**
a phone record that begins mid-session (the spool uploads on the periodic tick, `DriveLogLoop.kt`,
so a record can start or end mid-turn) and holds no `final=false` line names neither session. It
is read as the cascade, because joining cascade turns is the false positive above; a Live tail cut
that way, with `finished` on every visible delta, is then judged line by line. Pinned by
`test_an_unnamed_phone_segment_of_whole_lines_is_read_as_the_cascade_writes`.

**A Live turn's whole-turn repeat.** At its completion a Live session writes the trimmed turn once
more as `final=true` (head unit `:1035`, phone `:1980`; never after a typed line), then
`turn_complete`. So the last line he said before `turn_complete` is that repeat, by position,
with no test of its content. It is the device's whole `userTurnText`, so it is what is judged,
together with any visible deltas it does not contain. Where no `turn_complete` follows (the crisis
teardown, `silentDestroy`, writes `session_end reason="superseded"` and no repeat), the last line
is a tail delta and is joined like any other, unless it is EXACTLY his joined deltas once trimmed,
which is the repeat's own definition and happens when a spool tick falls between the two writes.

Every audit line is cut at 400 characters with a `...` mark (`LiveAudit.MAX_TEXT`), so a cut
repeat is not the whole turn: the deltas are judged instead, and the cut line as well when the
deltas do not begin with what it holds (the record began mid-turn, so only the cut line holds the
turn's start). The phone's own `session_end reason="crisis"` mark is read too, because a phrase
said past character 400 of a cascade turn is in no line at all (see `CRISIS_REASON`).

The rest of what it reads:

- every `user` line, final or not, and every `assistant` line, because a reply carrying the line is
  as much a memory of the distress as the line is (as the head unit's
  `CrisisMemoryGate.forgetsSession` decided in #458);
- no read-through, redaction or `can_mark_turns` filtering: a turn those rules drop still says the
  record was a crisis, and none of this text is ever shown to a model.

The answer is for the whole record, never for one turn: the turns said BEFORE the phrase are the
same exchange, which is the rule the head unit settled on in #458.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field

from agents.voice.crisis import detect
from devicelogs import voice_audit

SESSION_EDGES = frozenset({"session_start", "session_end"})
ROLES = ("user", "assistant")

# What `ConvoAudit`/`LiveAudit` append to a line they cut at 400 characters.
TRUNCATION_MARK = "..."

# The cascade audits its session as `"${b.kind.token}+$MODEL"` (`CascadeSession.kt:728`); a Live
# session audits the bare model name.
CASCADE_MODEL_JOIN = "+"

# What the phone's crisis teardown writes: `shutdown("crisis")` ends at
# `CascadeSession.kt:4019` `audit.sessionEnd(reason)`. Neither Live session leaves such a mark
# (their crisis branch calls `silentDestroy`, which writes reason "superseded"), so this is a
# second way in, never the only one. It earns its line because every audit line is truncated at
# 400 characters (`LiveAudit.MAX_TEXT`), and the cascade writes a whole turn on one line: a phrase
# said past character 400 is a crisis no re-reading of the text can see.
CRISIS_REASON = "crisis"

Line = tuple[str, dict]


def has_crisis_turn(raw: str, *, head_unit: bool = False) -> bool:
    """True when text a device held in one record says a crisis phrase, or when the record carries
    a device's own crisis mark. `head_unit`: the record was uploaded by a head unit."""
    lines = list(_audit_lines(raw))
    if any(e == "session_end" and f.get("reason") == CRISIS_REASON for e, f in lines):
        return True
    return any(detect(text) for session in _sessions(lines) for text in _texts(session, head_unit))


def log_has_crisis_turn(log, raw: str | None = None) -> bool:
    """`has_crisis_turn` for one stored record. A batch that cannot be read is not a crisis: the
    callers already have their own answer for an unreadable record."""
    from devicelogs.models import UPLOADER_HEAD_UNIT
    from devicelogs.render import batch_text

    try:
        text = batch_text(log) if raw is None else raw
    except ValueError:
        return False
    return has_crisis_turn(text, head_unit=log.uploader_kind == UPLOADER_HEAD_UNIT)


def _audit_lines(raw: str) -> Iterator[Line]:
    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag == voice_audit.AUDIT:
            yield voice_audit.parse_audit(msg)


def _sessions(lines: Iterable[Line]) -> Iterator[list[Line]]:
    """The record cut at every session start and end, keeping each session's start, its turn ends
    and the lines that carry text. The part before the first start is a session begun before the
    record."""
    session: list[Line] = []
    for event, fields in lines:
        if event in SESSION_EDGES and session:
            yield session
            session = []
        if event in ("session_start", "turn_complete") or (
            event in ROLES and isinstance(fields.get("text"), str)
        ):
            session.append((event, fields))
    if session:
        yield session


def _is_live(session: list[Line], head_unit: bool) -> bool:
    if head_unit or any(e == "user" and f.get("final") != "true" for e, f in session):
        return True
    start = next((f for e, f in session if e == "session_start"), None)
    return start is not None and CASCADE_MODEL_JOIN not in str(start.get("model", ""))


def _texts(session: list[Line], head_unit: bool) -> Iterator[str]:
    return _live_texts(session) if _is_live(session, head_unit) else _cascade_texts(session)


def _cascade_texts(session: list[Line]) -> Iterator[str]:
    """Each `user` line on its own (a whole turn), and each reply's consecutive lines joined."""
    reply: list[str] = []
    for event, fields in session:
        if event == "assistant":
            reply.append(fields["text"])
            continue
        if reply:
            yield "".join(reply)
            reply = []
        if event == "user":
            yield fields["text"]
    if reply:
        yield "".join(reply)


@dataclass
class _LiveTurn:
    """One Live turn as the device's two buffers hold it."""

    heard: list[tuple[str, bool]] = field(default_factory=list)  # userTurnText, line by line, final
    said: list[str] = field(default_factory=list)  # companionTurnText
    typed: bool = False  # began from a typed line, so no whole-turn repeat closes it
    complete: bool = False  # closed by its own `turn_complete`


def _live_texts(session: list[Line]) -> Iterator[str]:
    for turn in _live_turns(session):
        yield from _heard_texts(turn)
        if turn.said:
            yield "".join(turn.said)


def _live_turns(session: list[Line]) -> Iterator[_LiveTurn]:
    turn = _LiveTurn()
    for event, fields in session:
        if event == "turn_complete":
            turn.complete = True
            yield turn
            turn = _LiveTurn()
        elif event == "assistant":
            turn.said.append(fields["text"])
        elif event == "user" and fields.get("typed") == "true":
            # The device starts both buffers over from the typed text (phone `:1091-1093`).
            yield turn
            turn = _LiveTurn(heard=[(fields["text"], False)], typed=True)
        elif event == "user":
            turn.heard.append((fields["text"], fields.get("final") == "true"))
    yield turn


def _heard_texts(turn: _LiveTurn) -> Iterator[str]:
    """What he said in one Live turn: his joined deltas, or the whole-turn repeat for them."""
    if not turn.heard:
        return
    joined = "".join(text for text, _ in turn.heard)
    last, last_final = turn.heard[-1]
    if turn.typed or not last_final or len(turn.heard) == 1:
        yield joined
        return
    deltas = joined[: len(joined) - len(last)]
    if turn.complete or _is_repeat(deltas, last):
        yield from _with_repeat(deltas, last)
    else:
        yield joined


def _is_repeat(deltas: str, last: str) -> bool:
    """With no `turn_complete` after it, is `last` the repeat of `deltas`? Only when it is exactly
    what the device writes for them, `userTurnText.toString().trim()`."""
    whole = deltas.strip()
    if not whole:
        return False
    if _truncated(last):
        head = _head(last).strip()
        return bool(head) and whole.startswith(head)
    return whole == last.strip()


def _with_repeat(deltas: str, repeat: str) -> Iterator[str]:
    if not _truncated(repeat):
        yield repeat
        if deltas.strip() not in repeat:
            yield deltas
        return
    # Cut at 400 characters, so a phrase can be cut in half by it: the deltas are the text the
    # device held, and the cut line also when the deltas do not begin with it.
    head = _head(repeat)
    if deltas.strip():
        yield deltas
    if not deltas.strip().startswith(head.strip()):
        yield head


def _truncated(line: str) -> bool:
    return line.rstrip().endswith(TRUNCATION_MARK)


def _head(line: str) -> str:
    return line.rstrip()[: -len(TRUNCATION_MARK)]
