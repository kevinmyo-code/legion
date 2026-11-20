"""The blind review's flag-ON findings on the agents door (2026-11-19,
`.legion/reports/review-engine-voice-v1-1119.md`): the crisis line, the speech cost bounds, cancel
and disconnect, the first event, the help text and the schema. Google is never called; every
household, clip and word here is invented."""

# ruff: noqa: F811 - the `engine` fixture is imported from the turn tests and named in each test

from __future__ import annotations

import re
import threading
from pathlib import Path

import pytest
import yaml
from django.conf import settings
from django.core.cache import cache

from agents.models import AgentTurn
from agents.runtime import model as m
from llm.models import LlmUsage
from tests.test_engine_voice_turn import FakeSpeech, _events, _post, _wav, engine  # noqa: F401

pytestmark = pytest.mark.django_db

CARD_FILE = (
    Path(__file__).resolve().parents[2]
    / "headunit/app/src/main/java/com/kevin/midnightai/ui/CrisisCard.kt"
)


def _card_words() -> str:
    """The head unit crisis card's two text blocks, as one line: the words the engine says."""
    text = CARD_FILE.read_text(encoding="utf-8")
    body = text[text.index("Column(") : text.index('"DISMISS"')]
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
    return " ".join(p.replace("\\n", " ") for p in parts).replace("  ", " ").strip()


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


# ---- 1. a crisis hit says one fixed line, with the help number ----------------------------------


@pytest.mark.skipif(not CARD_FILE.exists(), reason="the head unit source is not in this checkout")
def test_a_crisis_hit_says_the_card_line_once_and_shows_the_card(auth_client, engine):
    line = _card_words()
    assert "988" in line
    engine.speech = FakeSpeech(heard="Honestly I want to die")
    events = _events(_post(auth_client, audio=_wav()))
    kinds = [k for k, _ in events]
    assert [d for k, d in events if k == "sentence"] == [{"text": line}]
    assert [d for k, d in events if k == "crisis"] == [{"text": line}]
    assert [text for text, _ in engine.speech.spoken] == [line]
    assert any(k == "audio" for k in kinds)
    assert events[-1][0] == "done" and events[-1][1]["status"] == "crisis"
    assert events[-1][1]["end"] is True
    assert engine.models == [] and not AgentTurn.objects.exists()


@pytest.mark.skipif(not CARD_FILE.exists(), reason="the head unit source is not in this checkout")
def test_a_typed_crisis_gets_the_same_line_in_text(auth_client, engine):
    events = _events(
        auth_client.post("/api/agents/turn", {"transcript": "I'm suicidal"}, format="json")
    )
    assert [d for k, d in events if k == "crisis"] == [{"text": _card_words()}]
    assert [d for k, d in events if k == "sentence"] == [{"text": _card_words()}]
    assert engine.models == []


# ---- 3. speech cost: bounded per turn, per day, and stopped by a cancel or a hang-up -----------


def test_a_long_answer_is_spoken_only_up_to_the_turns_bound(auth_client, engine):
    sentence = "This is one more fairly long sentence of the answer that goes on a while."
    engine.rounds = [m.say(" ".join([sentence] * 40))]
    events = _events(_post(auth_client, audio=_wav()))
    spoken = "".join(text for text, _ in engine.speech.spoken)
    assert len(spoken) <= 1_500
    assert ("speech_stopped", {"reason": "limit"}) in events
    # Every sentence is still shown.
    assert sum(1 for k, _ in events if k == "sentence") == 40


def test_with_no_cap_set_a_member_is_still_bounded_by_a_code_default(
    auth_client, engine, household_user, monkeypatch
):
    monkeypatch.delenv("LEGION_MEMBER_DAILY_CAP", raising=False)
    LlmUsage.objects.create(
        household=household_user.household,
        user=household_user,
        feature="agent_turn",
        model="tts",
        cost_estimate_micro_usd=50_000_000,
    )
    response = _post(auth_client, audio=_wav())
    assert response.status_code == 429 and response.data["code"] == "member_cap"
    assert engine.speech.transcribed == []
    response = _post(auth_client, kind="say", text="Your timer is done.")
    assert response.status_code == 429 and engine.speech.spoken == []


def test_say_has_its_own_rate_limit(auth_client, engine):
    rate = settings.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"].get("agents_say", "10/min")
    allowed = int(rate.split("/")[0])
    codes = [
        _post(auth_client, kind="say", text=f"Line {i}.").status_code for i in range(allowed + 1)
    ]
    assert codes[:allowed] == [200] * allowed
    assert codes[-1] == 429


def test_the_cancel_endpoint_stops_the_speech(auth_client, engine):
    engine.rounds = [m.say("One. Two. Three. Four. Five. Six.")]
    response = _post(auth_client, audio=_wav())
    assert response.status_code == 200
    seen = []
    for chunk in response.streaming_content:
        block = chunk.decode() if isinstance(chunk, bytes) else chunk
        seen.append(block)
        if block.startswith("event: sentence"):
            # The member's cancel lands while the answer is being spoken.
            turn = AgentTurn.objects.get()
            assert auth_client.post(f"/api/agents/turn/{turn.pk}/cancel").status_code == 200
    # The first words went to speech at once; nothing after the cancel did.
    assert len(engine.speech.spoken) == 1


def test_a_hang_up_stops_the_speech_that_is_playing(auth_client, engine):
    stopped = threading.Event()
    produced = []

    class SlowSpeech(FakeSpeech):
        def speak(self, text, voice, spoken):
            self.spoken.append((text, voice))
            try:
                for i in range(200):
                    produced.append(i)
                    stopped.wait(0.005)
                    yield b"\x01\x00" * 480
            finally:
                stopped.set()

    engine.speech = SlowSpeech()
    response = _post(auth_client, audio=_wav())
    for chunk in response.streaming_content:
        block = chunk.decode() if isinstance(chunk, bytes) else chunk
        if block.startswith("event: audio"):
            break
    response.close()
    assert stopped.wait(5.0)
    assert len(produced) < 200


# ---- 8. the first event does not wait for speech-to-text ----------------------------------------


def test_an_audio_turns_first_event_goes_before_transcription(auth_client, engine):
    response = _post(auth_client, audio=_wav())
    first = next(iter(response.streaming_content))
    first = first.decode() if isinstance(first, bytes) else first
    assert first.startswith("event: waiting")
    assert engine.speech.transcribed == []


# ---- 5 and 7. the help text is true; the schema lists what the view takes -----------------------


def _turn_request() -> dict:
    schema = yaml.safe_load((Path(settings.BASE_DIR) / "openapi.yaml").read_text(encoding="utf-8"))
    return schema["paths"]["/api/agents/turn"]["post"]["requestBody"]["content"]


def test_the_schema_lists_every_body_the_view_parses_and_audio_is_binary():
    content = _turn_request()
    assert set(content) == {
        "application/json",
        "multipart/form-data",
        "application/x-www-form-urlencoded",
    }
    schema = yaml.safe_load((Path(settings.BASE_DIR) / "openapi.yaml").read_text(encoding="utf-8"))
    ref = content["multipart/form-data"]["schema"]["$ref"].rsplit("/", 1)[-1]
    audio = schema["components"]["schemas"][ref]["properties"]["audio"]
    assert audio["format"] == "binary"


def test_the_help_text_says_the_transcript_is_kept():
    from agents.views import TurnRequestSerializer

    help_text = TurnRequestSerializer().fields["audio"].help_text
    assert "Never stored" not in help_text
    assert "audio is never stored" in help_text.lower()
    assert "transcript is kept" in help_text.lower()
