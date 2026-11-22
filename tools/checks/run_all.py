"""Run the review-finding checks (tools/checks/*.py) on a change, or count them over the whole tree.

    python tools/checks/run_all.py --base <rev> [--head HEAD]   # the gate: exit 1 on any FAIL
    python tools/checks/run_all.py --all [--head HEAD]          # counts only, exit 0

Gate mode reads base...head (from the merge base). It exits 2 when git cannot resolve a revision, so a
missing base never reads as a pass. In GitHub Actions it also writes annotations and a step summary.
Check (e), offhand quotes of Kevin, is not here: a quote's intent cannot be told from its text.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import clock_times  # noqa: E402
import em_dashes  # noqa: E402
import file_size  # noqa: E402
import gitdiff  # noqa: E402
import migrations  # noqa: E402
from common import FAIL, WARN, Finding  # noqa: E402

CHECKS = (
    ("a", clock_times.NAME, "real clock times in docs and comments", "FAIL on added lines"),
    ("b", file_size.NAME, "code files over 600 lines", "FAIL new file; WARN crossed or grew"),
    ("c", migrations.NAME, "schema files declared, existing ones untouched", "FAIL"),
    ("d", em_dashes.NAME, "em dashes in user-facing copy", "FAIL strings.xml + copy modules; WARN code"),
)
SKIPPED = "(e) offhand quotes: skipped, not mechanically detectable (intent is not in the text); reviewers keep it."
SHOW = 40


def run(change: gitdiff.Change) -> list[Finding]:
    return clock_times.check(change) + file_size.check(change) + migrations.check(change) + em_dashes.check(change)


def gate(base: str, head: str) -> int:
    try:
        change = gitdiff.from_git(base, head)
    except subprocess.CalledProcessError as e:
        print(f"checks: git could not resolve {base}...{head}: {(e.stderr or '').strip()}", file=sys.stderr)
        return 2
    findings = run(change)
    lines = [f"Review-finding checks on {base}...{head}: {len(change.status)} files changed", ""]
    if change.status:
        import blast_radius

        tier, rows = blast_radius.tier_of(list(change.status))
        lines += [f"Blast radius: {blast_radius.summary_line(tier, rows)}", ""]
    counts = Counter((f.check, f.level) for f in findings)
    for letter, name, what, rule in CHECKS:
        lines.append(f"({letter}) {what} [{rule}]: {counts[(name, FAIL)]} fail, {counts[(name, WARN)]} warn")
    lines.append(SKIPPED)
    lines.append("")
    for f in sorted(findings, key=lambda f: (f.level != FAIL, f.check, f.path, f.line))[:SHOW]:
        lines.append(f"{f.level} {f.check} {f.where()}: {f.message}")
    if len(findings) > SHOW:
        lines.append(f"... and {len(findings) - SHOW} more")
    text = "\n".join(lines)
    print(text)
    _github(findings, text)
    return 1 if any(f.level == FAIL for f in findings) else 0


def _github(findings: list[Finding], text: str) -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    for f in findings:
        kind = "error" if f.level == FAIL else "warning"
        loc = f"file={f.path}" + (f",line={f.line}" if f.line else "")
        print(f"::{kind} {loc}::{f.check}: {f.message}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("## Review-finding checks\n\n```\n" + text + "\n```\n")


def _levels(findings: list[Finding]) -> str:
    c = Counter(f.level for f in findings)
    return f"{c[FAIL]} fail, {c[WARN]} warn"


def count_all(head: str) -> int:
    change = gitdiff.whole_tree(head)
    print(f"Whole-tree counts at {head} ({len(change.status)} tracked files). Counts only, nothing fails.")
    clock = clock_times.check(change)
    print(f"(a) clock times in docs and comments: {len(clock)} in {len({f.path for f in clock})} files"
          f" ({_levels(clock)} if they were all new)")
    by_top = Counter(f.path.split("/", 1)[0] for f in clock)
    for top, n in by_top.most_common(8):
        print(f"      {top}: {n}")
    big = file_size.over_limit(change)
    print(f"(b) code files over {file_size.LIMIT} lines: {len(big)}")
    for path, n in sorted(big, key=lambda r: -r[1])[:5]:
        print(f"      {n:>5}  {path}")
    mig = migrations.check(change)
    print(f"(c) schema problems: {_levels(mig)}")
    for f in mig[:10]:
        print(f"      {f.where()}: {f.message}")
    dash = em_dashes.check(change)
    per_area = Counter((em_dashes.area_of(f.path) or ("?", ""))[0] for f in dash)
    print(f"(d) em dashes in copy: {len(dash)} lines ({_levels(dash)} if they were all new)")
    for name, level, _ in em_dashes.AREAS:
        print(f"      {name} [{level} in the gate]: {per_area[name]}")
    print(SKIPPED)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--all", action="store_true")
    a = ap.parse_args(argv)
    if a.all:
        return count_all(a.head)
    if not a.base:
        ap.error("--base is required (or --all)")
    return gate(a.base, a.head)


if __name__ == "__main__":
    sys.exit(main())
