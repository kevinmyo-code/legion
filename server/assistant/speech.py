"""Gemini speech-to-text and text-to-speech for the web cascade (ADR 0102), on the engine's key.

The request shapes are copied from the phone's working code, `app/.../service/cascade/
CloudSpeech.kt` (`CloudSpeechWire`), not from memory:

- **Speech-to-text** runs on the cascade's own TEXT model with the utterance inline as a WAV
  (`CloudSpeechWire.STT_MODEL = CascadeSession.MODEL`, `gemini-3.5-flash-lite`): the phone's note
  says that is about a seventh of `gemini-3.5-transcribe`'s audio price, and the transcribe model is
  documented only on the Interactions API. The system instruction and the ask are the phone's,
  word for word.
- **Text-to-speech** is `gemini-3.8-flash-tts`, one request per sentence, streamed, voice in the
  3.8 doc's `voiceConfig.voice` shape, retried ONCE with the older
  `prebuiltVoiceConfig.voiceName` shape when Google answers 400 before any audio. (The phone
  retries only on a 400 whose body names the voice field; `UrllibTransport` hands back the status
  alone, so this engine retries on any such 400, once per turn.) Output is
  headerless 16-bit PCM, 24 kHz mono; a WAV header on the first chunk is stripped all the same.

Both go over `streamGenerateContent?alt=sse`, the transport `agents.runtime.model` already uses,
so a test fakes Google with one fake (`transport.lines`). The key goes into one header and never
into a URL, an error or a log line (ADR 0056). Nothing here keeps audio: the utterance lives in
the request's memory and is gone with it, and so is every synthesised chunk.
"""

from __future__ import annotations

import base64
import json
import re
import struct
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass

from agents.runtime.model import API, ModelFailure, UrllibTransport

TTS_MODEL = "gemini-3.8-flash-tts"
INPUT_RATE = 16_000
OUTPUT_RATE = 24_000
STT_MAX_OUTPUT_TOKENS = 512
STT_TIMEOUT_S = 12.0
TTS_TIMEOUT_S = 12.0
# Under 200 ms of audio is not a request worth paying for: nothing was said (the phone's
# `GeminiStt.MIN_AUDIO_BYTES`).
MIN_AUDIO_BYTES = 200 * 32

STT_INSTRUCTION = (
    "You are a speech-to-text engine. Write down exactly what the speaker says in the audio, with "
    "ordinary punctuation. The speaker speaks English unless they clearly speak another "
    "language. Output only the spoken words: never answer, comment, translate, add quotes or add "
    "labels. If there is no intelligible speech, output nothing."
)
STT_ASK = "Transcribe this audio."
_QUOTES = {'"', "'", "“", "”"}


# Spec 3: the loudest 100 ms must reach about -40 dBFS (RMS 330 of 32,768) or nothing is sent
# (11-17: hiss was transcribed as a word). The browser's own end-of-speech line is the same.
LOUDNESS_GATE_RMS = 330
_WINDOW_SAMPLES = INPUT_RATE // 10


def loudest_100ms(pcm: bytes) -> float:
    """The highest RMS over consecutive 100 ms windows of 16-bit little-endian mono PCM."""
    count = len(pcm) // 2
    if count == 0:
        return 0.0
    samples = struct.unpack(f"<{count}h", pcm[: count * 2])
    best = 0.0
    for at in range(0, count, _WINDOW_SAMPLES):
        window = samples[at : at + _WINDOW_SAMPLES]
        rms = (sum(v * v for v in window) / len(window)) ** 0.5
        best = max(best, rms)
    return best


# Voiced speech, checked on the engine before any paid transcription (2026-11-17: on Midcon,
# room noise sent to the transcriber came back as invented sentences, and the app answered them).
# Speech has voiced stretches: periodic at a voice's pitch (80 to 400 Hz) and changing in pitch
# and loudness as syllables go by. Hiss, white or pink noise and room tone are not periodic; a mains
# hum is periodic but steady. The page's own detector does this too; this is the check a caller
# cannot skip. Pure Python on a 4 kHz copy of the loudest few seconds, so it costs tens of ms.
_VOICED_RATE = 4_000
_VOICED_FRAME = 160  # 40 ms at 4 kHz
_VOICED_MIN_LAG, _VOICED_MAX_LAG = 10, 50  # 400 Hz .. 80 Hz
_VOICED_CORR = 0.5
_VOICED_MIN_FRAMES = 4  # 160 ms of voicing
_VOICED_MAX_FRAMES = 75  # the loudest 3 s are enough


