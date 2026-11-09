"""The daily memory review, laptop half (memory-review ticket 16). Stdlib only.

The engine mints a `memory_review` task on the Alienware once a day at most (ticket 15); the
worker hands it to a helper like any task, and the helper runs this script around its own
judgement:

    python review_memories.py fetch --task N            # context.json into the task's work folder
    (the helper reads context.json with Read and writes changes.json with Write)
    python review_memories.py submit --task N           # posts changes.json, prints the counts
    python review_memories.py submit --task N --dry-run # the verdicts by handle, nothing written
    python review_memories.py run-now                   # Kevin, by hand: queue one review now

**Memory text never leaves the two work files.** This script prints counts, handles, actions and
verdicts, never a row or a transcript line; files, not command lines, carry the text, because a
command line is what the heartbeat's progress line can echo. `submit` deletes context.json and
changes.json after a real submit, and writes `result.json` (counts in words only) that the worker
uses as the task's summary.

Engine down or refusing: says so in words and exits 1. Nothing retries in a loop; the engine's
6 hour wait after a failure is the retry.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, load_config, read_json, write_json_atomic  # noqa: E402

API = "/api/member-memories/review/"
ACTIONS = ("merge", "correct", "expire", "relabel", "retire")
REASON_MAX = 200
CHANGES_MAX = 200
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class Refused(Exception):
    """Nothing was sent, or the engine said no. The message says why, in words."""


def _call(cfg: dict, method: str, path: str, body=None, timeout: float = 60.0):
    url = cfg["engine_url"].rstrip("/") + API + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Token {cfg['token']}")
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    elif method == "POST":
        req.add_header("Content-Length", "0")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode("utf-8")).get("detail", "")
        except (ValueError, OSError, AttributeError):
            detail = ""
        raise Refused(f"The engine answered HTTP {e.code}. {detail}".strip()) from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        raise Refused(f"The engine could not be reached ({type(e).__name__}).") from None


def _files(paths: Paths, task: int) -> tuple[Path, Path, Path, Path]:
    work = paths.task_work(task)
    return work / "context.json", work / "changes.json", work / "usage.json", work / "result.json"


def _result(paths: Paths, task: int, **fields) -> None:
    write_json_atomic(_files(paths, task)[3], fields)


def fetch(cfg: dict, paths: Paths, task: int) -> str:
    context_file = _files(paths, task)[0]
    context = _call(cfg, "GET", f"context?task={int(task)}")
    if not isinstance(context, dict) or "context_id" not in context:
        raise Refused("The engine's answer had no context_id.")
    write_json_atomic(context_file, context)
    return (
        f"Context ready ({context.get('mode', '?')} mode): {len(context.get('memories', []))} "
        f"memories, {len(context.get('conversations', []))} conversations, "
        f"{len(context.get('exact_duplicates', []))} exact duplicates the engine merges itself, "
        f"{len(context.get('pending_handles', []))} already waiting on Kevin. "
        f"Read {context_file} and write {_files(paths, task)[1]}."
    )


def validate(changes, handles: set[str], labels: set[str]) -> list[str]:
    """Problems with the change list, in words, by index. Never quotes a change's text."""
    if not isinstance(changes, list):
        return ["changes.json must be a JSON list of changes."]
    if len(changes) > CHANGES_MAX:
        return [f"At most {CHANGES_MAX} changes; there are {len(changes)}."]
    problems = []
    for i, c in enumerate(changes):
        where = f"change {i}"
        if not isinstance(c, dict):
            problems.append(f"{where} is not an object.")
            continue
        action = c.get("action")
        if action not in ACTIONS:
            problems.append(f"{where}: action must be one of {', '.join(ACTIONS)}.")
            continue
        named = c.get("handles") if action == "merge" else [c.get("handle")]
        if not isinstance(named, list) or not all(isinstance(h, str) for h in named):
            problems.append(f"{where}: name memories by handle (m1, m2, ...).")
            continue
        if action == "merge" and len(set(named)) < 2:
            problems.append(f"{where}: a merge names two or more handles.")
        unknown = [h for h in named if h not in handles]
        if unknown:
            problems.append(f"{where}: {', '.join(unknown)} not in context.json.")
        if action in ("merge", "correct") and not str(c.get("new_text") or "").strip():
            problems.append(f"{where}: {action} needs new_text.")
        if action == "correct":
            ev = c.get("evidence")
            if not isinstance(ev, list) or not ev or any(e not in labels for e in ev):
                problems.append(f"{where}: correct needs evidence, a list of L labels from "
                                "context.json.")
        if action == "expire" and not _DATE.match(str(c.get("event_date") or "")):
            problems.append(f"{where}: expire needs event_date as YYYY-MM-DD.")
        reason = c.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            problems.append(f"{where}: needs a short reason.")
        elif len(reason) > REASON_MAX:
            problems.append(f"{where}: reason is over {REASON_MAX} characters.")
        if c.get("confidence") not in ("high", "low"):
            problems.append(f"{where}: confidence must be high or low.")
    return problems


