# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Expression tags and the per-role voice in the web cascade (Kevin, 2026-11-17: "with tags where
possible"; members en-gb-advisor-3, the demo Puck).

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
    rest = "then we can look at the rest of it."
    assert speech_tags.for_speech(f"Well <long pause> {rest}") == f"Well {rest}"
    assert speech_tags.for_speech(f"Well <applause> {rest}") == f"Well {rest}"
    assert speech_tags.for_speech(f"Well <scream> {rest}") == f"Well {rest}"
    assert speech_tags.for_speech(f"Well <SIGH> {rest}") == f"Well <sigh> {rest}"
    assert speech_tags.for_speech(f"Well <throat-clearing> {rest}") == (
        f"Well <throat-clearing> {rest}"
    )
    assert "<long pause>" not in speech_tags.ALLOWED


def test_one_tag_a_sentence_and_no_cap_a_reply():
    assert speech_tags.for_speech(
        "<chuckle> Fine, <laugh> fine, then we can look at the rest of it."
    ) == ("<chuckle> Fine, fine, then we can look at the rest of it.")
    said = [
        speech_tags.for_speech(f"<sigh> Line {w}, then we can look at the rest of it.")
        for w in ("one", "two", "three")
    ]
    assert said == [
        f"<sigh> Line {w}, then we can look at the rest of it." for w in ("one", "two", "three")
    ]


def test_no_tag_on_a_short_line_or_a_list():
    # Section 9: a tag on a five-word line stretched it to twice its length.
    assert speech_tags.for_speech("<chuckle> Enjoy your evening.") == "Enjoy your evening."
    # Section 11: the reply that read out a list was "too much" with tags.
    line = "<sigh> You have milk, eggs, bread and two kinds of cheese on there."
    assert not speech_tags.has_tag(speech_tags.for_speech(line))


@pytest.mark.parametrize(
    "sentence",
    [
        "<chuckle> You spent $412.50 on groceries.",
        "<sigh> That's due on November third.",
        "<laugh> The code is AB12.",
        "<short pause> It went up 4%.",
        "<chuckle> See you at noon.",
        "<sigh> Sorry, I can't change that from here.",
        "<chuckle> Add oat milk to the groceries?",
        "<laugh> Nothing was changed.",
    ],
)
def test_a_figure_date_time_refusal_or_confirmation_keeps_no_tag(sentence):
    assert not speech_tags.has_tag(speech_tags.for_speech(sentence))


def test_a_casual_question_may_keep_its_tag():
    line = "<chuckle> Long week at the office for you, was it?"
    assert speech_tags.for_speech(line) == line


def test_a_code_line_that_is_not_expressive_is_spoken_plain():
    assert speech_tags.for_speech("<chuckle> Not answered.", expressive=False) == "Not answered."


def test_the_scripted_lines_carry_only_allowed_tags():
    from assistant import fillers, web_greeting

    for line in (web_greeting.recruiter_hello("Alfred"), *tour.CODE_LINES):
        for tag in speech_tags._TAG.findall(line):
            assert f"<{tag}>" in speech_tags.ALLOWED, line
    # Tool fillers and the failure lines carry none; an acknowledgement may carry an allowed one.
    for _slow, lines in fillers.FILLERS.values():
        assert not any(speech_tags.has_tag(line) for line in lines)
    for line in fillers.ACK:
        for tag in speech_tags._TAG.findall(line):
            assert f"<{tag}>" in speech_tags.ALLOWED


def test_a_filler_line_goes_to_speech_through_the_tag_rules():
    # Round 2 speech review 6: "<breath> Okay." went to speech raw, a tag on a two-word line.
    from tests.test_assistant_cascade import FakeSpeech

    speech = FakeSpeech()
    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(speech, "Puck", ["<breath> Okay.", "<chuckle> Checking the calendar for you now."])
    said = [text for text, _voice in speech.spoken]
    assert "Okay." in said and "<breath> Okay." not in said
    for line in (cascade.NOT_ANSWERED_MODEL, cascade.NOT_ANSWERED_TIMEOUT, cascade.NO_TEXT):
        assert not speech_tags.has_tag(line)


def test_the_model_is_told_the_tags_once():
    system = cascade.cached_system("Body.")
    assert system.count(speech_tags.PROMPT_LINE) == 1
    for tag in speech_tags._TAG.findall(speech_tags.PROMPT_LINE):
        assert f"<{tag}>" in speech_tags.ALLOWED
    assert "long pause" not in speech_tags.PROMPT_LINE
    assert cascade.PERSONA_NOTE in system


# =============================================================================
# The rules, in a turn: speech gets the tag, nothing else does
# =============================================================================


def test_a_model_tag_is_spoken_but_never_shown_stored_or_sent_back(member, engine):
    engine.model = FakeModel(
        [
            say(
                "<chuckle> The calendar is empty. "
                "<laugh> Enjoy the quiet evening while it lasts, honestly."
            )
        ]
    )
    events = _events(_post(member, audio=PCM))
    shown = [s["text"] for s in _of(events, "sentence")]
    assert shown and not any("<" in s for s in shown)
    done = _of(events, "done")[0]
    assert "<" not in done.get("final_text", "")
    spoken = [text for text, _voice in engine.speech.spoken]
    # Never on an answer's first sentence (section 9); one on the second.
    assert sum(t.count("<") for t in spoken) == 1
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


