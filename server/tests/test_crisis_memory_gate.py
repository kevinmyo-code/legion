# ruff: noqa: E501 - the LIVE_AUDIT fixtures are copied as a device writes them, one line each
"""A crisis exchange never becomes a long-term memory on the engine (ticket #460).

**Every line below is invented.** A made-up member saying made-up things to a made-up car.

The three engine paths that turn a device conversation record into something durable are covered:
consolidation (`member_memory.consolidate`), the nightly memory review's evidence
(`member_memory.review`) and open-loop proposals (`open_loops.words`). The model is never reached:
the consolidation tests hand the job their own transport and read whether it was called at all.

The second half of the file is what two RED review rounds cost: every shape the two apps can
really write, read as the ordinary speech it is. They are pure predicates over a string.
"""

from __future__ import annotations

import datetime

import pytest
from django.utils import timezone

from agents.voice import crisis as detector
from devicelogs import crisis
from member_memory import consolidate, review
from member_memory.models import MemberMemory, MemoryConsolidation
from open_loops import words
from tests.test_member_memory_consolidate import FakeModel, _log

# No module-wide `django_db`: most of this file is a pure predicate, and a test that opens a
# database connection it does not need is not free. `test_turn_cancel`'s
# `test_the_model_reader_thread_leaves_no_database_session_open` counts every session in
# `pg_stat_activity`, and needless connections earlier in the run are the likeliest reason it moved
# in CI run 45799926479. Each test that really needs a record marks itself.

A_MEMORY = [{"text": "Sam's gym is on Elm Street", "kind": "fact", "importance": 6, "durable": True}]

# The head unit: the phrase arrives as unfinished chunks and the socket is torn down on them, so
# there is no whole-turn line and no `turn_complete` after it. Its teardown (`silentDestroy`)
# writes "superseded", so the record carries no crisis mark at all and the phrase is the only thing
# that can carry this case.
CRISIS = """\
11-24 20:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-24 20:00:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 20:00:01.500  1 2 I LIVE_AUDIT: assistant text="Noted, Elm Street."
11-24 20:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 20:00:09.000  1 2 I LIVE_AUDIT: user final=false text="honestly I"
11-24 20:00:09.300  1 2 I LIVE_AUDIT: user final=false text=" want to"
11-24 20:00:09.600  1 2 I LIVE_AUDIT: user final=false text=" die"
11-24 20:00:10.000  1 2 I LIVE_AUDIT: session_end reason="superseded"
"""

# The phone writes the whole turn on one `final=true` line before it detects
# (`CascadeSession.kt:1837` audits, `:1841` detects and shuts the turn down). Its real teardown
# also writes `session_end reason="crisis"`; this record deliberately does not, so the phrase is
# what has to carry it. The mark has a case of its own below.
PHONE_CRISIS = """\
11-24 20:30:00.000  1 2 I LIVE_AUDIT: session_start model=cloud+gemini-3.5-flash-lite async_set=none
11-24 20:30:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 20:30:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 20:30:08.000  1 2 I LIVE_AUDIT: user final=true text="Some days I just want to die."
11-24 20:30:09.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""

# Only the assistant's reply carries a listed phrase, as the help line does.
CRISIS_IN_THE_REPLY = """\
11-24 21:00:00.000  1 2 I LIVE_AUDIT: session_start model=cloud+gemini-3.5-flash-lite async_set=none
11-24 21:00:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 21:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 21:00:05.000  1 2 I LIVE_AUDIT: user final=true text="I dont see the point of any of it."
11-24 21:00:06.000  1 2 I LIVE_AUDIT: assistant text="Call or text 988 to reach the Suicide and Crisis Lifeline."
11-24 21:00:07.000  1 2 I LIVE_AUDIT: turn_complete
11-24 21:00:08.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""

# Every audit line is truncated at 400 characters, so a phrase said late in a long turn is not in
# the record at all. What is left is the phone's own crisis mark.
TRUNCATED = """\
11-24 22:00:00.000  1 2 I LIVE_AUDIT: session_start model=cloud+gemini-3.5-flash-lite async_set=none
11-24 22:00:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 22:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 22:00:08.000  1 2 I LIVE_AUDIT: user final=true text="it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and it has been a long month and ..."
11-24 22:00:09.000  1 2 I LIVE_AUDIT: session_end reason="crisis"
"""