def submit(cfg: dict, paths: Paths, task: int, dry_run: bool = False) -> str:
    context_file, changes_file, usage_file, _ = _files(paths, task)
    context = read_json(context_file, None)
    if not isinstance(context, dict) or "context_id" not in context:
        raise Refused(f"No context.json for task {task}: run fetch first.")
    changes = read_json(changes_file, None)
    if changes is None:
        raise Refused(f"No readable changes.json for task {task}. Write [] if nothing should "
                      "change.")
    handles = {m.get("handle") for m in context.get("memories", []) if isinstance(m, dict)}
    labels = {c.get("label") for c in context.get("conversations", []) if isinstance(c, dict)}
    problems = validate(changes, handles, labels)
    if problems:
        raise Refused("Nothing was sent. " + " ".join(problems[:10]))
    body = {"context_id": context["context_id"], "changes": changes, "dry_run": dry_run}
    usage = read_json(usage_file, None)
    if isinstance(usage, dict):
        body["usage"] = {k: v for k, v in usage.items()
                         if isinstance(v, (int, float)) and not isinstance(v, bool)}
    answer = _call(cfg, "POST", "changes", body)
    counts = answer.get("counts") or {}
    line = f"Memory review: {answer.get('words', '')}".strip()
    if dry_run:
        rows = [line + " (dry run: nothing was written)"]
        for v in answer.get("verdicts") or []:
            rows.append(f"  {v.get('index')}: {v.get('action')} {','.join(v.get('handles') or [])}"
                        f" -> {v.get('status')} ({v.get('gate')})")
        return "\n".join(rows)
    for f in (context_file, changes_file):
        f.unlink(missing_ok=True)
    _result(paths, task, words=line, counts=counts, mode=answer.get("mode"))
    return line


def run_now(cfg: dict) -> str:
    answer = _call(cfg, "POST", "run")
    return str(answer.get("words") or "Queued.")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="review_memories.py", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--task", type=int, required=True)
    s = sub.add_parser("submit")
    s.add_argument("--task", type=int, required=True)
    s.add_argument("--dry-run", action="store_true")
    sub.add_parser("run-now")
    a = ap.parse_args(argv)
    paths = Paths().ensure()
    cfg = load_config(paths)
    if not cfg.get("engine_url") or not cfg.get("token"):
        print("No engine address or token in the worker's config.json.", file=sys.stderr)
        return 1
    try:
        if a.cmd == "fetch":
            paths.task_work(a.task).mkdir(parents=True, exist_ok=True)
            print(fetch(cfg, paths, a.task))
        elif a.cmd == "submit":
            print(submit(cfg, paths, a.task, dry_run=a.dry_run))
        else:
            print(run_now(cfg))
    except Refused as e:
        if a.cmd in ("fetch", "submit") and not getattr(a, "dry_run", False):
            _result(paths, a.task, error=str(e))
        print(str(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
