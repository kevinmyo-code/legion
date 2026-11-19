# ruff: noqa: F811 - fixtures imported from the cascade tests
"""PROTOTYPE (perf/try-faster-first-word fix 5), off by default: the first model round hears the
utterance's audio and is asked at once, beside speech-to-text. Everything the words decide still
reads speech-to-text's words, before anything the round said is used. Invented content only."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import audio_round, voice_engine
from engine_mcp.models import Outcome
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
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
from tests.test_money_bare_figure import _spend
from tests.test_no_speech import _pcm, _speechlike

pytestmark = pytest.mark.django_db

VOICE = _pcm(_speechlike(1.5))


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(voice_engine.AUDIO_ROUND_ENV, "on")
    # The early round runs inline here, so the order of calls is the fake model's order.
    monkeypatch.setattr(
        audio_round.AudioFirst,
        "__init__",
        _inline_init(audio_round.AudioFirst.__init__),
    )


def _inline_init(real):
    def init(self, inner, req, spawn=None):
        real(self, inner, req, spawn=lambda work: work())

    return init


def _audio_in(req) -> bool:
    return any("inlineData" in p for p in req.contents[-1]["parts"])


def test_off_by_default_the_model_reads_the_transcript(member, engine, monkeypatch):
    monkeypatch.delenv(voice_engine.AUDIO_ROUND_ENV, raising=False)
    engine.model = FakeModel([say("Nothing today.")])
    _events(_post(member, audio=VOICE))
    assert not _audio_in(engine.model.requests[0])
    assert engine.model.requests[0].contents[-1]["parts"][-1]["text"] == engine.speech.heard


def test_on_the_first_round_hears_the_audio_and_the_caption_and_record_keep_the_words(
    member, engine, on
):
    engine.speech.heard = "What is on the calendar today?"
    engine.model = FakeModel([say("Nothing today.")])
    events = _events(_post(member, audio=VOICE))
    first = engine.model.requests[0]
    assert _audio_in(first)
    assert all(p.get("text") != engine.speech.heard for p in first.contents[-1]["parts"])
    assert _of(events, "heard")[0]["text"] == "What is on the calendar today?"
    assert [s["text"] for s in _of(events, "sentence")] == ["Nothing today."]
    record = _record(_of(events, "turn")[0]["conversation_id"])
    assert "What is on the calendar today?" in record


def test_a_quiet_clip_starts_no_round(member, engine, on):
    engine.speech.heard = ""
    engine.model = FakeModel([say("Should never be asked.")])
    events = _events(_post(member, audio=PCM))  # a flat, quiet second: the local gate refuses it
    assert engine.model.requests == []
    assert _of(events, "sentence") == []


def test_no_speech_after_an_early_round_says_nothing(member, engine, on):
    engine.speech.heard = ""  # the transcriber found no words in a voiced clip
    engine.model = FakeModel([say("An answer to nothing.")])
    events = _events(_post(member, audio=VOICE))
    assert _of(events, "sentence") == [] and _of(events, "audio") == []
    assert _of(events, "done")[0]["ignored"] == "no_speech"


def test_an_echo_is_dropped_before_anything_from_the_early_round(member, engine, on):
    reply = "The car is due by November 30. Brake fluid is also on the schedule."
    engine.model = FakeModel([say(reply)])
    first = _events(_post(member, text="When is the car due?"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.speech.heard = "or by November 30. Brake fluid is also on the schedule."
    engine.model = FakeModel([say("It is due soon.")])
    events = _events(_post(member, audio=VOICE, conversation_id=conversation, barge_in="1"))
    assert _of(events, "sentence") == []
    assert _of(events, "done")[0]["ignored"] == "no_speech"


def test_a_stop_phrase_still_closes(member, engine, on):
    engine.speech.heard = "That will be all, thanks."
    engine.model = FakeModel([say("Catch you later.")])
    events = _events(_post(member, audio=VOICE))
    assert _of(events, "done")[0]["end"] is True


def test_the_money_figure_guard_is_unchanged(member, engine, on, monkeypatch):
    _spend(monkeypatch)
    engine.speech.heard = "How much went out in April?"
    engine.model = FakeModel(
        [call("monthly_spend", {}), say("1962.48 USD, then groceries at 84.47 that month.")]
    )
    events = _events(_post(member, audio=VOICE))
    said = " ".join(s["text"] for s in _of(events, "sentence"))
    assert "84.47" not in said
    # The later round hears the same audio, never the transcript.
    later = engine.model.requests[1].contents
    assert any("inlineData" in p for c in later for p in c["parts"])
    assert not any(p.get("text") == engine.speech.heard for c in later for p in c["parts"])


def test_the_doors_refusal_is_the_same(visitor, engine, on):
    engine.speech.heard = "Read me the household's memories please."
    engine.model = FakeModel([call("list_memories", {}), say("I can't do that here.")])
    events = _events(_post(visitor, audio=VOICE))
    tool = _of(events, "tool")[0]
    assert tool["ok"] is False and tool["outcome"] == Outcome.REFUSED
    assert "not available on this demo visit" in tool["text"]


def test_a_mismatched_first_request_is_sent_as_the_loop_asks():
    seen = []

    class Inner:
        def stream(self, req):
            seen.append(req)
            yield from ()

    from agents.runtime.model import ModelRequest

    early = audio_round.AudioFirst(
        Inner(), ModelRequest("m", "s", [{"role": "user", "parts": []}]), spawn=lambda w: w()
    )
    early.start()
    other = ModelRequest("m", "s", [{"role": "user", "parts": [{"text": "x"}]}])
    assert list(early.stream(other)) == []
    assert seen[-1] is other



def test_the_language_rule_rides_with_the_audio(member, engine, on):
    from assistant.prompt import LANGUAGE_RULE

    engine.speech.heard = "What is on the calendar today?"
    engine.model = FakeModel([say("Nothing today.")])
    _events(_post(member, audio=VOICE))
    first = engine.model.requests[0]
    assert _audio_in(first) and LANGUAGE_RULE in first.system