def test_the_speaker_speaks_code_lines_plain_and_figures_rounded():
    from tests.test_assistant_cascade import FakeSpeech

    speech = FakeSpeech()
    speaker = cascade.Speaker(speech, "Puck", enabled=True)
    speaker.add("<chuckle> Groceries were $2,333.61.")  # a verbatim line
    speaker.add("<chuckle> Fine, then we can look at the rest of it.", expressive=True)
    speaker.add("<sigh> Fine again, then we can look at the rest of it.", expressive=True)
    list(speaker.drain())
    speaker.close()
    said = [text for text, _voice in speech.spoken]
    # Two speak at once, so the order they reach speech is not fixed.
    # The first words alone, the rest of the round as one request; a tag per sentence.
    assert sorted(said) == sorted(
        [
            "Groceries were about $2,330.",
            "<chuckle> Fine, then we can look at the rest of it. "
            "<sigh> Fine again, then we can look at the rest of it.",
        ]
    )


def test_the_tour_shows_its_lines_plain_and_speaks_their_tag(visitor, engine):
    engine.speech.heard = "stop"
    events = _events(_post(visitor, tour=0, audio=PCM))
    assert [s["text"] for s in _of(events, "sentence")] == [tour.STOPPED_LINE]
    # Too short for a tag (section 9): said plain.
    assert not any("<" in t for t, _v in engine.speech.spoken)


def test_the_opening_is_shown_plain_and_spoken_with_its_tag(visitor, engine):
    events = _events(_post(visitor, greet="true"))
    assert "<" not in _of(events, "greeting")[0]["text"]
    assert any("<breath>" in t for t, _v in engine.speech.spoken)


# =============================================================================
# The voice, per role
# =============================================================================


def test_the_voice_map_members_advisor_visitors_puck(member, visitor, engine):
    # Kevin, 2026-11-17: "this is my choice Authoritative Advisor 3 / en-gb-advisor-3" for
    # Alfred; the demo stays Puck.
    engine.model = FakeModel([say("Hello.")])
    _events(_post(member, audio=PCM))
    assert {v for _t, v in engine.speech.spoken} == {"en-gb-advisor-3"}
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
    assert audio.get("Kore", "Checking the calendar.") is None
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
            # Google's words, as probed 2026-11-17 (round 3 S3).
            if "prebuiltVoiceConfig" in shape:
                raise ModelFailure(
                    f"Gemini answered HTTP 400: No matching speaker voice found for name: {name}"
                )
            raise ModelFailure("Gemini answered HTTP 400: Request contains an invalid argument.")
        audio = base64.b64encode(b"\x01\x00" * 240).decode()
        part = {"inlineData": {"mimeType": "audio/l16", "data": audio}}
        yield "data: " + json.dumps({"candidates": [{"content": {"parts": [part]}}]})


def test_a_refused_voice_falls_back_to_puck_rather_than_silence():
    transport = _Transport(refused="en-gb-unknown")
    speech = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport)
    audio = b"".join(speech.speak("Hello.", "en-gb-unknown", Spoken()))
    assert audio
    assert "en-gb-unknown" in speech.bad_voices
    # The next sentence goes straight to the fallback, with no refused request first.
    sent = len(transport.bodies)
    assert b"".join(speech.speak("Again.", "en-gb-unknown", Spoken()))
    assert len(transport.bodies) == sent + 1
    assert FALLBACK_VOICE == "Puck"


def test_scripted_greetings_never_repeat_running():
    from assistant import web_greeting

    said = [web_greeting.ack_line(pick=lambda options: options[0]) for _ in range(6)]
    assert all(a != b for a, b in zip(said, said[1:], strict=False))


# ---- round 2 speech review 8: only a <word> tag is a tag; markup and stage directions go ---------


@pytest.mark.parametrize(
    ("raw", "shown"),
    [
        ('Hold on <break time="500ms"/> there.', "Hold on there."),
        ("[laughs] That was good.", "That was good."),
        ("*laughs* That was good.", "That was good."),
        ("if spending < income and savings > zero", "if spending < income and savings > zero"),
        ("3 < 4 and 5 > 2", "3 < 4 and 5 > 2"),
    ],
)
def test_markup_and_stage_directions_are_removed_and_real_text_is_kept(raw, shown):
    assert speech_tags.untagged(raw) == shown
    assert speech_tags.for_speech(raw) == shown


def test_a_clip_made_in_one_roles_voice_is_never_played_to_the_other():
    class Speech:
        def speak(self, text, voice, spoken):
            yield voice.encode()

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(Speech(), "en-gb-advisor-3", ["Checking the calendar."])
    assert audio.pick("Puck", "list_events") is None
    assert audio.get("en-gb-advisor-3", "Checking the calendar.") == b"en-gb-advisor-3"
