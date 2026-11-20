"""The speech-to-text hedge (`agents/voice/hedge.py`), the phone's `SttHedge` rule, with fake
requests: a second identical request beside a slow first, at most two, one result used, the loser
dropped and still paid for. No test sleeps: a slow request is one held on an event, and the race's
own waits are a few milliseconds. No request leaves the process."""

from __future__ import annotations

import threading

import pytest

from agents.runtime.model import ModelFailure
from agents.voice import hedge
from agents.voice.speech import Heard, SpeechFailure

QUICK = 0.01  # the race's hedge wait in these tests: a held request is "slow" after this
LONG = 30.0  # a wait that must never run out in a passing test


class Requests:
    """Each call is the next scripted request: (gate, result or exception). A gate is None (answer
    at once) or a `threading.Event` the request waits on before it answers."""

    def __init__(self, *script):
        self.script = list(script)
        self.started = 0
        self.lock = threading.Lock()

    def __call__(self):
        with self.lock:
            i = self.started
            self.started += 1
        gate, outcome = self.script[i]
        if gate is not None:
            gate.wait(LONG)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def _race(requests, hedge_after_s=QUICK, timeout_s=LONG):
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
    requests = Requests((None, "first"))
    result, whys = _race(requests, hedge_after_s=LONG)
    assert result == hedge.Won("first", 0)
    assert requests.started == 1 and whys == []


def test_a_slow_first_gets_a_second_and_the_quicker_wins():
    held = threading.Event()
    requests = Requests((held, "first"), (None, "second"))
    try:
        result, whys = _race(requests)
    finally:
        held.set()
    assert result == hedge.Won("second", 1)
    assert whys == ["slow"] and requests.started == 2


def test_a_slow_first_that_still_answers_first_wins_and_the_second_is_dropped():
    first, second = threading.Event(), threading.Event()
    requests = Requests((first, "first"), (second, "second"))
    # The first is released only once the second has started: it is still the one used.
    threading.Thread(target=lambda: (_wait_started(requests, 2), first.set())).start()
    try:
        result, _whys = _race(requests)
    finally:
        first.set()
        second.set()
    assert result == hedge.Won("first", 0)


def _wait_started(requests, n):
    gate = threading.Event()
    while requests.started < n:
        gate.wait(0.001)


def test_a_failure_worth_retrying_gets_its_second_at_once():
    requests = Requests((None, ModelFailure("retry: could not be reached")), (None, "second"))
    result, whys = _race(requests, hedge_after_s=LONG)
    assert result == hedge.Won("second", 1) and whys == ["failed"]


def test_a_failure_not_worth_retrying_is_lost_with_no_second_request():
    boom = ModelFailure("HTTP 400")
    requests = Requests((None, boom))
    result, whys = _race(requests, hedge_after_s=LONG)
    assert result == hedge.Lost(boom) and whys == [] and requests.started == 1


def test_never_more_than_two_requests():
    held = threading.Event()
    requests = Requests(
        (held, ModelFailure("retry 1")), (None, ModelFailure("retry 2")), (None, "never")
    )
    threading.Thread(target=lambda: (_wait_started(requests, 2), held.set())).start()
    try:
        result, _whys = _race(requests)
    finally:
        held.set()
    assert isinstance(result, hedge.Lost) and requests.started == 2


def test_both_hung_is_lost_with_nothing():
    held = threading.Event()
    requests = Requests((held, "late"), (held, "late"))
    try:
        result, _whys = _race(requests, timeout_s=QUICK)
    finally:
        held.set()
    assert result == hedge.Lost(None) and requests.started == 2


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


@pytest.fixture
def quick_hedge(monkeypatch):
    monkeypatch.setattr(hedge, "hedge_after_for", lambda audio_ms: QUICK * 1000)


def test_hedged_speech_returns_one_transcript_from_the_quicker_request(quick_hedge):
    held = threading.Event()
    inner = FakeSpeech(
        (held, Heard("first words", 1000, 600, 40, 6)), (None, Heard("second", 1000, 5, 40, 6))
    )
    speech = hedge.HedgedSpeech(inner)
    try:
        heard = speech.transcribe(b"\x00\x00" * 16_000)
    finally:
        held.set()
    assert heard.text == "second"
    assert speech.stt_sent == 2
    assert list(speech.speak("Hi.", "Puck", None)) == [b"\x00\x00"]


def test_a_raced_transcription_is_paid_for_twice(quick_hedge):
    # Review C8: the abandoned request was sent and is billed. It is metered at the winner's own
    # counts (the same audio, the same ask): an estimate, never zero.
    held = threading.Event()
    inner = FakeSpeech(
        (held, Heard("slow", 1000, 600, 40, 6)), (None, Heard("quick", 1000, 5, 40, 6))
    )
    try:
        heard = hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
    finally:
        held.set()
    assert heard.text == "quick"
    assert (heard.prompt_tokens, heard.output_tokens) == (80, 12)


def test_one_request_is_paid_for_once(quick_hedge):
    heard = hedge.HedgedSpeech(FakeSpeech((None, Heard("quick", 1000, 5, 40, 6)))).transcribe(
        b"\x00\x00" * 16_000
    )
    assert (heard.prompt_tokens, heard.output_tokens) == (40, 6)


def test_hedged_speech_says_a_lost_race_as_the_engines_own_failure(quick_hedge):
    inner = FakeSpeech((None, ModelFailure("HTTP 503")), (None, ModelFailure("HTTP 503")))
    with pytest.raises(SpeechFailure, match="stt_unreachable"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
    inner = FakeSpeech((None, ModelFailure("HTTP 400")))
    with pytest.raises(SpeechFailure, match="stt_failed"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
    inner = FakeSpeech((None, SpeechFailure("stt_blocked")))
    with pytest.raises(SpeechFailure, match="stt_blocked"):
        hedge.HedgedSpeech(inner).transcribe(b"\x00\x00" * 16_000)
