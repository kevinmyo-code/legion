"""The one long-lived main Claude session on this laptop (ticket 02 shape, ticket 04 build).

`claude -p --input-format stream-json --output-format stream-json` with the worker's settings,
fed one JSON line per message on stdin. A reader thread turns stdout into parsed events on a
queue; the worker's main loop consumes them. Nothing here logs event content or stderr text:
both carry requests, summaries and paths.
"""
from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import threading
from pathlib import Path

ALLOWED_FULL = ["Bash", "PowerShell", "Read", "Write", "Edit", "Glob", "Grep", "Agent", "TaskStop",
                "WebFetch", "WebSearch"]
# The work laptop: the hook confines these to the repos root (reads) and the work folder (writes).
ALLOWED_LISTED = ["Bash", "PowerShell", "Read", "Write", "Edit", "Glob", "Grep", "Agent", "TaskStop"]

# Only project and local: no user settings, so Kevin's own hooks (ticket 06 reporter) and plugins
# do not run for the worker's session. cwd is the worker's work folder, which has neither.
SETTING_SOURCES = "project,local"

CREATE_NO_WINDOW = 0x08000000


def child_env(base: dict, home: Path) -> dict:
    """Kevin's env minus anything that ties the child to a parent Claude Code session."""
    env = {k: v for k, v in base.items()
           if k != "CLAUDECODE" and not k.startswith("CLAUDE_CODE_") and k != "ANTHROPIC_API_KEY"}
    env["LEGION_CLAUDE_HOME"] = str(home)
    return env


def resolve_claude(cmd: list[str]) -> list[str]:
    if len(cmd) == 1 and not os.path.isabs(cmd[0]):
        found = shutil.which(cmd[0])
        if found:
            return [found]
    return list(cmd)


def build_command(claude_cmd: list[str], *, settings: Path, prompt: str, session_id: str,
                  resume: bool, scope: str, add_dirs: list[str]) -> list[str]:
    settings = Path(settings)
    if not settings.is_absolute():
        raise ValueError("--settings must be an absolute path (relative resolves against cwd)")
    cmd = resolve_claude(claude_cmd) + [
        "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        "--permission-mode", "dontAsk", "--permission-prompts", "none",
        "--settings", str(settings),
        "--setting-sources", SETTING_SOURCES,
        "--append-system-prompt", prompt,
    ]
    for d in add_dirs:
        cmd += ["--add-dir", d]
    cmd += ["--allowedTools", *(ALLOWED_FULL if scope == "full" else ALLOWED_LISTED)]
    cmd += ["--resume", session_id] if resume else ["--session-id", session_id]
    return cmd


class MainSession:
    def __init__(self, cmd: list[str], cwd: Path, env: dict):
        self.cmd, self.cwd, self.env = cmd, Path(cwd), env
        self.events: "queue.Queue[dict]" = queue.Queue()
        self.proc: subprocess.Popen | None = None
        self.stderr_lines = 0
        self.bad_lines = 0

    def start(self) -> int:
        flags = CREATE_NO_WINDOW if sys.platform == "win32" else 0
        self.proc = subprocess.Popen(
            self.cmd, cwd=self.cwd, env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", bufsize=1,
            creationflags=flags)
        threading.Thread(target=self._read_out, daemon=True).start()
        threading.Thread(target=self._read_err, daemon=True).start()
        return self.proc.pid

    def _read_out(self):
        assert self.proc and self.proc.stdout
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except ValueError:
                self.bad_lines += 1
                continue
            if isinstance(ev, dict):
                self.events.put(ev)

    def _read_err(self):
        assert self.proc and self.proc.stderr
        for _ in self.proc.stderr:
            self.stderr_lines += 1

    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def send(self, text: str) -> None:
        if not self.alive():
            raise BrokenPipeError("main session is not running")
        msg = {"type": "user", "message": {"role": "user", "content": text}}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()

    def close(self, timeout: float = 30.0) -> int | None:
        if not self.proc:
            return None
        try:
            if self.proc.stdin and not self.proc.stdin.closed:
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return self.kill()

    def kill(self) -> int | None:
        if not self.proc:
            return None
        if self.proc.poll() is None:
            kill_tree(self.proc.pid)
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        return self.proc.returncode


def kill_tree(pid: int) -> None:
    """Stop a process and its helpers (Windows: taskkill /T)."""
    try:
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True,
                           timeout=20, creationflags=CREATE_NO_WINDOW)
        else:
            os.kill(pid, 9)
    except (OSError, subprocess.SubprocessError):
        pass
