#!/usr/bin/env python3
"""The LEGION prompt-obedience eval harness (.scratch/ai-craft/issues/01-eval-harness.md).

Runs a fixed set of golden task suites against the real Gemini REST endpoint, N times each,
scores them in code (or via a versioned LLM-judge for tone_judge), and prints a per-suite
table plus one-line verdicts. Every report is stamped with prompts_fingerprint (a sha256 over
the exact prompt-surface files the suites exercise) so a result pins to the prompt text that
produced it - a regression shows up as a hash change plus a pass-rate drop, not a vibe.

Run ON DEMAND, on Kevin's own Gemini key, never in CI (spend discipline - see README.md).

Usage:
    python harness.py --dry-run
    python harness.py --runs 1 --suites clerk_crud,outcome_honesty
    python harness.py                      # all suites, default 3 runs each
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import defaultdict
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    MODEL_FLASH,
    CallBudget,
    GeminiClient,
    PROMPT_SURFACE_FILES,
    prompts_fingerprint,
    read_key,
)
from suites import ALL_SUITES, SUITES_BY_NAME  # noqa: E402
from suites.tone_judge import JUDGES_DIR  # noqa: E402

RUNS_DIR = Path(__file__).resolve().parent / "runs"

DEFAULT_MAX_CALLS = 150
DEFAULT_RUNS = 3


def judge_prompt_fingerprint() -> str:
    """sha256 over every file in judges/, so a judge-prompt edit alone (no app-source change)
    still shows up as a different fingerprint on a tone_judge report."""
    import hashlib

    h = hashlib.sha256()
    for p in sorted(JUDGES_DIR.glob("*.md")):
        h.update(p.read_bytes())
    return h.hexdigest()


def estimate_calls(suite_names: list[str], runs: int) -> dict:
    """Sum of Case.calls_estimate across every case in every requested suite, times runs.
    Printed before any network call happens (--dry-run always does this and stops there)."""
    per_suite = {}
    for name in suite_names:
        suite = SUITES_BY_NAME[name]
        per_case = {c.key: c.calls_estimate * runs for c in suite.CASES}
        per_suite[name] = {"per_case": per_case, "total": sum(per_case.values())}
    return per_suite


def validate_suites(suite_names: list[str]) -> list[str]:
    """--dry-run's actual check: every suite module has the four required names, every case has
    a non-empty key, and the judge prompt files tone_judge depends on exist and parse. Returns a
    list of problems; empty means clean."""
    problems = []
    for name in suite_names:
        suite = SUITES_BY_NAME[name]
        for attr in ("NAME", "DESCRIPTION", "CASES", "run_case", "score"):
            if not hasattr(suite, attr):
                problems.append(f"{name}: missing required attribute '{attr}'")
        keys = [c.key for c in getattr(suite, "CASES", [])]
        if not keys:
            problems.append(f"{name}: no cases defined")
        if len(keys) != len(set(keys)):
            problems.append(f"{name}: duplicate case keys {keys}")
    if not JUDGES_DIR.exists() or not any(JUDGES_DIR.glob("*.md")):
        problems.append("tone_judge: no judge prompt files found under judges/")
    return problems


def run_suite(client: GeminiClient, suite, runs: int, run_dir: Path) -> dict:
    """Runs every case in `suite` `runs` times, saves each run's transcript to disk, and
    returns a summary dict: pass counts, per-case verdicts, and (for suites whose cases can
    disagree run-to-run, i.e. tone_judge) a flag when a case's verdict was not identical across
    all its runs - reported, never averaged away, per the ticket's own instruction."""
    suite_dir = run_dir / suite.NAME
    suite_dir.mkdir(parents=True, exist_ok=True)

    case_results = {}
    for case in suite.CASES:
        case_dir = suite_dir / case.key
        case_dir.mkdir(parents=True, exist_ok=True)
        verdicts = []
        for i in range(runs):
            result = suite.run_case(client, MODEL_FLASH, case, i)
            verdict = suite.score(case, result)
            verdicts.append(verdict)
            transcript_path = case_dir / f"run{i + 1}.json"
            transcript_path.write_text(
                json.dumps(
                    {
                        "case": case.key,
                        "run_index": i,
                        "passed": verdict.passed,
                        "detail": verdict.detail,
                        "final_text": result.final_text,
                        "calls_used": result.calls_used,
                        "error": result.error,
                        "transcript": result.transcript,
                    },
                    indent=2,
                    default=str,
                ),
                encoding="utf-8",
            )
        disagreement = len({v.passed for v in verdicts}) > 1
        case_results[case.key] = {
            "runs": runs,
            "passed": sum(1 for v in verdicts if v.passed),
            "disagreement": disagreement,
            "details": [v.detail for v in verdicts],
        }

    total_runs = sum(cr["runs"] for cr in case_results.values())
    total_passed = sum(cr["passed"] for cr in case_results.values())
    any_disagreement = any(cr["disagreement"] for cr in case_results.values())
    return {
        "suite": suite.NAME,
        "cases": case_results,
        "pass_rate": (total_passed / total_runs) if total_runs else 0.0,
        "any_disagreement": any_disagreement,
    }


