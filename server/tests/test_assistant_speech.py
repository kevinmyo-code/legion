"""`assistant/speech.py` (ADR 0102): the speech request shapes and the stream parsing, against a
fake transport. No test reaches Google."""

from __future__ import annotations

import base64
import json
import struct

import pytest

from agents.runtime.model import ModelFailure
from assistant import speech
from assistant.speech import GeminiSpeech, SpeechFailure, Spoken


class FakeTransport:
    """`lines` answers each request with the next scripted list of SSE events (or raises)."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.sent: list[dict] = []

    def lines(self, url, headers, body, timeout):
        self.sent.append({"url": url, "headers": headers, "body": json.loads(body)})
        answer = self.answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        for event in answer:
            yield f"data: {json.dumps(event)}".encode()


def _text(text):
    return {"candidates": [{"content": {"parts": [{"text": text}]}}]}


def _audio(raw: bytes):
    data = base64.b64encode(raw).decode()
    return {"candidates": [{"content": {"parts": [{"inlineData": {"data": data}}]}}]}


def _speech(transport):
    return GeminiSpeech(lambda: "test-key", stt_model="gemini-3.5-flash-lite", transport=transport)


def test_wav_is_a_44_byte_pcm_header():
    out = speech.wav(b"\x01\x00" * 10)
    assert out[:4] == b"RIFF" and out[8:12] == b"WAVE" and len(out) == 64
    assert struct.unpack("<I", out[24:28])[0] == 16_000
    assert speech.wav_header_length(out) == 44


def test_transcription_sends_the_phones_request_and_cleans_quotes():
    fake = FakeTransport(
        [_text('"Add milk'), _text(' to the list."'), {"usageMetadata": {"promptTokenCount": 9}}]
    )
    heard = _speech(fake).transcribe(b"\x00\x00" * 8000)
    assert heard.text == "Add milk to the list."
    assert heard.audio_ms == 500 and heard.prompt_tokens == 9
    sent = fake.sent[0]
    assert sent["url"].endswith("/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse")
    assert sent["headers"]["x-goog-api-key"] == "test-key"
    assert "test-key" not in sent["url"]
    body = sent["body"]
    assert body["systemInstruction"]["parts"][0]["text"] == speech.STT_INSTRUCTION
    parts = body["contents"][0]["parts"]
    assert parts[0]["inlineData"]["mimeType"] == "audio/wav"
    assert parts[1] == {"text": "Transcribe this audio."}
    assert body["generationConfig"] == {"maxOutputTokens": 512}


def test_too_little_audio_is_not_sent():
    fake = FakeTransport()
    assert _speech(fake).transcribe(b"\x00" * 100).text == ""
    assert fake.sent == []


def test_a_transcription_error_is_a_speech_failure():
    fake = FakeTransport([{"error": {"code": 500}}])
    with pytest.raises(SpeechFailure):
        _speech(fake).transcribe(b"\x00\x00" * 8000)


def test_speech_strips_a_wav_header_and_keeps_whole_samples():
    head = speech.wav(b"")[:44]
    fake = FakeTransport([_audio(head + b"\x01\x02\x03"), _audio(b"\x04\x05")])
    stats = Spoken()
    out = b"".join(_speech(fake).speak("Hello.", "Puck", stats))
    assert out == b"\x01\x02\x03\x04"
    assert stats.first_audio_ms is not None
    body = fake.sent[0]["body"]
    assert body["generationConfig"]["responseModalities"] == ["AUDIO"]
    assert body["generationConfig"]["speechConfig"]["voiceConfig"] == {"voice": "Puck"}
    assert fake.sent[0]["url"].endswith(f"/models/{speech.TTS_MODEL}:streamGenerateContent?alt=sse")


def test_a_400_before_audio_retries_once_with_the_legacy_voice_shape():
    fake = FakeTransport(ModelFailure("Gemini answered HTTP 400"), [_audio(b"\x01\x00")])
    out = b"".join(_speech(fake).speak("Hi.", "Puck", Spoken()))
    assert out == b"\x01\x00"
    assert fake.sent[1]["body"]["generationConfig"]["speechConfig"]["voiceConfig"] == {
        "prebuiltVoiceConfig": {"voiceName": "Puck"}
    }


def test_a_server_error_is_a_speech_failure_and_not_retried():
    fake = FakeTransport(ModelFailure("Gemini answered HTTP 503"))
    with pytest.raises(SpeechFailure):
        b"".join(_speech(fake).speak("Hi.", "Puck", Spoken()))
    assert len(fake.sent) == 1


def test_no_key_is_a_failure_and_nothing_is_sent():
    fake = FakeTransport()
    with pytest.raises(SpeechFailure):
        GeminiSpeech(lambda: None, stt_model="m", transport=fake).transcribe(b"\x00" * 8000)
    assert fake.sent == []


# ---- the prompt cache (`assistant/prompt_cache.py`) ---------------------------------------------

from agents.runtime.model import ModelRequest, request_body, say  # noqa: E402
from assistant.prompt_cache import CacheStore, CachingModel  # noqa: E402


class CacheFake:
    """`stream` records requests and answers a fixed round; `create_cache` names a cache."""

    def __init__(self, fail_cached: Exception | None = None):
        self.requests: list[ModelRequest] = []
        self.created: list[str] = []
        self.fail_cached = fail_cached

    def stream(self, req):
        self.requests.append(req)
        if req.cached_content and self.fail_cached is not None:
            raise self.fail_cached
        yield from say("Fine.")

    def create_cache(self, model, system, declarations, ttl_s=300):
        self.created.append(system)
        return f"cachedContents/{len(self.created)}"


DECLS = [{"name": "list_events", "description": "x", "parameters": {"type": "object"}}]


def _req(allow_tools=True, system="BODY"):
    return ModelRequest(
        model="m", system=system, contents=[], declarations=DECLS, allow_tools=allow_tools
    )


def _store(clock=lambda: 1000.0):
    return CacheStore(clock=clock, spawn=lambda work: work())


def test_the_first_round_goes_inline_and_the_next_uses_the_cache():
    inner = CacheFake()
    model = CachingModel(inner, _store())
    list(model.stream(_req()))
    assert inner.requests[0].cached_content is None and inner.created == ["BODY"]
    list(model.stream(_req()))
    sent = inner.requests[1]
    assert sent.cached_content == "cachedContents/1"
    body = request_body(sent)
    # Only cachedContent, contents and generationConfig travel with a cache (the phone's rule).
    assert set(body) == {"cachedContent", "contents", "generationConfig"}
    assert model.used == 1


def test_the_tool_free_last_round_goes_inline():
    inner = CacheFake()
    model = CachingModel(inner, _store())
    list(model.stream(_req()))
    list(model.stream(_req(allow_tools=False)))
    assert inner.requests[1].cached_content is None
    assert "toolConfig" in request_body(inner.requests[1])


def test_a_different_body_is_a_different_cache():
    inner = CacheFake()
    model = CachingModel(inner, _store())
    list(model.stream(_req(system="A")))
    list(model.stream(_req(system="B")))
    assert inner.created == ["A", "B"]


def test_a_broken_cache_is_dropped_and_the_round_goes_inline_once():
    inner = CacheFake(fail_cached=ModelFailure("Gemini answered HTTP 404"))
    model = CachingModel(inner, _store())
    list(model.stream(_req()))
    out = list(model.stream(_req()))
    assert out  # answered, inline
    assert [r.cached_content for r in inner.requests] == [None, "cachedContents/1", None]


def test_a_cache_near_expiry_is_not_used():
    now = [1000.0]
    inner = CacheFake()
    model = CachingModel(inner, _store(clock=lambda: now[0]))
    list(model.stream(_req()))
    now[0] += 1200 - 100
    list(model.stream(_req()))
    assert inner.requests[1].cached_content is None


def test_a_failed_create_backs_off():
    class Refusing(CacheFake):
        def create_cache(self, *a, **k):
            self.created.append("x")
            raise ModelFailure("Gemini refused the cache (HTTP 400)")

    now = [1000.0]
    inner = Refusing()
    model = CachingModel(inner, _store(clock=lambda: now[0]))
    list(model.stream(_req()))
    list(model.stream(_req()))
    assert inner.created == ["x"]
    now[0] += 61
    list(model.stream(_req()))
    assert inner.created == ["x", "x"]
