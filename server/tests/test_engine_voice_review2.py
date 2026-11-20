"""The v1b review's list for the agents door (2026-11-20, `.legion/reports/
review-engine-voice-v1-1119.md`, "Review v1b 4f4088ad"): line ids before a turn, the throttle's
`Retry-After`, `done.timing`, the noise verdict, filler metering, the contract version floor and
the character bound on what is spoken. Google is never called; every household, clip and word here
is invented."""

# ruff: noqa: F811 - the `engine` fixture is imported from the turn tests and named in each test

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from agents.runtime import model as m
from agents.voice import pipeline
from agents.voice.filler_audio import FillerAudio
from agents.voice.speech import GeminiSpeech, SpeechFailure
from llm.models import LlmUsage
from tests.test_engine_voice_turn import FakeSpeech, _events, _post, _wav, engine  # noqa: F401

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


def _full_filler_cache(user, monkeypatch):
    from assistant import voice_engine

    full = FillerAudio(spawn=lambda work: None)
    for line in full.default_lines():
        full._audio[(voice_engine.voice_for(user), line)] = b"\x00\x00"
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", full)


def _tts_rows(user):
    return LlmUsage.objects.filter(user=user, feature="agent_turn", model="tts")


# ---- 3. a line said before any turn row has an id of its own in the conversation ---------------


def test_lines_said_before_a_turn_have_ids_unique_in_the_conversation(auth_client, engine):
    first = _events(_post(auth_client, transcript="hi"))
    conversation = first[0][1]["conversation_id"]
    turn_ids = {d["line_id"] for k, d in first if k == "sentence"}
    ids = []
    for text in ("Your timer is done.", "Your tea is ready."):
        events = _events(_post(auth_client, kind="say", text=text, conversation_id=conversation))
        ids += [d["line_id"] for k, d in events if k == "sentence"]
    engine.speech = FakeSpeech(heard="I want to die")
    events = _events(_post(auth_client, audio=_wav(), conversation_id=conversation))
    ids += [d["line_id"] for k, d in events if k == "sentence"]
    assert len(ids) == 3 and len(set(ids)) == 3
    assert not set(ids) & turn_ids


# ---- 4. the throttle's 429 keeps Retry-After -------------------------------------------------


def test_a_throttled_turn_says_when_to_try_again(auth_client, engine, monkeypatch):
    from agents import views

    monkeypatch.setattr(
        views.AgentTurnThrottle, "THROTTLE_RATES", {"agents_turn": "1/min", "agents_say": "1/min"}
    )
    assert _post(auth_client, transcript="hi").status_code == 200
    response = _post(auth_client, transcript="hi again")
    assert response.status_code == 429 and response.data["code"] == "throttled"
    assert int(response["Retry-After"]) >= 1


# ---- 5. done.timing: offsets from the request's arrival, all of one kind ----------------------


def test_done_timing_is_offsets_from_the_request_and_nothing_else(auth_client, engine):
    timing = _events(_post(auth_client, audio=_wav()))[-1][1]["timing"]
    assert set(timing) == {"stt_done_ms", "heard_ms", "first_sentence_ms", "first_audio_ms"}
    assert timing["stt_done_ms"] <= timing["heard_ms"] <= timing["first_sentence_ms"]
    assert timing["stt_done_ms"] <= timing["first_audio_ms"]


# ---- 6. noise is the gate's own verdict --------------------------------------------------------


class _NoUsageTransport:
    """Google answering a request with no words and no usage block."""

    def __init__(self):
        self.sent = 0

    def lines(self, url, headers, data, timeout):
        self.sent += 1
        body = {"candidates": [{"content": {"parts": [{"text": ""}]}}]}
        yield f"data: {json.dumps(body)}".encode()


def _speech_with(transport):
    return GeminiSpeech(lambda: "not-a-real-key", stt_model="fake", transport=transport)


def test_a_clip_sent_to_speech_to_text_is_never_noise(auth_client, engine, monkeypatch):
    transport = _NoUsageTransport()
    engine.speech = _speech_with(transport)
    # A clip loud and voiced enough to be sent (the gates are the speech module's own).
    monkeypatch.setattr("agents.voice.speech.voiced_speech", lambda pcm: True)
    monkeypatch.setattr("agents.voice.speech.LOUDNESS_GATE_RMS", 0)
    done = _events(_post(auth_client, audio=_wav()))[-1][1]
    assert transport.sent == 1
    assert done["ignored"] == "no_speech"


def test_a_clip_the_gate_kept_back_is_noise(auth_client, engine):
    transport = _NoUsageTransport()
    engine.speech = _speech_with(transport)
    # The test clip is a flat, quiet tone: under the loudness gate, so never sent.
    done = _events(_post(auth_client, audio=_wav()))[-1][1]
    assert transport.sent == 0
    assert done["ignored"] == "noise"


# ---- 7. filler lines: measured tokens when reported, nothing when the fetch failed -------------


def test_filler_lines_are_metered_at_their_measured_tokens(
    auth_client, engine, household_user, monkeypatch
):
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: work()))
    _events(_post(auth_client, audio=_wav()))
    # FakeSpeech reports 5 in and 50 out for every request: the answer's and each line's.
    calls = len(engine.speech.spoken)
    assert calls > 1
    assert sum(r.output_tokens or 0 for r in _tts_rows(household_user)) == 50 * calls


def test_a_filler_line_that_could_not_be_fetched_costs_nothing(
    auth_client, engine, household_user, monkeypatch
):
    class FillersFail(FakeSpeech):
        def speak(self, text, voice, spoken):
            if "Nothing is on" not in text:
                raise SpeechFailure("tts_failed")
            yield from super().speak(text, voice, spoken)

    engine.speech = FillersFail()
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: work()))
    _events(_post(auth_client, audio=_wav()))
    # Only the answer's one request is paid for.
    assert sum(r.output_tokens or 0 for r in _tts_rows(household_user)) == 50


# ---- 8. the contract version floor -----------------------------------------------------------


@pytest.mark.parametrize("version", ["0", "2"])
def test_only_contract_1_is_served_because_no_version_0_ever_existed(auth_client, engine, version):
    response = _post(auth_client, transcript="hi", contract_version=version)
    assert response.status_code == 409 and response.data["code"] == "contract_unsupported"
    assert _post(auth_client, transcript="hi", contract_version="1").status_code == 200


# ---- 9. the character bound counts what is sent to speech -------------------------------------


def test_the_character_bound_counts_the_words_spoken_not_the_words_shown(
    auth_client, engine, household_user, monkeypatch
):
    _full_filler_cache(household_user, monkeypatch)
    # Each link is shown as 12 characters and spoken as "the link on screen" (18).
    sentence = " ".join(["http://x.io/"] * 36) + " end."
    assert len(sentence) * 3 <= 1_500
    engine.rounds = [m.say(" ".join([sentence] * 3))]
    _events(_post(auth_client, audio=_wav()))
    spoken = [text for text, _ in engine.speech.spoken]
    assert sum(map(len, spoken)) <= 1_500
