# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Blind review round 2, part 1 (turn path and safety), 2026-11-17
(`review-demo-voice-first-1117-turn.md`): one test per finding, written to fail before its fix.
Google is never called; every word is invented."""

from __future__ import annotations

import http.client

import pytest

from agents.runtime import model as runtime_model
from assistant.speech import GeminiSpeech, SpeechFailure
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
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
from tests.test_no_speech import _pcm, _speechlike

pytestmark = pytest.mark.django_db


# ---- finding 6: a transcription cut off mid-response is retried, then a spoken miss -------------


class _CutOff:
    """What `urlopen` hands back when the connection drops while the body streams."""

    def __init__(self, exc):
        self.exc = exc

    def __iter__(self):
        yield b'data: {"candidates": []}\n'
        raise self.exc

    def close(self):
        pass


@pytest.mark.parametrize(
    "exc",
    [
        ConnectionResetError("reset by peer"),
        http.client.IncompleteRead(b"partial"),
        TimeoutError("read timed out"),
    ],
)
def test_a_transcription_cut_off_mid_response_is_retried_then_a_speech_failure(monkeypatch, exc):
    calls = []

    def urlopen(req, timeout):
        calls.append(req.full_url)
        return _CutOff(exc)

    monkeypatch.setattr(runtime_model.urllib.request, "urlopen", urlopen)
    real = GeminiSpeech(lambda: "key", stt_model="stt")
    with pytest.raises(SpeechFailure):
        real.transcribe(_pcm(_speechlike()))
    assert len(calls) == 2  # tried once more, then said as a miss


# ---- finding 10: the robotic tap works outside a test transaction --------------------------------


@pytest.mark.django_db(transaction=True)
def test_the_robotic_tap_works_in_autocommit_as_in_production(visitor, engine):
    from agents.runtime.model import FakeModel, say

    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    response = visitor.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert response.status_code == 204
    assert "robotic turn=0 source=tap" in _record(conversation)


# ---- finding 5: remember refused after a web read is said, not passed over --------------------


def test_remember_refused_after_a_web_search_says_why(member, engine):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade

    engine.model = FakeModel(
        [
            call("web_search", {"query": "oat milk"}),
            call("remember", {"text": "Oat milk is the best."}),
            say("Noted."),
        ]
    )
    events = _events(_post(member, text="Look up oat milk and remember I like it."))
    remember = [t for t in _of(events, "tool") if t["name"] == "remember"][0]
    assert remember["ok"] is False
    said = [s["text"] for s in _of(events, "sentence")]
    assert said[-1] == cascade.READ_THROUGH_WAIT


# ---- finding 4: a turn in which a change went through is never "not for me" ---------------------


def test_not_for_me_after_a_write_says_what_was_done(member, engine):
    from agents.runtime.model import FakeModel, Finish, PartEvent, ToolCallEvent, Usage, say
    from assistant import cascade
    from checklists.models import Checklist

    both = [
        PartEvent({"functionCall": {"name": "create_checklist", "args": {"name": "Hardware"}}}),
        ToolCallEvent("", "create_checklist", {"name": "Hardware"}),
        PartEvent({"functionCall": {"name": cascade.NOT_FOR_ME_TOOL, "args": {}}}),
        ToolCallEvent("", cascade.NOT_FOR_ME_TOOL, {}),
        Usage(100, 0, 5, 105),
        Finish("STOP"),
    ]
    engine.model = FakeModel([both, say("I made the hardware list.")])
    events = _events(_post(member, text="Make a hardware list."))
    done = _of(events, "done")[0]
    assert "ignored" not in done
    assert done["writes_ran"] is True
    assert [s["text"] for s in _of(events, "sentence")] == ["I made the hardware list."]
    assert Checklist.objects.filter(name="Hardware").exists()


def test_no_write_runs_after_not_for_me(member, engine):
    from agents.runtime.model import FakeModel, Finish, PartEvent, ToolCallEvent, Usage
    from assistant import cascade
    from checklists.models import Checklist

    both = [
        PartEvent({"functionCall": {"name": cascade.NOT_FOR_ME_TOOL, "args": {}}}),
        ToolCallEvent("", cascade.NOT_FOR_ME_TOOL, {}),
        PartEvent({"functionCall": {"name": "create_checklist", "args": {"name": "Side talk"}}}),
        ToolCallEvent("", "create_checklist", {"name": "Side talk"}),
        Usage(100, 0, 5, 105),
        Finish("STOP"),
    ]
    engine.model = FakeModel([both])
    events = _events(_post(member, text="Ha, make a list of that, he said."))
    assert _of(events, "done")[0]["ignored"] == "not_addressed"
    assert not Checklist.objects.filter(name="Side talk").exists()
