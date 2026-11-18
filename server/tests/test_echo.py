# ruff: noqa: F811 - fixtures imported from the cascade tests
"""The server's echo layer (2026-11-17, after the real-model harness: our own reply played back at
5% volume was transcribed and paid for 3 times out of 3). A transcript that repeats the assistant's
own last reply ends the turn as no speech: no model round, nothing stored as the person's line, no
side-talk count, the first-utterance acknowledgement kept. Google is never called; every word is
invented."""

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
    events = _events(_post(member, audio=PCM, conversation_id=conversation))
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
