"""The blind recheck's list for the agents door before its flag is ever on (2026-11-20,
`.legion/reports/review-engine-voice-v1-1119.md`, "Recheck 2527c750"): tests it found missing, the
metering leaks, and the cheap contract fields (voice wire v1, d0d6c1f6). Google is never called;
every household, clip and word here is invented."""

# ruff: noqa: F811 - the `engine` fixture is imported from the turn tests and named in each test

from __future__ import annotations

import re
import threading

import pytest
from django.core.cache import cache

from agents.models import AgentTurn
from agents.runtime import events as ev
from agents.runtime import model as m
from agents.voice import allowance, pipeline
from agents.voice.filler_audio import FillerAudio
from agents.voice.speech import Heard
from llm.models import LlmUsage
from tests.test_engine_voice_turn import FakeSpeech, _events, _post, _wav, engine  # noqa: F401

pytestmark = pytest.mark.django_db

LINE_ID = re.compile(r"^\d+\.\d+\.\d+$")


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
    # Spoken up to the characters, not the requests: three of the four sentences, in fewer
    # requests than the request bound allows.
    assert 1_100 < sum(map(len, spoken)) <= 1_500
    assert len(spoken) < 5
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


def test_a_hang_up_closes_the_turn_itself(auth_client, engine, monkeypatch):
    # The view must close the pipeline when the stream is closed, not leave it to whoever else
    # still holds the generator (here the test does, as a traceback or a wrapper could): its
    # `finally` stops the speech and meters it.
    held, finished = [], []

    def voice_turn(*args, **kwargs):
        def turn():
            try:
                yield ev.waiting()
                yield ev.waiting()
                yield ev.done("done", "", False)
            finally:
                finished.append(True)

        held.append(turn())
        return held[-1]

    monkeypatch.setattr(pipeline, "voice_turn", voice_turn)
    response = _post(auth_client, audio=_wav())
    first = next(iter(response.streaming_content))
    assert b"waiting" in (first if isinstance(first, bytes) else first.encode())
    response.close()
    assert finished == [True]


# ---- 3. metering leaks --------------------------------------------------------------------------


def _tts_cost(user) -> int:
    """What this member's agent turns were metered for speech out, in micro-dollars."""
    from django.db.models import Sum

    rows = LlmUsage.objects.filter(user=user, feature="agent_turn", model="tts")
    return int(rows.aggregate(n=Sum("cost_estimate_micro_usd"))["n"] or 0)


def test_speech_cancelled_in_flight_is_still_metered(
    auth_client, engine, household_user, monkeypatch
):
    # Every filler line already in memory, so the only speech this turn pays for is the answer's
    # (review v1b: with an empty cache the filler lines alone made the cost above zero).
    full = FillerAudio(spawn=lambda work: None)
    for line in full.default_lines():
        full._audio[(_voice(household_user), line)] = b"\x00\x00"
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", full)
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
    rows = LlmUsage.objects.filter(user=household_user, feature="agent_turn", model="tts")
    # The answer's own speech, cut short with no usage reported: metered from the audio it made.
    assert sum(r.output_tokens or 0 for r in rows) > 0


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
    # Nothing in memory: the lines this turn sends for are fetched (here at once) and metered too.
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: work()))
    _events(_post(auth_client, audio=_wav()))
    assert tts_cost() > answer_only > 0


def _voice(user) -> str:
    from assistant import voice_engine

    return voice_engine.voice_for(user)


# ---- 5. contract fields (voice wire v1) ---------------------------------------------------------


def test_done_carries_the_contract_version_and_the_engine_timing(auth_client, engine):
    events = _events(_post(auth_client, audio=_wav()))
    done = events[-1][1]
    assert done["contract_version"] == 1
    timing = done["timing"]
    assert {"heard_ms", "first_sentence_ms", "first_audio_ms", "stt_done_ms"} <= set(timing)
    assert all(isinstance(v, int) and v >= 0 for v in timing.values())


def test_every_line_has_an_id_and_its_audio_says_which_line_it_is(auth_client, engine):
    engine.rounds = [m.say("One thing first. Then a second thing.")]
    events = _events(_post(auth_client, audio=_wav()))
    sentences = [d for k, d in events if k == "sentence"]
    assert [d["role"] for d in sentences] == ["answer", "answer"]
    ids = [d["line_id"] for d in sentences]
    assert all(LINE_ID.match(i) for i in ids) and len(set(ids)) == 2
    audio_ids = {d["line_id"] for k, d in events if k == "audio"}
    assert audio_ids == set(ids)


def test_the_crisis_line_has_an_id_and_a_role(auth_client, engine):
    engine.speech = FakeSpeech(heard="I want to die")
    events = _events(_post(auth_client, audio=_wav()))
    (line,) = [d for k, d in events if k == "sentence"]
    assert line["role"] == "answer" and LINE_ID.match(line["line_id"])
    assert {d["line_id"] for k, d in events if k == "audio"} == {line["line_id"]}


def test_a_filler_line_has_its_own_id(auth_client, engine, household_user, monkeypatch):
    from assistant import fillers
    from engine_mcp.tools import ToolResult

    audio = FillerAudio(spawn=lambda work: None)
    for line in fillers.lines_for("web_search"):
        audio._audio[(_voice(household_user), line)] = b"\x02\x00" * 240
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", audio)
    monkeypatch.setattr(
        "agents.legion.gateway.run_engine_tool", lambda *a, **k: ToolResult(text="Sunny today.")
    )
    engine.rounds = [m.call("web_search", {"query": "weather"}), m.say("Sunny.")]
    events = _events(_post(auth_client, audio=_wav()))
    (filler,) = [d for k, d in events if k == "filler"]
    assert LINE_ID.match(filler["line_id"])
    assert any(
        k == "audio" and d.get("filler") and d["line_id"] == filler["line_id"] for k, d in events
    )


def test_a_clip_the_engine_never_sent_is_noise_and_an_empty_transcript_is_no_speech(
    auth_client, engine
):
    class Gate(FakeSpeech):
        def transcribe(self, pcm):
            self.transcribed.append(len(pcm))
            return Heard("", len(pcm) // 32, 0, gated=True)  # the gate kept it: nothing sent

    engine.speech = Gate()
    assert _events(_post(auth_client, audio=_wav()))[-1][1]["ignored"] == "noise"
    engine.speech = FakeSpeech(heard="")
    assert _events(_post(auth_client, audio=_wav()))[-1][1]["ignored"] == "no_speech"


def test_refusals_carry_the_contract_codes(auth_client, engine, monkeypatch):
    response = _post(auth_client, transcript="hi", contract_version="2")
    assert response.status_code == 409 and response.data["code"] == "contract_unsupported"
    monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    response = _post(auth_client, transcript="hi")
    assert response.status_code == 503 and response.data["code"] == "no_key"


def test_a_throttled_turn_says_throttled(auth_client, engine, monkeypatch):
    from agents import views

    monkeypatch.setattr(
        views.AgentTurnThrottle, "THROTTLE_RATES", {"agents_turn": "1/min", "agents_say": "1/min"}
    )
    assert _post(auth_client, transcript="hi").status_code == 200
    response = _post(auth_client, transcript="hi again")
    assert response.status_code == 429 and response.data["code"] == "throttled"