# Ordinary car talk in the shape the HEAD UNIT writes it: every transcript chunk, then the whole
# trimmed turn once more (`GeminiLiveSession.kt:920-922` and `:1035`). The excluded phrase ends the
# turn with no punctuation after it, which is the ordinary shape because speech-to-text writes no
# punctuation. This record must still be distilled.
HEAD_UNIT_CAR_TALK = """\
11-24 08:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-24 08:00:01.000  1 2 I LIVE_AUDIT: user final=false text="does the wagon have"
11-24 08:00:01.300  1 2 I LIVE_AUDIT: user final=false text=" suicide doors"
11-24 08:00:01.600  1 2 I LIVE_AUDIT: user final=true text="does the wagon have suicide doors"
11-24 08:00:01.800  1 2 I LIVE_AUDIT: assistant text="It does"
11-24 08:00:01.900  1 2 I LIVE_AUDIT: assistant text=" not."
11-24 08:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 08:00:03.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 08:00:04.000  1 2 I LIVE_AUDIT: turn_complete
11-24 08:00:05.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""

# Ordinary car talk the detector is tuned to leave alone. This record must still be distilled.
CAR_TALK = """\
11-24 09:00:00.000  1 2 I LIVE_AUDIT: session_start model=cloud+gemini-3.5-flash-lite async_set=none
11-24 09:00:01.000  1 2 I LIVE_AUDIT: user final=true text="This traffic is killing me."
11-24 09:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 09:00:03.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street. Does the wagon have suicide doors?"
11-24 09:00:04.000  1 2 I LIVE_AUDIT: assistant text="It does not."
11-24 09:00:05.000  1 2 I LIVE_AUDIT: turn_complete
11-24 09:00:06.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""


@pytest.mark.django_db
@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY, TRUNCATED],
    ids=["head unit chunks", "phone whole turn", "only the reply", "truncated, marked"])
def test_a_crisis_conversation_is_never_distilled(household_user, household_a, raw):
    _log(household_a, household_user, raw)
    model = FakeModel(A_MEMORY)
    report = consolidate.run(key="test-key", transport=model)

    assert model.prompts == [], "no model may be asked about a crisis conversation"
    assert MemberMemory.objects.count() == 0
    assert (report.read, report.written, report.skipped) == (1, 0, 1)
    assert MemoryConsolidation.objects.get().outcome == "skipped"
    # And it is never read again, so nothing can distil it on a later run.
    assert consolidate.run(key="test-key", transport=model).read == 0


@pytest.mark.django_db
def test_the_turns_said_before_the_phrase_go_too(household_user, household_a):
    _log(household_a, household_user, CRISIS)
    model = FakeModel(A_MEMORY)
    consolidate.run(key="test-key", transport=model)

    assert "Elm Street" not in "".join(model.prompts)
    assert not MemberMemory.objects.filter(text__icontains="Elm").exists()


@pytest.mark.django_db
@pytest.mark.parametrize("raw", [CAR_TALK, HEAD_UNIT_CAR_TALK],
    ids=["phone whole turn", "head unit chunks and the repeat"])
def test_ordinary_car_talk_is_still_distilled(household_user, household_a, raw):
    _log(household_a, household_user, raw)
    model = FakeModel(A_MEMORY)
    report = consolidate.run(key="test-key", transport=model)

    assert len(model.prompts) == 1
    assert (report.read, report.written) == (1, 1)
    assert MemberMemory.objects.get().text == "Sam's gym is on Elm Street"


# --------------------------------------------- what the gate may never invent
#
# Three RED review blockers were one bug: joining two audit lines of DIFFERENT provenance makes one
# word out of two ("...suicide doors" + "does the wagon..." = "doorsdoes"), the `suicide doors`
# exclusion dies, and the `suicide` stem fires on ordinary car talk. Joining with a space instead
# breaks the exclusion the other way, by making `have` into `h ave`. So nothing of different
# provenance is joined at all, and these sweeps are what hold that. Each one is a shape the apps
# really write.

CAR_SENTENCE = "does the wagon have suicide doors"
CRISIS_SENTENCE = "some days I just want to die"


def _line(text: str, *, final: bool | None = True, role: str = "user") -> str:
    if role == "assistant":
        return f'assistant text="{text}"'
    return f'user final={"true" if final else "false"} text="{text}"'


