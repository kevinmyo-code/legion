"""How a helper subagent finishes a task (map claude-session, ticket 04).

    python report.py --task 41 --status done --summary "Found it: AW4 wiring rev C, two megabytes." \
        --found "C:/Users/kevin/Downloads/aw4.pdf"
    python report.py --task 41 --status needs_confirmation --question "OK to delete the old copy?"

Writes one JSON file into the local reports queue; the worker drains it and forwards the result to
the engine. This file, not the main session's chat text, is the source of truth for a task's status
(ticket 02 finding). Exits non-zero with the reason in words when the report is malformed, so the
helper sees the mistake and can retry with the right fields.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import STATUSES, Paths, write_json_atomic  # noqa: E402


def _file_entry(raw: str) -> dict:
    p = Path(raw).expanduser()
    if not p.is_absolute():
        p = Path.cwd() / p
    p = p.resolve()
    if not p.is_file():
        raise ValueError(f"no such file: {raw}")
    return {"path": str(p), "name": p.name, "size": p.stat().st_size}


def build(argv: list[str]) -> dict:
    ap = argparse.ArgumentParser(prog="report.py", description=__doc__.splitlines()[0])
    ap.add_argument("--task", type=int, required=True)
    ap.add_argument("--status", required=True, choices=STATUSES)
    ap.add_argument("--summary", default="")
    ap.add_argument("--question", default="")
    ap.add_argument("--found", action="append", default=[], metavar="PATH",
                    help="a file you found or made; listed for Kevin, never uploaded unless he asks")
    a = ap.parse_args(argv)

    if a.status == "needs_confirmation" and not a.question.strip():
        ap.error("needs_confirmation needs --question \"<one question for Kevin>\" "
                 "(put the question in --question, not in --summary)")
    if a.status in ("done", "failed") and not a.summary.strip():
        ap.error(f"{a.status} needs --summary \"<what happened, in words>\"")
    try:
        found = [_file_entry(f) for f in a.found]
    except ValueError as e:
        ap.error(str(e))
    return {
        "task": a.task,
        "status": a.status,
        "summary": a.summary.strip(),
        "question": a.question.strip(),
        "found": found,
        "at": time.time(),
    }


def main(argv: list[str] | None = None) -> int:
    report = build(sys.argv[1:] if argv is None else argv)
    paths = Paths().ensure()
    name = f"{time.time_ns()}-{os.getpid()}-task{report['task']}.json"
    write_json_atomic(paths.reports / name, report)
    print(f"reported task {report['task']}: {report['status']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
