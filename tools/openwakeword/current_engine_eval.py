"""The CURRENT wake engine (Silero VAD -> sherpa-onnx KWS -> Vosk grammar confirm, the app's operating
point from tools/wake_eval/core.py) on the same sets oww_eval.py scores, so the two engines compare
on identical audio: the same padded positives (2 s lead, 1 s tail, same noise bed and seed), the same
near-phrase clips, the same negative streams.

Each engine hears its own input gain: this one x4 (WAKE_INPUT_GAIN), openWakeWord x1. Opens are
counted with the app's 4 s trigger gap.

Needs sherpa-onnx==1.13.8 (the app's AAR) and vosk, plus the app's assets/wake-kws and the locally
fetched assets/vosk-model.

    python -I tools/openwakeword/current_engine_eval.py --positive DIR --near DIR --negative DIR \
        --levels 0,-12,-24 --snr 10,5 --noise-bed DIR --vosk-dir <assets/vosk-model> --out result.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "wake_eval"))
sys.path.insert(0, str(HERE))

import core  # noqa: E402
import engines  # noqa: E402
from oww_eval import pad, read_wav  # noqa: E402

GAP_SAMPLES = 4 * 16000


def run_engine(pcm: np.ndarray, kws, confirm, kws_dir: Path) -> list[int]:
    """Sample positions of opens, after the app's gain and its 4 s gap."""
    g = np.clip(pcm.astype(np.int32) * core.APP_INPUT_GAIN, -32768, 32767).astype(np.int16)
    events = core.replay(g.tolist(), engines.SherpaVad(kws_dir), kws, confirm)
    out, last = [], -10 ** 12
    for e in events:
        if e.stage == "open" and e.at_sample - last >= GAP_SAMPLES:
            out.append(e.at_sample)
            last = e.at_sample
    return out


def main() -> None:
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--positive", action="append", default=[])
    ap.add_argument("--near", action="append", default=[])
    ap.add_argument("--negative", action="append", default=[])
    ap.add_argument("--noise-bed")
    ap.add_argument("--snr", default="")
    ap.add_argument("--levels", default="0")
    ap.add_argument("--kws-dir", type=Path, default=engines.KWS_DIR)
    ap.add_argument("--vosk-dir", type=Path, default=engines.VOSK_DIR)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    point = core.grid("app")[0]
    kws = engines.SherpaKws(point, a.kws_dir)
    confirm = engines.VoskConfirm(point.confirm_policy, a.vosk_dir)
    rng = np.random.default_rng(7)  # same seed and draw order as oww_eval.py
    bed = None
    if a.noise_bed:
        bed = np.concatenate([read_wav(p) for p in sorted(Path(a.noise_bed).glob("*.wav"))])
    result: dict = {"engine": "current (VAD + KWS + Vosk confirm, app point)", "point": repr(point)}
    t0 = time.time()

    def clips(dirs):
        return [(p, read_wav(p)) for d in dirs for p in sorted(Path(d).glob("*.wav"))]

    def caught(cs, gain_db=0.0, snr=None):
        hit = 0
        for _, c in cs:
            c = np.clip(c.astype(np.float32) * 10 ** (gain_db / 20), -32768, 32767)
            hit += bool(run_engine(pad(c, bed, snr, rng), kws, confirm, a.kws_dir))
        return hit / max(1, len(cs))

    pos = clips(a.positive)
    if pos:
        rows = {}
        for lv in [float(v) for v in a.levels.split(",") if v]:
            rows[f"clean_{lv:+.0f}dB"] = {"clips": len(pos), "miss": 1 - caught(pos, gain_db=lv)}
            print(rows, flush=True)
        for s in [float(v) for v in a.snr.split(",") if v]:
            rows[f"noise_{s:.0f}dB_snr"] = {"clips": len(pos), "miss": 1 - caught(pos, snr=s)}
            print(rows, flush=True)
        result["positive"] = rows

    near = clips(a.near)
    if near:
        by: dict = {}
        for p, c in near:
            by.setdefault(p.stem.split("__")[0], []).append(
                bool(run_engine(pad(c.astype(np.float32), None, None, rng), kws, confirm, a.kws_dir)))
        result["near"] = {k: {"clips": len(v), "open_rate": float(np.mean(v))} for k, v in sorted(by.items())}
        allv = [x for v in by.values() for x in v]
        result["near_all"] = {"clips": len(allv), "open_rate": float(np.mean(allv))}
        print(result["near_all"], flush=True)

    neg = {}
    for d in a.negative:
        secs, fa = 0.0, 0
        files = sorted(Path(d).glob("*.wav"))
        for p in files:
            x = read_wav(p)
            secs += len(x) / 16000
            fa += len(run_engine(x, kws, confirm, a.kws_dir))
        neg[Path(d).name] = {"files": len(files), "hours": round(secs / 3600, 3), "false_accepts": fa,
                             "per_hour": fa / (secs / 3600)}
        print(Path(d).name, neg[Path(d).name], flush=True)
    result["negative"] = neg
    result["seconds"] = round(time.time() - t0, 1)
    Path(a.out).write_text(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