def _record(*lines: str) -> str:
    return "".join(
        f"11-24 06:{index // 60:02d}:{index % 60:02d}.000  1 2 I LIVE_AUDIT: {line}\n"
        for index, line in enumerate(lines)
    )


LIVE_START = "session_start model=gemini-live async_set=none"
CASCADE_START = "session_start model=cloud+gemini-3.5-flash-lite async_set=none"


def _chunks(text: str, *splits: int, finished: bool = False) -> list[str]:
    """`text` cut at `splits` into the chunks a Live session audits. The LAST chunk carries
    `finished`, as Gemini's own `inputTranscription.finished` lands on whichever chunk ends the
    utterance (`GeminiLiveSession.kt:922` on the head unit, `:1808` on the phone)."""
    cuts = [0, *splits, len(text)]
    parts = [text[a:b] for a, b in zip(cuts, cuts[1:], strict=False) if text[a:b]]
    return [_line(part, final=finished and i == len(parts) - 1) for i, part in enumerate(parts)]


def _split_run(text: str, at: int, *, finished: bool = False) -> str:
    """One unfinished run of two chunks, as the spool holds a turn that never closed."""
    return _record(*_chunks(text, at, finished=finished))


def _chunked_turn(text: str, split: int, *, finished: bool = False) -> str:
    """One turn as a Live session writes it: the chunks, then the whole trimmed turn once more."""
    return _record(
        LIVE_START,
        *_chunks(text, split, finished=finished),
        _line(text),
        "turn_complete",
        'session_end reason="stopped"',
    )


@pytest.mark.parametrize("finished", [False, True], ids=["unfinished tail", "finished tail"])
@pytest.mark.parametrize("split", range(len(CAR_SENTENCE) + 1))
def test_car_talk_is_quiet_at_every_chunk_boundary(split, finished):
    assert not crisis.has_crisis_turn(_chunked_turn(CAR_SENTENCE, split, finished=finished))


@pytest.mark.parametrize("finished", [False, True], ids=["unfinished tail", "finished tail"])
@pytest.mark.parametrize("split", range(len(CRISIS_SENTENCE) + 1))
def test_a_crisis_phrase_is_caught_at_every_chunk_boundary(split, finished):
    assert crisis.has_crisis_turn(_chunked_turn(CRISIS_SENTENCE, split, finished=finished))


# B3: the phone writes `turn_complete` only through `afterReply()` (`CascadeSession.kt:2926`), so a
# barge-in or a cut-off reply leaves two whole turns next to each other with only `interrupted`
# between them, or with nothing at all.
CUT_OFF = ("interrupted source=barge_in", "interrupted source=tap", "nudge reason=still_checking tool=none")


@pytest.mark.parametrize("between", CUT_OFF)
def test_two_car_talk_turns_across_a_cut_off_reply_are_quiet(between):
    assert not crisis.has_crisis_turn(_record(
        CASCADE_START,
        _line("does the wagon have suicide doors"),
        _line("It does not", role="assistant"),
        between,
        _line("what time is it"),
        "turn_complete",
    ))


@pytest.mark.parametrize("between", CUT_OFF)
def test_a_crisis_turn_across_a_cut_off_reply_is_still_caught(between):
    assert crisis.has_crisis_turn(_record(
        CASCADE_START,
        _line("does the wagon have suicide doors"),
        between,
        _line("some days I just want to die"),
        "turn_complete",
    ))


def test_two_car_talk_turns_with_no_event_between_them_are_quiet():
    assert not crisis.has_crisis_turn(_record(
        _line("got suicide doors"),
        _line("whats next"),
    ))


# B3's other half: `interrupted` is MID-turn on the head unit, between a turn's chunks and the
# whole-turn repeat they belong to, with that turn's own `turn_complete` still to come.
def test_a_head_unit_turn_interrupted_mid_turn_is_quiet():
    assert not crisis.has_crisis_turn(_record(
        _line("does the wagon have", final=False),
        "interrupted source=server",
        _line(" suicide doors", final=False),
        _line("does the wagon have suicide doors"),
        "turn_complete",
    ))


