r"""The worker's one warm LEGION worktree (2026-11-09, B1, after task 7's 24 minute first build).

    python warm_worktree.py claim --task 41 --branch feat/snake          # new branch off origin/dev
    python warm_worktree.py claim --task 42 --branch feat/snake --resume # carry on an existing one
    python warm_worktree.py claim --task 43                              # detached at origin/dev
    python warm_worktree.py release --task 41
    python warm_worktree.py status
    python warm_worktree.py prepare                                      # build it once, warm

A fresh `git worktree add` per task has no build outputs, so its first Gradle compile is a cold
one (23 min 56 s for task 7, against a 30 minute cap). This keeps ONE worktree,
`<work>\warm\wt`, and resets it per task to the requested base: `checkout -f -B <branch> <base>`
then `clean -fd`. Ignored files stay, so `build\`, `.gradle\`, the Kotlin and KSP caches and
`local.properties` carry over and the next compile is incremental. Anything a finished task left
uncommitted is stashed first, never lost.

One task at a time holds it (`warm-lease.json` in the worker root, which helpers cannot write).
The lease holds while its task runs, and while it waits on Kevin's answer (needs_confirmation, up
to HOLD_S) so ship.py runs on the same tree; that task's follow-up inherits it. The worker
releases it when a task finishes any other way. A claim that finds it busy says so and exits 3:
use a fresh worktree then, the way the skills did before (cold, but correct).

Prints what it did in words; the last line of a claim is the worktree path. Stdlib only.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, is_under, load_config, read_json, write_json_atomic  # noqa: E402

REPO_DEFAULT = Path(r"C:\Users\kevin\AndroidStudioProjects\legion")
LOCAL_FILES = ("local.properties", "deploy/.env", "deploy/cloudrun/.env")  # ship.py's list
HOLD_S = 6 * 3600  # a needs_confirmation task keeps the tree this long for its follow-up
EXIT_BUSY = 3
PREPARE_TASK = "warm"


class Busy(Exception):
    def __init__(self, task):
        super().__init__(f"the warm worktree is in use by task {task}")
        self.task = task


class GitFailed(Exception):
    pass


def warm_path(paths: Paths) -> Path:
    return paths.work / "warm" / "wt"


def lease_path(paths: Paths) -> Path:
    return paths.root / "warm-lease.json"


def is_warm(paths: Paths, wt) -> bool:
    return is_under(wt, warm_path(paths))


def _tasks(paths: Paths) -> dict:
    return (read_json(paths.state, {}) or {}).get("tasks", {}) or {}


def holder(paths: Paths, now: float | None = None):
    """The task holding the warm worktree now, or None. A lease whose task finished (other than
    a recent needs_confirmation) or is gone from the worker's state is free."""
    now = time.time() if now is None else now
    task = (read_json(lease_path(paths), {}) or {}).get("task")
    if task is None:
        return None
    if task == PREPARE_TASK:
        at = (read_json(lease_path(paths), {}) or {}).get("at", 0)
        return task if now - at < 3600 else None
    t = _tasks(paths).get(str(task))
    if not t:
        return None
    if t.get("status") == "running":
        return task
    if t.get("status") == "needs_confirmation" and now - t.get("finished_at", now) < HOLD_S:
        return task
    return None


def take_lease(paths: Paths, task, *, branch: str | None = None, now: float | None = None) -> None:
    """Hold the warm worktree for `task`. Its parent's lease passes to it. Raises Busy."""
    now = time.time() if now is None else now
    h = holder(paths, now)
    parent = (_tasks(paths).get(str(task)) or {}).get("parent")
    if h is not None and str(h) not in (str(task), str(parent)):
        raise Busy(h)
    write_json_atomic(lease_path(paths), {"task": task, "branch": branch, "at": now})


def release(paths: Paths, task) -> bool:
    lease = read_json(lease_path(paths), {}) or {}
    if lease.get("task") is not None and str(lease.get("task")) == str(task):
        write_json_atomic(lease_path(paths), {})
        return True
    return False


