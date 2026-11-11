"""Kyutai engines for the laptop voice service: streaming STT with its own end of turn (semantic VAD
pause head) and streaming TTS that can take text while the model is still writing.

Native Windows + CUDA through the PyTorch `moshi` package (no WSL). Separate venv from the
Whisper/Kokoro backend because moshi pins aiohttp<3.12 and brings torch:
    uv pip install torch==2.9.1 torchaudio==2.9.1 --index-url https://download.pytorch.org/whl/cu128
    uv pip install moshi==0.2.13 "aiohttp>=3.10.5,<3.12" soundfile psutil
Weights (Hugging Face, cached under HF_HOME, never in the repo): kyutai/stt-1b-en_fr-candle (the
repo whose config carries the pause heads), kyutai/tts-1.6b-en_fr, voices from kyutai/tts-voices.
Model code is in kyutai_core.py (written in the 2026-11-10 spike); this file adapts it to the
service's engine interfaces.
"""

from __future__ import annotations

import os
import queue
import threading

os.environ.setdefault("NO_TORCH_COMPILE", "1")  # torch.compile needs Triton, which Windows lacks

import numpy as np  # noqa: E402

try:
    from . import kyutai_core as core  # noqa: E402
    from .engines import SttResult  # noqa: E402
except ImportError:  # run as a script
    import kyutai_core as core  # type: ignore  # noqa: E402
    from engines import SttResult  # type: ignore  # noqa: E402


class _SttStream:
    """Adapts core.KyutaiSttSession to the service's (kind, value) events."""

    def __init__(self, eng: "KyutaiStt", lang: str | None):
        self.lang = lang
        self.s = eng.start()
        self.quiet_ms = 0

    def feed(self, pcm: np.ndarray):
        out = []
        loud = len(pcm) and np.abs(pcm.astype(np.int32)).mean() > 300
        self.quiet_ms = 0 if loud else self.quiet_ms + len(pcm) * 1000 // 16000
        for kind, value in self.s.feed(pcm):
            if kind == "pause":
                out.append(("pause", (value, self.quiet_ms)))
            else:
                out.append((kind, value))
        return out

    def finish(self) -> SttResult:
        return SttResult(text=self.s.finish(), lang=self.lang, lang_prob=None)


class KyutaiStt(core.KyutaiStt):
    end_of_turn = True
    partials = True
    max_sessions = 1          # batch size 1: one live stream at a time
    languages = "en,fr"

    def __init__(self, *a, **kw):
        # tuning knobs from the launcher (numbers only): pause threshold and extra flush
        kw.setdefault("pause_threshold", float(os.environ.get("LEGION_VOICE_KYUTAI_PAUSE", "0.6")))
        kw.setdefault("flush_extra_s", float(os.environ.get("LEGION_VOICE_KYUTAI_FLUSH_EXTRA_S", "1.6")))
        super().__init__(*a, **kw)

    def open_stream(self, lang: str | None):
        return _SttStream(self, lang)


class _TextStream:
    """Streamed text in, audio out. A producer thread runs the model's text-streaming generator;
    push() hands text over without blocking, pull() waits briefly for audio."""

    _DONE = object()

    def __init__(self, eng: "KyutaiTts", voice: str, speed: float):
        self.text_q: queue.Queue = queue.Queue()
        self.audio_q: queue.Queue = queue.Queue()
        self.aborted = False
        self.done = False
        self.error: BaseException | None = None
        self._ended = False

        def produce():
            gen = eng.synth_stream(iter(self.text_q.get, None), voice, speed)
            try:
                for chunk in gen:
                    if self.aborted:
                        break
                    self.audio_q.put(chunk)
            except BaseException as exc:  # noqa: BLE001 - handed to the server through pull()
                self.error = exc
            finally:
                try:
                    gen.close()
                except Exception:  # noqa: BLE001
                    pass
                self.audio_q.put(self._DONE)

        self.thread = threading.Thread(target=produce, name="kyutai-tts", daemon=True)
        self.thread.start()

    def push(self, text: str) -> None:
        if not self._ended:
            self.text_q.put(text)

    def end(self) -> None:
        """No more text. Non-blocking: the model finishes speaking and pull() reports done."""
        if not self._ended:
            self._ended = True
            self.text_q.put(None)

    def pull(self, timeout: float = 0.05):
        if self.done:
            return None
        try:
            item = self.audio_q.get(timeout=timeout)
        except queue.Empty:
            return None
        if item is self._DONE:
            self.done = True
            if self.error is not None:
                raise RuntimeError(f"kyutai tts failed: {type(self.error).__name__}") from self.error
            return None
        return item

    def finish(self) -> list:
        self.end()
        out = []
        while not self.done:
            item = self.pull(timeout=10)
            if item is not None:
                out.append(item)
        self.thread.join(timeout=5)
        return out

    def abort(self) -> None:
        self.aborted = True
        self.end()
        self.thread.join(timeout=5)


class KyutaiTts(core.KyutaiTts):
    text_streaming = True
    max_sessions = 1

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.allowlist = list(core.VOICES)   # fixed ids; the engine maps each to one file in kyutai/tts-voices
        self.voices = self.allowlist

    def open_text_stream(self, voice: str, speed: float):
        return _TextStream(self, voice, speed)