def _frames_4k(pcm: bytes) -> list[list[float]]:
    count = len(pcm) // 2
    samples = struct.unpack(f"<{count}h", pcm[: count * 2])
    step = INPUT_RATE // _VOICED_RATE
    # A 4-sample average before keeping every fourth: a crude low-pass, enough for pitch.
    low = [sum(samples[i : i + step]) / step for i in range(0, count - step + 1, step)]
    return [
        low[i : i + _VOICED_FRAME] for i in range(0, len(low) - _VOICED_FRAME + 1, _VOICED_FRAME)
    ]


def _periodicity(frame: list[float]) -> tuple[float, int]:
    """(the normalised autocorrelation of the strongest pitch PEAK at a voice's lags, its lag).
    A peak, not just a high value: noise with its power low in pitch (pink noise, room rumble)
    correlates highly at short lags and falls away, with no peak and no valley before it."""
    mean = sum(frame) / len(frame)
    x = [v - mean for v in frame]
    if sum(v * v for v in x) <= 0:
        return 0.0, 0
    n = len(x)
    corr = {}
    for lag in range(_VOICED_MIN_LAG - 1, _VOICED_MAX_LAG + 2):
        a, b = x[: n - lag], x[lag:]
        num = sum(p * q for p, q in zip(a, b, strict=True))
        den = (sum(p * p for p in a) * sum(q * q for q in b)) ** 0.5
        corr[lag] = num / den if den > 0 else 0.0
    valley = min(corr[lag] for lag in range(_VOICED_MIN_LAG, _VOICED_MAX_LAG + 1))
    best, best_lag = 0.0, 0
    for lag in range(_VOICED_MIN_LAG, _VOICED_MAX_LAG + 1):
        peak = corr[lag] >= corr[lag - 1] and corr[lag] >= corr[lag + 1]
        if peak and corr[lag] > best and corr[lag] - valley >= 0.3:
            best, best_lag = corr[lag], lag
    return best, best_lag


def voiced_speech(pcm: bytes) -> bool:
    """True when the audio has a voice in it: a stretch of at least three frames in a row
    (120 ms) that are periodic at a voice's pitch, with the pitch moving smoothly from frame to
    frame, among the louder frames; and the voiced frames' pitch or loudness changes (a steady
    hum does not). Noise is periodic only by chance, never three frames running at one pitch."""
    frames = _frames_4k(pcm)
    if not frames:
        return False
    power = [sum(v * v for v in f) / len(f) for f in frames]
    floor = max(power) / 30
    loud = [i for i, p in enumerate(power) if p >= floor][: _VOICED_MAX_FRAMES * 2]
    pitch: dict[int, int] = {}
    for i in loud:
        corr, lag = _periodicity(frames[i])
        if corr >= _VOICED_CORR and lag:
            pitch[i] = lag
    run, best = 1, 1
    for i in sorted(pitch):
        prev = pitch.get(i - 1)
        run = run + 1 if prev is not None and abs(pitch[i] - prev) <= 2 else 1
        best = max(best, run)
    if best < 3 or len(pitch) < _VOICED_MIN_FRAMES:
        return False
    lags = list(pitch.values())
    powers = [power[i] for i in pitch]
    mean_lag = sum(lags) / len(lags)
    lag_spread = (sum((v - mean_lag) ** 2 for v in lags) / len(lags)) ** 0.5
    mean_p = sum(powers) / len(powers)
    power_cv = (sum((v - mean_p) ** 2 for v in powers) / len(powers)) ** 0.5 / mean_p
    # A hum: one pitch, one loudness.
    return lag_spread >= 1.0 or power_cv >= 0.25


