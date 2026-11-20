"""The blind recheck's list for the agents door before its flag is ever on (2026-11-20,
`.legion/reports/review-engine-voice-v1-1119.md`, "Recheck 2527c750"): tests it found missing, the
metering leaks, and the cheap contract fields (voice wire v1, d0d6c1f6). Google is never called;
every household, clip and word here is invented."""

# ruff: noqa: F811 - the `engine` fixture is imported from the turn tests and named in each test

from __future__ import annotations

import gc
import logging
import re
import threading

import pytest
from django.core.cache import cache

from agents.models import AgentTurn
from agents.runtime import model as m
from agents.voice import allowance, pipeline
from agents.voice.filler_audio import FillerAudio
from llm.models import LlmUsage
from tests.test_engine_voice_turn import FakeSpeech, _events, _post, _wav, engine  # noqa: F401

pytestmark = pytest.mark.django_db

LINE_ID = re.compile(r"^\d+\.1\.\d+$")


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


def _blocks(response):
    for chunk in response.streaming_content:
        yield chunk.decode() if isinstance(chunk, bytes) else chunk


# ---- 2. tests the recheck found missing ---------------------------------------------------------


def test_the_character_bound_alone_stops_the_speech(auth_client, engine):
    # Four sentences of 400 characters: the request bound is far off, the 1,500 characters are not.
    long = " ".join(["word"] * 79) + " end."
    engine.rounds = [m.say(" ".join([long] * 4))]
    events = _events(_post(auth_client, audio=_wav()))
    spoken = [text for text, _ in engine.speech.spoken]
    assert len(spoken) == 3 and sum(map(len, spoken)) <= 1_500
    assert sum(1 for k, d in events if k == "speech_stopped") == 1


def test_the_daily_allowance_counts_only_agent_turns(household_user, monkeypatch):
    monkeypatch.delenv("LEGION_MEMBER_DAILY_CAP", raising=False)

    def spend(feature):
        LlmUsage.objects.create(
            household=household_user.household,
            user=household_user,
            feature=feature,
            model="tts",
            cost_estimate_micro_usd=5_000_000,
        )

    spend("web_cascade")
    assert not allowance.over_cap(household_user)
    spend("agent_turn")
    assert allowance.over_cap(household_user)


def test_a_hang_up_finishes_the_turn_before_the_close_returns(auth_client, engine, caplog):
    # Without `turn.close()` in the view, the pipeline's `finally` (speech stopped, speech metered,
    # the timing line) waited for garbage collection; with it off here, it never runs in time.
    gate = threading.Event()

    class SlowSpeech(FakeSpeech):
        def speak(self, text, voice, spoken):
            self.spoken.append((text, voice))
            for _ in range(200):
                gate.wait(0.005)
                yield b"\x01\x00" * 480

    engine.speech = SlowSpeech()
    gc.disable()
    try:
        response = _post(auth_client, audio=_wav())
        for block in _blocks(response):
            if block.startswith("event: audio"):
                break
        with caplog.at_level(logging.WARNING, logger="agents.voice.pipeline"):
            response.close()
            assert any("agents_voice_turn" in r.getMessage() for r in caplog.records)
    finally:
        gate.set()
        gc.enable()


# ---- 3. metering leaks --------------------------------------------------------------------------


def _tts_cost(user) -> int:
    """What this member's agent turns were metered for speech out, in micro-dollars."""
    from django.db.models import Sum

    rows = LlmUsage.objects.filter(user=user, feature="agent_turn", model="tts")
    return int(rows.aggregate(n=Sum("cost_estimate_micro_usd"))["n"] or 0)


def test_speech_cancelled_in_flight_is_still_metered(auth_client, engine, household_user):
    gate = threading.Event()

    class NoUsageSpeech(FakeSpeech):
        # Google sends the usage at the end of a stream: a cancelled one never gets that far.
        def speak(self, text, voice, spoken):
            self.spoken.append((text, voice))
            for _ in range(200):
                gate.wait(0.005)
                spoken.audio_ms += 20
                yield b"\x01\x00" * 480

    engine.speech = NoUsageSpeech()
    engine.rounds = [m.say("A sentence that takes a while to say.")]
    response = _post(auth_client, audio=_wav())
    cancelled = False
    try:
        for block in _blocks(response):
            if block.startswith("event: audio") and not cancelled:
                turn = AgentTurn.objects.get()
                assert auth_client.post(f"/api/agents/turn/{turn.pk}/cancel").status_code == 200
                cancelled = True
    finally:
        gate.set()
    assert cancelled
    assert _tts_cost(household_user) > 0


def test_filler_warm_up_is_metered_to_the_turn_that_started_it(
    auth_client, engine, household_user, monkeypatch
):
    def tts_cost():
        return _tts_cost(household_user)

    # Every line already in memory: nothing to fetch, only the answer's speech is metered.
    full = FillerAudio(spawn=lambda work: None)
    for line in full.default_lines():
        full._audio[(_voice(household_user), line)] = b"\x00\x00"
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", full)
    _events(_post(auth_client, audio=_wav()))
    answer_only = tts_cost()
    LlmUsage.objects.all().delete()
    # Nothing in memory: the lines this turn sends for are metered too, at an estimate.
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: None))
    _events(_post(auth_client, audio=_wav()))
    assert tts_cost() > answer_only > 0


def _voice(user) -> str:
    from assistant import voice_engine

    return voice_engine.voice_for(user)
