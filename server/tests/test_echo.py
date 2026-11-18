# ruff: noqa: F811 - fixtures imported from the cascade tests
"""The server's echo layer (2026-11-17, after the real-model harness: our own reply played back at
5% volume was transcribed and paid for 3 times out of 3). A transcript that repeats the assistant's
own last reply ends the turn as no speech: no model round, nothing stored as the person's line, no
side-talk count, the first-utterance acknowledgement kept.

Round 4 (both reviewers): text alone cannot tell an echo from a person repeating words, so the
check runs ONLY on a turn the page marks `barge_in=1` (its audio began while a reply played: the
one way an echo can be posted). Any other turn is never checked. Google is never called; every
word is invented."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, say
from assistant import cascade, echo
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

REPLY = (
    "The Wagon is due for an oil change in about 670 miles, or by November 30. Brake fluid is "
    "also on the schedule."
)


@pytest.mark.parametrize(
    "heard",
    [
        REPLY,
        "or by November 30. Brake fluid is also on the schedule.",
        "The wagon is due for an oil change in about 617 miles or by November 13th.",
    ],
    ids=["whole", "tail", "two-misheard"],
)
def test_the_reply_heard_back_is_an_echo(heard):
    assert echo.is_echo(heard, REPLY)


@pytest.mark.parametrize(
    "heard",
    [
        "Is the oil change due soon?",
        "When is the wagon due for tires?",
        "yes",
        "do that",
        "the second one",
        "yes do that",
    ],
)
def test_a_real_answer_or_question_is_not_an_echo(heard):
    assert not echo.is_echo(heard, REPLY)


@pytest.mark.django_db
def test_an_echo_turn_asks_no_model_keeps_nothing_and_keeps_the_acknowledgement(
    member, engine, caplog
):
    engine.model = FakeModel([say(REPLY)])
    first = _events(_post(member, text="When is the car due?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel([])
    engine.speech.heard = "or by November 30. Brake fluid is also on the schedule."
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert engine.model.requests == []
    assert _of(events, "sentence") == [] and _of(events, "audio") == []
    assert _of(events, "done")[0]["ignored"] == "no_speech"
    record = _record(conversation)
    assert 'Brake fluid is also on the schedule."' in record  # the reply, once
    assert record.count("Brake fluid") == 1  # never stored as the person's line
    assert "status=echo" in record
    assert any("echo" in r.getMessage() and "Brake" not in r.getMessage() for r in caplog.records)
    # The echo neither counts as side talk nor uses up the first-utterance acknowledgement.
    from devicelogs import web_conversation
    from devicelogs.models import DeviceLog

    row = DeviceLog.objects.get(batch_id=conversation)
    assert web_conversation.utterances_received(row) == 1
    assert cascade.NOT_FOR_ME_TOOL  # imported for the reader


def _after_reply(member, engine, reply: str) -> str:
    engine.model = FakeModel([say(reply)])
    first = _events(_post(member, text="Anything for me?"))
    return _of(first, "turn")[0]["conversation_id"]


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("reply", "heard"),
    [
        (
            "The stats quiz Thursday night is the big one this week.",
            "The stats quiz Thursday night?",
        ),
        (
            "Want me to remind you about the dentist tomorrow morning?",
            "Remind me about the dentist tomorrow.",
        ),
        ("Want me to add milk to the shopping list?", "Add milk to the shopping list."),
    ],
)
def test_without_the_barge_in_flag_their_words_always_go_through(member, engine, reply, heard):
    conversation = _after_reply(member, engine, reply)
    engine.model = FakeModel([say("Sure.")])
    engine.speech.heard = heard
    events = _events(_post(member, audio=PCM, conversation_id=conversation))
    assert len(engine.model.requests) == 1
    assert _of(events, "heard")[0]["text"] == heard
    assert "ignored" not in _of(events, "done")[0]


@pytest.mark.django_db
@pytest.mark.parametrize(
    "heard", ["stop", "No, cancel that.", "What's on the calendar for Friday?"]
)
def test_with_the_flag_a_real_interruption_is_answered(member, engine, heard):
    conversation = _after_reply(member, engine, REPLY)
    engine.model = FakeModel([say("Okay.")])
    engine.speech.heard = heard
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert _of(events, "heard")[0]["text"] == heard
    assert "ignored" not in (_of(events, "done") or [{}])[0]


@pytest.mark.django_db
def test_the_reply_compared_is_the_one_that_was_playing(member, engine):
    """After a not-for-me turn, two replies in a row must not merge into one "last reply"."""
    conversation = _after_reply(member, engine, "Nothing is due today.")
    engine.speech.heard = "Pass me the salt, would you?"
    engine.model = FakeModel([cascade_call_not_for_me()])
    _events(_post(member, audio=PCM, conversation_id=conversation))
    engine.model = FakeModel([say(REPLY)])
    _events(_post(member, text="And the car?", conversation_id=conversation))
    engine.model = FakeModel([])
    engine.speech.heard = REPLY
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert engine.model.requests == []
    assert _of(events, "done")[0]["ignored"] == "no_speech"


def cascade_call_not_for_me():
    from agents.runtime.model import call

    return call(cascade.NOT_FOR_ME_TOOL, {})