def _worth_retry(said: str) -> bool:
    """A dropped request or a server error (the phone's `worthRetry`); a 4xx is not."""
    if "could not be reached" in said or "stopped answering" in said:
        return True
    match = re.search(r"HTTP (\d{3})", said)
    return bool(match) and int(match.group(1)) >= 500


def wav(pcm: bytes, rate: int = INPUT_RATE) -> bytes:
    """16-bit mono PCM in a 44-byte RIFF/WAVE header (`CloudSpeechWire.wav`)."""
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    header += b"data" + struct.pack("<I", len(pcm))
    return header + pcm


def wav_header_length(chunk: bytes) -> int:
    """How many leading bytes of a first chunk are a WAV header, or 0."""
    if len(chunk) < 44 or chunk[:4] != b"RIFF":
        return 0
    at = 12
    while at + 8 <= len(chunk):
        ident = chunk[at : at + 4]
        (length,) = struct.unpack("<I", chunk[at + 4 : at + 8])
        if ident == b"data":
            return at + 8
        at += 8 + length
    return 44


# What a transcriber writes for audio that held no words (round 2 speech review 10): a bracketed
# or starred note, a "no speech" sentence, or punctuation alone. Any of these is no speech.
_NO_SPEECH = re.compile(
    # A note in brackets or stars and nothing else ("[inaudible]", "(silence)", "*music*"), or a
    # plain "no speech" sentence. A spoken "Nothing." is an answer and stays.
    r"^\s*(?:[\[\(\*<][^\]\)\*>]{1,40}[\]\)\*>]\s*)+$"
    r"|^\s*(?:no (?:intelligible |audible |clear )?(?:speech|audio|words)"
    r"(?: (?:detected|present|heard|found))?|inaudible|unintelligible|silence)[\s.!]*$",
    re.I,
)
_ONLY_PUNCTUATION = re.compile(r"^[\W_]*$")


def clean_transcript(raw: str) -> str:
    """The words, or "" when the transcriber wrote no speech: a no-speech note, punctuation
    alone, or its own instruction echoed back (round 2 speech review 10)."""
    text = raw.strip()
    if len(text) >= 2 and text[0] in _QUOTES and text[-1] in _QUOTES:
        text = text[1:-1].strip()
    if _ONLY_PUNCTUATION.match(text) or _NO_SPEECH.match(text):
        return ""
    # Words around a marker are real speech and are kept as written (round 3 speech, small c:
    # "(two percent)" was cut from a real sentence).
    if _echoes_the_instruction(text):
        return ""
    return text


def _echoes_the_instruction(text: str) -> bool:
    """True when the transcript is the ask or a run of the instruction's own words."""
    words = re.findall(r"[a-z']+", text.lower())
    if not words:
        return False
    if " ".join(words) == " ".join(re.findall(r"[a-z']+", STT_ASK.lower())):
        return True
    instruction = " ".join(re.findall(r"[a-z']+", STT_INSTRUCTION.lower()))
    return len(words) >= 4 and " ".join(words) in instruction


def transcribe_body(pcm: bytes) -> dict:
    return {
        "systemInstruction": {"parts": [{"text": STT_INSTRUCTION}]},
        "contents": [
            {
                "role": "user",
                "parts": [
                    {
                        "inlineData": {
                            "mimeType": "audio/wav",
                            "data": base64.b64encode(wav(pcm)).decode("ascii"),
                        }
                    },
                    {"text": STT_ASK},
                ],
            }
        ],
        "generationConfig": {"maxOutputTokens": STT_MAX_OUTPUT_TOKENS},
    }


# What a refused voice name is spoken in instead (the phone's own voice).
FALLBACK_VOICE = "Puck"
# How long a voice Google refused by name is spoken in the fallback before it is tried again
# (round 3 speech S3: not for the life of the process).
BAD_VOICE_S = 300.0
# Google's words for a voice name it does not have (probed 2026-11-17: the legacy shape says
# this; the new shape answers only "Request contains an invalid argument.").
_NO_SUCH_VOICE = re.compile(r"no matching speaker voice|voice.*not (found|supported)", re.I)