def _git(*args, cwd=None) -> str:
    p = subprocess.run(["git", *args], cwd=str(cwd) if cwd else None, capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise GitFailed(f"git {args[0] if args[0] != '-C' else args[2]} failed: "
                        f"{(p.stderr or p.stdout).strip()[-300:]}")
    return p.stdout.strip()


def claim(paths: Paths, cfg: dict, task, *, branch: str | None = None, base: str = "origin/dev",
          resume: bool = False, git=_git, say=print, now: float | None = None) -> Path:
    take_lease(paths, task, branch=branch, now=now)
    repo = Path((cfg.get("ship") or {}).get("repo") or REPO_DEFAULT)
    wt = warm_path(paths)
    try:
        if not (wt / ".git").exists():
            wt.parent.mkdir(parents=True, exist_ok=True)
            git("-C", str(repo), "fetch", "origin")
            git("-C", str(repo), "worktree", "add", "--detach", str(wt), base)
            say("Made the warm worktree (its first build is a cold one).")
        else:
            git("-C", str(wt), "fetch", "origin")
            if git("-C", str(wt), "status", "--porcelain"):
                git("-C", str(wt), "stash", "push", "-u", "-m", f"warm worktree: left before task {task}")
                say("Stashed what the last task left uncommitted (git stash list in the worktree).")
        if branch and resume:
            git("-C", str(wt), "checkout", "-f", branch)
        elif branch:
            git("-C", str(wt), "checkout", "-f", "-B", branch, base)
        else:
            git("-C", str(wt), "checkout", "-f", "--detach", base)
        git("-C", str(wt), "clean", "-fd")  # untracked only: ignored build outputs stay
    except GitFailed:
        release(paths, task)
        raise
    for rel in LOCAL_FILES:
        src, dst = repo / rel, wt / rel
        if src.is_file() and not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
    what = (f"on {branch}" + ("" if resume else f" off {base}")) if branch else f"detached at {base}"
    say(f"Warm worktree for task {task}, {what}, build outputs kept:")
    say(str(wt))
    return wt


def main(argv: list[str] | None = None, paths: Paths | None = None) -> int:
    ap = argparse.ArgumentParser(prog="warm_worktree.py", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("claim", help="hold the warm worktree for a task and reset it to a base")
    c.add_argument("--task", required=True, type=int)
    c.add_argument("--branch")
    c.add_argument("--base", default="origin/dev")
    c.add_argument("--resume", action="store_true", help="check out an existing branch, no reset")
    r = sub.add_parser("release", help="let the warm worktree go")
    r.add_argument("--task", required=True, type=int)
    sub.add_parser("status", help="who holds it")
    p = sub.add_parser("prepare", help="make it at origin/dev and compile once (idle laptop only)")
    p.add_argument("--base", default="origin/dev")
    a = ap.parse_args(sys.argv[1:] if argv is None else argv)
    paths = paths or Paths()
    cfg = load_config(paths)
    if a.cmd == "status":
        h = holder(paths)
        print(f"Warm worktree {warm_path(paths)}: " + (f"held by task {h}." if h is not None else "free."))
        return 0
    if a.cmd == "release":
        print("Released." if release(paths, a.task) else f"Task {a.task} did not hold it.")
        return 0
    if a.cmd == "claim" and a.resume and not a.branch:
        ap.error("--resume needs --branch")
    task = a.task if a.cmd == "claim" else PREPARE_TASK
    try:
        wt = claim(paths, cfg, task, branch=getattr(a, "branch", None), base=a.base,
                   resume=getattr(a, "resume", False))
    except Busy as e:
        print(f"Busy: {e}. Use a fresh worktree in your work folder instead "
              f"(git -C <repo> worktree add <work>\\wt ...); its first build is cold.")
        return EXIT_BUSY
    except GitFailed as e:
        print(f"Not claimed: {e}.")
        return 2
    if a.cmd == "prepare":
        import build_lock
        print("Compiling once so the next task's build is warm.")
        t0 = time.time()
        try:
            code = build_lock.run_steps(paths, PREPARE_TASK, [["gradlew.bat", "compileDebugKotlin",
                                                               "-Pnokey"]], cwd=wt)
        except build_lock.TimedOut as e:
            print(f"Not built: {e}.")
            code = build_lock.EXIT_TIMED_OUT
        finally:
            release(paths, PREPARE_TASK)
        print(f"Compile {'passed' if code == 0 else f'failed (exit {code})'} in "
              f"{round((time.time() - t0) / 60, 1)} minutes.")
        return code
    return 0


if __name__ == "__main__":
    sys.exit(main())
