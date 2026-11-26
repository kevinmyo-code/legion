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

from member_memory import consolidate, review
from member_memory.models import MemberMemory, MemoryConsolidation
from open_loops import words
from tests.test_member_memory_consolidate import FakeModel, _log

pytestmark = pytest.mark.django_db

A_MEMORY = [{"text": "Sam's gym is on Elm Street", "kind": "fact", "importance": 6, "durable": True}]

# The crisis phrase arrives as unfinished chunks and the socket is torn down on them: no
# `user final=true` line and no `turn_complete` after it, which is what the devices spool.
CRISIS = """\
11-24 20:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-24 20:00:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 20:00:01.500  1 2 I LIVE_AUDIT: assistant text="Noted, Elm Street."
11-24 20:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 20:00:09.000  1 2 I LIVE_AUDIT: user final=false text="honestly I"
11-24 20:00:09.300  1 2 I LIVE_AUDIT: user final=false text=" want to"
11-24 20:00:09.600  1 2 I LIVE_AUDIT: user final=false text=" die"
11-24 20:00:10.000  1 2 I LIVE_AUDIT: session_end reason="crisis"
"""

# The phone writes the whole turn on one `final=true` line before it detects
# (`CascadeSession.kt:1837` audits, `:1841` detects and shuts the turn down).
PHONE_CRISIS = """\
11-24 20:30:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-24 20:30:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-24 20:30:02.000  1 2 I LIVE_AUDIT: turn_complete
11-24 20:30:08.000  1 2 I LIVE_AUDIT: user final=true text="Some days I just want to die."
11-24 20:30:09.000  1 2 I LIVE_AUDIT: session_end reason="crisis"
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


@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY])
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


def test_the_turns_said_before_the_phrase_go_too(household_user, household_a):
    _log(household_a, household_user, CRISIS)
    model = FakeModel(A_MEMORY)
    consolidate.run(key="test-key", transport=model)

    assert "Elm Street" not in "".join(model.prompts)
    assert not MemberMemory.objects.filter(text__icontains="Elm").exists()


def test_ordinary_car_talk_is_still_distilled(household_user, household_a):
    _log(household_a, household_user, CAR_TALK)
    model = FakeModel(A_MEMORY)
    report = consolidate.run(key="test-key", transport=model)

    assert len(model.prompts) == 1
    assert (report.read, report.written) == (1, 1)
    assert MemberMemory.objects.get().text == "Sam's gym is on Elm Street"


@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY])
def test_the_review_reads_no_evidence_from_a_crisis_record(household_user, household_a, raw):
    _log(household_a, household_user, raw)
    assert review._transcripts(household_user, household_a, timezone.now()) == []


def test_the_review_still_reads_an_ordinary_record(household_user, household_a):
    _log(household_a, household_user, CAR_TALK)
    read = review._transcripts(household_user, household_a, timezone.now())
    assert len(read) == 1 and "Elm Street" in read[0][2]


@pytest.mark.parametrize("raw", [CRISIS, PHONE_CRISIS, CRISIS_IN_THE_REPLY])
def test_a_crisis_record_proposes_no_open_loop(household_user, household_a, raw):
    log = _log(household_a, household_user, raw, run_id="r-1")
    assert words.own_text(log) == ""


def test_an_ordinary_record_still_gives_his_own_words(household_user, household_a):
    log = _log(household_a, household_user, CAR_TALK, run_id="r-2")
    assert "Elm Street" in words.own_text(log)


def test_a_record_older_than_the_review_window_is_not_read(household_user, household_a):
    """The review's own window still applies; the gate adds nothing to it."""
    _log(household_a, household_user, CAR_TALK, age=datetime.timedelta(days=review.REVIEW_DAYS + 1))
    assert review._transcripts(household_user, household_a, timezone.now()) == []
