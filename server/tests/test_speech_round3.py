# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Blind review round 3, speech (`review-demo-voice-first-1117-speech-r3.md`): one test per finding,
written to fail before its fix. Ruling B stands: the failure mode is "spoken exactly", never
"spoken wrong", so heuristics are removed, not tuned. Google is never called; every word is
invented."""

from __future__ import annotations

import pytest

from assistant import cascade
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    engine,
    member,
    visitor,
    visitor_user,
)

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
        # Round 4 speech N2: a clock time with "one", "both", and a span of time.
        "Your quiz is at one tonight.",
        "Lunch at one, then the dentist.",
        "One tomorrow works for the call.",
        "Both lists are long today.",
        "Your trip is a week from now.",
        "The car is due in a month or so.",
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


def test_a_stray_400_marks_nothing_and_the_sentence_is_said_in_puck():
    # Round 4 N3: a failed sentence is said once in Puck before giving up; nothing is marked.
    from assistant.speech import GeminiSpeech, Spoken

    transport = _Voices(stray=2)
    speech = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport)
    first = Spoken()
    assert b"".join(speech.speak("Hello.", "en-gb-advisor-3", first))
    assert first.voice == "Puck"
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


# ---- S4: a long or multi-round answer is spoken to its end; when speech must stop early, the
# ---- closing honesty line is still said and the page is told ------------------------------------

LONG_ANSWER = " ".join(f"Point {n} is about the week ahead and what it holds." for n in range(1, 9))


def _two_tools_then(answer: str):
    from agents.runtime.model import FakeModel, call, say

    def lead(text, name):
        return say(text)[:2] + call(name)

    return FakeModel(
        [
            lead("Let me look at the calendar first.", "list_events"),
            lead("And the lists as well.", "list_checklists"),
            say(answer),
        ]
    )


@pytest.mark.django_db
def test_a_long_answer_after_two_tools_is_spoken_to_its_end(member, engine, monkeypatch):
    from tests.test_assistant_cascade import _events, _post

    # One request per sentence: 2 leads and 9 answer sentences, past the old cap of 6.
    monkeypatch.setattr(cascade, "SPEECH_GROUPING", "sentence")

    engine.model = _two_tools_then(LONG_ANSWER + " That is the lot.")
    events = _events(_post(member, text="Walk me through the week.", spoken="true"))
    spoken = " ".join(text for text, _voice in engine.speech.spoken)
    assert "That is the lot." in spoken
    assert not [e for k, e in events if k == "speech_stopped"]


@pytest.mark.django_db
def test_when_speech_stops_early_the_closing_line_is_said_and_the_page_is_told(
    visitor, engine, monkeypatch
):
    from demo import budget as demo_budget
    from tests.test_assistant_cascade import _events, _post

    monkeypatch.setattr(demo_budget, "reserve_speech_requests", lambda *a, **k: None)
    monkeypatch.setattr(cascade, "SPEECH_GROUPING", "sentence")
    # A claimed change with no write: the closing honesty line must still be heard.
    engine.model = _two_tools_then(LONG_ANSWER + " I've added it to your list.")
    events = _events(_post(visitor, text="Walk me through the week.", spoken="true"))
    spoken = [text for text, _voice in engine.speech.spoken]
    assert any(cascade.CHANGE_NOT_MADE in text for text in spoken), spoken
    assert [e for k, e in events if k == "speech_stopped"]


# ---- small b: only stage directions are stripped; other bracketed or starred words are kept -----


@pytest.mark.parametrize(
    ("text", "kept"),
    [
        ("That is *really* close.", "That is really close."),
        ("Per [Reuters] the rate held.", "Per Reuters the rate held."),
        ("Well [laughs] that went well.", "Well that went well."),
        ("Okay *sighs* fine.", "Okay fine."),
        ("Right [short pause] next one.", "Right next one."),
    ],
)
def test_only_stage_directions_are_stripped(text, kept):
    from assistant import speech_tags

    assert speech_tags.untagged(text) == kept


# ---- small d: the cached examples obey the one figure rule: exact, in digits ---------------------


def test_the_spoken_examples_write_every_figure_exactly_in_digits():
    import re

    examples = cascade.SPOKEN_EXAMPLES
    assert not re.search(r"\b(about|around|roughly|nearly|almost)\s+\$?\d", examples, re.I)
    words = re.compile(
        r"\b(two|three|four|five|six|seven|eight|nine|ten|twenty|hundred|thousand)\b", re.I
    )
    # Only inside the rule sentence at the end ("never more than three items") may a count be
    # a word; every example reply writes its figures in digits.
    replies = re.findall(r'-> "([^"]*)"', examples)
    assert replies and not [r for r in replies if words.search(r)]


# ---- finding 8 leftovers: the greeting's extra speech fetch and a transcription retry are
# ---- reserved before they can be paid for --------------------------------------------------------


@pytest.mark.django_db
def test_a_transcription_retry_and_the_greetings_fetch_are_reserved(visitor, engine, monkeypatch):
    from agents.runtime.model import FakeModel, say
    from demo import budget as demo_budget
    from tests.test_assistant_cascade import PCM, _events, _post

    asked = []
    real = demo_budget.reserve_cascade_turn

    def spy(**kwargs):
        asked.append(kwargs)
        return real(**kwargs)

    monkeypatch.setattr(demo_budget, "reserve_cascade_turn", spy)
    engine.model = FakeModel([say("Hi."), say("Hi.")])
    _events(_post(visitor, text="Hi.", spoken="true"))
    _events(_post(visitor, audio=PCM))
    typed, heard = asked
    assert heard["rounds"] == typed["rounds"] + 1  # one transcription retry
    asked.clear()
    _events(_post(visitor, greet="true"))
    missing = cascade.FILLER_AUDIO.missing("Puck")
    assert asked[0]["speech_requests"] == cascade.MAX_TTS_REQUESTS + missing + 1


# ---- the greeting stays short: about 14 words spoken (2026-11-17: 7.1 to 8.0 s measured) --------


def test_every_scripted_greeting_line_is_short():
    from assistant import speech_tags, web_greeting

    for line in (*web_greeting.ACK_LINES, web_greeting.recruiter_hello("Alfred")):
        spoken = speech_tags.untagged(line)
        assert len(spoken.split()) <= web_greeting.MAX_GREETING_WORDS, line


@pytest.mark.parametrize(
    ("greeting", "kept"),
    [
        ("Busy stretch ahead, but nothing you can't handle.", True),
        (
            "Looks like quite a busy stretch is coming up for you over the next few days, "
            "so pace yourself and keep an eye on it.",
            False,
        ),
    ],
)
def test_a_model_greeting_over_the_word_cap_is_refused(greeting, kept):
    assert cascade.greeting_is_honest(greeting) is kept


def test_a_sentence_whose_speech_fails_in_the_member_voice_is_said_in_puck_once():
    """Round 4 speech N3: a refusal that arrives as an in-stream error says only "(400)", so the
    voice is never marked bad; the sentence is said in Puck rather than not at all."""
    import base64
    import json

    from assistant.speech import GeminiSpeech, Spoken

    class InStream:
        def __init__(self):
            self.voices = []

        def lines(self, url, headers, data, timeout):
            shape = json.loads(data)["generationConfig"]["speechConfig"]["voiceConfig"]
            name = shape.get("voice") or shape.get("prebuiltVoiceConfig", {}).get("voiceName")
            self.voices.append(name)
            if name == "en-gb-advisor-3":
                yield 'data: {"error": {"code": 400}}'
                return
            audio = base64.b64encode(b"\x01\x00" * 240).decode()
            part = {"inlineData": {"mimeType": "audio/l16", "data": audio}}
            yield "data: " + json.dumps({"candidates": [{"content": {"parts": [part]}}]})

    transport = InStream()
    speech = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport)
    stats = Spoken()
    assert b"".join(speech.speak("Hello there.", "en-gb-advisor-3", stats))
    assert stats.voice == "Puck"
    assert transport.voices[-1] == "Puck"
    assert not speech.bad_voices  # the error named no voice: nothing is marked