def speak_body(text: str, voice: str, *, legacy_voice: bool) -> dict:
    shape = {"prebuiltVoiceConfig": {"voiceName": voice}} if legacy_voice else {"voice": voice}
    return {
        "contents": [{"role": "user", "parts": [{"text": text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": shape},
        },
    }


@dataclass(frozen=True)
class Heard:
    text: str
    audio_ms: int
    ms: int
    prompt_tokens: int = 0
    output_tokens: int = 0


@dataclass
class Spoken:
    """What one sentence's synthesis cost, for the timing line (no content)."""

    audio_ms: int = 0
    first_audio_ms: int | None = None
    prompt_tokens: int = 0
    output_tokens: int = 0
    failed: str | None = None
    # The voice that actually spoke it (a refused voice is spoken in FALLBACK_VOICE): audio is
    # kept under this voice only (round 3 speech S3).
    voice: str | None = None


class SpeechFailure(ModelFailure):
    """Speech could not be had. `str(exc)` is a short token, safe to log; never content."""


def _events(lines: Iterable[bytes]) -> Iterator[dict]:
    for raw in lines:
        line = raw.decode("utf-8", "replace").strip() if isinstance(raw, bytes) else raw.strip()
        if not line.startswith("data:"):
            continue
        try:
            yield json.loads(line[5:].strip())
        except ValueError:
            continue


def _parts(ev: dict) -> list[dict]:
    cands = ev.get("candidates") or []
    cand = cands[0] if cands and isinstance(cands[0], dict) else {}
    return [p for p in (cand.get("content") or {}).get("parts") or [] if isinstance(p, dict)]


class GeminiSpeech:
    """Speech-to-text and text-to-speech on the household's key. `transport` is
    `agents.runtime.model.UrllibTransport` or a test's fake with the same `lines`."""

    def __init__(
        self,
        key: Callable[[], str | None],
        *,
        stt_model: str,
        transport=None,
        api: str = API,
        clock: Callable[[], float] | None = None,
    ):
        import time

        self._key = key
        self._stt_model = stt_model
        self._transport = transport or UrllibTransport()
        self._api = api
        self._clock = clock or time.monotonic
        # Voices Google refused by name, until when (round 3 speech S3): spoken in
        # FALLBACK_VOICE instead until then.
        self.bad_voices: dict[str, float] = {}
        self.stt_sent = 0

    def _headers(self) -> dict:
        key = self._key()
        if not key:
            raise SpeechFailure("no_key")
        return {"Content-Type": "application/json", "x-goog-api-key": key}

    def _stream(self, model: str, body: dict, timeout: float) -> Iterator[dict]:
        url = f"{self._api}/models/{model}:streamGenerateContent?alt=sse"
        data = json.dumps(body).encode()
        yield from _events(self._transport.lines(url, self._headers(), data, timeout))

    def transcribe(self, pcm: bytes) -> Heard:
        """The words. A dropped request or a server error is tried once more; then it is a
        `SpeechFailure`, which the turn says aloud as a miss (finding 8)."""
        try:
            return self._transcribe_once(pcm)
        except SpeechFailure:
            raise
        except ModelFailure as exc:
            if not _worth_retry(str(exc)):
                raise SpeechFailure("stt_failed") from None
        try:
            return self._transcribe_once(pcm)
        except SpeechFailure:
            raise
        except ModelFailure:
            raise SpeechFailure("stt_unreachable") from None

    def _transcribe_once(self, pcm: bytes) -> Heard:
        audio_ms = len(pcm) // 32
        if len(pcm) < MIN_AUDIO_BYTES or loudest_100ms(pcm) < LOUDNESS_GATE_RMS:
            return Heard("", audio_ms, 0)
        if not voiced_speech(pcm):
            # Loud enough but no voice in it: never sent, so never invented into words.
            return Heard("", audio_ms, 0)
        started = self._clock()
        text, usage = [], {}
        # Requests actually sent, a retry included: what the turn is charged for (round 3).
        self.stt_sent += 1
        for ev in self._stream(self._stt_model, transcribe_body(pcm), STT_TIMEOUT_S):
            if isinstance(ev.get("error"), dict):
                raise SpeechFailure(f"stt_error_{ev['error'].get('code', '?')}")
            if (ev.get("promptFeedback") or {}).get("blockReason"):
                raise SpeechFailure("stt_blocked")
            if isinstance(ev.get("usageMetadata"), dict):
                usage = ev["usageMetadata"]
            for part in _parts(ev):
                if isinstance(part.get("text"), str) and not part.get("thought"):
                    text.append(part["text"])
        return Heard(
            clean_transcript("".join(text)),
            audio_ms,
            int((self._clock() - started) * 1000),
            int(usage.get("promptTokenCount") or 0),
            int(usage.get("candidatesTokenCount") or 0),
        )

    def speak(self, text: str, voice: str, spoken: Spoken) -> Iterator[bytes]:
        """Yields whole 16-bit samples of `text` in `voice`, 24 kHz mono, as they arrive.
        `spoken` is filled in as it goes. Before any audio, the phone's two retries
        (`CloudSpeech.kt` 459-467): a 400 is tried once more with the legacy voice shape, and a
        dropped request or a server error once more as it was. A failure after audio has played
        ends the sentence there (a retry would say its start twice)."""
        started = self._clock()
        sent = 0
        retried = False
        legacy = False
        until = self.bad_voices.get(voice)
        if until is not None and self._clock() < until:
            voice = FALLBACK_VOICE
        else:
            self.bad_voices.pop(voice, None)
        while True:
            try:
                spoken.voice = voice
                for chunk in self._speak_once(text, voice, spoken, started, legacy):
                    sent += len(chunk)
                    yield chunk
                return
            except ModelFailure as exc:
                said = str(exc)
                if sent:
                    raise SpeechFailure(spoken.failed or "tts_failed") from None
                if "400" in said and _NO_SUCH_VOICE.search(said) and voice != FALLBACK_VOICE:
                    # Google says it has no voice by this name: said in the fallback voice rather
                    # than not at all (Kevin, 2026-11-17), for a few minutes (round 3 S3).
                    self.bad_voices[voice] = self._clock() + BAD_VOICE_S
                    voice, legacy = FALLBACK_VOICE, False
                elif "400" in said and not legacy:
                    # The new voice shape refused: this request once more in the older shape,
                    # whose error names the voice when the voice is the problem. Nothing is kept.
                    legacy = True
                elif _worth_retry(said) and not retried:
                    retried = True
                else:
                    raise SpeechFailure(spoken.failed or "tts_failed") from None
                spoken.failed = None

    def _speak_once(
        self, text, voice, spoken: Spoken, started: float, legacy: bool = False
    ) -> Iterator[bytes]:
        first = True
        odd = b""
        body = speak_body(text, voice, legacy_voice=legacy)
        try:
            for ev in self._stream(TTS_MODEL, body, TTS_TIMEOUT_S):
                if isinstance(ev.get("error"), dict):
                    spoken.failed = f"tts_error_{ev['error'].get('code', '?')}"
                    raise ModelFailure(f"Gemini answered an error ({ev['error'].get('code')})")
                if isinstance(ev.get("usageMetadata"), dict):
                    usage = ev["usageMetadata"]
                    spoken.prompt_tokens = int(usage.get("promptTokenCount") or 0)
                    spoken.output_tokens = int(usage.get("candidatesTokenCount") or 0)
                for part in _parts(ev):
                    data = (part.get("inlineData") or {}).get("data")
                    if not data:
                        continue
                    try:
                        chunk = base64.b64decode(data)
                    except ValueError:
                        continue
                    if first:
                        first = False
                        chunk = chunk[wav_header_length(chunk) :]
                    chunk = odd + chunk
                    odd = b""
                    if len(chunk) % 2:
                        odd, chunk = chunk[-1:], chunk[:-1]
                    if not chunk:
                        continue
                    if spoken.first_audio_ms is None:
                        spoken.first_audio_ms = int((self._clock() - started) * 1000)
                    spoken.audio_ms += len(chunk) // 48
                    yield chunk
        except ModelFailure:
            spoken.failed = spoken.failed or "tts_failed"
            raise
