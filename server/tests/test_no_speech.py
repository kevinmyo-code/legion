# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Noise must never become a reply (2026-11-17: on Midcon, a transcriber hint made room noise come
back as invented sentences, and the app answered them and listened again: it talked to itself).

- The transcriber's instruction carries no literal examples and no sample figures.
- Audio with no voice in it is never sent to a paid call: loudness first, then a voiced-speech
  check on the engine that a client cannot skip.
- No speech is silence: no reply, no filler, nothing on screen, the window not used up, and only
  a paid transcription (if one was made) kept from the demo budget.
Synthesised signals only.
"""

from __future__ import annotations

import math
import random
import re
import struct

import pytest

from agents.runtime.model import FakeModel
from assistant import speech
from assistant.speech import GeminiSpeech, voiced_speech
from demo.models import DemoBudgetDay
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

pytestmark = pytest.mark.django_db
RATE = 16_000


def _pcm(values) -> bytes:
    return struct.pack(f"<{len(values)}h", *(max(-32767, min(32767, int(v))) for v in values))


def _speechlike(seconds=2.0, peak=5000):
    out = []
    for i in range(int(RATE * seconds)):
        t = i / RATE
        f0 = 120 + 30 * math.sin(2 * math.pi * 0.7 * t)
        syllable = 0.55 + 0.45 * math.sin(2 * math.pi * 4 * t)
        out.append(
            sum(math.sin(2 * math.pi * f0 * h * t) / h for h in range(1, 6)) * peak * syllable
        )
    return out


def _pink(seconds=2.0, scale=1500, seed=3):
    rnd = random.Random(seed)
    b = [0.0] * 6
    out = []
    for _ in range(int(RATE * seconds)):
        w = rnd.gauss(0, 1)
        b[0] = 0.99886 * b[0] + w * 0.0555179
        b[1] = 0.99332 * b[1] + w * 0.0750759
        b[2] = 0.96900 * b[2] + w * 0.1538520
        b[3] = 0.86650 * b[3] + w * 0.3104856
        b[4] = 0.55000 * b[4] + w * 0.5329522
        b[5] = -0.7616 * b[5] - w * 0.0168980
        out.append((sum(b) + w * 0.5362) * scale)
    return out


NOISE = {
    "white_low": lambda: [random.Random(1).gauss(0, 400) for _ in range(RATE * 2)],
    "white_mid": lambda: [random.Random(2).gauss(0, 2500) for _ in range(RATE * 2)],
    "white_high": lambda: [random.Random(3).gauss(0, 9000) for _ in range(RATE * 2)],
    "pink": _pink,
    "hum_100hz": lambda: [6000 * math.sin(2 * math.pi * 100 * i / RATE) for i in range(RATE * 2)],
}


def test_the_transcriber_instruction_has_no_examples_or_sample_figures():
    for text in (speech.STT_INSTRUCTION, speech.STT_ASK):
        assert not re.search(r"\d{3,}", text), text
        assert not re.search(r"\d", text), text
        assert '"' not in text and "e.g." not in text.lower() and "for example" not in text.lower()


@pytest.mark.parametrize("name", sorted(NOISE))
def test_loud_noise_is_not_voiced_speech(name):
    assert not voiced_speech(_pcm(NOISE[name]()))


def test_a_voice_is_voiced_speech():
    assert voiced_speech(_pcm(_speechlike()))


def test_noise_never_reaches_a_paid_transcription():
    class Counting:
        calls = 0

        def lines(self, url, headers, data, timeout):
            Counting.calls += 1
            return iter(())

    real = GeminiSpeech(lambda: "key", stt_model="stt", transport=Counting())
    for name, make in NOISE.items():
        heard = real.transcribe(_pcm(make()))
        assert heard.text == "" and heard.prompt_tokens == 0, name
    assert real.transcribe(b"\x00\x00" * RATE * 2).text == ""
    assert Counting.calls == 0


def test_no_speech_is_silence_on_the_page(member, engine):
    engine.speech = FakeSpeech(heard="")
    engine.model = FakeModel([])
    events = _events(_post(member, audio=PCM))
    kinds = [k for k, _ in events]
    assert "heard" not in kinds and "sentence" not in kinds and "filler" not in kinds
    assert "audio" not in kinds
    done = _of(events, "done")[0]
    assert done["ignored"] == "no_speech" and "words" not in done
    assert engine.model.requests == []


def test_no_speech_keeps_nothing_of_the_demo_budget_when_nothing_was_paid(visitor, engine):
    from assistant.speech import Heard

    class Gated(FakeSpeech):
        def transcribe(self, pcm):
            # What the engine's gate returns: no voice, so no paid call (no tokens).
            return Heard("", len(pcm) // 32, 0)

    engine.speech = Gated()
    _events(_post(visitor, audio=PCM))
    day = DemoBudgetDay.objects.get()
    assert day.reserved_cents == 0


# ---- round 2 speech review 10: the transcriber's own no-speech answers are no speech ------------


@pytest.mark.parametrize(
    ("raw", "words"),
    [
        ("[inaudible]", ""),
        ("(silence)", ""),
        ("*music*", ""),
        ("No speech.", ""),
        ("...", ""),
        ("Transcribe this audio.", ""),
        ("Write down exactly what the speaker says", ""),
        # Round 3 speech, small c: a marker counts only as the WHOLE transcript; words around it
        # are real speech and are never cut.
        ("[laughs] okay sure", "[laughs] okay sure"),
        ("No speech detected.", ""),
        ("No audible speech.", ""),
        ("Rates went up (two percent) this year.", "Rates went up (two percent) this year."),
        ("Nothing.", "Nothing."),
        ('"Add milk to the list."', "Add milk to the list."),
    ],
)
def test_a_no_speech_answer_or_an_echo_of_the_instruction_is_no_speech(raw, words):
    assert speech.clean_transcript(raw) == words


def test_the_transcriber_defaults_to_english():
    assert "English unless" in speech.STT_INSTRUCTION
