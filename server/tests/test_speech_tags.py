# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Expression tags and the per-role voice in the web cascade (Kevin, 2026-11-17: "i like neno
plain. and with tags where possible", then "recruiter demo can stay puck plain").

Google is never called: the cascade tests' fakes stand in for the model and speech; a fake
transport stands in for Google's speech endpoint. Every word here is invented.
"""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, say
from assistant import cascade, speech_tags, tour, voice_engine
from assistant.speech import FALLBACK_VOICE, GeminiSpeech, Spoken
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


# =============================================================================
# The rules, on strings
# =============================================================================


def test_strip_removes_every_tag_and_tidies_the_spaces():
    assert speech_tags.untagged("Sure. <chuckle> That's it.") == "Sure. That's it."
    assert speech_tags.untagged("Okay, <short pause> done <breath>.") == "Okay, done."
    assert speech_tags.untagged("<long pause>Hello</b>") == "Hello"
    assert speech_tags.untagged("No tags here.") == "No tags here."
    assert speech_tags.untagged("3 < 4 and 5 > 2") == "3 < 4 and 5 > 2"


def test_only_allowed_tags_are_spoken_and_long_pause_is_not_one():
    budget = speech_tags.Budget()
    assert speech_tags.for_speech("Well <long pause> then.", budget) == "Well then."
    assert speech_tags.for_speech("Well <breath> then.", budget) == "Well then."
    assert budget.left == 2
    assert speech_tags.for_speech("Well <SIGH> then.", budget) == "Well <sigh> then."
    assert "<long pause>" not in speech_tags.ALLOWED


def test_one_tag_a_sentence_and_two_a_reply():
    budget = speech_tags.Budget()
    once = speech_tags.for_speech("<chuckle> Fine, <laugh> fine.", budget)
    assert once == "<chuckle> Fine, fine."
    assert speech_tags.for_speech("<sigh> Again.", budget) == "<sigh> Again."
    assert speech_tags.for_speech("<laugh> A third.", budget) == "A third."
    assert budget.left == 0


@pytest.mark.parametrize(
    "sentence",
    [
        "<chuckle> You spent $412.50 on groceries.",
        "<sigh> That's due on November 3.",
        "<laugh> The code is AB12.",
        "<short pause> It went up 4%.",
    ],
)
def test_a_sentence_with_a_figure_keeps_no_tag(sentence):
    assert not speech_tags.has_tag(speech_tags.for_speech(sentence, speech_tags.Budget()))


def test_a_code_line_that_is_not_expressive_is_spoken_plain():
    spoken = speech_tags.for_speech(
        "<chuckle> Not answered.", speech_tags.Budget(), expressive=False
    )
    assert spoken == "Not answered."


def test_the_scripted_lines_carry_only_allowed_tags():
    from assistant import fillers, web_greeting

    for line in (web_greeting.recruiter_hello("Alfred"), *tour.CODE_LINES):
        for tag in speech_tags._TAG.findall(line):
            assert f"<{tag}>" in speech_tags.ALLOWED, line
    # Fillers and the failure lines carry none.
    for line in fillers.all_lines():
        assert not speech_tags.has_tag(line)
    for line in (cascade.NOT_ANSWERED_MODEL, cascade.NOT_ANSWERED_TIMEOUT, cascade.NO_TEXT):
        assert not speech_tags.has_tag(line)


def test_the_model_is_told_the_tags_once():
    system = cascade.cached_system("Body.")
    assert system.count(speech_tags.PROMPT_LINE) == 1
    for tag in speech_tags.ALLOWED:
        assert tag in speech_tags.PROMPT_LINE
    assert "long pause" not in speech_tags.PROMPT_LINE


# =============================================================================
# The rules, in a turn: speech gets the tag, nothing else does
# =============================================================================


def test_a_model_tag_is_spoken_but_never_shown_stored_or_sent_back(member, engine):
    engine.model = FakeModel([say("<chuckle> Nothing on today. <laugh> Enjoy it.")])
    events = _events(_post(member, audio=PCM))
    shown = [s["text"] for s in _of(events, "sentence")]
    assert shown and not any("<" in s for s in shown)
    done = _of(events, "done")[0]
    assert "<" not in done.get("final_text", "")
    spoken = [text for text, _voice in engine.speech.spoken]
    assert sum(speech_tags.has_tag(t) for t in spoken) == 1  # one per sentence, here one sentence
    # The record (and so the next turn's history) holds the words without tags.
    from tests.test_assistant_cascade import _record

    record = _record(_of(events, "turn")[0]["conversation_id"])
    assert "<chuckle>" not in record and "<laugh>" not in record
    conversation = _of(events, "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("Still nothing.")])
    _events(_post(member, text="And now?", conversation_id=conversation))
    sent_back = str(engine.model.requests[0].contents)
    assert "<chuckle>" not in sent_back and "<laugh>" not in sent_back


def test_a_money_answer_carries_no_tag(member, engine):
    engine.model = FakeModel([say("<sigh> You spent four hundred dollars, $400.00, last month.")])
    _events(_post(member, audio=PCM))
    assert not any(speech_tags.has_tag(t) for t, _v in engine.speech.spoken)


def test_a_tag_only_sentence_is_dropped_and_checks_see_plain_text(member, engine):
    engine.model = FakeModel([say("<chuckle> One moment. <short pause> Your day is clear.")])
    events = _events(_post(member, audio=PCM))
    # "One moment." is a filler opening and still dropped with its tag on.
    assert [s["text"] for s in _of(events, "sentence")] == ["Your day is clear."]


def test_the_speaker_speaks_code_lines_plain_and_keeps_the_reply_budget():
    from tests.test_assistant_cascade import FakeSpeech

    speech = FakeSpeech()
    speaker = cascade.Speaker(speech, "Puck", enabled=True)
    speaker.add("<chuckle> Groceries were fifty dollars.")  # a verbatim or refusal line
    speaker.add("<chuckle> Fine.", expressive=True)
    speaker.add("<sigh> Fine again.", expressive=True)
    speaker.add("<laugh> A third.", expressive=True)
    list(speaker.drain())
    speaker.close()
    said = [text for text, _voice in speech.spoken]
    # Two speak at once, so the order they reach speech is not fixed.
    assert sorted(said) == sorted(
        ["Groceries were fifty dollars.", "<chuckle> Fine.", "<sigh> Fine again.", "A third."]
    )


def test_the_tour_shows_its_lines_plain_and_speaks_their_tag(visitor, engine):
    engine.speech.heard = "stop"
    events = _events(_post(visitor, tour=0, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == [speech_tags.untagged(tour.STOPPED_LINE)]
    assert any("<chuckle>" in t for t, _v in engine.speech.spoken)


def test_the_opening_is_shown_plain_and_spoken_with_its_pause(visitor, engine):
    events = _events(_post(visitor, greet="true"))
    assert "<" not in _of(events, "greeting")[0]["text"]
    assert any("<short pause>" in t for t, _v in engine.speech.spoken)


# =============================================================================
# The voice, per role
# =============================================================================


def test_members_speak_in_neno_and_a_visit_in_puck(member, visitor, engine):
    engine.model = FakeModel([say("Hello.")])
    _events(_post(member, audio=PCM))
    assert {v for _t, v in engine.speech.spoken} == {"en-us-neno"}
    engine.speech.spoken.clear()
    engine.model = FakeModel([say("Hello.")])
    _events(_post(visitor, audio=PCM))
    assert {v for _t, v in engine.speech.spoken} == {"Puck"}


def test_each_role_voice_can_be_overridden(household_user, visitor_user, monkeypatch):
    monkeypatch.setenv(voice_engine.VOICE_ENV, "Kore")
    monkeypatch.setenv(voice_engine.RECRUITER_VOICE_ENV, "Charon")
    assert voice_engine.voice_for(household_user) == "Kore"
    assert voice_engine.voice_for(visitor_user) == "Charon"


def test_filler_audio_is_kept_per_voice():
    class Speech:
        def speak(self, text, voice, spoken):
            yield voice.encode()

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(Speech(), "Puck", ["Checking the calendar."])
    assert audio.get("en-us-neno", "Checking the calendar.") is None
    assert audio.get("Puck", "Checking the calendar.") == b"Puck"


class _Transport:
    """Google's speech endpoint: refuses one voice name in both shapes, speaks any other."""

    def __init__(self, refused: str):
        self.refused = refused
        self.bodies: list[dict] = []

    def lines(self, url, headers, data, timeout):
        import base64
        import json

        from agents.runtime.model import ModelFailure

        body = json.loads(data)
        self.bodies.append(body)
        shape = body["generationConfig"]["speechConfig"]["voiceConfig"]
        name = shape.get("voice") or shape.get("prebuiltVoiceConfig", {}).get("voiceName")
        if name == self.refused:
            raise ModelFailure("HTTP 400 invalid argument")
        audio = base64.b64encode(b"\x01\x00" * 240).decode()
        part = {"inlineData": {"mimeType": "audio/l16", "data": audio}}
        yield "data: " + json.dumps({"candidates": [{"content": {"parts": [part]}}]})


def test_a_refused_voice_falls_back_to_puck_rather_than_silence():
    transport = _Transport(refused="en-us-neno")
    speech = GeminiSpeech(lambda: "key", transport=transport)
    audio = b"".join(speech.speak("Hello.", "en-us-neno", Spoken()))
    assert audio
    assert "en-us-neno" in speech.bad_voices
    # The next sentence goes straight to the fallback, with no refused request first.
    sent = len(transport.bodies)
    assert b"".join(speech.speak("Again.", "en-us-neno", Spoken()))
    assert len(transport.bodies) == sent + 1
    assert FALLBACK_VOICE == "Puck"
