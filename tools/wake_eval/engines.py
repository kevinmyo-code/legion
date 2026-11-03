"""Native half of the wake-word offline eval (wake-word ticket 20): sherpa-onnx and vosk.

Imported lazily by `wake_eval.py` so `core.py` and its tests need neither. API shapes were
read off sherpa-onnx 1.13.8's Python wheel (the version the app's AAR is) with `inspect` on
2026-10-30: `KeywordSpotter(tokens, encoder, decoder, joiner, keywords_file, num_threads,
sample_rate, feature_dim, max_active_paths, keywords_score, keywords_threshold,
num_trailing_blanks, ...)`, `get_result(stream) -> str`, and
`VoiceActivityDetector(VadModelConfig, buffer_size_in_seconds)`.

The model files are the app's own assets, never a download: `app/src/main/assets/wake-kws/`
and `app/src/main/assets/vosk-model/` (the latter fetched locally per its README).
"""
from __future__ import annotations

import json
import tempfile
from pathlib import Path

import core

REPO = Path(__file__).resolve().parents[2]
KWS_DIR = REPO / "app" / "src" / "main" / "assets" / "wake-kws"
VOSK_DIR = REPO / "app" / "src" / "main" / "assets" / "vosk-model"
ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"


class SherpaVad:
    """Silero VAD with the app's config. Only the flag is read; segments are drained."""

    def __init__(self, kws_dir: Path = KWS_DIR):
        import sherpa_onnx

        silero = sherpa_onnx.SileroVadModelConfig(
            model=str(kws_dir / "silero_vad.onnx"), **core.APP_VAD
        )
        cfg = sherpa_onnx.VadModelConfig(silero_vad=silero, sample_rate=core.SAMPLE_RATE, num_threads=1)
        self._vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)

    def accept(self, floats):
        self._vad.accept_waveform(floats)

    def is_speech(self) -> bool:
        speech = self._vad.is_speech_detected()
        while not self._vad.empty():
            self._vad.pop()
        return speech


class SherpaKws:
    """One spotter per operating point, its keywords file holding one spelling."""

    def __init__(self, point: core.Point, kws_dir: Path = KWS_DIR):
        import sherpa_onnx

        self._tmp = tempfile.TemporaryDirectory(prefix="wake_eval_")
        kw_file = Path(self._tmp.name) / "keywords.txt"
        kw_file.write_text(core.keyword_line(point.spelling) + "\n", encoding="utf-8")
        self._spotter = sherpa_onnx.KeywordSpotter(
            tokens=str(kws_dir / "tokens.txt"),
            encoder=str(kws_dir / ENCODER),
            decoder=str(kws_dir / DECODER),
            joiner=str(kws_dir / JOINER),
            keywords_file=str(kw_file),
            num_threads=1,
            sample_rate=core.SAMPLE_RATE,
            feature_dim=80,
            max_active_paths=core.APP_MAX_ACTIVE_PATHS,
            keywords_score=point.keywords_score,
            keywords_threshold=point.keywords_threshold,
            num_trailing_blanks=point.num_trailing_blanks,
        )
        self._stream = self._spotter.create_stream()

    def accept(self, floats):
        self._stream.accept_waveform(core.SAMPLE_RATE, floats)

    def is_ready(self) -> bool:
        return self._spotter.is_ready(self._stream)

    def decode(self):
        self._spotter.decode_stream(self._stream)

    def keyword(self) -> str:
        return self._spotter.get_result(self._stream)

    def fresh(self):
        self._stream = self._spotter.create_stream()


_VOSK_MODELS: dict = {}


def _vosk_model(vosk_dir: Path):
    """One loaded Vosk model per directory: it is the slow part, and it is read-only."""
    import vosk

    key = str(vosk_dir)
    if key not in _VOSK_MODELS:
        vosk.SetLogLevel(-1)
        _VOSK_MODELS[key] = vosk.Model(key)
    return _VOSK_MODELS[key]


class VoskConfirm:
    """Stage 2 as WakeWordEngine.confirmWithVosk: grammar [phrase, "[unk]"], reset after each."""

    def __init__(self, policy: str, vosk_dir: Path = VOSK_DIR, phrase: str = core.APP_PHRASE):
        import vosk

        self._policy = policy
        self._phrase = phrase
        grammar = json.dumps([phrase, "[unk]"])
        self._rec = vosk.KaldiRecognizer(_vosk_model(vosk_dir), core.SAMPLE_RATE, grammar)
        self._rec.SetWords(True)

    def __call__(self, pcm: list[int]) -> tuple[bool, str]:
        data = b"".join(int(s).to_bytes(2, "little", signed=True) for s in pcm)
        self._rec.AcceptWaveform(data)
        raw = self._rec.FinalResult()
        self._rec.Reset()
        text = json.loads(raw).get("text", "").strip()
        return core.vosk_text_accepts(self._policy, text, self._phrase), text
