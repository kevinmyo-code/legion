"""Offline wake-word eval on Kevin's own recordings (wake-word ticket 20).

Replays the WAVs the phone's enrollment screen saved through the same pipeline the app runs
(Silero VAD -> sherpa-onnx KWS -> Vosk grammar confirm on a 2 s ring), with the same model
files as the app's assets, and sweeps keywordsThreshold, keywordsScore, numTrailingBlanks,
keyword spellings and confirm policies. Reports recall on the positive takes (overall and per
condition) and false accepts per hour on the negative takes.

**No numbers from this belong anywhere until it has run on his recordings.** Ticket 20's
"Sweep results" section is filled from this script's output and nothing else.

Setup (once; sherpa-onnx pinned to the app's AAR version):

    python -m venv .venv-wake
    .venv-wake/Scripts/python -m pip install "sherpa-onnx==1.13.8" vosk

Pull the recordings (release build, so not run-as; the external files dir is pullable):

    adb pull /sdcard/Android/data/com.kevin.legion/files/wake-samples ./wake-samples

Run from the repo root:

    .venv-wake/Scripts/python tools/wake_eval/wake_eval.py ./wake-samples --grid app
    .venv-wake/Scripts/python tools/wake_eval/wake_eval.py ./wake-samples --grid quick --out sweep.json

`--grid app` is the shipped operating point alone; check it reproduces the per-take verdicts in
the sidecars before trusting a sweep. `quick` and `full` are in `core.GRIDS`.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import core  # noqa: E402


def run(samples: list[core.Sample], points: list[core.Point], kws_dir: Path, vosk_dir: Path) -> list[core.Score]:
    import engines

    vocab = core.read_vocab(kws_dir / "tokens.txt")
    scores: list[core.Score] = []
    confirmers: dict[str, object] = {}
    for i, point in enumerate(points, 1):
        bad = core.validate_spelling(point.spelling, vocab)
        if bad:
            print(f"skip {point.spelling!r}: tokens not in tokens.txt: {bad}", file=sys.stderr)
            continue
        kws = engines.SherpaKws(point, kws_dir)
        if point.confirm_policy == "kws_only":
            confirm = None
        else:
            confirm = confirmers.setdefault(point.confirm_policy, engines.VoskConfirm(point.confirm_policy, vosk_dir))
        score = core.Score(point)
        for s in samples:
            events = core.replay(s.pcm, engines.SherpaVad(kws_dir), kws, confirm)
            score.add(s, core.opened(events))
        scores.append(score)
        print(f"[{i}/{len(points)}] recall={score.recall} fa/h={score.false_accepts_per_hour}", file=sys.stderr)
    return scores


def main(argv: list[str] | None = None) -> int:
    # Keyword spellings carry "▁" (SentencePiece's word mark); a Windows console is cp1252.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("samples", type=Path, help="the pulled wake-samples/ directory")
    ap.add_argument("--grid", choices=sorted(core.GRIDS), default="app")
    ap.add_argument("--out", type=Path, help="write every point's score here as JSON")
    ap.add_argument("--kws-dir", type=Path, default=None, help="default: the app's assets/wake-kws")
    ap.add_argument("--vosk-dir", type=Path, default=None, help="default: the app's assets/vosk-model")
    ap.add_argument("--top", type=int, default=10)
    args = ap.parse_args(argv)

    samples, skipped = core.load_samples(args.samples)
    for line in skipped:
        print(f"skipped {line}", file=sys.stderr)
    positives = sum(s.positive for s in samples)
    negatives = len(samples) - positives
    if not positives or not negatives:
        print(
            f"need at least one positive and one negative take; found {positives} and {negatives}. "
            "Record them on the phone's enrollment screen first.",
            file=sys.stderr,
        )
        return 2

    import engines

    kws_dir = args.kws_dir or engines.KWS_DIR
    vosk_dir = args.vosk_dir or engines.VOSK_DIR
    scores = core.rank(run(samples, core.grid(args.grid), kws_dir, vosk_dir))
    print(f"{positives} positive takes, {negatives} negative "
          f"({sum(s.seconds for s in samples if not s.positive):.0f} s). "
          f"Target: recall >= {core.RECALL_TARGET:.0%}, <= {core.FA_PER_HOUR_TARGET} false accept/h.")
    for s in scores[: args.top]:
        d = s.as_dict()
        mark = " (shipped)" if s.point.is_app else ""
        meets = "MEETS" if s.meets(core.RECALL_TARGET, core.FA_PER_HOUR_TARGET) else "misses"
        print(f"{meets} recall={d['recall']} fa/h={d['false_accepts_per_hour']} {d['point']}{mark}")
    if args.out:
        args.out.write_text(json.dumps([s.as_dict() for s in scores], indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
