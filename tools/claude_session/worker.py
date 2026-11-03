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
- issues the confirmation tokens send_mail.py and the ask-list hook check.

Logs carry ids, statuses, counts and exit codes. Never a request, a summary, a question or a path.

    python worker.py            # run (what the scheduled task starts, via pythonw.exe)
    python worker.py --check    # validate the config and print the claude command's shape
"""
from __future__ import annotations

import argparse
import collections
import ctypes
import datetime
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
from common import (DELIVER_EMAIL_SELF, DELIVER_PHONE, PAGE_CAP_BYTES, UPLOAD_CAP_BYTES, Paths, load_config, norm,  # noqa: E402
                    read_json, write_json_atomic)
from engine_client import EngineClient, EngineDown, EngineError  # noqa: E402
from session import MainSession, build_command, child_env, kill_tree  # noqa: E402

log = logging.getLogger("legion.claude")

DEFAULTS = {
    "claude_cmd": ["claude"],
    "claude_images": ["claude.exe", "node.exe"],
    "heartbeat_s": 20.0,
    "tick_s": 0.5,
    "task_cap_s": 30 * 60.0,
    "report_grace_s": 60.0,
    "hang_s": 10 * 60.0,
    "restart_min_s": 5.0,
    "backoff_max_s": 300.0,
    "keep_task_s": 48 * 3600.0,
    "keep_work_days": 30,
    "keep_awake": True,
}

PROGRESS_CAP = 120  # heartbeat `progress` text per task (ticket 04 addition, status extras)

TASK_RE = re.compile(r"\btask\s*#?\s*(\d+)", re.I)
TASK_FLAG_RE = re.compile(r"--task\s+(\d+)")

SAY_RESTARTED = "The laptop's Claude session restarted while this was running, so it did not finish."
SAY_HUNG = "The laptop's Claude session stopped answering and was restarted, so this did not finish."
SAY_CAP = "Stopped after 30 minutes without finishing."
SAY_CANCELLED = "Cancelled before it finished, as asked."
SAY_NO_REPORT = "The helper stopped without reporting a result."
SAY_KILLED = "The helper was stopped before it finished."


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
                 today=None, drives=None, base_env=None):
        self.paths = paths.ensure()
        self.cfg = {**DEFAULTS, **cfg}
        self.scope = self.cfg.get("scope", "listed")
        self.name = self.cfg["name"]
        self.engine = engine or EngineClient(self.cfg["engine_url"], self.cfg["token"])
        self.power = power if power is not None else Power(self.cfg["keep_awake"])
        self.clock = clock
        self.today = today or (lambda: datetime.date.today().isoformat())
        self.drives = drives if drives is not None else fixed_drives
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
                     "them is on the ask list.")
        else:
            scope = (f"Work laptop. Only the repos folder {self.cfg.get('repos_root', '(not set)')}, "
                     "read only, plus the task's work folder for scratch files. Return summaries "
                     "only: never --found, never upload, no web. Mail only as text to Kevin himself, and only "
                     "when the task message gives a token.")
        for k, v in {"<<MACHINE>>": self.name, "<<PYTHON>>": cmd_path(helper_python()),
                     "<<REPORT>>": cmd_path(HERE / "report.py"),
                     "<<SEND_MAIL>>": cmd_path(HERE / "send_mail.py"), "<<SCOPE>>": scope}.items():
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
            tid = self.tool_uses.get(ev.get("tool_use_id", ""))
            if tid is None:
                tid = self._task_from_text(ev.get("description", ""), ev.get("prompt", ""))
            agent = ev.get("task_id")
            t = self._task(tid) if tid is not None else None
            if t and agent:
                t["agent"] = agent
                self.state["agents"][agent] = int(tid)
                self._write_agents()
                self.dirty = True
                log.info("task %s helper started", tid)
        elif typ == "system" and sub == "task_progress":
            tid = self.tool_uses.get(ev.get("tool_use_id", ""))
            t = self._task(tid) if tid is not None else self._task_for_agent(ev.get("task_id"))
            if t:
                t["turns"] = int((ev.get("usage") or {}).get("tool_uses") or t.get("turns", 0))
                desc = " ".join(str(ev.get("description") or "").split())
                if desc:
                    self.progress[t["id"]] = desc[:PROGRESS_CAP]  # memory only, never logged
                self.dirty = True
        elif typ == "system" and sub in ("task_updated", "task_notification"):
            status = (ev.get("patch") or {}).get("status") if sub == "task_updated" else ev.get("status")
            t = self._task_for_agent(ev.get("task_id"))
            if t and sub == "task_notification":
                t["turns"] = int((ev.get("usage") or {}).get("tool_uses") or t.get("turns", 0))
            if t and status in ("completed", "killed", "failed") and t["status"] == "running" \
                    and not t.get("helper_ended_at"):
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

    def _task_from_text(self, *texts: str) -> int | None:
        for text in texts:
            m = TASK_FLAG_RE.search(text or "") or TASK_RE.search(text or "")
            if m and self._task(m.group(1)):
                return int(m.group(1))
        return None

    def _task_for_agent(self, agent) -> dict | None:
        tid = self.state["agents"].get(agent or "")
        return self._task(tid) if tid is not None else None

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
            if not t or t["status"] != "running":
                log.info("report for task %s ignored (not running)", r.get("task"))
                continue
            self.finish(t, r.get("status", "failed"), r.get("summary", ""), r.get("question", ""),
                        r.get("found") or [])

    def finish(self, t: dict, status: str, summary: str, question: str = "", found=()) -> None:
        now = self.clock()
        found = [] if self.scope != "full" else [
            {"path": f["path"], "name": f["name"], "size": int(f["size"])} for f in found]
        t.update(status=status, summary=summary, question=question, found=found, finished_at=now)
        payload = {"status": status, "summary": summary, "question": question,
                   "turns": int(t.get("turns", 0)),
                   "duration_ms": int(max(0.0, now - t.get("started_at", now)) * 1000),
                   "found": found}
        self.state["outbox"].append({"task": t["id"], "payload": payload})
        self.dirty = True
        log.info("task %s finished %s found=%s", t["id"], status, len(found))

    def check_timers(self, now: float) -> None:
        for t in self.running():
            if now - t["started_at"] > self.cfg["task_cap_s"]:
                self._stop_helper(t, "stop", f"[stop {t['id']}] Task {t['id']} hit the 30 minute "
                                  f"limit. Stop task {t['id']}'s helper now with TaskStop. One line.")
                self.finish(t, "failed", SAY_CAP)
            elif t.get("helper_ended_at") and now - t["helper_ended_at"] > self.cfg["report_grace_s"]:
                self.finish(t, "failed", SAY_KILLED if t.get("helper_end") == "killed" else SAY_NO_REPORT)
        if self.awaiting_since is not None and now - self.awaiting_since > self.cfg["hang_s"]:
            log.warning("main session did not answer; restarting it")
            self.stop_session(kill=True)
            for t in self.running():
                self.finish(t, "failed", SAY_HUNG)
            self.start_session(now)

    def _stop_helper(self, t: dict, verb: str, text: str) -> None:
        unsent = [p for p in self.pending if p[0] == t["id"]]
        if unsent:  # never reached the main session: drop it, nothing to stop
            for p in unsent:
                self.pending.remove(p)
            return
        self.pending.appendleft((None, text))
        log.info("task %s %s sent", t["id"], verb)

    # ------------------------------------------------------------ engine contact

    def contact_engine(self, now: float) -> None:
        if now < self.next_contact:
            return
        try:
            self.flush_outbox()
            self.power.update()
            running = sorted(t["id"] for t in self.running())
            self.progress = {k: v for k, v in self.progress.items() if k in running}
            reply = self.engine.heartbeat({
                "name": self.name, "hostname": socket.gethostname(), "scope": self.scope,
                "state": "busy" if running else "idle", "running": running,
                "progress": {str(k): v for k, v in self.progress.items()},
                "claude_version": self.claude_version})
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
            self.upload_pages(item["task"], reply)

    def upload_pages(self, tid, reply) -> None:
        """Ticket 09: a `deliver: phone` task's page goes up right after its finish, without
        waiting a heartbeat. The engine requested it at finish (asking for it was Kevin's yes);
        [upload_requested] still checks it is a file this task listed. If the engine is down,
        the next heartbeat asks again."""
        t = self._task(tid)
        if not t or t.get("deliver") != DELIVER_PHONE or not isinstance(reply, dict):
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
                    log.warning("page for task %s not uploaded yet: engine unreachable", tid)
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
             "started_at": now, "turns": 0, "deliver": task.get("deliver"),
             "confirms": bool(parent) and task.get("confirms") is True}
        self.state["tasks"][str(tid)] = t
        self.dirty = True
        self.pending.append((tid, self.task_message(t, work)))
        log.info("task %s claimed%s", tid, f" (follow-up to {parent})" if parent else "")

    def task_message(self, t: dict, work: Path) -> str:
        tid, parent = t["id"], t.get("parent")
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
            lines.append(f"Kevin now says: {t['request']}")
            if t.get("confirms"):  # the phone marked this as Kevin's answer to task P's question
                tok = self.issue_token(tid, parent, "yes")
                lines.append(f"This is Kevin's answer to task {parent}'s question. If it is a clear "
                             f"yes, the step it asked "
                             f"about may now run, for task {tid} only. Confirmation token: {tok} "
                             f"(send_mail.py takes it as --task {tid} --token {tok}). Not a clear "
                             "yes: do not run it, report what Kevin said.")
        else:
            lines.append(f"[task {tid}] {t['request']}")
        if t.get("deliver") == DELIVER_EMAIL_SELF:
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