# B4: a stuttered or re-emitted opening chunk is ordinary speech-to-text output.
@pytest.mark.parametrize("stutter", [
    ("does", " does", " the wagon have suicide doors"),
    ("it", " it", " has a suicide knob"),
    ("my", " my", " truck has suicide doors"),
    (" it", " has", " a", " a", " suicide knob"),
])
def test_a_stuttered_chunk_is_quiet(stutter):
    assert not crisis.has_crisis_turn(_record(
        *[_line(chunk, final=False) for chunk in stutter],
        _line("".join(stutter).strip()),
        "turn_complete",
    ))


# The spool uploads on the periodic tick (`DriveLogLoop.kt:75-78`), not only at session end, so a
# record can begin or end mid-turn: no `session_start`, no `turn_complete`, a partial chunk at
# either edge.
def test_a_record_cut_mid_turn_is_quiet_on_car_talk():
    assert not crisis.has_crisis_turn(_record(
        _line(" suicide doors", final=False),
        _line("does the wagon have suicide doors"),
        "turn_complete",
        _line("my gym moved to", final=False),
    ))


def test_a_record_cut_mid_crisis_turn_is_still_caught():
    assert crisis.has_crisis_turn(_record(
        "turn_complete",
        _line("honestly I", final=False),
        _line(" want to", final=False),
        _line(" die", final=False),
    ))


def test_a_reply_never_joins_onto_what_he_said():
    """A Live session keeps his words and its own in two buffers (`userTurnText`,
    `companionTurnText`), so a reply between two of his chunks joins neither of them: his chunks
    join each other across it, exactly as the device appends them."""
    assert not crisis.has_crisis_turn(_record(
        _line("I want to", final=False),
        _line("hold on", role="assistant"),
        _line(" diet soda", final=False),
    ))
    assert not crisis.has_crisis_turn(_record(
        _line("does the wagon have ", final=False),
        _line("one moment", role="assistant"),
        _line("suicide doors"),
        'session_end reason="superseded"',
    ))
    assert crisis.has_crisis_turn(_record(
        _line("some days I just want", final=False),
        _line("mm", role="assistant"),
        _line(" to die"),
        'session_end reason="superseded"',
    ))


# -------------------------------------------- the whole-turn repeat, by position
#
# A Live session writes the trimmed turn once more just before `turn_complete`, so the last line
# he said before it is the repeat with no test of its content (which a stutter defeated). With no
# `turn_complete` after it, a last line is the repeat only when it is exactly his joined deltas.


def test_the_repeat_is_quiet_with_or_without_a_turn_end():
    chunks_then_repeat = (
        _line("does the wagon have suicide doors", final=False),
        _line("does the wagon have suicide doors"),
    )
    assert not crisis.has_crisis_turn(_record(*chunks_then_repeat, "turn_complete"))
    assert not crisis.has_crisis_turn(_record(*chunks_then_repeat))


def test_a_phrase_in_the_whole_turn_line_is_caught_either_way():
    lines = (_line("some days I just", final=False), _line("some days I just want to die"))
    assert crisis.has_crisis_turn(_record(*lines, "turn_complete"))
    assert crisis.has_crisis_turn(_record(*lines))


def test_a_truncated_whole_turn_line_does_not_speak_for_the_turn():
    """Cut at the 400-character cap, that line can hold half a word, so the chunks are the text."""
    assert not crisis.has_crisis_turn(_record(
        _line("does the wagon have suicide doors", final=False),
        _line("does the wagon have suicide..."),
        "turn_complete",
    ))
    assert crisis.has_crisis_turn(_record(
        _line("some days I just want to die", final=False),
        _line("some days I just want to..."),
        "turn_complete",
    ))


@pytest.mark.parametrize("phrase", detector.PHRASES)
def test_every_phrase_survives_every_chunk_boundary(phrase):
    """No split of any phrase, mid-word included, loses it: the run is joined as the device joins
    it, so the text judged is the text the device held."""
    said = f"honestly {phrase} today"
    missed = [i for i in range(1, len(said)) if not crisis.has_crisis_turn(_split_run(said, i))]
    assert missed == []


# ---------------------------------- r4: Gemini's `finished` flag lands on a chunk, not a turn
#
# Both Live sessions audit each transcript delta with Gemini's own `finished` flag
# (`audit.user(chunk, final = finished)`, head unit `GeminiLiveSession.kt:922`, phone `:1808`), so a
# `final=true` line in a Live session is sometimes the last few words of an utterance. The crisis
# teardown (`silentDestroy`, `session_end reason="superseded"`) writes no whole-turn repeat and no
# `turn_complete` after it, on the car and on the phone's Live session alike.