def print_table(summaries: list[dict]) -> None:
    print(f"\n{'suite':<20}{'pass rate':>12}{'verdict':>12}")
    for s in summaries:
        rate = s["pass_rate"]
        verdict = "PASS" if rate == 1.0 else ("WEAK" if rate >= 0.7 else "FAIL")
        flag = " (judge disagreement)" if s.get("any_disagreement") else ""
        print(f"{s['suite']:<20}{rate * 100:>11.0f}%{verdict:>12}{flag}")
        for case_key, cr in s["cases"].items():
            mark = "ok" if cr["passed"] == cr["runs"] else f"{cr['passed']}/{cr['runs']}"
            dis = " [DISAGREEMENT]" if cr["disagreement"] else ""
            print(f"    {case_key:<30}{mark:>8}{dis}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--suites", default="all", help="comma-separated suite names, or 'all' (default)")
    ap.add_argument("--runs", type=int, default=DEFAULT_RUNS, help=f"runs per case (default {DEFAULT_RUNS})")
    ap.add_argument("--max-calls", type=int, default=DEFAULT_MAX_CALLS, help=f"hard cap on total REST calls (default {DEFAULT_MAX_CALLS})")
    ap.add_argument("--dry-run", action="store_true", help="validate suites and print the spend estimate; no network calls")
    args = ap.parse_args()

    suite_names = list(SUITES_BY_NAME.keys()) if args.suites == "all" else [s.strip() for s in args.suites.split(",")]
    unknown = [s for s in suite_names if s not in SUITES_BY_NAME]
    if unknown:
        print(f"Unknown suite(s): {unknown}. Known: {list(SUITES_BY_NAME.keys())}", file=sys.stderr)
        return 2

    estimate = estimate_calls(suite_names, args.runs)
    grand_total = sum(v["total"] for v in estimate.values())
    print(f"Model: {MODEL_FLASH}")
    print(f"Suites: {suite_names}  Runs/case: {args.runs}")
    print(f"Estimated call count: {grand_total} (cap {args.max_calls})")
    for name, v in estimate.items():
        print(f"  {name}: {v['total']} calls  ({v['per_case']})")

    problems = validate_suites(suite_names)
    if problems:
        print("\nValidation problems:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("\nValidation: OK (all suites well-formed)")

    if args.dry_run:
        print("\n--dry-run: stopping before any network call.")
        return 0

    if grand_total > args.max_calls:
        print(
            f"\nEstimated {grand_total} calls exceeds --max-calls {args.max_calls}. "
            "Raise --max-calls, cut --runs, or run fewer --suites.",
            file=sys.stderr,
        )
        return 1

    api_key = read_key()
    budget = CallBudget(cap=args.max_calls)
    client = GeminiClient(api_key, budget)

    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_dir = RUNS_DIR / timestamp
    run_dir.mkdir(parents=True, exist_ok=True)

    summaries = []
    for name in suite_names:
        suite = SUITES_BY_NAME[name]
        print(f"\n=== running {name} ({len(suite.CASES)} cases x {args.runs} runs) ===")
        summaries.append(run_suite(client, suite, args.runs, run_dir))

    fingerprint = prompts_fingerprint()
    judge_fp = judge_prompt_fingerprint()
    report = {
        "timestamp": timestamp,
        "model": MODEL_FLASH,
        "runs_per_case": args.runs,
        "prompts_fingerprint": fingerprint,
        "prompt_surface_files": [str(p.relative_to(Path(__file__).resolve().parents[2])) for p in PROMPT_SURFACE_FILES],
        "judge_prompt_fingerprint": judge_fp,
        "calls_estimated": grand_total,
        "calls_actual": budget.used,
        "suites": summaries,
    }
    report_path = run_dir / "report.json"
    report_path.write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")

    print_table(summaries)
    print(f"\nprompts_fingerprint: {fingerprint}")
    print(f"judge_prompt_fingerprint: {judge_fp}")
    print(f"Calls estimated: {grand_total}  Calls actual: {budget.used}")
    print(f"Report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
