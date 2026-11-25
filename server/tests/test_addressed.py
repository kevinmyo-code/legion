# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Knowing when it is being spoken to (`.legion/reports/voice-persona-1117.md` section 10, items
1, 3 and 4 for the web): the model may judge a sentence "not for me", code combines that with the
signals it can read, and an unsure sentence gets a cue on screen and never a change. Invented."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade
from assistant.companions import companion_for
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
)

pytestmark = pytest.mark.django_db


def _warm(monkeypatch, engine, user):
    from assistant import fillers, voice_engine

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(engine.speech, voice_engine.voice_for(user))
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)
    monkeypatch.setattr(cascade, "LINES", fillers.LinePicker())


def test_side_talk_is_ignored_and_keeps_nothing_but_a_count(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    engine.model = FakeModel([say("Hi.")])
    first = _of(_events(_post(member, text="Hi.")), "turn")[0]["conversation_id"]
    engine.speech.heard = "Wow, the voice on this thing is wild."
    engine.model = FakeModel([call(cascade.NOT_FOR_ME_TOOL, {})])
    events = _events(_post(member, audio=PCM, conversation_id=first))
    # Nothing of its own is said, and nothing of theirs is kept. Since 2026-11-24 the
    # acknowledgement is said on every turn of theirs (Kevin: the same voice behavior as the
    # phone), and whether a sentence was for the assistant is the MODEL's answer, which only
    # arrives after it. So one acknowledgement ("Mm.") is heard over side talk, and exactly one:
    # what this test protects is that nothing follows it and nothing is recorded.
    assert _of(events, "sentence") == []
    assert [f["tool"] for f in _of(events, "filler")] == [""]
    assert len(_of(events, "audio")) == len(_of(events, "filler"))
    done = _of(events, "done")[0]
    assert done["ignored"] == "not_addressed" and "words" not in done
    record = _record(_of(events, "turn")[0]["conversation_id"])
    assert "voice on this thing" not in record
    assert "addressed decision=not_addressed" in record
    assert len(engine.model.requests) == 1  # the same model call, no extra round


def test_an_utterance_that_needs_a_look_gets_an_acknowledgement(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    engine.speech.heard = "What did we spend on groceries last month?"
    engine.model = FakeModel([say("About four hundred.")])
    events = _events(_post(member, audio=PCM))
    assert [f["tool"] for f in _of(events, "filler")] == [""]


def test_a_direct_question_is_answered(member, engine):
    engine.speech.heard = "What's on the calendar today?"
    engine.model = FakeModel([say("Nothing today.")])
    events = _events(_post(member, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == ["Nothing today."]
    assert "ignored" not in _of(events, "done")[0]


def test_the_name_always_gets_through(member, engine, household_user):
    name = companion_for(household_user).name
    engine.speech.heard = f"{name}, the lights are weird in here."
    engine.model = FakeModel([call(cascade.NOT_FOR_ME_TOOL, {}), say("Want me to look?")])
    events = _events(_post(member, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == ["Want me to look?"]
    assert "ignored" not in _of(events, "done")[0]
    sent = engine.model.requests[1].contents[-1]["parts"][0]["functionResponse"]["response"]
    assert sent["message"] == cascade.NAMED_RESPONSE


def test_an_unsure_sentence_shows_a_cue_and_never_writes(member, engine):
    from checklists.models import Checklist

    before = Checklist.objects.count()
    engine.speech.heard = "Can you make a list for the party?"
    engine.model = FakeModel(
        [call(cascade.NOT_FOR_ME_TOOL, {}), call("create_checklist", {"name": "Party"})]
    )
    events = _events(_post(member, audio=PCM))
    done = _of(events, "done")[0]
    assert done["ignored"] == "unsure" and done["words"] == cascade.UNSURE_CUE
    assert _of(events, "sentence") == []
    assert Checklist.objects.count() == before


@pytest.mark.parametrize(
    ("signals", "model_says_not", "decision"),
    [
        ({"named": True, "answers_question": False, "second_person": False}, True, "addressed"),
        ({"named": False, "answers_question": False, "second_person": False}, False, "addressed"),
        ({"named": False, "answers_question": True, "second_person": False}, True, "unsure"),
        ({"named": False, "answers_question": False, "second_person": True}, True, "unsure"),
        (
            {"named": False, "answers_question": False, "second_person": False},
            True,
            "not_addressed",
        ),
    ],
)
def test_the_decision_combines_the_signals(signals, model_says_not, decision):
    assert cascade.addressed_decision(signals, model_says_not) == decision


def test_the_signals_read_from_the_words():
    signals = cascade.addressed_signals("Alfred, what's next?", "Alfred", "Anything else?")
    assert signals == {"named": True, "answers_question": True, "second_person": True}
    quiet = cascade.addressed_signals("Ha, that was funny.", "Alfred", "Done.")
    assert quiet == {"named": False, "answers_question": False, "second_person": False}


def test_side_talk_twice_gets_one_acknowledgement_each_and_no_more(
    member, engine, monkeypatch, household_user
):
    """Round 3 speech S5 said only the first utterance after the tap may get "one sec"; Kevin
    reversed that on 2026-11-24 (the phone says one every turn). What still holds is at most ONE a
    turn, and nothing else said."""
    _warm(monkeypatch, engine, household_user)
    engine.model = FakeModel([say("Morning.")])
    opened = _events(_post(member, greet="true"))
    conversation = _of(opened, "turn")[0]["conversation_id"]
    acks = 0
    for _ in range(2):
        engine.speech.heard = "Did you look at what we spent on groceries last month?"
        engine.model = FakeModel([call(cascade.NOT_FOR_ME_TOOL, {})])
        events = _events(_post(member, audio=PCM, conversation_id=conversation))
        turn_acks = [f for f in _of(events, "filler") if f["tool"] == ""]
        assert len(turn_acks) <= 1, turn_acks
        assert _of(events, "sentence") == []
        acks += len(turn_acks)
    assert acks == 2


def test_room_noise_gets_no_acknowledgement_and_the_next_question_still_does(
    member, engine, monkeypatch, household_user
):
    """Round 4 speech: only utterances heard as speech are answered at all, so noise says nothing
    (`fillers.ack_skip` NOISE) and the question after it is an ordinary turn."""
    _warm(monkeypatch, engine, household_user)
    engine.model = FakeModel([say("Morning.")])
    conversation = _of(_events(_post(member, greet="true")), "turn")[0]["conversation_id"]
    engine.speech.heard = ""  # room noise: nothing heard
    noise = _events(_post(member, audio=PCM, conversation_id=conversation))
    assert _of(noise, "filler") == []
    engine.speech.heard = "What did we spend on groceries last month?"
    engine.model = FakeModel([say("About four hundred.")])
    events = _events(_post(member, audio=PCM, conversation_id=conversation))
    assert [f["tool"] for f in _of(events, "filler")] == [""]
