r"""One build at a time on this laptop, with a heat check (map alfred-builds, ticket 08).

    python build_lock.py --task 41 --cwd <wt> -- gradlew.bat testDebugUnitTest
    python build_lock.py --task 41 --cwd <wt> --step "gradlew.bat compileDebugKotlin" \
        --step "gradlew.bat testDebugUnitTest"
    python build_lock.py --task 41 --cwd <wt>\server -- uv run pytest

Every Gradle run and every full test suite a worker task starts goes through this (ship.py and
undo.py use it as a library). It:

1. **Takes the lock**, `build.lock` in the worker root. An OS file lock (msvcrt on Windows, flock
   elsewhere), so a crashed holder releases it with its process. While another task holds it,
   this says "Waiting for another build (task K)." once and waits, up to `--wait-min` minutes.
2. **Checks the heat before each step**, from laptopctl's elevated agent: the same local endpoint
   its MCP server's `thermal_status` reads (`GET http://127.0.0.1:47613/status`, header `X-Token`
   from `%LOCALAPPDATA%\laptopctl\token`; `laptopctl/client.py`). The hottest of the CPU and GPU
   sensors above 92 C pauses before the next step until it is under 80 C. The agent unreachable is
   said in words and the build goes on: a monitor being down is not a reason to stop work.
   **No CPU cap** (Kevin, 2026-11-05: "dont cap the cpu"); this only waits.
3. **Gradle gets a private daemon registry** for the worker
   (`-Dorg.gradle.daemon.registry.base=<root>\gradle-daemons`), so Kevin's own `gradlew --stop`
   never kills a worker build and the worker's never kills his, and `--stop` runs after.

Prints what it did in words. Exit code: the first failing step's, 75 when the lock or the heat
wait timed out (nothing ran past that point). Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, read_json, write_json_atomic  # noqa: E402

HOT_C = 92.0
COOL_C = 80.0
HEAT_POLL_S = 20.0
HEAT_MAX_WAIT_S = 30 * 60
LOCK_POLL_S = 10.0
LOCK_WAIT_S = 40 * 60
EXIT_TIMED_OUT = 75

LAPTOPCTL_PORT = 47613
LAPTOPCTL_TOKEN = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "laptopctl" / "token"
GRADLE_NAMES = ("gradlew", "gradlew.bat", "gradlew.cmd")


class TimedOut(Exception):
    """The lock or the heat wait ran out of time. Nothing ran past this point."""


# --------------------------------------------------------------------------------------- heat


def read_heat(timeout: float = 3.0) -> float | None:
    """The hottest CPU/GPU temperature laptopctl's agent reports, or None when it cannot be read."""
    try:
        token = LAPTOPCTL_TOKEN.read_text(encoding="utf-8").strip()
        req = urllib.request.Request(f"http://127.0.0.1:{LAPTOPCTL_PORT}/status",
                                     headers={"X-Token": token})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status = json.loads(r.read())
    except (OSError, ValueError, urllib.error.URLError):
        return None
    return hottest(status)


def hottest(status: dict) -> float | None:
    temps = (status or {}).get("temps_c") or {}
    picked = [float(v) for k, v in temps.items() if k in ("cpu", "gpu") and v is not None]
    if not picked:
        picked = [float(v) for v in temps.values() if isinstance(v, (int, float))]
    return max(picked) if picked else None


def heat_gate(before: str, *, read=read_heat, sleep=time.sleep, clock=time.monotonic, say=print,
              hot_c: float = HOT_C, cool_c: float = COOL_C, poll_s: float = HEAT_POLL_S,
              max_wait_s: float = HEAT_MAX_WAIT_S) -> float | None:
    """Wait while the laptop is too hot to start `before`. Returns the last reading (None: unknown).
    Raises TimedOut when it stays hot past `max_wait_s`."""
    t = read()
    if t is None:
        say(f"Heat unknown (the laptopctl agent did not answer); starting {before} anyway.")
        return None
    if t <= hot_c:
        return t
    say(f"The laptop is at {t:.0f} C, over {hot_c:.0f}; pausing before {before} until it is under "
        f"{cool_c:.0f} C.")
    start = clock()
    while True:
        sleep(poll_s)
        t = read()
        if t is None:
            say(f"Heat unknown again (the laptopctl agent stopped answering); starting {before}.")
            return None
        if t < cool_c:
            say(f"Cooled to {t:.0f} C after {round((clock() - start) / 60)} minutes; starting {before}.")
            return t
        if clock() - start > max_wait_s:
            raise TimedOut(f"the laptop stayed over {cool_c:.0f} C for {round(max_wait_s / 60)} "
                           f"minutes, so {before} did not start")


# --------------------------------------------------------------------------------------- lock


def _try_lock(fh) -> bool:
    fh.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


def _unlock(fh) -> None:
    fh.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
    except OSError:
        pass


