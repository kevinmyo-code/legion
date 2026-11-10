"""LEGION Claude session worker (map claude-session, ticket 04). One per laptop.

Runs in Kevin's logon session (Task Scheduler "LEGION Claude session", at logon, through a lock):
- heartbeat to the engine every 20 s, claim queued tasks, keep the PC awake while on mains power;
- one long-lived main Claude session (session.py) that gets `[task N]` messages and hands each to a
  background helper subagent; rotated daily, resumed with `--resume` after a restart;
- drains report.py's queue and forwards each result to the engine (report.py is the source of
  truth for a task's status, not the main session's chat text);
- cancel and the 30 minute cap: a message asking the main session to TaskStop that one helper;
  kill and resume only when the main session itself stops answering;
- uploads a file only when the engine says Kevin asked for it and the file is one that task listed;
  a `deliver: files` task's files go up straight after its finish (ADR 0080: naming them was the
  yes), unless there are more than 20 or 100 MB of them, when the task asks first;
- issues the confirmation tokens send_mail.py and the ask-list hook check;
- worker workflows (map worker-workflows, ticket 05): reads Kevin's private skills for
  `workflow.json` sidecars every 5 minutes, sends the engine the catalog's hash on every heartbeat
  and the catalog itself only when the engine holds a different hash; a `kind: workflow` task gets
  its skill, Kevin's words, a dated report file under `%USERPROFILE%/.legion/reports/<workflow>/`
  and its declared stops; a scheduled run gets no token of any kind but the workflow's own
  standing `email_self`. Every finished task adds one line to `%USERPROFILE%/.legion/
  task-log.jsonl` (request text, kept 180 days, never leaves the laptop) for the workflow miner.

Logs carry ids, statuses, counts and exit codes. Never a request, a summary, a question or a path.

    python worker.py            # run (what the scheduled task starts, via pythonw.exe)
    python worker.py --check    # validate the config and print the claude command's shape
"""
from __future__ import annotations

import argparse
import collections
import ctypes
import datetime
import json
import logging
import logging.handlers
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from common import (DELIVER_EMAIL_SELF, DELIVER_FILES, DELIVER_PHONE, FILES_MAX_COUNT,  # noqa: E402
                    FILES_MAX_TOTAL_BYTES, PAGE_CAP_BYTES, UPLOAD_CAP_BYTES, Paths, load_config,
                    norm, read_json, write_json_atomic)
from engine_client import EngineClient, EngineDown, EngineError  # noqa: E402
from session import MainSession, build_command, child_env, kill_tree  # noqa: E402
import warm_worktree  # noqa: E402
import workflows as wfl  # noqa: E402

log = logging.getLogger("legion.claude")

DEFAULTS = {
    "claude_cmd": ["claude"],
    "claude_images": ["claude.exe", "node.exe"],
    "heartbeat_s": 20.0,
    "tick_s": 0.5,
    "task_cap_s": 30 * 60.0,
    "report_grace_s": 60.0,
    # A helper's `completed` is final only once it has no live background work of its own; the CLI
    # sends the same event when it parks waiting on a build (task 7, 2026-11-06). Events about its
    # children can trail the notification by a moment, so the verdict waits this long.
    "settle_s": 2.0,
    "hang_s": 10 * 60.0,
    "restart_min_s": 5.0,
    "backoff_max_s": 300.0,
    "keep_task_s": 48 * 3600.0,
    "keep_work_days": 30,
    "keep_awake": True,
    # Worker workflows (ticket 05): how often the private skills folder is re-read, and how long
    # reports and the task log are kept.
    "catalog_s": 300.0,
    "keep_reports_days": 180,
    "keep_task_log_days": 180,
}

KIND_WORKFLOW = "workflow"
ORIGIN_SCHEDULE = "schedule"
SAY_NO_WORKFLOW = ("The {name} workflow is not on this laptop any more, so nothing ran. Its "
                   "skill folder or workflow.json is gone or fails the lint.")
REPORT_NAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})-task-\d+\.md$")


def legion_home() -> Path:
    """Kevin's `%USERPROFILE%/.legion` folder: agent rules, durable reports, the task log."""
    return Path(os.environ.get("USERPROFILE") or Path.home()) / ".legion"

PROGRESS_CAP = 120  # heartbeat `progress` text per task (ticket 04 addition, status extras)

TASK_RE = re.compile(r"\btask\s*#?\s*(\d+)", re.I)
TASK_FLAG_RE = re.compile(r"--task\s+(\d+)")

SAY_RESTARTED = "The laptop's Claude session restarted while this was running, so it did not finish."
SAY_HUNG = "The laptop's Claude session stopped answering and was restarted, so this did not finish."
SAY_CAP = "Stopped after 30 minutes without finishing."
SAY_CANCELLED = "Cancelled before it finished, as asked."
SAY_NO_REPORT = ("The helper finished without reporting a result, and a reminder to report did "
                 "not get one.")
SAY_KILLED = "The helper was stopped before it finished."

# memory-review ticket 16: the daily memory review is one built-in task kind. Its progress line is
# this fixed phrase, never the helper's own step text, and its summary is the engine's counts
# line from result.json, never what the helper wrote, so no memory text reaches the engine's task
# row, its steps or the main session.
KIND_MEMORY_REVIEW = "memory_review"
REVIEW_PROGRESS = "Reviewing memories"
SAY_REVIEW_NO_RESULT = "The memory review ended without sending its changes."
REVIEW_TEXT_FILES = ("context.json", "changes.json")


def files_question(count: int, total: int) -> str:
    """ADR 0080: the one question a files task asks when the find is past the caps."""
    mb = total / (1024 * 1024)
    size = f"{mb:.0f} MB" if mb >= 1 else f"{max(1, total // 1024)} KB"
    noun = "file" if count == 1 else "files"
    return f"I found {count} {noun}, {size} in all. Send them all to your phone?"


def files_instructions(confirmed: bool) -> str:
    """ADR 0080: what a `deliver: files` helper is told. Find by name, path and metadata; open a
    document only to tell candidates apart, and say so; ask when unsure or past the caps."""
    text = ("Kevin wants these files on his phone; naming them was his yes for exactly those "
            "files. Find them by name, folder, date, type and size. Do not open or read a "
            "document's contents (scans, IDs, statements, letters) unless you must to tell two "
            "candidates apart; if you did, say so in the summary. List every file to send with "
            "--found (each under 25 MB) and LEGION sends them to his phone. The summary names "
            "them and their sizes in words.")
    if confirmed:
        return text + " He has already said yes to sending all of them; list them all."
    return text + (f" If what you found is not clearly what he asked for, or it is more than "
                   f"{FILES_MAX_COUNT} files or {FILES_MAX_TOTAL_BYTES // (1024 * 1024)} MB, "
                   "finish needs_confirmation with one question naming the count and size, and "
                   "still list them with --found.")


def over_files_caps(found) -> bool:
    total = sum(int(f.get("size") or 0) for f in found)
    return len(found) > FILES_MAX_COUNT or total > FILES_MAX_TOTAL_BYTES


# ---------------------------------------------------------------- machine helpers (Windows)

