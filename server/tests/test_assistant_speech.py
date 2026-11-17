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