CAR_SENTENCES = [CAR_SENTENCE, "it has a suicide knob", "my truck has suicide doors", "i want the two suicide doors"]
CRISIS_SENTENCES = [CRISIS_SENTENCE, "I dont want to live anymore", "I am going to hurt myself", "there is nothing to live for"]
TORN_DOWN = 'session_end reason="superseded"'


def _torn_down(text: str, *splits: int, finished: bool = True, start: str | None = LIVE_START) -> str:
    """A Live turn torn down by the crisis branch on its chunks: no repeat, no `turn_complete`."""
    return _record(*([start] if start else []), *_chunks(text, *splits, finished=finished), TORN_DOWN)


def _boundaries(text: str) -> range:
    return range(1, len(text))


@pytest.mark.parametrize("said", CRISIS_SENTENCES)
def test_a_crisis_torn_down_on_a_finished_tail_is_caught_at_every_boundary(said):
    """B5: `finished` on the tail delta, the real teardown, no repeat."""
    assert [i for i in _boundaries(said) if not crisis.has_crisis_turn(_torn_down(said, i))] == []


@pytest.mark.parametrize("said", CAR_SENTENCES)
def test_car_talk_with_a_finished_tail_is_quiet_at_every_boundary(said):
    """B6, with the repeat and `turn_complete`, and with neither (the record ends on the tail)."""
    with_repeat = [i for i in _boundaries(said) if crisis.has_crisis_turn(_record(
        LIVE_START, *_chunks(said, i, finished=True), _line(said), "turn_complete"))]
    torn_down = [i for i in _boundaries(said) if crisis.has_crisis_turn(_torn_down(said, i))]
    record_ends = [i for i in _boundaries(said) if crisis.has_crisis_turn(_record(
        *_chunks(said, i, finished=True)))]
    assert (with_repeat, torn_down, record_ends) == ([], [], [])


def _middle_finished(text: str, i: int, j: int) -> list[str]:
    """Three chunks with `finished` on the middle one."""
    return [_line(text[:i], final=False), _line(text[i:j]), _line(text[j:], final=False)]


@pytest.mark.parametrize("said", CRISIS_SENTENCES + CAR_SENTENCES)
def test_finished_on_a_middle_chunk_changes_nothing(said):
    expected = detector.detect(said)
    pairs = [(i, j) for i in _boundaries(said) for j in range(i + 1, len(said))]
    wrong = [(i, j) for i, j in pairs
             if crisis.has_crisis_turn(_record(LIVE_START, *_middle_finished(said, i, j), TORN_DOWN)) != expected]
    assert wrong == []


@pytest.mark.parametrize("said", CRISIS_SENTENCES + CAR_SENTENCES)
def test_finished_on_every_chunk_of_a_live_session_changes_nothing(said):
    """Every chunk `final=true` and none `false`: the session's own start says it is Live."""
    expected = detector.detect(said)
    wrong = [i for i in _boundaries(said) if crisis.has_crisis_turn(_record(
        LIVE_START, _line(said[:i]), _line(said[i:]), TORN_DOWN)) != expected]
    assert wrong == []


@pytest.mark.parametrize("phrase", detector.PHRASES)
@pytest.mark.parametrize("finished", [False, True], ids=["unfinished tail", "finished tail"])
def test_every_bare_phrase_survives_a_teardown_at_every_boundary(phrase, finished):
    """The phrase is the whole utterance, so no padding hides a split at its first letter."""
    missed = [i for i in _boundaries(phrase)
              if not crisis.has_crisis_turn(_torn_down(phrase, i, finished=finished))]
    assert missed == []


@pytest.mark.parametrize("phrase", detector.PHRASES)
def test_every_phrase_survives_a_finished_tail_at_every_boundary(phrase):
    said = f"honestly {phrase} today"
    assert [i for i in _boundaries(said) if not crisis.has_crisis_turn(_torn_down(said, i))] == []


def test_a_car_upload_is_read_as_live_with_no_session_start():
    """The record began mid-session (the periodic tick), so nothing in it names the session, and
    every chunk happens to carry `finished`. The head unit writes `user` lines only from its Live
    session, so its upload is read that way."""
    raw = _record(_line("I dont want to"), _line(" live anymore"), TORN_DOWN)
    assert crisis.has_crisis_turn(raw, head_unit=True)


