"""Shared paths and small file helpers for the Claude session worker (map claude-session, ticket 04).

Everything the worker, report.py, send_mail.py and the ask-list hook share lives under one folder:
`%APPDATA%\\legion-claude\\` on Kevin's laptops, or `$LEGION_CLAUDE_HOME` (tests, and the worker
sets it explicitly in the child env so helpers and the hook find the same folder).

Stdlib only, Python 3.11+.
"""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

HOME_ENV = "LEGION_CLAUDE_HOME"

STATUSES = ("done", "failed", "needs_confirmation")
DELIVER_EMAIL_SELF = "email_self"  # ruling 17
UPLOAD_CAP_BYTES = 25 * 1024 * 1024  # uploads to the engine, per file


def home() -> Path:
    env = os.environ.get(HOME_ENV)
    if env:
        return Path(env)
    appdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    return Path(appdata) / "legion-claude"


class Paths:
    """Every file the worker family touches, from one root."""

    def __init__(self, root: Path | None = None):
        self.root = Path(root) if root else home()
        self.config = self.root / "config.json"
        self.state = self.root / "state.json"
        self.reports = self.root / "reports"
        self.tokens = self.root / "tokens"
        self.work = self.root / "work"
        self.logs = self.root / "logs"
        self.agents = self.root / "agents.json"
        self.settings = self.root / "worker-settings.json"
        self.pid = self.root / "session.pid"
        self.lock = self.root / "worker.lock"

    def ensure(self) -> "Paths":
        for d in (self.root, self.reports, self.tokens, self.work, self.logs):
            d.mkdir(parents=True, exist_ok=True)
        return self

    def task_work(self, task_id: int) -> Path:
        return self.work / f"task-{int(task_id)}"


def read_json(path: Path, default=None):
    try:
        with open(path, encoding="utf-8-sig") as f:  # tolerate a BOM from PowerShell
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_json_atomic(path: Path, obj) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=1)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def norm(p) -> str:
    """Case- and separator-normalised absolute path, symlinks resolved where possible."""
    try:
        r = os.path.realpath(os.path.abspath(os.path.expanduser(str(p))))
    except (OSError, ValueError):
        r = os.path.abspath(str(p))
    return os.path.normcase(os.path.normpath(r))


def is_under(path, root) -> bool:
    p, r = norm(path), norm(root)
    if p == r:
        return True
    return p.startswith(r.rstrip("\\/") + os.sep)


def load_config(paths: Paths) -> dict:
    cfg = read_json(paths.config, {}) or {}
    cfg.setdefault("scope", "listed")
    return cfg
