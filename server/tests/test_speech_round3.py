"""Blind review round 3, speech (`review-demo-voice-first-1117-speech-r3.md`): one test per finding,
written to fail before its fix. Ruling B stands: the failure mode is "spoken exactly", never
"spoken wrong", so heuristics are removed, not tuned. Google is never called; every word is
invented."""

from __future__ import annotations

import pytest

from assistant import cascade

# ---- S2: a model-worded greeting carries no figure in any form ----------------------------------


@pytest.mark.parametrize(
    "greeting",
    [
        "Groceries at nine hundred, wow.",
        "Four hundred on the nose this month.",
        "Half your grocery budget is gone.",
        "Your quiz is at nine tonight.",
        "That's your third workout this week.",
        "Three-quarters of the budget is spent.",
        "A quarter of the month is left.",
        "Dentist at noon.",
        "Twice this week already.",
        "Back at seven.",
    ],
)
def test_a_greeting_with_a_number_word_ordinal_fraction_or_clock_time_is_refused(greeting):
    assert cascade.greeting_states_a_figure(greeting)


@pytest.mark.parametrize(
    "greeting",
    [
        "Morning, Sam. One thing before you head out: the car wants a look.",
        "Evening. A quiet one, by the look of it.",
        "Good to see you. Nothing pressing today.",
    ],
)
def test_one_as_a_pronoun_or_article_is_allowed(greeting):
    assert not cascade.greeting_states_a_figure(greeting)


# ---- S3: a refused voice is decided from Google's words, for a few minutes, and a clip is kept
# ---- under the voice that actually spoke it ------------------------------------------------------

NEW_SHAPE_400 = "Gemini answered HTTP 400: Request contains an invalid argument."
NO_SUCH_VOICE_400 = (
    "Gemini answered HTTP 400: No matching speaker voice found for name: {name} and language:"
)


class _Voices:
    """Google as probed 2026-11-17: an unknown voice name is a generic 400 in the new shape and
    "No matching speaker voice" in the legacy shape. `stray` makes every request a 400 that is
    not about the voice."""

    def __init__(self, refused: str = "", stray: int = 0):
        self.refused = refused
        self.stray = stray
        self.bodies: list[dict] = []

    def lines(self, url, headers, data, timeout):
        import base64
        import json

        from agents.runtime.model import ModelFailure

        body = json.loads(data)
        self.bodies.append(body)
        shape = body["generationConfig"]["speechConfig"]["voiceConfig"]
        legacy = "prebuiltVoiceConfig" in shape
        name = shape.get("voice") or shape.get("prebuiltVoiceConfig", {}).get("voiceName")
        if self.stray:
            self.stray -= 1
            raise ModelFailure(NEW_SHAPE_400)
        if name == self.refused:
            raise ModelFailure(NO_SUCH_VOICE_400.format(name=name) if legacy else NEW_SHAPE_400)
        audio = base64.b64encode(b"\x01\x00" * 240).decode()
        part = {"inlineData": {"mimeType": "audio/l16", "data": audio}}
        yield "data: " + json.dumps({"candidates": [{"content": {"parts": [part]}}]})


def _shape(body) -> str:
    voice = body["generationConfig"]["speechConfig"]["voiceConfig"]
    return "legacy" if "prebuiltVoiceConfig" in voice else "new"


def test_a_stray_400_fails_one_sentence_and_marks_nothing():
    from assistant.speech import GeminiSpeech, SpeechFailure, Spoken

    transport = _Voices(stray=2)
    speech = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport)
    with pytest.raises(SpeechFailure):
        b"".join(speech.speak("Hello.", "en-gb-advisor-3", Spoken()))
    assert not speech.bad_voices
    stats = Spoken()
    assert b"".join(speech.speak("Again.", "en-gb-advisor-3", stats))
    assert _shape(transport.bodies[-1]) == "new"
    assert stats.voice == "en-gb-advisor-3"


def test_a_refused_voice_is_spoken_in_puck_for_a_few_minutes_then_tried_again():
    from assistant import speech as speech_module
    from assistant.speech import GeminiSpeech, Spoken

    now = [1000.0]
    transport = _Voices(refused="en-gb-advisor-3")
    speech = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport, clock=lambda: now[0])
    stats = Spoken()
    assert b"".join(speech.speak("Hello.", "en-gb-advisor-3", stats))
    assert stats.voice == "Puck"
    transport.refused = ""
    now[0] += speech_module.BAD_VOICE_S + 1
    stats = Spoken()
    assert b"".join(speech.speak("Again.", "en-gb-advisor-3", stats))
    assert stats.voice == "en-gb-advisor-3"


def test_a_clip_spoken_in_the_fallback_is_never_kept_under_the_members_voice():
    from assistant.speech import GeminiSpeech

    speech = GeminiSpeech(
        lambda: "key", stt_model="stt", transport=_Voices(refused="en-gb-advisor-3")
    )
    audio = cascade.FillerAudio(spawn=lambda work: work())
    line = cascade.SPOKEN_FAILURES[0]
    audio.warm(speech, "en-gb-advisor-3", [line])
    assert audio.get("en-gb-advisor-3", line) is None
