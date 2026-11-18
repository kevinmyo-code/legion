# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Round 3 N4 (`review-demo-voice-first-1117-turn-r3.md`): no paid call starts after a cancel. A
cancel already there when the turn starts stops speech-to-text, the greeting's model call and the
filler prefetch; a cancel while the turn waits on speech is seen within 250 ms, so the slot is
free well inside the page's 6 s of `busy` re-sends. Google is never called."""

from __future__ import annotations

import threading
import time
import uuid

import pytest

from agents.runtime.model import FakeModel, say
from assistant import cascade
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


class _Flag:
    """A `CancelCheck` the test sets by hand."""

    def __init__(self, hit=False):
        self.hit = hit
        self.asked = 0

    def __call__(self):
        self.asked += 1
        return self.hit

    def often(self):
        return self()


@pytest.fixture
def cancel(monkeypatch):
    flag = _Flag()
    monkeypatch.setattr(cascade, "CancelCheck", lambda *a, **k: flag)
    return flag


def _status(events):
    return (_of(events, "done") or [{}])[0].get("status")


def test_a_turn_cancelled_before_it_starts_pays_for_no_transcription(member, engine, cancel):
    cancel.hit = True
    events = _events(_post(member, audio=PCM, turn_id=str(uuid.uuid4())))
    assert engine.speech.transcribed == []
    assert _status(events) == "cancelled"


def test_a_greeting_cancelled_before_it_starts_makes_no_model_call(member, engine, cancel):
    cancel.hit = True
    engine.model = FakeModel([say("Morning.")])
    events = _events(_post(member, greet="true", turn_id=str(uuid.uuid4())))
    assert engine.model.requests == []
    assert engine.speech.spoken == []
    assert _status(events) == "cancelled"


def test_a_cancelled_spoken_turn_fetches_no_filler_audio(member, engine, cancel, monkeypatch):
    fetched = []
    audio = cascade.FillerAudio(spawn=lambda work: work())
    monkeypatch.setattr(audio, "warm", lambda speech, voice: fetched.append(voice) or 1)
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)
    cancel.hit = True
    _events(_post(member, text="What's on?", spoken="true", turn_id=str(uuid.uuid4())))
    assert fetched == []


def test_a_cancel_while_waiting_on_speech_ends_the_turn_within_a_second(
    member, engine, cancel, monkeypatch
):
    monkeypatch.setattr(cascade, "TTS_WAIT_S", 20.0)
    stalled = threading.Event()

    def speak(text, voice, spoken):
        # The speech service stops sending mid-answer; the browser cancels 300 ms later, while
        # the turn is already waiting for the first chunk.
        stalled.set()
        threading.Timer(0.3, lambda: setattr(cancel, "hit", True)).start()
        time.sleep(5)
        yield b"\x01\x00" * 480

    engine.speech.speak = speak
    engine.model = FakeModel([say("Here is a long answer about the week ahead.")])
    started = time.monotonic()
    events = _events(_post(member, text="What's on?", spoken="true", turn_id=str(uuid.uuid4())))
    assert stalled.is_set()
    assert time.monotonic() - started < 1.0
    assert _status(events) == "cancelled"