class BuildLock:
    """The worker's one build slot. Use as a context manager after `acquire`, or `with
    BuildLock(...).held(): ...`."""

    def __init__(self, paths: Paths, task, *, sleep=time.sleep, clock=time.monotonic, say=print):
        self.paths, self.task = paths, task
        self.file = paths.root / "build.lock"
        self.info = paths.root / "build.lock.json"
        self.sleep, self.clock, self.say = sleep, clock, say
        self._fh = None

    def holder(self):
        return (read_json(self.info, {}) or {}).get("task")

    def acquire(self, wait_s: float = LOCK_WAIT_S, poll_s: float = LOCK_POLL_S) -> None:
        self.paths.root.mkdir(parents=True, exist_ok=True)
        fh = open(self.file, "a+b")
        if fh.tell() == 0:
            fh.write(b"0")
            fh.flush()
        start, said = self.clock(), False
        while not _try_lock(fh):
            if not said:
                other = self.holder()
                self.say(f"Waiting for another build (task {other})." if other is not None
                         else "Waiting for another build.")
                said = True
            if self.clock() - start > wait_s:
                fh.close()
                raise TimedOut(f"another build held the lock for over {round(wait_s / 60)} minutes")
            self.sleep(poll_s)
        self._fh = fh
        write_json_atomic(self.info, {"task": self.task, "pid": os.getpid(), "at": time.time()})

    def release(self) -> None:
        if self._fh is None:
            return
        try:
            write_json_atomic(self.info, {})
        except OSError:
            pass
        _unlock(self._fh)
        self._fh.close()
        self._fh = None

    def __enter__(self):
        if self._fh is None:
            self.acquire()
        return self

    def __exit__(self, *exc):
        self.release()
        return False


# --------------------------------------------------------------------------------------- steps


def is_gradle(argv: list[str]) -> bool:
    return bool(argv) and Path(argv[0]).name.lower() in GRADLE_NAMES


def registry_flag(paths: Paths) -> str:
    return f"-Dorg.gradle.daemon.registry.base={paths.root / 'gradle-daemons'}"


def _resolve(argv: list[str], cwd: Path | None) -> list[str]:
    """`gradlew.bat` relative to the step's cwd: CreateProcess does not search the child's cwd."""
    if argv and cwd and not os.path.isabs(argv[0]):
        local = Path(cwd) / argv[0]
        if local.exists():
            return [str(local)] + argv[1:]
    return argv


def default_runner(argv: list[str], cwd: Path | None) -> int:
    return subprocess.run(argv, cwd=str(cwd) if cwd else None, check=False).returncode


def run_steps(paths: Paths, task, steps: list[list[str]], *, cwd: Path | None = None,
              heat: bool = True, runner=default_runner, read=read_heat, sleep=time.sleep,
              clock=time.monotonic, say=print, lock_wait_s: float = LOCK_WAIT_S,
              heat_max_wait_s: float = HEAT_MAX_WAIT_S) -> int:
    """Run each step under the lock, heat-gated, Gradle with the private registry and `--stop`
    after. Returns the first non-zero exit code (0 when all passed). Raises TimedOut."""
    lock = BuildLock(paths, task, sleep=sleep, clock=clock, say=say)
    lock.acquire(wait_s=lock_wait_s)
    gradles: list[str] = []
    try:
        for argv in steps:
            argv = _resolve(list(argv), cwd)
            name = " ".join([Path(argv[0]).name] + argv[1:2]) if argv else "the step"
            if heat:
                heat_gate(name, read=read, sleep=sleep, clock=clock, say=say,
                          max_wait_s=heat_max_wait_s)
            if is_gradle(argv):
                argv = argv + [registry_flag(paths)]
                if argv[0] not in gradles:
                    gradles.append(argv[0])
            code = runner(argv, cwd)
            if code != 0:
                say(f"{name} failed (exit {code}).")
                return code
        return 0
    finally:
        for g in gradles:
            runner([g, "--stop", registry_flag(paths)], cwd)
        lock.release()


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    rest: list[str] = []
    if "--" in argv:
        i = argv.index("--")
        argv, rest = argv[:i], argv[i + 1:]
    ap = argparse.ArgumentParser(prog="build_lock.py", description=__doc__.splitlines()[0])
    ap.add_argument("--task", required=True)
    ap.add_argument("--cwd", default=None)
    ap.add_argument("--step", action="append", default=[], help="one command; repeat for more")
    ap.add_argument("--no-heat", action="store_true", help="skip the heat check (tests only)")
    ap.add_argument("--wait-min", type=float, default=LOCK_WAIT_S / 60)
    a = ap.parse_args(argv)
    steps = [[t.strip('"') for t in shlex.split(s, posix=os.name != "nt")] for s in a.step]
    if rest:
        steps.append(rest)
    if not steps:
        ap.error("give a command after --, or --step \"<command>\"")
    try:
        code = run_steps(Paths(), a.task, steps, cwd=Path(a.cwd) if a.cwd else None,
                         heat=not a.no_heat, lock_wait_s=a.wait_min * 60)
    except TimedOut as e:
        print(f"Not run: {e}.")
        return EXIT_TIMED_OUT
    print("All steps passed." if code == 0 else f"Stopped: a step failed (exit {code}).")
    return code


if __name__ == "__main__":
    sys.exit(main())
