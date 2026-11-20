"""`POST /api/agents/turn` with audio (V1, dark): the agents door hears and speaks through the
engine's speech stage (`agents/voice`), behind `LEGION_AGENTS_TURN` like the rest of the route.

Google is never called: the text model is `FakeModel`, speech a fake with `GeminiSpeech`'s two
methods. Every household, clip and word here is invented."""

from __future__ import annotations

import io
import json
import wave

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from agents.legion.flags import STT_HEDGE_ENV, TURN_ENV
from agents.legion.lines import MISSED_LINE, STT_FAILED
from agents.models import AgentTurn
from agents.runtime import model as m
from agents.voice import hedge, pipeline
from agents.voice.filler_audio import FillerAudio
from agents.voice.speech import Heard, SpeechFailure
from assistant import fillers
from llm.models import LlmUsage

pytestmark = pytest.mark.django_db


class FakeSpeech:
    def __init__(self, heard="What is on the calendar today?", fail=None):
        self.heard = heard
        self.fail = fail
        self.transcribed: list[int] = []
        self.spoken: list[tuple[str, str]] = []

    def transcribe(self, pcm):
        self.transcribed.append(len(pcm))
        if self.fail:
            raise SpeechFailure(self.fail)
        return Heard(self.heard, len(pcm) // 32, 7, 40, 6)

    def speak(self, text, voice, spoken):
        self.spoken.append((text, voice))
        spoken.first_audio_ms = 3
        spoken.audio_ms = 20
        spoken.prompt_tokens, spoken.output_tokens = 5, 50
        yield b"\x01\x00" * 480


def _wav(seconds: float = 1.0, rate: int = 16_000, channels: int = 1, width: int = 2) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(b"\x10\x00" * channels * int(rate * seconds) * (width // 2 or 1))
    return out.getvalue()


def _events(response) -> list[tuple[str, dict]]:
    assert response.status_code == 200, (response.status_code, getattr(response, "data", None))
    body = b"".join(response.streaming_content).decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


@pytest.fixture
def engine(monkeypatch):
    """The fakes the agents door is handed: set `engine.rounds` / `engine.speech` first."""

    class Engine:
        rounds = [m.say("Nothing is on today.")]
        speech = FakeSpeech()
        models: list = []

    def model_factory(key):
        model = m.FakeModel(Engine.rounds)
        Engine.models.append(model)
        return model

    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    monkeypatch.delenv(STT_HEDGE_ENV, raising=False)
    monkeypatch.setattr("agents.views.GeminiClient", model_factory)
    monkeypatch.setattr("agents.views.GeminiSpeech", lambda key, stt_model: Engine.speech)
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: None))
    return Engine


def _post(client, **fields):
    if "audio" in fields:
        fields["audio"] = SimpleUploadedFile("u.wav", fields["audio"], "audio/wav")
    return client.post("/api/agents/turn", fields, format="multipart")


# ---- dark ---------------------------------------------------------------------------------------


def test_switched_off_audio_is_refused_and_never_transcribed(auth_client, engine, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    response = _post(auth_client, audio=_wav())
    assert response.status_code == 404
    assert engine.speech.transcribed == [] and engine.models == []
    assert not AgentTurn.objects.exists()


# ---- a spoken turn ------------------------------------------------------------------------------


def test_audio_in_is_heard_answered_and_spoken(auth_client, engine):
    events = _events(_post(auth_client, audio=_wav(1.0)))
    kinds = [k for k, _ in events]
    assert kinds[0] == "turn" and kinds[1] == "heard" and kinds[-1] == "done"
    assert events[1][1] == {"text": "What is on the calendar today?"}
    assert ("sentence", {"text": "Nothing is on today."}) in events
    audio = [d for k, d in events if k == "audio"]
    assert audio and all(d["rate"] == 24_000 and d["pcm"] for d in audio)
    assert kinds.index("sentence") < kinds.index("audio") < kinds.index("done")
    # The WAV header is not audio: the transcriber got exactly the samples.
    assert engine.speech.transcribed == [32_000]
    assert [text for text, _ in engine.speech.spoken] == ["Nothing is on today."]
    turn = AgentTurn.objects.get()
    assert turn.transcript == "What is on the calendar today?"
    # Speech is metered on the member's cap, as on the web door.
    models = set(LlmUsage.objects.values_list("model", flat=True))
    assert {"stt", "tts"} <= models


def test_a_typed_transcript_still_answers_in_text_only(auth_client, engine):
    events = _events(auth_client.post("/api/agents/turn", {"transcript": "hi"}, format="json"))
    kinds = [k for k, _ in events]
    assert "audio" not in kinds and "heard" not in kinds and kinds[-1] == "done"
    assert engine.speech.spoken == [] and engine.speech.transcribed == []


def test_a_transcript_with_speak_is_spoken(auth_client, engine):
    events = _events(_post(auth_client, transcript="hi", speak="true"))
    assert any(k == "audio" for k, _ in events)
    assert engine.speech.transcribed == []


def test_no_speech_in_the_clip_is_silence_and_no_model_call(auth_client, engine):
    engine.speech = FakeSpeech(heard="")
    events = _events(_post(auth_client, audio=_wav()))
    assert events[-1] == (
        "done",
        {"status": "nothing_heard", "final_text": "", "writes_ran": False, "ignored": "no_speech"},
    )
    assert engine.models == [] and not AgentTurn.objects.exists()


def test_a_failed_transcription_says_the_miss_and_ends_in_words(auth_client, engine):
    engine.speech = FakeSpeech(fail="stt_unreachable")
    events = _events(_post(auth_client, audio=_wav()))
    assert events[-1] == ("error", {"words": STT_FAILED})
    assert [text for text, _ in engine.speech.spoken] == [MISSED_LINE]
    assert any(k == "audio" for k, _ in events)
    assert engine.models == [] and not AgentTurn.objects.exists()


# ---- limits and shapes --------------------------------------------------------------------------


def test_an_utterance_over_28_seconds_is_refused_before_any_speech(auth_client, engine):
    response = _post(auth_client, audio=_wav(28.5))
    assert response.status_code == 413 and response.data["code"] == "too_long"
    assert engine.speech.transcribed == []


def test_28_seconds_is_still_taken(auth_client, engine):
    _events(_post(auth_client, audio=_wav(28.0)))
    assert engine.speech.transcribed == [28 * 32_000]


@pytest.mark.parametrize(
    "clip",
    [
        b"\x10\x00" * 16_000,  # raw PCM, no header
        _wav(rate=24_000),
        _wav(channels=2),
        _wav(width=1),
    ],
    ids=["raw_pcm", "24khz", "stereo", "8bit"],
)
def test_anything_but_16khz_mono_pcm16_wav_is_refused(auth_client, engine, clip):
    response = _post(auth_client, audio=clip)
    assert response.status_code == 400 and response.data["code"] == "bad_audio"
    assert engine.speech.transcribed == []


@pytest.mark.parametrize(
    "fields",
    [{}, {"transcript": "hi", "audio": "BOTH"}, {"kind": "say"}, {"kind": "nonsense"}],
)
def test_one_utterance_per_turn(auth_client, engine, fields):
    if fields.get("audio") == "BOTH":
        fields["audio"] = _wav()
    response = _post(auth_client, **fields)
    assert response.status_code == 400 and response.data["code"] == "bad_request"
    assert engine.speech.transcribed == [] and engine.models == []


def test_over_the_member_cap_nothing_is_transcribed(auth_client, engine, monkeypatch):
    monkeypatch.setattr("llm.cap.over_cap", lambda user: True)
    response = _post(auth_client, audio=_wav())
    assert response.status_code == 429 and response.data["code"] == "member_cap"
    assert engine.speech.transcribed == []


# ---- kinds --------------------------------------------------------------------------------------


def test_say_speaks_the_line_with_no_model_and_no_turn_row(auth_client, engine):
    events = _events(_post(auth_client, kind="say", text="Your timer is done."))
    assert ("sentence", {"text": "Your timer is done."}) in events
    assert any(k == "audio" for k, _ in events)
    assert events[-1][0] == "done" and events[-1][1]["status"] == "say"
    assert engine.models == [] and not AgentTurn.objects.exists()


@pytest.mark.parametrize("kind", ["greeting", "opener", "check_in", "proactive"])
def test_kinds_not_built_yet_are_refused_in_words(auth_client, engine, kind):
    response = _post(auth_client, kind=kind)
    assert response.status_code == 400 and response.data["code"] == "kind_not_built"
    assert kind in response.data["detail"]
    assert engine.speech.transcribed == [] and engine.models == []


# ---- the crisis pre-check -----------------------------------------------------------------------


def test_distress_heard_calls_no_model_and_keeps_no_words(auth_client, engine):
    engine.speech = FakeSpeech(heard="I want to die")
    events = _events(_post(auth_client, audio=_wav()))
    kinds = [k for k, _ in events]
    # One fixed line, never the model's (`tests/test_engine_voice_review.py` pins its words).
    assert "crisis" in kinds and kinds.count("sentence") == 1
    assert events[-1][0] == "done" and events[-1][1]["status"] == "crisis"
    assert events[-1][1]["end"] is True and events[-1][1]["final_text"] == ""
    assert engine.models == [] and not AgentTurn.objects.exists()


def test_a_typed_crisis_transcript_is_checked_too(auth_client, engine):
    events = _events(
        auth_client.post("/api/agents/turn", {"transcript": "I'm suicidal"}, format="json")
    )
    assert [k for k, _ in events][-2:] == ["crisis", "done"]
    assert engine.models == []


def test_suicide_doors_is_car_talk(auth_client, engine):
    engine.speech = FakeSpeech(heard="Does it have suicide doors?")
    events = _events(_post(auth_client, audio=_wav()))
    assert "crisis" not in [k for k, _ in events] and engine.models


# ---- the hedge is an option, off by default -----------------------------------------------------


def test_the_hedge_is_off_by_default_and_on_by_its_switch(auth_client, engine, monkeypatch):
    seen = []
    real = hedge.HedgedSpeech

    def wrap(inner):
        seen.append(inner)
        return real(inner)

    monkeypatch.setattr("agents.views.HedgedSpeech", wrap)
    _events(_post(auth_client, audio=_wav()))
    assert seen == []
    monkeypatch.setenv(STT_HEDGE_ENV, "on")
    engine.speech = FakeSpeechOnce()
    _events(_post(auth_client, audio=_wav()))
    assert len(seen) == 1


class FakeSpeechOnce(FakeSpeech):
    """What the hedge drives: one request at a time."""

    stt_sent = 0

    def _transcribe_once(self, pcm):
        self.stt_sent += 1
        return self.transcribe(pcm)


# ---- figures and fillers ------------------------------------------------------------------------


def test_say_speaks_its_figures_exactly(auth_client, engine):
    _events(_post(auth_client, kind="say", text="The total is $1,962.48 today."))
    assert [text for text, _ in engine.speech.spoken] == ["The total is $1,962.48 today."]


def test_a_slow_tool_gets_its_filler_line_from_memory(auth_client, engine, monkeypatch):
    audio = FillerAudio(spawn=lambda work: None)
    audio._audio = {
        (voice, one): b"\x02\x00" * 240
        for voice in ("Puck", "Kore", "Charon")
        for one in fillers.lines_for("web_search")
    }
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", audio)
    monkeypatch.setattr("assistant.voice_engine.voice_for", lambda user: "Puck")
    monkeypatch.setattr("agents.views.voice_engine.voice_for", lambda user: "Puck")
    from engine_mcp.tools import ToolResult

    # The search itself never leaves the process.
    monkeypatch.setattr(
        "agents.legion.gateway.run_engine_tool", lambda *a, **k: ToolResult(text="Sunny today.")
    )
    engine.rounds = [m.call("web_search", {"query": "weather"}), m.say("Sunny.")]
    events = _events(_post(auth_client, audio=_wav()))
    kinds = [k for k, _ in events]
    filler = [d for k, d in events if k == "filler"]
    assert (
        filler
        and filler[0]["tool"] == "web_search"
        and filler[0]["text"] in fillers.lines_for("web_search")
    )
    assert kinds.index("working") < kinds.index("filler") < kinds.index("sentence")
    assert any(k == "audio" and d.get("filler") for k, d in events)
