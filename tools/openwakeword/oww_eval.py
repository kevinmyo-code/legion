"""Offline openWakeWord eval: misses on positive clips, false accepts per hour on negative audio.

Scores audio exactly the way the phone's OwwStream does (and `openwakeword.Model.predict` fed
1280-sample frames): per 80 ms frame, the embedding of the newest 76 mel rows (the mel buffer starts
as 76 rows of ones), the head on the newest N feature rows, the first five scores zeroed. It does it
with the embedding and head batched where the models allow; `--check` compares it with
`openwakeword.Model` streaming on one file first.

Decisions copy OwwDecider: a frame at or above the threshold opens, and no second open within 4 s.
- Positive set: each clip gets 2 s of lead-in and 1 s after (silence, or a noise bed); a clip is
  caught when any frame opens. Miss rate = clips never caught.
- Negative set: each file is one continuous stream; every open is a false accept. Rate per hour of
  audio. A set with zero false accepts only supports "fewer than about 1 / hours".
- Near-phrase set: like positives, reported as the share of clips that open.

    python -I tools/openwakeword/oww_eval.py --mel M --embedding E --head H \
        --positive DIR [--positive DIR ...] --negative DIR [...] --near DIR \
        --thresholds 0.3,0.4,0.5,0.6,0.7 --out result.json
"""
from __future__ import annotations

import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

FRAME = 1280
GAP_FRAMES = 50  # 4 s / 80 ms, WakeWordEngine.MIN_TRIGGER_GAP_MS
WARMUP = 5


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as f:
        assert f.getframerate() == 16000 and f.getnchannels() == 1 and f.getsampwidth() == 2, path
        return np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)


class Scorer:
    def __init__(self, mel: str, embedding: str, head: str, seed: int = 0):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        cpu = ["CPUExecutionProvider"]
        self.mel = ort.InferenceSession(mel, opts, providers=cpu)
        self.emb = ort.InferenceSession(embedding, opts, providers=cpu)
        self.head = ort.InferenceSession(head, opts, providers=cpu)
        self.head_in = self.head.get_inputs()[0].name
        self.n = int(self.head.get_inputs()[0].shape[1])
        rng = np.random.default_rng(seed)
        noise = rng.integers(-1000, 1000, 16000 * 4).astype(np.float32)
        rows = self._mel(noise)
        self.init_features = self._embed(np.stack([rows[i:i + 76] for i in range(0, rows.shape[0] - 75, 8)]))

    def _mel(self, x: np.ndarray) -> np.ndarray:
        out = self.mel.run(None, {"input": x[None, :].astype(np.float32)})[0]
        return np.squeeze(out, axis=(0, 1)) / 10 + 2

    def _embed(self, windows: np.ndarray) -> np.ndarray:
        out = []
        for i in range(0, len(windows), 512):
            out.append(self.emb.run(None, {"input_1": windows[i:i + 512, :, :, None].astype(np.float32)})[0])
        return np.concatenate(out).reshape(-1, 96)

    def scores(self, audio: np.ndarray) -> np.ndarray:
        """One score per whole 1280-sample frame, as the streaming port gives them."""
        n_frames = len(audio) // FRAME
        if n_frames == 0:
            return np.zeros(0, np.float32)
        audio = audio[: n_frames * FRAME].astype(np.float32)
        # The mel model is NOT frame-local (its dB floor follows the loudest value in its input), so
        # a whole-clip mel differs from the streamed one by up to 0.13 in score. Run it exactly as
        # the phone does: per frame, on that frame plus the 480 samples before it.
        rows = [self._mel(audio[max(0, (k + 1) * FRAME - FRAME - 480): (k + 1) * FRAME]) for k in range(n_frames)]
        mel = np.concatenate([np.ones((76, 32), np.float32)] + rows)
        ends = 76 + 8 * np.arange(n_frames) + 5  # exclusive end of each frame's window
        windows = np.stack([mel[e - 76:e] for e in ends])
        feats = np.concatenate([self.init_features, self._embed(windows)])
        k0 = len(self.init_features)
        heads = np.stack([feats[k0 + f + 1 - self.n: k0 + f + 1] for f in range(n_frames)])
        # train.py exports the head with batch fixed at 1, as the phone runs it.
        heads = heads.astype(np.float32)
        s = np.array([self.head.run(None, {self.head_in: heads[i:i + 1]})[0][0, 0] for i in range(len(heads))],
                     dtype=np.float32)
        s[:WARMUP] = 0
        return s


def opens(scores: np.ndarray, threshold: float) -> int:
    n, last = 0, -10 ** 9
    for i in np.flatnonzero(scores >= threshold):
        if i - last >= GAP_FRAMES:
            n += 1
            last = i
    return n


