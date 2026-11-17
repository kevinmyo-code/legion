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


def test_side_talk_is_ignored_and_keeps_nothing_but_a_count(member, engine):
    engine.speech.heard = "Wow, the voice on this thing is wild."
    engine.model = FakeModel([call(cascade.NOT_FOR_ME_TOOL, {})])
    events = _events(_post(member, audio=PCM))
    assert _of(events, "sentence") == []
    assert _of(events, "audio") == [] or all(e.get("filler") for e in _of(events, "audio"))
    done = _of(events, "done")[0]
    assert done["ignored"] == "not_addressed" and "words" not in done
    record = _record(_of(events, "turn")[0]["conversation_id"])
    assert "voice on this thing" not in record
    assert "addressed decision=not_addressed" in record
    assert len(engine.model.requests) == 1  # the same model call, no extra round


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
