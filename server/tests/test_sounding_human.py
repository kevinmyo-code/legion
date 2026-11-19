# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Sounding human (Kevin, 2026-11-17: "it sounded like an AI. real people dont talk like that";
`.legion/reports/voice-persona-1117.md` section 8): spoken-style examples, the acknowledgement
and the first clause early, one breath per reply, picking up after an interruption, and the
`robotic` marker. Google is never called; every word is invented."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, fillers
from devicelogs import web_conversation
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    FakeSpeech,
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
    visitor,
    visitor_user,
)
from tests.test_demo_strip_tester_only import make_visit

pytestmark = pytest.mark.django_db


def _warm(monkeypatch, engine, user):
    from assistant import voice_engine

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(engine.speech, voice_engine.voice_for(user))
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)
    monkeypatch.setattr(cascade, "LINES", fillers.LinePicker())
    engine.speech.spoken.clear()


# ---- 1. written for the ear --------------------------------------------------------------------


def test_the_cached_prefix_teaches_by_example_with_invented_content(member, engine):
    _events(_post(member, text="Hi."))
    system = engine.model.requests[0].system
    assert cascade.SPOKEN_EXAMPLES in system
    assert "never more than three items" in cascade.SPOKEN_EXAMPLES
    # Small: the pinned prefix grows by a few hundred characters, not a manual.
    assert len(cascade.SPOKEN_EXAMPLES) < 900


# ---- 2. first sound early ----------------------------------------------------------------------


def test_a_request_gets_an_acknowledgement_before_the_model_answers(
    member, engine, monkeypatch, household_user
):
    _warm(monkeypatch, engine, household_user)
    engine.speech.heard = "What did we spend on groceries last month?"
    engine.model = FakeModel([say("About four hundred.")])
    events = _events(_post(member, audio=PCM))
    kinds = [k for k, _ in events]
    ack = _of(events, "filler")[0]
    assert ack["tool"] == "" and ack["text"] in [
        cascade.speech_tags.untagged(line) for line in fillers.ACK
    ]
    assert kinds.index("filler") < kinds.index("sentence")
    # No tag reaches the page.
    assert "<" not in ack["text"]


@pytest.mark.parametrize("words", ["Thanks!", "Hi there.", "Okay cool.", "That's all, thanks."])
def test_small_talk_and_sign_offs_get_no_acknowledgement(
    member, engine, monkeypatch, household_user, words
):
    _warm(monkeypatch, engine, household_user)
    engine.speech.heard = words
    engine.model = FakeModel([say("Anytime.")])
    assert _of(_events(_post(member, audio=PCM)), "filler") == []


def test_a_typed_turn_gets_no_acknowledgement(member, engine, monkeypatch, household_user):
    _warm(monkeypatch, engine, household_user)
    engine.model = FakeModel([say("About four hundred.")])
    assert _of(_events(_post(member, text="What did we spend last month?")), "filler") == []


def test_the_acknowledgement_never_repeats_running():
    picker = fillers.LinePicker(choose=lambda options: options[0])
    said = [picker.pick(fillers.lines_for(fillers.ACK_TOOL)) for _ in range(6)]
    assert all(a != b for a, b in zip(said, said[1:], strict=False))


def test_a_long_first_sentence_has_its_first_clause_spoken_at_once():
    head, rest = cascade.first_clause(
        "So the router reads your question first, then it picks one specialist and that one "
        "answers it."
    )
    assert head == "So the router reads your question first,"
    assert rest.startswith("then it picks")
    assert cascade.first_clause("Short and sweet.") == ("Short and sweet.", "")


# ---- 3. one breath per reply -------------------------------------------------------------------


def test_a_short_reply_goes_to_speech_as_its_first_words_then_one_request():
    speech = FakeSpeech()
    speaker = cascade.Speaker(speech, "Puck", enabled=True)
    for sentence in ("Three things.", "The quiz is the big one.", "Then two readings."):
        speaker.add(sentence)
    list(speaker.drain())
    speaker.close()
    said = sorted(text for text, _voice in speech.spoken)
    assert said == sorted(["Three things.", "The quiz is the big one. Then two readings."])


def test_sentence_grouping_is_the_old_one_request_each(monkeypatch):
    monkeypatch.setattr(cascade, "SPEECH_GROUPING", "sentence")
    speech = FakeSpeech()
    speaker = cascade.Speaker(speech, "Puck", enabled=True)
    for sentence in ("One.", "Two.", "Three."):
        speaker.add(sentence)
    list(speaker.drain())
    speaker.close()
    assert len(speech.spoken) == 3


def test_a_round_ending_in_a_tool_speaks_what_it_held(member, engine):
    # One round that speaks and then calls a tool, then the answer.
    engine.model = FakeModel(
        [
            say("Right. Checking that and the list too.")[:2] + call("list_events", {}),
            say("All clear."),
        ]
    )
    _events(_post(member, audio=PCM))
    said = [text for text, _voice in engine.speech.spoken]
    assert "All clear." in said
    assert any("list too" in text for text in said)


# ---- 4. it reacts to an interruption -----------------------------------------------------------


def test_after_an_interruption_the_model_is_told_the_reply_was_cut(member, engine):
    engine.model = FakeModel([say("A long answer about everything.")])
    events = _events(_post(member, text="Tell me everything."))
    conversation = _of(events, "turn")[0]["conversation_id"]
    from datetime import UTC, datetime

    from devicelogs.models import DeviceLog

    row = DeviceLog.objects.get(batch_id=conversation)
    web_conversation.append(row, [web_conversation.engine_interrupted_line(datetime.now(UTC))])
    engine.model = FakeModel([say("Sure.")])
    _events(_post(member, text="Actually, just the car.", conversation_id=conversation))
    history = str(engine.model.requests[0].contents)
    assert web_conversation.CUT_OFF_NOTE in history


# ---- the robotic marker -------------------------------------------------------------------------


@pytest.mark.parametrize(
    "words",
    ["That sounded robotic.", "You sound like a robot", "that was so robotic", "It sounds fake."],
)
def test_saying_it_sounded_robotic_marks_the_turn_and_asks_no_model(member, engine, words):
    engine.model = FakeModel([say("Hello.")])
    first = _events(_post(member, text="Hi."))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.speech.heard = words
    engine.model = FakeModel([])
    events = _events(_post(member, audio=PCM, conversation_id=conversation))
    assert [s["text"] for s in _of(events, "sentence")] == [cascade.ROBOTIC_ACK]
    assert engine.model.requests == []
    record = _record(conversation)
    assert "robotic turn=0 source=voice" in record


def test_the_thumbs_down_marks_the_turn_for_a_visitor_and_never_another_conversation(
    visitor, visitor_user, member, engine
):
    # The marker is the tester visit's only (`tests/test_demo_strip_tester_only.py`).
    make_visit(visitor_user, unmetered=True)
    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    response = visitor.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert response.status_code == 204
    assert "robotic turn=0 source=tap" in _record(conversation)
    other = member.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert other.status_code == 404
