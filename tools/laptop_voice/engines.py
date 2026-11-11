"""Speech engines for the laptop voice service.

Each engine is synchronous and runs on the service's single GPU worker thread. Models and caches
live outside the repo (LEGION_VOICE_MODELS, default ~/.legion/laptop-voice/models). Nothing here
writes audio or text to disk.
"""

from __future__ import annotations

import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Protocol

import numpy as np

STT_RATE = 16_000
TTS_RATE = 24_000

MODELS_DIR = Path(os.environ.get("LEGION_VOICE_MODELS", Path.home() / ".legion" / "laptop-voice" / "models"))


@dataclass
class SttResult:
    text: str
    lang: str | None
    lang_prob: float | None


class SttEngine(Protocol):
    name: str
    languages: str

    def transcribe(self, pcm: np.ndarray, lang: str | None) -> SttResult: ...


class TtsEngine(Protocol):
    name: str
    voices: list[str]
    default_voice: str

    def synth(self, text: str, voice: str, speed: float) -> Iterator[np.ndarray]: ...


def add_cuda_dll_dirs() -> None:
    """cuBLAS / cuDNN come from the nvidia-* pip wheels; make their DLLs findable on Windows."""
    if os.name != "nt":
        return
    import importlib.util

    spec = importlib.util.find_spec("nvidia")
    if spec is None or not spec.submodule_search_locations:
        return
    for root in spec.submodule_search_locations:
        for b in sorted(Path(root).glob("*/bin")):
            os.add_dll_directory(str(b))
            os.environ["PATH"] = str(b) + os.pathsep + os.environ.get("PATH", "")


# Clause splitting: the first audio waits for the first piece, so keep pieces short.
_SPLIT = re.compile(r"(?<=[.!?;:])\s+|(?<=,)\s+(?=\S+(?:\s+\S+){3,})")


def split_for_speech(text: str, max_words: int = 14, first_max_words: int = 8) -> list[str]:
    """Clause pieces for synthesis. The first piece is kept to 8 words or fewer (first audio waits
    for it), later ones to 14."""
    pieces: list[str] = []
    for part in _SPLIT.split(text.strip()):
        part = part.strip()
        if not part:
            continue
        words = part.split()
        while words:
            cap = first_max_words if not pieces else max_words
            if len(words) <= cap:
                pieces.append(" ".join(words))
                break
            pieces.append(" ".join(words[:cap]))
            words = words[cap:]
    return pieces


class WhisperStt:
    """faster-whisper (CTranslate2) on CUDA. Multilingual with language detection."""

    languages = "multi"

    def __init__(self, model: str = "large-v3-turbo", device: str = "cuda", compute_type: str = "float16"):
        add_cuda_dll_dirs()
        from faster_whisper import WhisperModel

        self.name = f"whisper-{model}"
        self.model = WhisperModel(model, device=device, compute_type=compute_type,
                                  download_root=str(MODELS_DIR / "faster-whisper"))

    def transcribe(self, pcm: np.ndarray, lang: str | None) -> SttResult:
        audio = pcm.astype(np.float32) / 32768.0
        segments, info = self.model.transcribe(
            audio, language=lang, beam_size=1, vad_filter=False, condition_on_previous_text=False,
            without_timestamps=True)
        text = " ".join(s.text.strip() for s in segments).strip()
        return SttResult(text=text, lang=info.language, lang_prob=round(float(info.language_probability), 3))


class ParakeetStt:
    """NVIDIA Parakeet TDT 0.6b v3 via onnx-asr on onnxruntime CUDA. 25 European languages."""

    languages = "25-eu"

    def __init__(self, model: str = "nemo-parakeet-tdt-0.6b-v3"):
        add_cuda_dll_dirs()
        import onnx_asr
        import onnxruntime as ort

        self.name = model
        providers = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider") if p in ort.get_available_providers()]
        self.model = onnx_asr.load_model(model, path=str(MODELS_DIR / model), providers=providers)

    def transcribe(self, pcm: np.ndarray, lang: str | None) -> SttResult:
        audio = pcm.astype(np.float32) / 32768.0
        text = self.model.recognize(audio, sample_rate=STT_RATE)
        return SttResult(text=str(text).strip(), lang=None, lang_prob=None)


class KokoroTts:
    """Kokoro-82M v1.0 through kokoro-onnx on onnxruntime CUDA."""

    def __init__(self, default_voice: str = "am_puck"):
        add_cuda_dll_dirs()
        import onnxruntime as ort
        from kokoro_onnx import Kokoro

        d = MODELS_DIR / "kokoro"
        model, voices = d / "kokoro-v1.0.onnx", d / "voices-v1.0.bin"
        providers = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider") if p in ort.get_available_providers()]
        sess = ort.InferenceSession(str(model), providers=providers)
        self.kokoro = Kokoro.from_session(sess, str(voices))
        self.name = "kokoro-v1.0"
        self.voices = sorted(self.kokoro.get_voices())
        self.default_voice = default_voice
        self.providers = sess.get_providers()

    def synth(self, text: str, voice: str, speed: float) -> Iterator[np.ndarray]:
        lang = "en-gb" if voice.startswith(("bf_", "bm_")) else "en-us"
        for piece in split_for_speech(text):
            samples, sr = self.kokoro.create(piece, voice=voice, speed=speed, lang=lang)
            x = np.asarray(samples, dtype=np.float32)
            if sr != TTS_RATE:
                raise RuntimeError(f"unexpected sample rate {sr}")
            yield np.clip(x * 32767.0, -32768, 32767).astype(np.int16)


