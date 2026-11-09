"""A stand-in for `claude -p --input-format stream-json --output-format stream-json` in tests.

Emits the event shapes ticket 02 measured on CLI 2.1.316 (assistant Agent tool_use, tool_result,
result, system task_started / task_progress / task_updated / task_notification, then an unprompted
main turn), plus `background_tasks_changed` and a helper's own background children as measured on
2.1.319 (2026-11-09), and runs the real report.py as a helper would. Never calls the real CLI.

Behaviour per task comes from a `FAKE{...}` JSON blob in the message text:
    sleep (s, default 0.3), status, summary, question, found [paths], report (default true),
    progress (the task_progress description),
    hang (no result for this turn), crash (exit at once), no_agent (answer without a helper),
    pause (s: the helper first parks on its own background build for that long, the way 2.1.319
    shows it: a child task_started with parent_task_id, the agent's `completed` notification,
    background_tasks_changed, then the child ends and the agent's task_started fires again),
    nudge_report (answer a `[report N]` reminder by running report.py with nudge_status and
    nudge_summary).
Writes what it saw (argv, env facts, each message) to $FAKE_CLAUDE_LOG as JSON lines.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT_LOCK = threading.Lock()
KILLED: set = set()
SPECS: dict = {}
LIVE: dict = {}  # background task id -> parent task id (None for one the main window owns)
SESSION = "fake-session"


def emit(ev: dict) -> None:
    with OUT_LOCK:
        sys.stdout.write(json.dumps(ev) + "\n")
        sys.stdout.flush()


def note(obj: dict) -> None:
    path = os.environ.get("FAKE_CLAUDE_LOG")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj) + "\n")


def result(text: str) -> None:
    emit({"type": "result", "subtype": "success", "is_error": False, "num_turns": 1,
          "result": text, "session_id": SESSION})


def parse_fake(text: str) -> dict:
    i = text.find("FAKE{")
    if i < 0:
        return {}
    try:
        obj, _ = json.JSONDecoder().raw_decode(text[i + 4:])
        return obj if isinstance(obj, dict) else {}
    except ValueError:
        return {}


def live_changed() -> None:
    tasks = [{"task_id": k, "task_type": "local_bash" if v else "local_agent",
              **({"parent_task_id": v} if v else {})} for k, v in list(LIVE.items())]
    emit({"type": "system", "subtype": "background_tasks_changed", "tasks": tasks,
          "session_id": SESSION})


def agent_started(n: int, agent: str, tool_use_id: str) -> None:
    LIVE[agent] = None
    live_changed()
    emit({"type": "system", "subtype": "task_started", "task_id": agent, "tool_use_id": tool_use_id,
          "description": f"task {n}: fake", "task_type": "local_agent", "is_backgrounded": True,
          "session_id": SESSION})


def agent_completed(agent: str, tool_use_id: str, summary: str) -> None:
    emit({"type": "system", "subtype": "task_updated", "task_id": agent,
          "patch": {"status": "completed"}, "session_id": SESSION})
    emit({"type": "system", "subtype": "task_notification", "task_id": agent,
          "tool_use_id": tool_use_id, "status": "completed", "summary": summary,
          "usage": {"tool_uses": 2}, "session_id": SESSION})
    LIVE.pop(agent, None)
    live_changed()


def wait_unless_killed(agent: str, watch: str, seconds: float) -> bool:
    """Sleep `seconds`; False (after emitting the kill) if `agent` is stopped meanwhile."""
    end = time.time() + seconds
    while time.time() < end:
        if agent in KILLED:
            emit({"type": "system", "subtype": "task_updated", "task_id": agent,
                  "patch": {"status": "killed"}, "session_id": SESSION})
            LIVE.pop(agent, None)
            LIVE.pop(watch, None)
            live_changed()
            return False
        time.sleep(0.05)
    return True


def run_report(n: int, status: str, summary: str, question: str = "", found=()) -> None:
    args = [sys.executable, str(HERE / "report.py"), "--task", str(n), "--status", status]
    if summary and status != "needs_confirmation":
        args += ["--summary", summary]
    if question:
        args += ["--question", question]
    for f in found:
        args += ["--found", f]
    r = subprocess.run(args, capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if r.returncode:
        note({"report_error": r.stderr[-500:]})


def helper(n: int, tool_use_id: str, spec: dict) -> None:
    agent = f"agent{n}"
    agent_started(n, agent, tool_use_id)
    emit({"type": "system", "subtype": "task_progress", "task_id": agent, "tool_use_id": tool_use_id,
          "description": spec.get("progress", "Working"), "usage": {"tool_uses": 1},
          "session_id": SESSION})
    if not wait_unless_killed(agent, agent, float(spec.get("sleep", 0.3))):
        return
    if spec.get("pause"):
        child = f"bash{n}"
        LIVE[child] = agent
        # The description names the task on purpose: a child is never to be taken for the helper.
        emit({"type": "system", "subtype": "task_started", "task_id": child,
              "tool_use_id": f"toolu_child{n}", "description": f"Compile task {n} through the lock",
              "is_backgrounded": True, "parent_task_id": agent, "owned_by_subagent": True,
              "task_type": "local_bash", "session_id": SESSION})
        live_changed()
        agent_completed(agent, tool_use_id, "Waiting for the compile to finish.")
        emit({"type": "assistant", "parent_tool_use_id": None, "message": {
            "role": "assistant", "content": [{"type": "text", "text": f"[task {n}] still building"}]}})
        result(f"[task {n}] still building")
        if not wait_unless_killed(agent, child, float(spec["pause"])):
            return
        emit({"type": "system", "subtype": "task_updated", "task_id": child,
              "patch": {"status": "completed"}, "session_id": SESSION})
        emit({"type": "system", "subtype": "task_notification", "task_id": child,
              "tool_use_id": f"toolu_child{n}", "status": "completed",
              "summary": "Background command completed (exit code 0)", "session_id": SESSION})
        LIVE.pop(child, None)
        live_changed()
        agent_started(n, agent, tool_use_id)  # the CLI resumes the parked helper
    if spec.get("report", True):
        run_report(n, spec.get("status", "done"), spec.get("summary", "Done."),
                   spec.get("question", ""), spec.get("found", []))
    agent_completed(agent, tool_use_id, "helper finished")
    emit({"type": "assistant", "parent_tool_use_id": None,
          "message": {"role": "assistant", "content": [{"type": "text", "text": f"[task {n}] done"}]}})
    result(f"[task {n}] done")


def main() -> int:
    if "--version" in sys.argv:
        print("2.1.316 (Claude Code)")
        return 0
    note({"argv": sys.argv[1:], "cwd": os.getcwd(),
          "claudecode": "CLAUDECODE" in os.environ,
          "claude_code_keys": sorted(k for k in os.environ if k.startswith("CLAUDE_CODE_")),
          "api_key": "ANTHROPIC_API_KEY" in os.environ,
          "home": os.environ.get("LEGION_CLAUDE_HOME")})
    started = False
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        text = msg["message"]["content"]
        note({"msg": text})
        if not started:
            emit({"type": "system", "subtype": "init", "session_id": SESSION, "tools": []})
            started = True
        spec = parse_fake(text)
        if spec.get("crash"):
            os._exit(3)
        m = re.match(r"\[report (\d+)\]", text)
        if m:  # the worker's reminder: the main window runs report.py itself, once
            n = int(m.group(1))
            s = SPECS.get(n, {})
            if s.get("nudge_report"):
                run_report(n, s.get("nudge_status", "done"), s.get("nudge_summary", "Reported late."))
            result(f"[task {n}] reported" if s.get("nudge_report") else "ok")
            continue
        m = re.match(r"\[(cancel|stop) (\d+)\]", text)
        if m:
            n = int(m.group(2))
            emit({"type": "assistant", "parent_tool_use_id": None, "message": {"role": "assistant",
                  "content": [{"type": "tool_use", "id": f"toolu_stop{n}", "name": "TaskStop",
                               "input": {"task_id": f"agent{n}"}}]}})
            KILLED.add(f"agent{n}")
            result(f"Stopped task {n}.")
            continue
        m = re.match(r"\[task (\d+)\]", text)
        if not m or spec.get("no_agent"):
            if not spec.get("hang"):
                result("ok")
            continue
        n = int(m.group(1))
        SPECS[n] = spec
        tuid = f"toolu_{n}"
        emit({"type": "assistant", "parent_tool_use_id": None, "message": {"role": "assistant",
              "content": [{"type": "tool_use", "id": tuid, "name": "Agent",
                           "input": {"description": f"task {n}: fake", "prompt": f"Task {n}.",
                                     "run_in_background": True}}]}})
        emit({"type": "user", "parent_tool_use_id": None, "message": {"role": "user", "content": [
            {"tool_use_id": tuid, "type": "tool_result",
             "content": [{"type": "text", "text": "Async agent launched successfully."}]}]}})
        threading.Thread(target=helper, args=(n, tuid, spec), daemon=True).start()
        if not spec.get("hang"):
            result(f"[task {n}] started")
    return 0


if __name__ == "__main__":
    sys.exit(main())