def pad(clip: np.ndarray, bed: np.ndarray | None, snr_db: float | None, rng) -> np.ndarray:
    lead, tail = 16000 * 2, 16000
    total = lead + len(clip) + tail
    # A microphone never gives exact digital zeros, and on exact zeros the batch mel and the
    # streamed mel differ (openwakeword.utils notes it): a noise floor about -70 dBFS, seeded per
    # clip so every engine and every run hears the same one.
    x = np.random.default_rng(len(clip)).normal(0, 10, total).astype(np.float32)
    x[lead: lead + len(clip)] += clip
    if bed is not None and snr_db is not None:
        s = rng.integers(0, max(1, len(bed) - total))
        noise = bed[s: s + total].astype(np.float32)
        if len(noise) < total:
            noise = np.resize(noise, total)
        p_sig = np.mean(clip.astype(np.float32) ** 2)
        p_noise = np.mean(noise ** 2) + 1e-9
        x += noise * np.sqrt(p_sig / (p_noise * 10 ** (snr_db / 10)))
    return np.clip(x, -32768, 32767).astype(np.int16)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mel", required=True)
    ap.add_argument("--embedding", required=True)
    ap.add_argument("--head", required=True)
    ap.add_argument("--positive", action="append", default=[])
    ap.add_argument("--near", action="append", default=[])
    ap.add_argument("--negative", action="append", default=[])
    ap.add_argument("--noise-bed", help="dir of WAVs mixed under positives at --snr (held out from training)")
    ap.add_argument("--snr", default="", help="comma list of SNR dB for noisy positive rows")
    ap.add_argument("--levels", default="0", help="comma list of dB gains for clean positive rows")
    ap.add_argument("--thresholds", default="0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9")
    ap.add_argument("--check", help="a WAV to compare against openwakeword.Model streaming first")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    th = [float(t) for t in a.thresholds.split(",")]
    sc = Scorer(a.mel, a.embedding, a.head)
    result: dict = {"thresholds": th, "head": a.head, "gap_frames": GAP_FRAMES}
    rng = np.random.default_rng(7)

    if a.check:
        from openwakeword.model import Model

        m = Model(wakeword_models=[a.head], inference_framework="onnx",
                  melspec_model_path=a.mel, embedding_model_path=a.embedding)
        name = list(m.models)[0]
        x = pad(read_wav(Path(a.check)).astype(np.float32), None, None, rng)
        x = x[: len(x) // FRAME * FRAME]
        py = np.array([m.predict(x[i:i + FRAME])[name] for i in range(0, len(x), FRAME)])
        ours = sc.scores(x)
        diff = float(np.max(np.abs(py[sc.n:] - ours[sc.n:])))
        result["check"] = {"file": Path(a.check).name, "frames": len(py), "max_abs_diff_from_frame_n": diff}
        print("check vs openwakeword.Model: max |diff| =", diff, flush=True)

    bed = None
    if a.noise_bed:
        bed = np.concatenate([read_wav(p) for p in sorted(Path(a.noise_bed).glob("*.wav"))])

    def clip_set(dirs):
        return [(p, read_wav(p)) for d in dirs for p in sorted(Path(d).glob("*.wav"))]

    def caught_rate(clips, gain_db=0.0, snr=None):
        peaks = []
        for _, c in clips:
            c = np.clip(c.astype(np.float32) * 10 ** (gain_db / 20), -32768, 32767)
            peaks.append(float(sc.scores(pad(c, bed, snr, rng)).max()))
        names = [p.stem for p, _ in clips]
        peaks = np.array(peaks)
        return {"clips": len(clips), "rate": {str(t): float(np.mean(peaks >= t)) for t in th},
                "peak_median": float(np.median(peaks)) if len(peaks) else None,
                "peaks": dict(zip(names, [round(float(v), 4) for v in peaks]))}

    t0 = time.time()
    pos = clip_set(a.positive)
    if pos:
        rows = {}
        for lv in [float(v) for v in a.levels.split(",") if v]:
            r = caught_rate(pos, gain_db=lv)
            rows[f"clean_{lv:+.0f}dB"] = {"clips": r["clips"], "miss": {t: 1 - v for t, v in r["rate"].items()},
                                          "peak_median": r["peak_median"], "peaks": r["peaks"]}
        for s in [float(v) for v in a.snr.split(",") if v]:
            r = caught_rate(pos, snr=s)
            rows[f"noise_{s:.0f}dB_snr"] = {"clips": r["clips"], "miss": {t: 1 - v for t, v in r["rate"].items()},
                                            "peak_median": r["peak_median"], "peaks": r["peaks"]}
        result["positive"] = rows
        print(json.dumps({k: {"miss": v["miss"]} for k, v in rows.items()}), flush=True)

    near = clip_set(a.near)
    if near:
        by_phrase: dict = {}
        for p, c in near:
            key = p.stem.split("__")[0]
            by_phrase.setdefault(key, []).append(float(sc.scores(pad(c, None, None, rng)).max()))
        result["near"] = {k: {"clips": len(v), "open_rate": {str(t): float(np.mean(np.array(v) >= t)) for t in th}}
                          for k, v in sorted(by_phrase.items())}
        allp = np.concatenate([np.array(v) for v in by_phrase.values()])
        result["near_all"] = {"clips": len(allp), "open_rate": {str(t): float(np.mean(allp >= t)) for t in th}}
        print(json.dumps(result["near_all"]), flush=True)

    neg_rows = {}
    for d in a.negative:
        files = sorted(Path(d).glob("*.wav"))
        secs, fa = 0.0, {t: 0 for t in th}
        worst = []
        for p in files:
            x = read_wav(p)
            secs += len(x) / 16000
            s = sc.scores(x)
            for t in th:
                fa[t] += opens(s, t)
            worst.append((float(s.max()) if len(s) else 0.0, p.name))
        hours = secs / 3600
        neg_rows[Path(d).name] = {
            "files": len(files), "hours": round(hours, 3),
            "false_accepts": {str(t): fa[t] for t in th},
            "per_hour": {str(t): fa[t] / hours for t in th},
            "top_peaks": sorted(worst, reverse=True)[:5],
        }
        print(Path(d).name, json.dumps(neg_rows[Path(d).name]["false_accepts"]), f"{hours:.2f} h", flush=True)
    if neg_rows:
        hours = sum(r["hours"] for r in neg_rows.values())
        neg_rows["all"] = {"hours": round(hours, 3), "per_hour": {
            str(t): sum(r["false_accepts"][str(t)] for r in neg_rows.values()) / hours for t in th}}
    result["negative"] = neg_rows
    result["seconds"] = round(time.time() - t0, 1)
    Path(a.out).write_text(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
