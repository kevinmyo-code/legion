"""Run a suite: each check's setup, act, verify, then cleanup in `finally`; a table and a JSON log."""
from __future__ import annotations

import datetime as dt
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

PASS, FAIL, MANUAL, BLOCKED, SKIPPED, NOT_RUN = "pass", "fail", "manual", "blocked", "skipped", "not run"


class Fail(Exception):
    """The observable assertion did not hold: an app bug, or the check is stale."""


class Blocked(Exception):
    """The check could not be carried out (guard asked for approval, a switch is off, no route)."""


class Abort(Exception):
    """Stop the whole run (the phone is locked or gone). Never worked around."""


Step = Callable[["Ctx"], None]


@dataclass
class Check:
    id: str
    title: str
    # Repo path -> symbols in it this check relies on (labels, log lines, functions). `stale` reads it.
    deps: dict[str, list[str]]
    setup: Step | None = None
    act: Step | None = None
    verify: Step | None = None
    cleanup: Step | None = None
    # Set for a check that needs a real voice or a real call: what Kevin does instead.
    manual: str = ""
    # Needs the test-turn door (an assistant turn).
    door: bool = False
    # Runs whatever came before (the version gate, the crash scan).
    always: bool = False


@dataclass
class Suite:
    name: str
    title: str
    version_code: int
    checks: list[Check]


@dataclass
class Result:
    id: str
    title: str
    status: str = NOT_RUN
    reason: str = ""
    cleanup: str = ""
    seconds: float = 0.0
    steps: list[dict] = field(default_factory=list)
    evidence: dict = field(default_factory=dict)


class Ctx:
    """What a check's steps get: the device, the run's facts, a scratch dict, and a recorder.

    `note` is for facts safe to keep in the log: statuses, counts, tool NAMES, dates the test set.
    Never screen text, never what the assistant said (personal data; the log is outside the repo
    but still never carries it)."""

    def __init__(self, device, suite: Suite, run: dict):
        self.device = device
        self.suite = suite
        self.run = run
        self.data: dict = {}
        self.result: Result | None = None
        self.mark = ""

    def note(self, key: str, value) -> None:
        if self.result is not None:
            self.result.evidence[key] = value

    def step(self, name: str, ok: bool = True, detail: str = "") -> None:
        if self.result is not None:
            self.result.steps.append({"step": name, "ok": ok, **({"detail": detail[:120]} if detail else {})})


def report_dir() -> Path:
    return Path(os.environ.get("LEGION_VERIFY_OUT") or Path.home() / ".legion" / "reports" / "verify")


def run_suite(suite: Suite, device, only: set[str] | None = None, clock=time.monotonic) -> dict:
    run = {"suite": suite.name, "title": suite.title, "expected_version_code": suite.version_code,
           "started": dt.datetime.now().isoformat(timespec="seconds"), "device": device.describe()}
    ctx = Ctx(device, suite, run)
    results: list[Result] = []
    stop: str | None = None
    gate: str | None = None  # why the non-`always` checks do not run (wrong build)
    try:
        run["phone_mark"] = device.mark()
    except Abort as e:
        stop = str(e)
    for check in suite.checks:
        r = Result(check.id, check.title)
        results.append(r)
        if only and check.id not in only and not check.always:
            r.status, r.reason = SKIPPED, "not selected (--only)"
            continue
        if stop:
            r.reason = f"run stopped: {stop}"
            continue
        if check.manual:
            r.status, r.reason = MANUAL, check.manual
            continue
        if gate and not check.always:
            r.status, r.reason = SKIPPED, gate
            continue
        if check.door and not run.get("door_checked"):
            run["door_checked"] = True
            run["door"] = device.door_ready()
        if check.door and run["door"] is not True:
            r.status, r.reason = BLOCKED, f"test-turn door: {run['door']}"
            continue
        stop = _run_one(ctx, check, r, clock)
        if check.id == "version" and r.status != PASS:
            gate = "installed build is not the suite's (see the version check)"
    run["finished"] = dt.datetime.now().isoformat(timespec="seconds")
    run["stopped"] = stop or ""
    run["results"] = [r.__dict__ for r in results]
    run["totals"] = {s: sum(1 for r in results if r.status == s) for s in (PASS, FAIL, MANUAL, BLOCKED, SKIPPED, NOT_RUN)}
    return run


def _run_one(ctx: Ctx, check: Check, r: Result, clock) -> str | None:
    """Runs one check; returns a reason to stop the whole run, or None."""
    ctx.result, ctx.data = r, {}
    t0 = clock()
    stop = None
    try:
        ctx.mark = ctx.device.mark()
        for name in ("setup", "act", "verify"):
            fn = getattr(check, name)
            if fn:
                fn(ctx)
        r.status = PASS
    except Fail as e:
        r.status, r.reason = FAIL, str(e)
    except Blocked as e:
        r.status, r.reason = BLOCKED, str(e)
    except Abort as e:
        r.status, r.reason, stop = BLOCKED, f"run stopped: {e}", str(e)
    except Exception as e:  # noqa: BLE001 - a broken check is reported, the run goes on
        r.status, r.reason = FAIL, f"check error {type(e).__name__}: {str(e)[:200]}"
    finally:
        if check.cleanup:
            r.cleanup = _cleanup(ctx, check)
            if r.cleanup.startswith("LEFT BEHIND") and r.status == PASS:
                r.reason = "passed, but its test item was not cleaned up"
        r.seconds = round(clock() - t0, 1)
    return stop


def _cleanup(ctx: Ctx, check: Check) -> str:
    try:
        check.cleanup(ctx)
        return ctx.data.get("cleanup", "ok")
    except Abort as e:
        return f"LEFT BEHIND (phone stopped answering: {e})"
    except (Fail, Blocked) as e:
        return f"LEFT BEHIND: {e}"
    except Exception as e:  # noqa: BLE001
        return f"LEFT BEHIND (cleanup error {type(e).__name__}: {str(e)[:120]})"


def table(run: dict) -> str:
    rows = [("check", "result", "cleanup", "why")]
    for r in run["results"]:
        rows.append((r["id"], r["status"].upper(), r["cleanup"] or "-", r["reason"] or ""))
    w0 = max(len(a) for a, *_ in rows)
    w1 = max(len(b) for _, b, *_ in rows)
    w2 = min(max(len(c) for _, _, c, _ in rows), 40)
    lines = [f"{a:<{w0}}  {b:<{w1}}  {c[:40]:<{w2}}  {d}" for a, b, c, d in rows]
    lines.insert(1, "-" * min(len(lines[0]) + 20, 120))
    t = run["totals"]
    head = f"suite {run['suite']} ({run['title']}), expects versionCode {run['expected_version_code']}"
    tail = ", ".join(f"{v} {k}" for k, v in t.items() if v)
    if run.get("stopped"):
        tail += f"  | STOPPED: {run['stopped']}"
    return "\n".join([head, *lines, tail])


def write_log(run: dict, out_dir: Path | None = None) -> Path:
    out = out_dir or report_dir()
    out.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    kind = "dryrun" if run.get("device", {}).get("fake") else "phone"
    path = out / f"verify-{run['suite']}-{kind}-{stamp}.json"
    path.write_text(json.dumps(run, indent=2) + "\n", encoding="utf-8")
    return path
