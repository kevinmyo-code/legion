"""The speech-to-text hedge (`agents/voice/hedge.py`), the phone's `SttHedge` rule, with fake
requests: a second identical request beside a slow first, at most two, one result used, the loser
dropped. Times are scaled down; no request leaves the process."""

from __future__ import annotations

import threading
import time

import pytest

from agents.runtime.model import ModelFailure
from agents.voice import hedge
from agents.voice.speech import Heard, SpeechFailure


class Requests:
    """Each call is the next scripted request: (delay seconds, result or exception)."""

    def __init__(self, *script):
        self.script = list(script)
        self.started: list[float] = []
        self.t0 = time.monotonic()
        self.lock = threading.Lock()

    def __call__(self):
        with self.lock:
            i = len(self.started)
            self.started.append(time.monotonic() - self.t0)
        delay, outcome = self.script[i]
        time.sleep(delay)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def _race(requests, hedge_after_s=0.15, timeout_s=2.0, seconds=None):
    whys = []
    result = hedge.race(
        requests,
        hedge_after_s=hedge_after_s,
        attempt_timeout_s=timeout_s,
        worth_retry=lambda exc: "retry" in str(exc),
        on_second=whys.append,
    )
    return result, whys


def test_a_quick_first_answer_is_the_only_request():
    requests = Requests((0.0, "first"))
    result, whys = _race(requests)
    assert result == hedge.Won("first", 0)
    assert len(requests.started) == 1 and whys == []


def test_a_slow_first_gets_a_second_at_the_hedge_time_and_the_quicker_wins():
    requests = Requests((0.6, "first"), (0.0, "second"))
    result, whys = _race(requests)
    assert result == hedge.Won("second", 1)
    assert whys == ["slow"]
    assert 0.12 <= requests.started[1] < 0.4


def test_a_slow_first_that_still_answers_first_wins_and_the_second_is_dropped():
    requests = Requests((0.2, "first"), (0.5, "second"))
    result, _whys = _race(requests)
    assert result == hedge.Won("first", 0)


def test_a_failure_worth_retrying_gets_its_second_at_once():
    requests = Requests((0.0, ModelFailure("retry: could not be reached")), (0.0, "second"))
    result, whys = _race(requests, hedge_after_s=1.0)
    assert result == hedge.Won("second", 1) and whys == ["failed"]
    assert requests.started[1] < 0.5


def test_a_failure_not_worth_retrying_is_lost_with_no_second_request():
    boom = ModelFailure("HTTP 400")
    requests = Requests((0.0, boom))
    result, whys = _race(requests)
    assert result == hedge.Lost(boom) and whys == [] and len(requests.started) == 1


def test_never_more_than_two_requests():
    requests = Requests(
        (0.3, ModelFailure("retry 1")), (0.0, ModelFailure("retry 2")), (0.0, "never")
    )
    result, _whys = _race(requests)
    assert isinstance(result, hedge.Lost) and len(requests.started) == 2


def test_both_hung_is_lost_with_nothing():
    requests = Requests((5.0, "late"), (5.0, "late"))
    result, _whys = _race(requests, hedge_after_s=0.05, timeout_s=0.1)
    assert result == hedge.Lost(None) and len(requests.started) == 2


@pytest.mark.parametrize(
    ("audio_ms", "after_ms"),
    [
        (1_000, 1_500),
        (4_000, 1_500),
        (4_001, 1_750),
        (6_000, 2_000),
        (9_000, 2_750),
        (20_000, 3_000),
    ],
)
def test_the_hedge_time_grows_with_the_audio_as_the_phones(audio_ms, after_ms):
    assert hedge.hedge_after_for(audio_ms) == after_ms


# ---- HedgedSpeech: the hedge around the engine's speech client ---------------------------------


class FakeSpeech:
    def __init__(self, *script):
        self.requests = Requests(*script)
        self.stt_sent = 0
        self.spoken = []

    def _transcribe_once(self, pcm):
        self.stt_sent += 1
        return self.requests()

    def speak(self, text, voice, spoken):
        self.spoken.append(text)
        yield b"\x00\x00"


def test_hedged_speech_returns_one_transcript_from_the_quicker_request(monkeypatch):
    monkeypatch.setattr(hedge, "hedge_after_for", lambda audio_ms: 100)
    inner = FakeSpeech((0.6, Heard("first words", 1000, 600)), (0.0, Heard("second", 1000, 5)))
    speech = hedge.HedgedSpeech(inner)
    heard = speech.transcribe(b"\x00\x00" * 16_000)
    assert heard.text == "second"
    assert speech.stt_sent == 2
    assert list(speech.speak("Hi.", "Puck", None)) == [b"\x00\x00"]


def test_hedged_speech_says_a_lost_race_as_the_engines_own_failure(monkeypatch):
    monkeypatch.setattr(hedge, "hedge_after_for", lambda audio_ms: 100)
    inner = FakeSpeech((0.0, ModelFailure("HTTP 503")), (0.0, ModelFailure("HTTP 503")))
    with pytest.raises(SpeechFailure, match="stt_unreachable"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
    inner = FakeSpeech((0.0, ModelFailure("HTTP 400")))
    with pytest.raises(SpeechFailure, match="stt_failed"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
    inner = FakeSpeech((0.0, SpeechFailure("stt_blocked")))
    with pytest.raises(SpeechFailure, match="stt_blocked"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