class FakeStt:
    """For tests: returns a fixed text after an optional delay."""

    name = "fake-stt"
    languages = "multi"

    def __init__(self, delay_s: float = 0.0, text: str = "set a timer for twelve minutes"):
        self.delay_s, self.text = delay_s, text

    def transcribe(self, pcm: np.ndarray, lang: str | None) -> SttResult:
        time.sleep(self.delay_s)
        return SttResult(text=self.text if len(pcm) else "", lang=lang or "en", lang_prob=1.0)


class FakeTts:
    """For tests: 220 Hz tone, 60 ms per character, with an optional delay per piece."""

    name = "fake-tts"
    voices = ["am_puck", "am_michael"]
    default_voice = "am_puck"

    def __init__(self, delay_s: float = 0.0):
        self.delay_s = delay_s

    def synth(self, text: str, voice: str, speed: float) -> Iterator[np.ndarray]:
        for piece in split_for_speech(text):
            time.sleep(self.delay_s)
            n = int(TTS_RATE * 0.06 * len(piece) / speed)
            t = np.arange(n) / TTS_RATE
            yield (np.sin(2 * np.pi * 220 * t) * 8000).astype(np.int16)


def load_kyutai(which: str):
    """Kyutai engines live in kyutai.py (optional dependency: the `moshi` package)."""
    try:
        from . import kyutai as K
    except ImportError:
        import kyutai as K  # type: ignore
    return K.KyutaiStt() if which == "stt" else K.KyutaiTts()


class FakeStreamingStt:
    """For tests: a streaming STT that ends the turn itself after 0.5 s of quiet following speech."""

    name = "fake-stream-stt"
    languages = "en"
    end_of_turn = True
    partials = True

    def __init__(self, text: str = "set a timer for twelve minutes", quiet_ms: int = 500, feed_delay_s: float = 0.0):
        self.text, self.quiet_ms, self.feed_delay_s = text, quiet_ms, feed_delay_s

    def transcribe(self, pcm: np.ndarray, lang: str | None) -> SttResult:
        return SttResult(self.text, "en", 1.0)

    def open_stream(self, lang: str | None):
        outer = self

        class Sess:
            heard = 0
            quiet = 0

            def feed(self, pcm: np.ndarray):
                time.sleep(outer.feed_delay_s)
                ev = []
                loud = np.abs(pcm.astype(np.int32)).mean() > 300
                ms = len(pcm) * 1000 // STT_RATE
                if loud:
                    self.heard += ms
                    self.quiet = 0
                    ev.append(("partial", " ".join(outer.text.split()[: 1 + self.heard // 400])))
                else:
                    self.quiet += ms
                if self.heard:
                    prob = min(1.0, self.quiet / outer.quiet_ms)
                    ev.append(("pause", (prob, self.quiet)))
                    if self.quiet >= outer.quiet_ms:
                        # like Kyutai: the event carries the text and the session moves on, so a
                        # finish() after it returns an empty string
                        ev.append(("end_of_turn", outer.text))
                        self.heard = 0
                return ev

            def finish(self) -> SttResult:
                return SttResult(outer.text if self.heard else "", "en", 1.0)

        return Sess()


class FakeStreamingTts(FakeTts):
    """For tests: speaks each whole word once it has arrived (a word is done at the next space, or at
    end()). Each word takes word_s to "generate", so pull() often returns nothing yet, like a model
    step that is slower than the pull timeout."""

    name = "fake-stream-tts"
    text_streaming = True

    def __init__(self, delay_s: float = 0.0, word_s: float = 0.0):
        super().__init__(delay_s)
        self.word_s = word_s

    def open_text_stream(self, voice: str, speed: float):
        word_s = self.word_s

        class Sess:
            buf = ""
            ended = False
            done = False
            ready_at = 0.0

            def push(self, text: str) -> None:
                self.buf += text

            def end(self) -> None:
                self.ended = True

            def pull(self, timeout: float = 0.0):
                if self.done:
                    return None
                if self.ended:
                    self.buf = self.buf.strip() + " " if self.buf.strip() else ""
                if " " not in self.buf.lstrip():
                    if self.ended:
                        self.done = True
                    return None
                now = time.monotonic()
                if not self.ready_at:
                    self.ready_at = now + word_s
                if now < self.ready_at:
                    time.sleep(min(timeout, self.ready_at - now))
                    if time.monotonic() < self.ready_at:
                        return None
                self.ready_at = time.monotonic() + word_s
                word, self.buf = self.buf.lstrip().split(" ", 1)
                n = int(TTS_RATE * 0.06 * len(word))
                return (np.sin(2 * np.pi * 220 * np.arange(n) / TTS_RATE) * 8000).astype(np.int16)

            def abort(self) -> None:
                self.buf, self.done = "", True

        return Sess()
