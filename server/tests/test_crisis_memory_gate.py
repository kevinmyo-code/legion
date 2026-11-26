# ruff: noqa: E501 - the LIVE_AUDIT fixtures are copied as a device writes them, one line each
"""A crisis exchange never becomes a long-term memory on the engine (ticket #460).

**Every line below is invented.** A made-up member saying made-up things to a made-up car.

The three engine paths that turn a device conversation record into something durable are covered:
consolidation (`member_memory.consolidate`), the nightly memory review's evidence
(`member_memory.review`) and open-loop proposals (`open_loops.words`). The model is never reached:
the consolidation tests hand the job their own transport and read whether it was called at all.
"""

from __future__ import annotations

import datetime

import pytest
from django.utils import timezone

from devicelogs import crisis
from member_memory import consolidate, review
from member_memory.models import MemberMemory, MemoryConsolidation
from open_loops import words
from tests.test_member_memory_consolidate import FakeModel, _log

# No module-wide `django_db`: the boundary sweeps and the repeat test are pure predicates, and a
# test that opens a database connection it does not need is not free. `test_turn_cancel`'s
# `test_the_model_reader_thread_leaves_no_database_session_open` counts every session in
# `pg_stat_activity`, so sixty-five needless connections earlier in the run are enough to move it
# (CI run 45799926479). Each test that really needs a record marks itself.

A_MEMORY = [{"text": "Sam's gym is on Elm Street", "kind": "fact", "importance": 6, "durable": True}]

# The head unit: the phrase arrives as unfinished chunks and the socket is torn down on them, so
# there is no `user final=true` line and no `turn_complete` after it. Its teardown
# (`silentDestroy`) writes "superseded", so the record carries no crisis mark at all and the
# phrase is the only thing that can carry this case.
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
11-24 20:30:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-24 20:30:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 20:30:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 20:30:08.000  1 2 I LIVE_AUDIT: user final=true text="Some days I just want to die."
11-24 20:30:09.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""

# Only the assistant's reply carries a listed phrase, as the help line does.
CRISIS_IN_THE_REPLY = """\
11-24 21:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
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
11-24 22:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
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
11-24 09:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
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


# ----------------------------------------------------------- chunk boundaries
#
# The head unit cuts a turn at whatever boundary the transcript arrives on, so the gate has to read
# the same sentence identically at every one of them. Two things could break that, and both did
# (RED review B1 and B2): judging a chunk on its own, where an excluded phrase has no car-talk word
# in front of it, and appending the whole-turn repeat to the chunks it repeats, which fuses its
# first word onto the turn's last one. These are pure-predicate sweeps, no model and no record.

CAR_SENTENCE = "does the wagon have suicide doors"
CRISIS_SENTENCE = "some days I just want to die"


def _head_unit_turn(text: str, split: int) -> str:
    """One turn as the head unit writes it: the chunks, then the whole trimmed turn once more."""
    lines = ["session_start model=gemini-live async_set=none"]
    lines += [f'user final=false text="{chunk}"' for chunk in (text[:split], text[split:]) if chunk]
    lines += [f'user final=true text="{text}"', "turn_complete", 'session_end reason="stopped"']
    return "".join(
        f"11-24 07:{index // 60:02d}:{index % 60:02d}.000  1 2 I LIVE_AUDIT: {line}\n"
        for index, line in enumerate(lines)
    )


@pytest.mark.parametrize("split", range(len(CAR_SENTENCE) + 1))
def test_car_talk_is_quiet_at_every_chunk_boundary(split):
    assert not crisis.has_crisis_turn(_head_unit_turn(CAR_SENTENCE, split))


@pytest.mark.parametrize("split", range(len(CRISIS_SENTENCE) + 1))
def test_a_crisis_phrase_is_caught_at_every_chunk_boundary(split):
    assert crisis.has_crisis_turn(_head_unit_turn(CRISIS_SENTENCE, split))


def test_the_whole_turn_repeat_is_not_read_as_more_words():
    """The repeat is the turn we already have, so it adds nothing and fuses nothing."""
    chunks = '11-24 07:30:01.000  1 2 I LIVE_AUDIT: user final=false text="does the wagon have suicide doors"\n'
    repeat = '11-24 07:30:02.000  1 2 I LIVE_AUDIT: user final=true text="does the wagon have suicide doors"\n'
    assert not crisis.has_crisis_turn(chunks)
    assert not crisis.has_crisis_turn(chunks + repeat)
    # Truncated at the 400-character cap, the repeat is a marked prefix of the same turn.
    cut = '11-24 07:30:02.000  1 2 I LIVE_AUDIT: user final=true text="does the wagon have suicide..."\n'
    assert not crisis.has_crisis_turn(chunks + cut)


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