class Power:
    """Keep the PC awake while on mains (ruling 7). ES_CONTINUOUS applies to the calling thread,
    so `update` is called from the worker's main loop thread only."""

    ES_CONTINUOUS = 0x80000000
    ES_SYSTEM_REQUIRED = 0x00000001

    def __init__(self, enabled: bool = True):
        self.enabled = enabled and sys.platform == "win32"
        self.holding = False

    def on_mains(self) -> bool:
        class SPS(ctypes.Structure):
            _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                        ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                        ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]
        s = SPS()
        if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)):
            return True  # unknown: treat as mains, the cost is a PC that stays awake
        return s.ACLineStatus != 0  # 0 offline (battery), 1 online, 255 unknown

    def update(self) -> None:
        if not self.enabled:
            return
        want = self.on_mains()
        if want != self.holding:
            flags = self.ES_CONTINUOUS | (self.ES_SYSTEM_REQUIRED if want else 0)
            ctypes.windll.kernel32.SetThreadExecutionState(flags)
            self.holding = want
            log.info("keep-awake %s", "on (mains)" if want else "off (battery)")

    def release(self) -> None:
        if self.enabled and self.holding:
            ctypes.windll.kernel32.SetThreadExecutionState(self.ES_CONTINUOUS)
            self.holding = False


def input_idle_seconds() -> int | None:
    """Seconds since Windows last saw keyboard or mouse input in this session (ticket 15: the
    engine mints the memory review only on an idle laptop). None when it cannot tell."""
    if sys.platform != "win32":
        return None

    class LASTINPUTINFO(ctypes.Structure):
        _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

    info = LASTINPUTINFO()
    info.cbSize = ctypes.sizeof(info)
    try:
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
            return None
        now = ctypes.windll.kernel32.GetTickCount() & 0xFFFFFFFF
    except (AttributeError, OSError):
        return None
    return max(0, ((now - info.dwTime) & 0xFFFFFFFF) // 1000)


def fixed_drives() -> list[str]:
    if sys.platform != "win32":
        return ["/"]
    k32 = ctypes.windll.kernel32
    mask = k32.GetLogicalDrives()
    out = []
    for i in range(26):
        if mask & (1 << i):
            root = f"{chr(65 + i)}:\\"
            if k32.GetDriveTypeW(ctypes.c_wchar_p(root)) == 3:  # DRIVE_FIXED
                out.append(root)
    return out


def process_image(pid: int) -> str:
    if sys.platform != "win32":
        return ""
    try:
        out = subprocess.run(["tasklist", "/FI", f"PID eq {int(pid)}", "/FO", "CSV", "/NH"],
                             capture_output=True, text=True, timeout=20,
                             creationflags=0x08000000).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""
    return out.split(",")[0].strip('"').lower() if out.startswith('"') else ""


def process_cmdline(pid: int) -> str:
    if sys.platform != "win32":
        return ""
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command",
             f"(Get-CimInstance Win32_Process -Filter 'ProcessId = {int(pid)}').CommandLine"],
            capture_output=True, text=True, timeout=30, creationflags=0x08000000)
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def helper_python() -> str:
    exe = Path(sys.executable)
    if exe.name.lower() == "pythonw.exe" and (exe.parent / "python.exe").exists():
        exe = exe.parent / "python.exe"
    return str(exe)


def cmd_path(p) -> str:
    """A path written so it runs as the first word in both Bash and PowerShell."""
    s = str(p).replace("\\", "/")
    return f'"{s}"' if " " in s else s


