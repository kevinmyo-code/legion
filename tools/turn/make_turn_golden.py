"""Golden vectors for the phone's Smart Turn / Silero port (WhisperLogMelGoldenTest, TurnModelsGoldenTest).

Writes app/src/test/resources/turn/:
  golden_<name>.pcm   16 kHz mono int16 LE, synthetic (no real voice is committed)
  golden_<name>.f32   WhisperFeatureExtractor(chunk_length=8, do_normalize=True) features, float32 LE [80*800]
  golden.json         Smart Turn v3.2 cpu probability per vector, Silero v6.2 probabilities per 32 ms window

Two more vectors (2026-11-20), far from the 0.5 end-of-turn threshold, for decision-level asserts:
  far_high  a falling-pitch phrase with a decaying envelope and 0.8 s of room tone (about 0.96)
  far_low   synth()'s voice cut off mid-syllable, no tail (about 0.01)
Both stay on their side of 0.5 under feature noise up to 2e-3 and at every ORT optimisation level.
`--far-only` writes just these two and adds them to an existing golden.json, leaving the rest as is.

Feature extraction and inference exactly as the offline end-of-turn eval (pipecat smart-turn inference.py):
last 8 s, zero-padded at the front, padding="max_length", truncation=True.

Needs numpy, transformers, onnxruntime and the two models. Run with -I from any directory:
  python -I tools/turn/make_turn_golden.py <models_dir>
where <models_dir> holds smart-turn/smart-turn-v3.2-cpu.onnx and silero/silero_vad.onnx, or pass nothing to use
app/src/main/assets/turn/.
"""
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from transformers import WhisperFeatureExtractor

SR = 16000
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "turn")


def synth(seconds, seed):
    """Voiced, syllable-modulated harmonics with a little noise, then 0.6 s of room tone."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    f0 = 120 + 40 * np.sin(2 * np.pi * 0.7 * t)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    voice = sum((0.6 / h) * np.sin(h * phase) for h in range(1, 12))
    env = np.clip(np.sin(2 * np.pi * 3.5 * t), 0, None) ** 0.6
    x = 0.25 * voice * env + 0.003 * rng.standard_normal(n)
    tail = int(0.6 * SR)
    x[-tail:] = 0.003 * rng.standard_normal(tail)
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)
    return pcm


def synth_far_high(seed=5):
    """5 s of syllables on a pitch falling 160 to 140 Hz, fading out, then 0.8 s of room tone."""
    rng = np.random.default_rng(seed)
    seconds = 5.0
    n = int(seconds * SR)
    t = np.arange(n) / SR
    f0 = 160 + (140 - 160) * (t / seconds)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    voice = sum((0.6 / h) * np.sin(h * phase) for h in range(1, 12))
    env = np.clip(np.sin(2 * np.pi * 4.0 * t), 0, None) ** 0.6 * np.exp(-1.5 * t / seconds)
    x = 0.25 * voice * env + 0.003 * rng.standard_normal(n)
    x = np.concatenate([x, 0.003 * rng.standard_normal(int(0.8 * SR))])
    return np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)


def synth_far_low(seconds=2.0, seed=3):
    """synth()'s voice with no tail: it stops mid-syllable."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    f0 = 120 + 40 * np.sin(2 * np.pi * 0.7 * t)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    voice = sum((0.6 / h) * np.sin(h * phase) for h in range(1, 12))
    env = np.clip(np.sin(2 * np.pi * 3.5 * t), 0, None) ** 0.6
    x = 0.25 * voice * env + 0.003 * rng.standard_normal(n)
    return np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)


def write_vector(fe, st, meta, name, pcm):
    audio = pcm.astype(np.float32) / 32768.0
    f = features(fe, audio)
    prob = float(st.run(None, {"input_features": f})[0][0, 0])
    pcm.astype("<i2").tofile(os.path.join(OUT, f"golden_{name}.pcm"))
    f.reshape(-1).astype("<f4").tofile(os.path.join(OUT, f"golden_{name}.f32"))
    meta["vectors"][name] = {"samples": int(len(pcm)), "smart_turn_prob": prob}
    print(name, len(pcm), prob)


def features(fe, audio):
    n = 8 * SR
    audio = audio[-n:] if len(audio) > n else np.pad(audio, (n - len(audio), 0))
    f = fe(audio, sampling_rate=SR, return_tensors="np", padding="max_length", max_length=n,
           truncation=True, do_normalize=True)
    return f.input_features.astype(np.float32)


def silero_probs(path, pcm):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(path, sess_options=so, providers=["CPUExecutionProvider"])
    x = pcm.astype(np.float32) / 32768.0
    state = np.zeros((2, 1, 128), dtype=np.float32)
    ctx = np.zeros((1, 64), dtype=np.float32)
    sr = np.array(SR, dtype=np.int64)
    out = []
    for i in range(len(x) // 512):
        inp = np.concatenate([ctx, x[i * 512:(i + 1) * 512][None, :]], axis=1)
        p, state = sess.run(None, {"input": inp, "state": state, "sr": sr})
        ctx = inp[:, -64:]
        out.append(float(p[0, 0]))
    return out


def main():
    far_only = "--far-only" in sys.argv
    sys.argv = [a for a in sys.argv if a != "--far-only"]
    if len(sys.argv) > 1:
        st_path = os.path.join(sys.argv[1], "smart-turn", "smart-turn-v3.2-cpu.onnx")
        vad_path = os.path.join(sys.argv[1], "silero", "silero_vad.onnx")
    else:
        assets = os.path.join(ROOT, "app", "src", "main", "assets", "turn")
        st_path = os.path.join(assets, "smart-turn-v3.2-cpu.onnx")
        vad_path = os.path.join(assets, "silero_vad_v6_2.onnx")
    os.makedirs(OUT, exist_ok=True)
    fe = WhisperFeatureExtractor(chunk_length=8)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    st = ort.InferenceSession(st_path, sess_options=so, providers=["CPUExecutionProvider"])
    golden = os.path.join(OUT, "golden.json")
    if far_only:
        with open(golden) as fh:
            meta = json.load(fh)
    else:
        meta = {"vectors": {}}
        for name, seconds, seed in (("short", 3.2, 1), ("long", 9.0, 2)):
            write_vector(fe, st, meta, name, synth(seconds, seed))
    write_vector(fe, st, meta, "far_high", synth_far_high())
    write_vector(fe, st, meta, "far_low", synth_far_low())
    if far_only:
        with open(golden, "w") as fh:
            json.dump(meta, fh, indent=1)
        return
    meta["silero_short"] = silero_probs(vad_path, np.fromfile(os.path.join(OUT, "golden_short.pcm"), dtype="<i2"))
    with open(os.path.join(OUT, "golden.json"), "w") as fh:
        json.dump(meta, fh, indent=1)


if __name__ == "__main__":
    main()