def test_an_unnamed_phone_segment_of_whole_lines_is_read_as_the_cascade_writes():
    """The known residue, pinned: a phone record that begins mid-session with no `final=false`
    line names neither session. It is read as the cascade (each line a whole turn), because
    joining cascade turns is round 2's B3. A Live tail cut this way, `finished` on every chunk,
    is judged line by line."""
    assert not crisis.has_crisis_turn(_record(_line("does the wagon have suicide doors"), _line("does it")))
    assert not crisis.has_crisis_turn(_record(_line("I dont want to"), _line(" live anymore"), TORN_DOWN))


def test_a_typed_line_is_never_taken_for_the_repeat():
    """A typed turn has no whole-turn repeat (`!wasTyped`, phone `GeminiLiveSession.kt:1980`), and
    the device starts its turn text from the typed text (`:1091-1092`)."""
    assert crisis.has_crisis_turn(_record(
        LIVE_START,
        'user final=true typed=true text="some days I just want"',
        _line(" to die"),
        "turn_complete",
    ))


def test_a_record_cut_between_the_repeat_and_its_turn_end_is_quiet():
    """The repeat and `turn_complete` are two writes, so a spool tick can fall between them."""
    for said in CAR_SENTENCES:
        for i in _boundaries(said):
            raw = _record(LIVE_START, *_chunks(said, i, finished=True), _line(said))
            assert not crisis.has_crisis_turn(raw), (said, i)


def test_a_truncated_repeat_after_a_mid_turn_cut_is_still_read():
    """r4 residue 1: the record begins with a late chunk, so the cut repeat is the only line that
    holds the start of the turn."""
    assert crisis.has_crisis_turn(_record(
        _line(" and that is all of it", final=False),
        _line("some days I just want to die and that is all of it and more ..."),
        "turn_complete",
    ))


@pytest.mark.django_db
@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY, TRUNCATED],
    ids=["head unit chunks", "phone whole turn", "only the reply", "truncated, marked"])
def test_the_review_reads_no_evidence_from_a_crisis_record(household_user, household_a, raw):
    _log(household_a, household_user, raw)
    assert review._transcripts(household_user, household_a, timezone.now()) == []


@pytest.mark.django_db
def test_the_review_still_reads_an_ordinary_record(household_user, household_a):
    _log(household_a, household_user, CAR_TALK)
    read = review._transcripts(household_user, household_a, timezone.now())
    assert len(read) == 1 and "Elm Street" in read[0][2]


@pytest.mark.django_db
@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY, TRUNCATED],
    ids=["head unit chunks", "phone whole turn", "only the reply", "truncated, marked"])
def test_a_crisis_record_proposes_no_open_loop(household_user, household_a, raw):
    log = _log(household_a, household_user, raw, run_id="r-1")
    assert words.own_text(log) == ""


@pytest.mark.django_db
def test_an_ordinary_record_still_gives_his_own_words(household_user, household_a):
    log = _log(household_a, household_user, CAR_TALK, run_id="r-2")
    assert "Elm Street" in words.own_text(log)


@pytest.mark.django_db
def test_a_record_older_than_the_review_window_is_not_read(household_user, household_a):
    """The review's own window still applies; the gate adds nothing to it."""
    _log(household_a, household_user, CAR_TALK, age=datetime.timedelta(days=review.REVIEW_DAYS + 1))
    assert review._transcripts(household_user, household_a, timezone.now()) == []


@pytest.mark.django_db
def test_a_head_unit_upload_is_gated_as_a_live_session(household_user, household_a):
    """`log_has_crisis_turn` reads who uploaded the record: the car's teardown shape with no
    `session_start` is caught on a head unit upload, and read as the cascade on a phone one."""
    from devicelogs.models import UPLOADER_HEAD_UNIT

    raw = _record(_line("I dont want to"), _line(" live anymore"), TORN_DOWN)
    assert crisis.log_has_crisis_turn(_log(household_a, household_user, raw, uploader_kind=UPLOADER_HEAD_UNIT))
    assert not crisis.log_has_crisis_turn(_log(household_a, household_user, raw))