def setup_logging(paths: Paths) -> None:
    paths.logs.mkdir(parents=True, exist_ok=True)
    h = logging.handlers.RotatingFileHandler(paths.logs / "worker.log", maxBytes=1_000_000,
                                             backupCount=3, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(h)
    log.setLevel(logging.INFO)


# ---------------------------------------------------------------- the worker

class Worker:
    def __init__(self, paths: Paths, cfg: dict, *, engine=None, power=None, clock=time.time,
                 today=None, drives=None, base_env=None, idle=None):
        self.paths = paths.ensure()
        self.cfg = {**DEFAULTS, **cfg}
        self.scope = self.cfg.get("scope", "listed")
        self.name = self.cfg["name"]
        self.engine = engine or EngineClient(self.cfg["engine_url"], self.cfg["token"])
        self.power = power if power is not None else Power(self.cfg["keep_awake"])
        self.clock = clock
        self.today = today or (lambda: datetime.date.today().isoformat())
        self.drives = drives if drives is not None else fixed_drives
        self.idle = idle if idle is not None else input_idle_seconds
        self.base_env = dict(os.environ if base_env is None else base_env)

        st = read_json(paths.state, {}) or {}
        self.state = {"session_id": st.get("session_id"), "session_date": st.get("session_date"),
                      "session_started": bool(st.get("session_started")),
                      "tasks": st.get("tasks", {}), "outbox": st.get("outbox", []),
                      "agents": st.get("agents", {}), "last_work_prune": st.get("last_work_prune")}
        self.dirty = False
        self.session: MainSession | None = None
        self.session_started_at = 0.0
        self.resume_failures = 0
        self.restart_failures = 0
        self.next_session_try = 0.0
        self.pending: collections.deque = collections.deque()  # (task_id or None, text)
        self.awaiting_since: float | None = None
        self.tool_uses: dict[str, int] = {}
        self.next_contact = 0.0
        self.backoff = 0.0
        self.uploaded: set = set()
        self.refused: set = set()
        self.claude_version = ""
        self.progress: dict[int, str] = {}  # task id -> latest helper step (heartbeat only)
        # Live background tasks in the main session (CLI task ids) -> the task id that owns each
        # (a helper agent for its own builds and waits), None for one the main window owns.
        # `background_tasks_changed` replaces it whole; task_started and task ends keep it current.
        self.live: dict[str, str | None] = {}
        # Worker workflows (ticket 05). The catalog is read from Kevin's private skills folder; the
        # engine's echoed hash says whether it already holds this one.
        legion = legion_home()
        self.skills_dir = Path(self.cfg.get("skills_dir") or (self.paths.skills / "skills"))
        self.reports_root = Path(self.cfg.get("workflow_reports") or (legion / "reports"))
        self.task_log = Path(self.cfg.get("task_log") or (legion / "task-log.jsonl"))
        self.agent_rules = Path(self.cfg.get("agent_rules") or (legion / "AGENT-RULES.md"))
        self.catalog: list[dict] = []
        self.catalog_hash: str | None = None
        self.catalog_at: float | None = None
        self.engine_wf_hash: str | None = None
        self.wf_resend_at = 0.0

    # ------------------------------------------------------------ state

    def _task(self, tid) -> dict | None:
        return self.state["tasks"].get(str(int(tid)))

    def running(self) -> list[dict]:
        return [t for t in self.state["tasks"].values() if t["status"] == "running"]

    def save(self, force: bool = False) -> None:
        if self.dirty or force:
            write_json_atomic(self.paths.state, self.state)
            self.dirty = False

    def _write_agents(self) -> None:
        write_json_atomic(self.paths.agents, self.state["agents"])

    # ------------------------------------------------------------ startup

    def startup(self) -> None:
        """Once per worker process: settings file, stale child, tasks orphaned by a restart."""
        self.write_settings()
        self.refresh_catalog(self.clock(), force=True)
        pidinfo = read_json(self.paths.pid, None)
        if pidinfo and pidinfo.get("pid") and pidinfo.get("session"):
            # Pids are recycled: kill only a process whose command line carries our session uuid,
            # never Kevin's own claude. An unverified orphan exits by itself when its stdin closes.
            image = process_image(pidinfo["pid"])
            if image and image in [i.lower() for i in self.cfg["claude_images"]] \
                    and pidinfo["session"] in process_cmdline(pidinfo["pid"]):
                kill_tree(int(pidinfo["pid"]))
                log.info("stopped the previous main session process")
            else:
                log.info("previous main session pid not verified; left alone")
        for t in self.running():
            self.finish(t, "failed", SAY_RESTARTED)
        self.claude_version = self.read_claude_version()
        self.prune(self.clock())
        self.save(force=True)

    def read_claude_version(self) -> str:
        try:
            out = subprocess.run(list(self.cfg["claude_cmd"]) + ["--version"], capture_output=True,
                                 text=True, timeout=60, env=child_env(self.base_env, self.paths.root),
                                 creationflags=0x08000000 if sys.platform == "win32" else 0)
            return (out.stdout.strip().splitlines() or [""])[0][:60]
        except (OSError, subprocess.SubprocessError):
            return ""

    def write_settings(self) -> None:
        hook = HERE / "asklist_hook.py"
        command = f"{cmd_path(helper_python())} {cmd_path(hook)}"
        matcher = "Bash|PowerShell|Write|Edit|MultiEdit|NotebookEdit"
        if self.scope != "full":  # reads and web only need the hook on the work laptop
            matcher += "|Read|Glob|Grep|WebFetch|WebSearch"
        write_json_atomic(self.paths.settings, {"hooks": {"PreToolUse": [
            {"matcher": matcher, "hooks": [{"type": "command", "command": command}]}]}})

    def add_dirs(self) -> list[str]:
        if self.cfg.get("add_dirs"):
            return list(self.cfg["add_dirs"])
        if self.scope == "full":
            return list(self.drives())
        return [self.cfg["repos_root"]] if self.cfg.get("repos_root") else []

    def system_prompt(self) -> str:
        text = (HERE / "main_session_prompt.md").read_text(encoding="utf-8")
        if self.scope == "full":
            scope = ("Full access (Kevin's ruling): every fixed drive (" + ", ".join(self.add_dirs())
                     + "), read and write, as Kevin's Windows user, not elevated. Reading anything is "
                     "fine. Kevin's files outside the work folder are his: deleting or overwriting "
                     "them is on the ask list. Kevin's Gmail is readable here, read only: "
                     "rule 10.")
        else:
            scope = (f"Work laptop. Only the repos folder {self.cfg.get('repos_root', '(not set)')}, "
                     "read only, plus the task's work folder for scratch files. Return summaries "
                     "only: never --found, never upload, no web. Mail only as text to Kevin himself, and only "
                     "when the task message gives a token. No Gmail here: rule 10 does "
                     "not apply on this machine.")
        for k, v in {"<<MACHINE>>": self.name, "<<PYTHON>>": cmd_path(helper_python()),
                     "<<REPORT>>": cmd_path(HERE / "report.py"),
                     "<<SEND_MAIL>>": cmd_path(HERE / "send_mail.py"),
                     # alfred-builds tickets 03, 05, 08: the ship step, undo, the build lock.
                     "<<SHIP>>": cmd_path(HERE / "ship.py"), "<<UNDO>>": cmd_path(HERE / "undo.py"),
                     "<<BUILD_LOCK>>": cmd_path(HERE / "build_lock.py"),
                     "<<WARM>>": cmd_path(HERE / "warm_worktree.py"),
                     "<<SCOPE>>": scope}.items():
            text = text.replace(k, v)
        return text

    # ------------------------------------------------------------ the main session

    def ensure_session(self, now: float) -> None:
        if self.session and self.session.alive():
            return
        if self.session is not None:  # it died
            code = self.session.proc.returncode if self.session.proc else None
            quick = now - self.session_started_at < 15
            log.warning("main session exited code=%s stderr_lines=%s", code, self.session.stderr_lines)
            self.session = None
            self.awaiting_since = None
            for t in self.running():
                self.finish(t, "failed", SAY_RESTARTED)
            if quick and self.state["session_started"]:
                self.resume_failures += 1
            self.restart_failures += 1
            delay = min(self.cfg["restart_min_s"] * 2 ** (self.restart_failures - 1),
                        self.cfg["backoff_max_s"])
            self.next_session_try = now + delay
        if now < self.next_session_try:
            return
        self.start_session(now)

    def start_session(self, now: float, fresh: bool = False) -> None:
        today = self.today()
        resume = (not fresh and self.state["session_id"] and self.state["session_started"]
                  and self.state["session_date"] == today and self.resume_failures < 2)
        if not resume:
            self.state.update(session_id=str(uuid.uuid4()), session_date=today, session_started=False)
            self.resume_failures = 0
            self.dirty = True
        cmd = build_command(self.cfg["claude_cmd"], settings=self.paths.settings,
                            prompt=self.system_prompt(), session_id=self.state["session_id"],
                            resume=bool(resume), scope=self.scope, add_dirs=self.add_dirs())
        self.session = MainSession(cmd, self.paths.work, child_env(self.base_env, self.paths.root))
        try:
            pid = self.session.start()
        except OSError as e:
            log.error("could not start the main session (%s)", type(e).__name__)
            self.session = None
            self.restart_failures += 1
            self.next_session_try = now + min(self.cfg["restart_min_s"] * 2 ** self.restart_failures,
                                              self.cfg["backoff_max_s"])
            return
        self.session_started_at = now
        write_json_atomic(self.paths.pid, {"pid": pid, "session": self.state["session_id"]})
        log.info("main session started (%s) pid=%s", "resume" if resume else "new", pid)
        self.save()

    def stop_session(self, kill: bool = False) -> None:
        if not self.session:
            return
        code = self.session.kill() if kill else self.session.close()
        log.info("main session stopped code=%s", code)
        self.session = None
        self.awaiting_since = None

    def maybe_rotate(self, now: float) -> None:
        if not (self.session and self.session.alive()):
            return
        if self.state["session_date"] == self.today():
            return
        if self.running() or self.pending or self.awaiting_since is not None:
            return  # rotate only when idle: closing the process stops its helpers
        self.stop_session()
        self.start_session(now, fresh=True)
        self.prune(now)
        log.info("main session rotated for the new day")

    def flush_pending(self, now: float) -> None:
        if not self.pending or self.awaiting_since is not None:
            return
        if not (self.session and self.session.alive()):
            return
        _tid, text = self.pending.popleft()
        try:
            self.session.send(text)
        except (OSError, ValueError):
            self.pending.appendleft((_tid, text))
            return
        self.awaiting_since = now

    # ------------------------------------------------------------ events from the main session

    def drain_events(self, now: float) -> None:
        if not self.session:
            return
        while True:
            try:
                ev = self.session.events.get_nowait()
            except Exception:
                return
            self.on_event(ev, now)

    def on_event(self, ev: dict, now: float) -> None:
        typ, sub = ev.get("type"), ev.get("subtype")
        if typ == "assistant" and not ev.get("parent_tool_use_id"):
            for c in (ev.get("message") or {}).get("content") or []:
                if c.get("type") == "tool_use" and c.get("name") in ("Agent", "Task"):
                    tid = self._task_from_text((c.get("input") or {}).get("description", ""),
                                               (c.get("input") or {}).get("prompt", ""))
                    if tid is not None:
                        self.tool_uses[c.get("id", "")] = tid
        elif typ == "system" and sub == "task_started":
            agent = ev.get("task_id")
            parent = ev.get("parent_task_id")
            if agent:
                self.live[agent] = parent
            if parent or ev.get("owned_by_subagent"):
                return  # a helper's own build, wait or subagent: never the helper itself
            tid = self.tool_uses.get(ev.get("tool_use_id", ""))
            if tid is None:
                tid = self._task_from_text(ev.get("description", ""), ev.get("prompt", ""))
            t = self._task(tid) if tid is not None else None
            if t and agent:
                resumed = t.get("agent") == agent
                t["agent"] = agent
                for k in ("helper_completed_at", "helper_ended_at", "helper_end", "helper_paused",
                          "nudged_at"):
                    t.pop(k, None)  # a helper that runs again has not ended
                self.state["agents"][agent] = int(tid)
                self._write_agents()
                self.dirty = True
                log.info("task %s helper %s", tid, "resumed" if resumed else "started")
        elif typ == "system" and sub == "background_tasks_changed":
            self.live = {x["task_id"]: x.get("parent_task_id")
                         for x in ev.get("tasks") or [] if isinstance(x, dict) and x.get("task_id")}
        elif typ == "system" and sub == "task_progress":
            tid = self.tool_uses.get(ev.get("tool_use_id", ""))
            t = self._task(tid) if tid is not None else self._task_for_agent(ev.get("task_id"))
            if t:
                t["turns"] = int((ev.get("usage") or {}).get("tool_uses") or t.get("turns", 0))
                desc = " ".join(str(ev.get("description") or "").split())
                if t.get("kind") == KIND_MEMORY_REVIEW:
                    self.progress[t["id"]] = REVIEW_PROGRESS  # never the helper's own words
                    self.keep_usage(t, ev.get("usage"))
                elif desc:
                    self.progress[t["id"]] = desc[:PROGRESS_CAP]  # memory only, never logged
                self.dirty = True
        elif typ == "system" and sub in ("task_updated", "task_notification"):
            status = (ev.get("patch") or {}).get("status") if sub == "task_updated" else ev.get("status")
            if status in ("completed", "killed", "failed"):
                self.live.pop(ev.get("task_id") or "", None)
            t = self._task_for_agent(ev.get("task_id"))
            if t and sub == "task_notification":
                t["turns"] = int((ev.get("usage") or {}).get("tool_uses") or t.get("turns", 0))
                if t.get("kind") == KIND_MEMORY_REVIEW:
                    self.keep_usage(t, ev.get("usage"))
            if not t or t["status"] != "running" or t.get("helper_ended_at"):
                return
            if status == "completed":
                # Final, or a pause on its own background work: the raw event is the same either
                # way (CLI 2.1.319), so check_timers decides from its live children after settle_s.
                if not t.get("helper_completed_at"):
                    t["helper_completed_at"] = now
                    self.dirty = True
            elif status in ("killed", "failed"):
                t["helper_ended_at"] = now
                t["helper_end"] = status
                self.dirty = True
                log.info("task %s helper ended %s", t["id"], status)
        elif typ == "result":
            self.awaiting_since = None
            if not self.state["session_started"]:
                self.state["session_started"] = True
                self.dirty = True
            self.resume_failures = 0
            self.restart_failures = 0

    def keep_usage(self, t: dict, usage) -> None:
        """The review helper's token counts, numbers only, for review_memories.py submit to
        forward (so subscription use is measured, not guessed)."""
        if not isinstance(usage, dict):
            return
        counts = {str(k)[:40]: v for k, v in usage.items()
                  if isinstance(v, (int, float)) and not isinstance(v, bool)}
        if counts:
            try:
                write_json_atomic(self.paths.task_work(t["id"]) / "usage.json", counts)
            except OSError:
                pass

    def review_result(self, t: dict) -> tuple[str, str]:
        """A memory review's status and summary come from result.json, which
        review_memories.py writes with the engine's counts (or its own error words). Whatever the
        helper typed is dropped, so a memory never reaches the task row."""
        res = read_json(self.paths.task_work(t["id"]) / "result.json", None)
        if isinstance(res, dict) and isinstance(res.get("words"), str) and res["words"]:
            return "done", res["words"][:500]
        if isinstance(res, dict) and isinstance(res.get("error"), str) and res["error"]:
            return "failed", ("The memory review failed: " + res["error"])[:500]
        return "failed", SAY_REVIEW_NO_RESULT

    def _task_from_text(self, *texts: str) -> int | None:
        for text in texts:
            m = TASK_FLAG_RE.search(text or "") or TASK_RE.search(text or "")
            if m and self._task(m.group(1)):
                return int(m.group(1))
        return None

    def _task_for_agent(self, agent) -> dict | None:
        tid = self.state["agents"].get(agent or "")
        return self._task(tid) if tid is not None else None

    def _has_live_work(self, agent) -> bool:
        """Does the helper `agent` still own live background work (its builds, waits or
        subagents, at any depth)?"""
        if not agent:
            return False
        for task_id in self.live:
            seen, cur = set(), self.live.get(task_id)
            while cur and cur not in seen:
                if cur == agent:
                    return True
                seen.add(cur)
                cur = self.live.get(cur)
        return False

    # ------------------------------------------------------------ reports, timers, finishing

    def drain_reports(self, now: float) -> None:
        for f in sorted(self.paths.reports.glob("*.json")):
            r = read_json(f, None)
            try:
                f.unlink()
            except OSError:
                continue
            if not isinstance(r, dict) or "task" not in r:
                log.warning("dropped an unreadable report")
                continue
            t = self._task(r["task"])
            if t and t["status"] == "failed" and not t.get("late_report"):
                self.keep_late_report(t, r)
                continue
            if not t or t["status"] != "running":
                log.info("report for task %s ignored (not running)", r.get("task"))
                continue
            if t.get("kind") == KIND_MEMORY_REVIEW:
                status, summary = self.review_result(t)
                self.finish(t, status, summary)
                continue
            self.finish(t, r.get("status", "failed"), r.get("summary", ""), r.get("question", ""),
                        r.get("found") or [])

    def keep_late_report(self, t: dict, r: dict) -> None:
        """A helper's report that came after the worker failed its task. Still in the outbox (the
        engine was down): the report replaces the failure. Already delivered: the engine refuses a
        second finish (FinishView, 409), so it is kept on this laptop, never silently dropped."""
        t["late_report"] = True
        self.dirty = True
        for item in self.state["outbox"]:
            if item["task"] == t["id"]:
                found = [] if self.scope != "full" else [
                    {"path": f["path"], "name": f["name"], "size": int(f["size"])}
                    for f in r.get("found") or []]
                fields = {"status": r.get("status", "failed"), "summary": r.get("summary", ""),
                          "question": r.get("question", ""), "found": found}
                item["payload"].update(fields)
                t.update(fields)
                log.warning("late report for task %s replaced its unsent failure (%s)", t["id"],
                            t["status"])
                return
        late = self.paths.reports / "late"
        late.mkdir(parents=True, exist_ok=True)
        write_json_atomic(late / f"{int(t['id'])}.json", {**r, "kept_at": self.clock()})
        log.warning("late report for task %s after it failed; the engine already has the failure, "
                    "kept in reports/late", t["id"])

    def finish(self, t: dict, status: str, summary: str, question: str = "", found=()) -> None:
        now = self.clock()
        found = [] if self.scope != "full" else [
            {"path": f["path"], "name": f["name"], "size": int(f["size"])} for f in found]
        if t.get("kind") == KIND_MEMORY_REVIEW:
            found = []  # a review never lists a file
            for name in REVIEW_TEXT_FILES:  # memory text never outlives the task on the laptop
                try:
                    (self.paths.task_work(t["id"]) / name).unlink(missing_ok=True)
                except OSError:
                    pass
        if (t.get("deliver") == DELIVER_FILES and status == "done" and not t.get("confirms")
                and over_files_caps(found)):
            # ADR 0080: naming the files is the yes only up to 20 files and 100 MB. Past that the
            # task asks, the files stay listed, and Kevin's yes is a follow-up that sends them.
            status = "needs_confirmation"
            question = files_question(len(found), sum(f["size"] for f in found))
            log.info("task %s over the files caps (%s files); asking first", t["id"], len(found))
        t.update(status=status, summary=summary, question=question, found=found, finished_at=now)
        self.log_task(t, status)
        payload = {"status": status, "summary": summary, "question": question,
                   "turns": int(t.get("turns", 0)),
                   "duration_ms": int(max(0.0, now - t.get("started_at", now)) * 1000),
                   "found": found}
        self.state["outbox"].append({"task": t["id"], "payload": payload})
        if status != "needs_confirmation":  # a question keeps the warm tree for its follow-up
            try:
                if warm_worktree.release(self.paths, t["id"]):
                    log.info("task %s released the warm worktree", t["id"])
            except OSError:
                pass
        nudge = f"[report {t['id']}]"
        for p in [p for p in self.pending if p[0] is None and p[1].startswith(nudge)]:
            self.pending.remove(p)
        self.dirty = True
        log.info("task %s finished %s found=%s", t["id"], status, len(found))

    def check_timers(self, now: float) -> None:
        for t in self.running():
            if now - t["started_at"] > self.cfg["task_cap_s"]:
                self._stop_helper(t, "stop", f"[stop {t['id']}] Task {t['id']} hit the 30 minute "
                                  f"limit. Stop task {t['id']}'s helper now with TaskStop. One line.")
                self.finish(t, "failed", SAY_CAP)
            elif t.get("helper_completed_at") and not t.get("helper_ended_at"):
                if now - t["helper_completed_at"] < self.cfg["settle_s"]:
                    continue
                if self._has_live_work(t.get("agent")):
                    if not t.get("helper_paused"):  # waits on its own build: the cap still holds
                        t["helper_paused"] = True
                        self.dirty = True
                        log.info("task %s helper paused on its own background work", t["id"])
                    continue
                t["helper_ended_at"] = now
                t["helper_end"] = "completed"
                self.dirty = True
                log.info("task %s helper ended completed", t["id"])
            elif t.get("helper_ended_at") and now - t["helper_ended_at"] > self.cfg["report_grace_s"]:
                if t.get("helper_end") != "completed":
                    self.finish(t, "failed", SAY_KILLED if t.get("helper_end") == "killed"
                                else SAY_NO_REPORT)
                elif not t.get("nudged_at"):
                    t["nudged_at"] = now
                    self.dirty = True
                    self.pending.append((None, self.nudge_message(t["id"])))
                    log.info("task %s finished without a report; reminder sent", t["id"])
                elif now - t["nudged_at"] > self.cfg["report_grace_s"]:
                    self.finish(t, "failed", SAY_NO_REPORT)
        if self.awaiting_since is not None and now - self.awaiting_since > self.cfg["hang_s"]:
            log.warning("main session did not answer; restarting it")
            self.stop_session(kill=True)
            for t in self.running():
                self.finish(t, "failed", SAY_HUNG)
            self.start_session(now)

    @staticmethod
    def nudge_message(tid: int) -> str:
        return (f"[report {tid}] Task {tid}'s helper finished without running report.py, so LEGION "
                f"has no result for it. Run exactly one report.py line for task {tid} yourself now, "
                "from the helper's last result: --status done --summary only if that result clearly "
                "says the work finished, --status needs_confirmation --question if it ended on a "
                "question for Kevin, otherwise --status failed --summary with what it said, in "
                "words. Then one line.")

    def _stop_helper(self, t: dict, verb: str, text: str) -> None:
        unsent = [p for p in self.pending if p[0] == t["id"]]
        if unsent:  # never reached the main session: drop it, nothing to stop
            for p in unsent:
                self.pending.remove(p)
            return
        self.pending.appendleft((None, text))
        log.info("task %s %s sent", t["id"], verb)

    # ------------------------------------------------------------ engine contact

    # ------------------------------------------------------------ workflows (ticket 05)

    def refresh_catalog(self, now: float, force: bool = False) -> None:
        """Re-read the sidecars at most every `catalog_s`. A sidecar that fails the lint is left
        out and logged by folder name only, never its text."""
        if not force and self.catalog_at is not None and now - self.catalog_at < self.cfg["catalog_s"]:
            return
        self.catalog_at = now
        try:
            entries, problems = wfl.load_catalog(self.skills_dir)
        except OSError:
            log.warning("workflow catalog unreadable; keeping the last one")
            return
        new_hash = wfl.catalog_hash(entries)
        if new_hash != self.catalog_hash:
            log.info("workflow catalog: %s workflow(s), %s left out%s", len(entries), len(problems),
                     "".join(f"; {folder} fails the lint" for folder, _ in problems))
        self.catalog, self.catalog_hash = entries, new_hash

    def workflow(self, name) -> dict | None:
        return next((e for e in self.catalog if e["name"] == name), None)

    def catalog_fields(self, now: float) -> dict:
        """The hash always; the catalog only when the engine holds another. An engine that does
        not echo a hash (one deployed before workflows) gets the list at most every `catalog_s`."""
        if self.catalog_hash is None:
            return {}
        out = {"workflows_hash": self.catalog_hash}
        if self.engine_wf_hash != self.catalog_hash and now >= self.wf_resend_at:
            out["workflows"] = wfl.catalog_for_engine(self.catalog)
        return out

    def note_engine_hash(self, reply: dict, sent: dict, now: float) -> None:
        if "workflows_hash" in reply:
            self.engine_wf_hash = reply.get("workflows_hash")
            if reply.get("workflows_dropped"):
                log.warning("engine dropped %s workflow(s) from the catalog",
                            int(reply["workflows_dropped"]))
            refused = len(reply.get("workflows_refused") or [])
            if refused != getattr(self, "_refused_schedules", 0):
                # A schedule this machine kind does not run on its own (a listed machine): count
                # only; the words are on the engine's workflow list.
                log.info("engine runs %s workflow schedule(s) here only when Kevin asks", refused)
                self._refused_schedules = refused
        elif "workflows" in sent:
            self.wf_resend_at = now + self.cfg["catalog_s"]

    def report_path(self, t: dict) -> Path:
        day = datetime.date.fromtimestamp(t.get("started_at") or self.clock()).isoformat()
        return self.reports_root / t["workflow"] / f"{day}-task-{int(t['id'])}.md"

    def log_task(self, t: dict, status: str) -> None:
        """One line per finished task for the workflow miner (R4): request text included, so it
        stays on this laptop and is pruned at `keep_task_log_days`. The Alienware only: the miner
        reads its own worker's tasks, and the work laptop keeps no such log."""
        if self.scope != "full":
            return
        line = {"date": datetime.datetime.fromtimestamp(self.clock()).isoformat(timespec="seconds"),
                "task": int(t["id"]), "kind": t.get("kind") or "ask", "workflow": t.get("workflow"),
                "origin": t.get("origin"), "status": status, "request": t.get("request", "")}
        try:
            self.task_log.parent.mkdir(parents=True, exist_ok=True)
            with open(self.task_log, "a", encoding="utf-8") as f:
                f.write(json.dumps(line) + "\n")
        except OSError:
            log.warning("task %s not written to the task log", t["id"])

    def prune_workflow_files(self, now: float) -> None:
        """Reports the worker named (`<workflow>/<date>-task-<N>.md` under a workflow this laptop
        has) and task-log lines past their days. Nothing else in Kevin's reports folder."""
        cutoff = datetime.date.fromtimestamp(now) - datetime.timedelta(
            days=self.cfg["keep_reports_days"])
        for entry in self.catalog:
            folder = self.reports_root / entry["name"]
            if not folder.is_dir():
                continue
            for f in folder.iterdir():
                m = REPORT_NAME_RE.match(f.name)
                try:
                    if m and f.is_file() and datetime.date.fromisoformat(m.group(1)) < cutoff:
                        f.unlink()
                except (OSError, ValueError):
                    pass
        if not self.task_log.is_file():
            return
        keep_after = datetime.datetime.fromtimestamp(now) - datetime.timedelta(
            days=self.cfg["keep_task_log_days"])
        try:
            lines = self.task_log.read_text(encoding="utf-8").splitlines()
        except OSError:
            return
        kept = []
        for line in lines:
            try:
                if datetime.datetime.fromisoformat(json.loads(line)["date"]) >= keep_after:
                    kept.append(line)
            except (ValueError, KeyError, TypeError):
                continue
        if len(kept) != len(lines):
            tmp = self.task_log.with_suffix(".tmp")
            try:
                tmp.write_text("".join(x + "\n" for x in kept), encoding="utf-8")
                os.replace(tmp, self.task_log)
            except OSError:
                pass

    def contact_engine(self, now: float) -> None:
        if now < self.next_contact:
            return
        try:
            self.flush_outbox()
            self.power.update()
            self.refresh_catalog(now)
            running = sorted(t["id"] for t in self.running())
            self.progress = {k: v for k, v in self.progress.items() if k in running}
            wf_fields = self.catalog_fields(now)
            reply = self.engine.heartbeat({
                "name": self.name, "hostname": socket.gethostname(), "scope": self.scope,
                "state": "busy" if running else "idle", "running": running,
                "progress": {str(k): v for k, v in self.progress.items()},
                "claude_version": self.claude_version,
                # memory-review ticket 15: the engine mints the daily review only when idle.
                "input_idle_s": self.idle(), "on_mains": self.on_mains(), **wf_fields})
            self.note_engine_hash(reply, wf_fields, now)
            for tid in reply.get("cancel_requested") or []:
                t = self._task(tid)
                if t and t["status"] == "running":
                    self._stop_helper(t, "cancel", f"[cancel {t['id']}] Kevin cancelled task "
                                      f"{t['id']}. Stop task {t['id']}'s helper now with TaskStop. "
                                      "Do not report it. One line.")
                    self.finish(t, "cancelled", SAY_CANCELLED)
            for req in reply.get("files_requested") or []:
                self.upload_requested(req)
            if self.session and self.session.alive():
                for task in self.engine.claim(self.name):
                    self.accept(task, now)
            self.flush_outbox()
            self.backoff = 0.0
            self.next_contact = now + self.cfg["heartbeat_s"]
        except EngineDown as e:
            self.backoff = min(max(self.cfg["heartbeat_s"], self.backoff * 2 or self.cfg["heartbeat_s"]),
                               self.cfg["backoff_max_s"])
            self.next_contact = now + self.backoff
            log.warning("engine unreachable (%s); next try in %.0f s", e, self.backoff)

    def on_mains(self) -> bool | None:
        fn = getattr(self.power, "on_mains", None)
        if fn is None or sys.platform != "win32":
            return None
        try:
            return bool(fn())
        except Exception:  # noqa: BLE001 - a power query must never stop a heartbeat
            return None

    def flush_outbox(self) -> None:
        while self.state["outbox"]:
            item = self.state["outbox"][0]
            reply = None
            try:
                reply = self.engine.finish(item["task"], item["payload"])
            except EngineError as e:
                log.warning("finish for task %s refused HTTP %s; dropped", item["task"], e.status)
            self.state["outbox"].pop(0)
            self.dirty = True
            self.upload_at_finish(item["task"], reply)

    def upload_at_finish(self, tid, reply) -> None:
        """Ticket 09 and ADR 0080: a `deliver: phone` task's page, and a `deliver: files` task's
        files, go up right after its finish, without waiting a heartbeat. The engine requested
        them at finish (asking for them was Kevin's yes); [upload_requested] still checks each is
        a file this task listed. If the engine is down, the next heartbeat asks again."""
        t = self._task(tid)
        if (not t or t.get("deliver") not in (DELIVER_PHONE, DELIVER_FILES)
                or not isinstance(reply, dict)):
            return
        by_name = {}
        for f in t.get("found", []):
            by_name.setdefault(f["name"], f["path"])
        for f in reply.get("files") or []:
            if f.get("requested") and not f.get("uploaded") and f.get("name") in by_name:
                try:
                    self.upload_requested({"task": tid, "file_id": f.get("id"),
                                           "path": by_name[f["name"]]})
                except EngineDown:
                    log.warning("file for task %s not uploaded yet: engine unreachable", tid)
                    return

    def upload_requested(self, req: dict) -> None:
        fid, tid, path = req.get("file_id"), req.get("task"), req.get("path") or ""
        key = (tid, fid)
        if key in self.uploaded or key in self.refused:
            return
        t = self._task(tid) if tid is not None else None
        allowed = {norm(f["path"]) for f in (t or {}).get("found", [])}
        reason = None
        if self.scope != "full":
            reason = "work laptop never uploads"
        elif t and t.get("deliver_refused"):
            reason = "a scheduled run never sends files"
        elif not t or norm(path) not in allowed:
            reason = "not a file that task listed"
        elif not Path(path).is_file():
            reason = "file is gone"
        elif Path(path).stat().st_size > UPLOAD_CAP_BYTES:
            reason = "over the upload cap"
        elif (t.get("deliver") == DELIVER_PHONE and path.lower().endswith((".html", ".htm"))
              and Path(path).stat().st_size > PAGE_CAP_BYTES):
            reason = "page over the 5 MB cap"
        if reason:
            self.refused.add(key)
            log.warning("file %s for task %s not uploaded: %s", fid, tid, reason)
            return
        try:
            self.engine.upload(tid, fid, Path(path))
        except EngineError as e:
            log.warning("file %s for task %s upload refused HTTP %s", fid, tid, e.status)
            self.refused.add(key)
            return
        self.uploaded.add(key)
        log.info("file %s for task %s uploaded", fid, tid)

    # ------------------------------------------------------------ claimed tasks

    def accept(self, task: dict, now: float) -> None:
        tid = int(task["id"])
        if self._task(tid):
            return
        work = self.paths.task_work(tid)
        work.mkdir(parents=True, exist_ok=True)
        parent = task.get("parent")
        t = {"id": tid, "parent": parent, "request": task.get("request", ""), "status": "running",
             "kind": task.get("kind") or "ask",
             "started_at": now, "turns": 0, "deliver": task.get("deliver"),
             "confirms": bool(parent) and task.get("confirms") is True,
             "origin": task.get("origin"), "workflow": task.get("workflow"),
             "params": task.get("params")}
        self.state["tasks"][str(tid)] = t
        self.dirty = True
        if (t.get("origin") == ORIGIN_SCHEDULE and not parent
                and t["deliver"] in (DELIVER_PHONE, DELIVER_FILES)):
            # R3: an unattended run never sends a page or files on its own; only the standing
            # email_self may ride it. The engine never mints one; this holds if it ever did.
            t["deliver_refused"], t["deliver"] = t["deliver"], None
        if t["kind"] == KIND_WORKFLOW:
            self.refresh_catalog(now, force=True)
            if not t["workflow"] or self.workflow(t["workflow"]) is None:
                # Never run as free text: the engine's catalog is older than this laptop's.
                log.info("task %s claimed; its workflow is not on this laptop", tid)
                self.finish(t, "failed", SAY_NO_WORKFLOW.format(
                    name=str(t["workflow"] or "").replace("-", " ")))
                return
        self.pending.append((tid, self.task_message(t, work)))
        log.info("task %s claimed%s", tid, f" (follow-up to {parent})" if parent else "")

    def workflow_lines(self, t: dict, work: Path) -> list[str]:
        """Ticket 05: what a workflow task's message adds. The skill is always the workflow's own;
        Kevin's words and the params text stay on this laptop, in the main session."""
        entry = self.workflow(t["workflow"])
        name = entry["name"]
        lines = []
        if t.get("origin") == ORIGIN_SCHEDULE and not t.get("parent"):
            lines.append("Scheduled run; nobody is waiting on this. No one said yes to anything: "
                         "no confirmation token of any kind is given. Anything on the ask list "
                         "(deleting or overwriting Kevin's files, sending, booking or paying, "
                         "installing, system settings) and any ship or deploy step: stop with "
                         "needs_confirmation. Do not ask more than one question.")
        elif not t.get("parent"):
            lines.append("Kevin's words: \"" + t["request"] + "\"")
        if t.get("params"):
            lines.append(f'Params text: "{t["params"]}"')
        for p in [p for p in entry.get("params") or []]:
            default = (entry.get("defaults") or {}).get(p["name"])
            need = "required" if p.get("required") else (f'default "{default}"' if default else
                                                       "optional")
            lines.append(f"Param {p['name']}: {p['says']} ({need}).")
        if any(p.get("required") for p in entry.get("params") or []):
            lines.append("A required param Kevin's words do not give: stop with "
                         "needs_confirmation asking for it, one short question.")
        if self.agent_rules.is_file():
            lines.append(f"Read {self.agent_rules} first.")
        if self.scope == "full" and "report" in entry.get("outputs", ()):
            report = self.report_path(t)
            try:
                report.parent.mkdir(parents=True, exist_ok=True)
            except OSError:
                pass
            lines.append(f"Report file: {report} (a new file: write it with the Write tool and "
                         "list it with --found; never overwrite an older report).")
        if entry.get("asks"):
            lines.append("This workflow stops for Kevin's yes at: "
                         + "; ".join(a["says"] for a in entry["asks"]))
        return lines

    def review_message(self, t: dict, work: Path) -> str:
        """Ticket 16: the memory review's helper prompt is a file rendered into its work folder,
        so the main session never carries memory text or the review's rules in its own context."""
        tid = t["id"]
        text = (HERE / "memory_review_prompt.md").read_text(encoding="utf-8")
        for k, v in {"<<TASK>>": str(tid), "<<WORK>>": str(work),
                     "<<PYTHON>>": cmd_path(helper_python()),
                     "<<REVIEW>>": cmd_path(HERE / "review_memories.py"),
                     "<<REPORT>>": cmd_path(HERE / "report.py")}.items():
            text = text.replace(k, v)
        (work / "instructions.md").write_text(text, encoding="utf-8")
        return "\n".join([
            f"[task {tid}] Memory review (kind memory_review). {t['request']}",
            f"Start ONE background helper as usual (description starting exactly `task {tid}:`). "
            f"Its whole prompt: \"Read {work / 'instructions.md'} with the Read tool and follow it "
            "exactly.\" Add none of the usual helper rules; that file has its own. When it "
            f"finishes, reply with one line: [task {tid}] memory review finished. Never repeat "
            "anything it said about memories.",
            f"Work folder: {work}",
        ])

    def task_message(self, t: dict, work: Path) -> str:
        tid, parent = t["id"], t.get("parent")
        if t.get("kind") == KIND_MEMORY_REVIEW and self.scope == "full":
            return self.review_message(t, work)
        lines = []
        if parent:
            p = self._task(parent)
            head = f"[task {tid}] Follow-up to task {parent}."
            if p:
                head += f' Task {parent} was: "{p["request"]}".'
                if p["status"] == "needs_confirmation":
                    head += f' It stopped to ask Kevin: "{p.get("question", "")}".'
                elif p.get("summary"):
                    head += f' Its result: "{p["summary"]}".'
                if p.get("found"):
                    head += " Files it listed: " + "; ".join(f["path"] for f in p["found"]) + "."
            lines.append(head)
            if t.get("kind") == KIND_WORKFLOW:
                lines.append(f"Task {parent} was workflow {t['workflow']}: use the "
                             f"{t['workflow']} skill again.")
            lines.append(f"Kevin now says: {t['request']}")
            if t.get("confirms"):  # the phone marked this as Kevin's answer to task P's question
                tok = self.issue_token(tid, parent, "yes")
                lines.append(f"This is Kevin's answer to task {parent}'s question. If it is a clear "
                             f"yes, the step it asked "
                             f"about may now run, for task {tid} only. Confirmation token: {tok} "
                             f"(send_mail.py takes it as --task {tid} --token {tok}). Not a clear "
                             "yes: do not run it, report what Kevin said.")
        elif t.get("kind") == KIND_WORKFLOW:
            lines.append(f"[task {tid}] Workflow {t['workflow']}. Use the {t['workflow']} skill.")
        else:
            lines.append(f"[task {tid}] {t['request']}")
        if t.get("kind") == KIND_WORKFLOW:
            lines.extend(self.workflow_lines(t, work))
        standing = self.workflow(t.get("workflow")) if t.get("kind") == KIND_WORKFLOW else None
        if (t.get("deliver") == DELIVER_EMAIL_SELF and t.get("origin") == ORIGIN_SCHEDULE
                and not t.get("parent") and (standing or {}).get("deliver") != DELIVER_EMAIL_SELF):
            # R3: an unattended run's one standing yes is the one its own workflow.json declares,
            # never one the engine row claims.
            lines.append("This scheduled run has no yes to send mail. Do not send any.")
        elif t.get("deliver") == DELIVER_EMAIL_SELF:
            mail = self.cfg.get("mail") or {}
            if mail.get("address") and mail.get("kevin") and self.scope == "full":
                tok = self.issue_token(tid, parent, "email_self")
                lines.append("Kevin asked for the result by email; send it to him with send_mail.py "
                             f"--task {tid} --token {tok} --to kevin --attach FILE; that is the yes "
                             "for this one send to Kevin only. Anyone else still needs "
                             "needs_confirmation.")
            elif mail.get("address") and mail.get("kevin"):  # work laptop, ruling 18
                tok = self.issue_token(tid, parent, "email_self")
                lines.append("Kevin asked for the result by email; send him the summary as text "
                             f"with send_mail.py --task {tid} --token {tok} --to kevin (no "
                             "attachments from this laptop); that is the yes for this one send "
                             "to Kevin only.")
            else:
                lines.append("Kevin asked for the result by email, but this laptop does not send "
                             "mail. Say so in the summary.")
        elif t.get("deliver") == DELIVER_PHONE:
            if self.scope == "full":
                lines.append("Kevin wants the result as a page on his phone. Write ONE self-contained "
                             "HTML file in the work folder (a <title>, inline CSS, readable on a phone, "
                             "no external scripts, under 5 MB) and list it with --found; LEGION sends "
                             "it to his phone, that is his yes for this one page. The summary says the "
                             "page is ready and what is in it, in a sentence.")
            else:  # the engine refuses this; held here too (ruling 5)
                lines.append("Kevin asked for the result as a page on his phone, but this laptop "
                             "returns summaries only. Say so in the summary.")
        elif t.get("deliver") == DELIVER_FILES:
            if self.scope == "full":
                lines.append(files_instructions(bool(t.get("confirms"))))
            else:  # the engine refuses this; held here too (ruling 5)
                lines.append("Kevin asked for files on his phone, but this laptop returns "
                             "summaries only. Say so in the summary.")
        lines.append(f"Work folder: {work}")
        return "\n".join(lines)

    def issue_token(self, tid: int, parent, kind: str) -> str:
        tok = secrets.token_urlsafe(16)
        write_json_atomic(self.paths.tokens / f"{tid}-{kind}.json", {
            "token": tok, "task": int(tid), "parent": int(parent) if parent else None, "kind": kind,
            "expires_at": self.clock() + self.cfg["task_cap_s"] + 600})
        log.info("task %s confirmation issued (%s)", tid, kind)
        return tok

    # ------------------------------------------------------------ housekeeping

    def prune(self, now: float) -> None:
        self.prune_workflow_files(now)
        keep = self.cfg["keep_task_s"]
        for key, t in list(self.state["tasks"].items()):
            if t["status"] != "running" and now - t.get("finished_at", now) > keep:
                del self.state["tasks"][key]
        live = {str(k) for k in self.state["tasks"]}
        self.state["agents"] = {a: n for a, n in self.state["agents"].items() if str(n) in live}
        self._write_agents()
        for f in self.paths.tokens.glob("*.json"):
            if (read_json(f, {}) or {}).get("expires_at", 0) < now:
                f.unlink(missing_ok=True)
        for f in (self.paths.reports / "late").glob("*.json"):
            if (read_json(f, {}) or {}).get("kept_at", 0) < now - keep:
                f.unlink(missing_ok=True)
        cutoff = now - self.cfg["keep_work_days"] * 86400
        for d in self.paths.work.glob("task-*"):
            try:
                if d.is_dir() and d.stat().st_mtime < cutoff and not self._task(d.name[5:] or 0):
                    import shutil
                    shutil.rmtree(d, ignore_errors=True)
            except (OSError, ValueError):
                pass
        self.dirty = True

    # ------------------------------------------------------------ loop

    def tick(self) -> None:
        now = self.clock()
        self.ensure_session(now)
        self.drain_events(now)
        self.drain_reports(now)
        self.check_timers(now)
        self.flush_pending(now)
        self.maybe_rotate(now)
        self.contact_engine(now)
        self.save()

    def run(self, stop: threading.Event) -> None:
        self.startup()
        try:
            while not stop.is_set():
                try:
                    self.tick()
                except Exception:  # keep running; the traceback has no task text in it
                    log.exception("tick failed")
                stop.wait(self.cfg["tick_s"])
        finally:
            self.stop_session()
            self.power.release()
            self.save(force=True)


# ---------------------------------------------------------------- entry point

REQUIRED = ("name", "scope", "engine_url", "token")


def single_instance(paths: Paths):
    """Hold a lock for the life of the process so a second worker exits."""
    f = open(paths.lock, "a+")
    try:
        if sys.platform == "win32":
            import msvcrt
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        return None
    return f


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="LEGION Claude session worker")
    ap.add_argument("--check", action="store_true", help="validate config and exit")
    a = ap.parse_args(argv)
    paths = Paths().ensure()
    cfg = load_config(paths)
    missing = [k for k in REQUIRED if not cfg.get(k)]
    if cfg.get("scope") not in ("full", "listed"):
        missing.append("scope (full or listed)")
    if cfg.get("scope") == "listed" and not cfg.get("repos_root"):
        missing.append("repos_root")
    if missing:
        print(f"Config {paths.config} is missing: {', '.join(missing)}. Run install.ps1.")
        return 2
    if os.environ.get("ANTHROPIC_API_KEY"):
        print("Note: ANTHROPIC_API_KEY is set in this environment; the worker removes it from the "
              "Claude session so the subscription login is used.")
    setup_logging(paths)
    w = Worker(paths, cfg)
    if a.check:
        w.write_settings()
        cmd = build_command(w.cfg["claude_cmd"], settings=paths.settings, prompt="<prompt>",
                            session_id="<uuid>", resume=False, scope=w.scope, add_dirs=w.add_dirs())
        print("OK:", " ".join(c if c != "<prompt>" else '"<prompt>"' for c in cmd))
        return 0
    lock = single_instance(paths)
    if lock is None:
        print("Another worker is already running.")
        return 1
    log.info("worker start name=%s scope=%s", w.name, w.scope)
    stop = threading.Event()
    try:
        w.run(stop)
    except KeyboardInterrupt:
        pass
    log.info("worker stop")
    return 0


if __name__ == "__main__":
    sys.exit(main())
