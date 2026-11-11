"""Reference vectors for the phone's openWakeWord port (OwwStreamGoldenTest).

Runs the official Python implementation (dscripka/openWakeWord, `openwakeword.Model`, ONNX
framework) on one invented clip, fed 1280 samples (80 ms) at a time, and writes:

- `golden.pcm`: the clip, 16 kHz mono int16 little endian
- `golden_features.f32`: per frame, the newest 96-value feature row (`preprocessor.feature_buffer[-1]`)
- `golden.json`: per-frame scores, the head file's sha256, sizes and provenance

The clip is seeded noise around one synthetic "hey <name>" utterance (a TTS clip, never a person's
recording), so it is safe in a public repo. Python's feature buffer starts from UNSEEDED random
noise, which no port can reproduce, so scores are only comparable once the head's input window holds
no start-up rows (`first_comparable_frame`); feature rows are comparable from frame 0.

Run (the training venv has openwakeword installed):

    python -I tools/openwakeword/make_oww_golden.py --mel <melspectrogram.onnx> \
        --embedding <embedding_model.onnx> --head <hey_alfred.onnx> --clip <tts.wav> \
        --out app/src/test/resources/openwakeword
"""
from __future__ import annotations

import argparse
import hashlib
import json
import wave
from pathlib import Path

import numpy as np

FRAME = 1280


def read_wav16k(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as f:
        assert f.getframerate() == 16000 and f.getnchannels() == 1 and f.getsampwidth() == 2, path
        return np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mel", required=True)
    ap.add_argument("--embedding", required=True)
    ap.add_argument("--head", required=True)
    ap.add_argument("--clip", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--clip-note", default="", help="where the TTS clip came from (voice, licence)")
    a = ap.parse_args()

    from openwakeword.model import Model

    rng = np.random.default_rng(a.seed)
    speech = read_wav16k(Path(a.clip))
    lead = rng.integers(-300, 300, 16000 * 2).astype(np.int16)
    tail = rng.integers(-300, 300, 16000).astype(np.int16)
    audio = np.concatenate([lead, speech, tail])
    audio = audio[: len(audio) // FRAME * FRAME]

    model = Model(
        wakeword_models=[a.head],
        inference_framework="onnx",
        melspec_model_path=a.mel,
        embedding_model_path=a.embedding,
    )
    name = list(model.models.keys())[0]
    frames = model.model_inputs[name]
    scores, features = [], []
    for i in range(0, len(audio), FRAME):
        p = model.predict(audio[i : i + FRAME])
        scores.append(float(p[name]))
        features.append(model.preprocessor.feature_buffer[-1].astype(np.float32))

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    audio.astype("<i2").tofile(out / "golden.pcm")
    np.stack(features).astype("<f4").tofile(out / "golden_features.f32")
    sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()  # noqa: E731
    meta = {
        "source": "openwakeword.Model (dscripka/openWakeWord 368c037), onnx, fed 1280 samples per call",
        "clip": "seeded noise (+-300) 2 s, one synthetic TTS utterance, 1 s noise; no human recording",
        "clip_source": a.clip_note,
        "frames": len(scores),
        "head_input_frames": int(frames),
        # Every head window from here on holds only rows computed from this audio.
        "first_comparable_frame": int(frames),
        "scores": scores,
        "mel_sha256": sha(a.mel),
        "embedding_sha256": sha(a.embedding),
        "head_sha256": sha(a.head),
    }
    (out / "golden.json").write_text(json.dumps(meta, indent=1))
    print(f"{len(scores)} frames, max score {max(scores):.4f} at frame {int(np.argmax(scores))}")


if __name__ == "__main__":
    main()
