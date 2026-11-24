"""Interactive Claude Code sessions reporter (map claude-session, ticket 06).

Run by SessionStart, UserPromptSubmit, Notification, Stop and SessionEnd hooks in Kevin's own
`~/.claude/settings.json`. Reads the hook JSON from stdin and POSTs
`{machine, session_id, project, state, last_prompt_summary, at}` to `/api/claude/sessions/`.

- Fire and forget: 2 s timeout, always exits 0, prints nothing (hook stdout can reach the model).
- No config (`%APPDATA%\\legion-claude\\config.json`) means do nothing.
- The prompt summary (first 120 chars) is sent only from a `full` scope machine (ruling 5).
- The worker's own main session is skipped (it sets LEGION_CLAUDE_WORKER, see session.py).
- The session's own name (`/rename`, `--name`) rides along from a `full` machine, read from
  Claude Code's `~/.claude/sessions/<pid>.json` (an undocumented file: read for the name only,
  never its pipe or key). Interactive sessions only have one; none found sends a blank name and
  the engine keeps the name it has.

Also `--install-settings PATH` / `--uninstall-settings PATH`, used by install.ps1 / uninstall.ps1
to merge these hooks into a settings file without touching anyone else's hooks.

Stdlib only.
"""
from __future__ import annotations

# ruff: noqa: E402
# Review round 4d: these lines run before any other import. Python puts this script's own folder
# first on the path, so a file planted here (`json.py`, a `json/` package, `json.pyc`) would
# shadow the standard library: the folder is taken off the front and added back at the END, so
# only this folder's own modules come from it. Bytecode is never read from or written to a
# `__pycache__` here (`sys.pycache_prefix`, a folder under the worker's own root that no helper
# may write), so a forged cached .pyc beside the source is never loaded.
import sys

sys.path[:] = [_p for _p in sys.path if _p.replace("\\", "/").rstrip("/").lower()
               != __file__.replace("\\", "/").rsplit("/", 1)[0].lower()]
import os

sys.pycache_prefix = os.path.join(os.environ.get("LEGION_CLAUDE_HOME") or os.path.join(
    os.environ.get("APPDATA") or os.path.expanduser("~/AppData/Roaming"), "legion-claude"),
    "pycache")

import datetime
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.append(os.path.dirname(os.path.abspath(__file__)))  # this folder's own modules, after the standard library

from common import Paths, load_config, read_json, stdin_text, write_json_atomic  # noqa: E402
from engine_client import EngineClient  # noqa: E402

WORKER_MARKER = "LEGION_CLAUDE_WORKER"
TIMEOUT_S = 2.0
SUMMARY_CHARS = 120
NAME_CHARS = 80
STATES = {
    "SessionStart": "working",
    "UserPromptSubmit": "working",
    "Notification": "waiting",
    "Stop": "idle",
    "SessionEnd": "ended",
}
OURS = "session_hook.py"


def session_name(session_id: str, sessions_dir: Path | None = None) -> str:
    """The name Claude Code shows for `session_id`, or "" (no file, unreadable, unnamed)."""
    folder = sessions_dir or Path.home() / ".claude" / "sessions"
    try:
        for f in folder.glob("*.json"):
            data = read_json(f, None)
            if isinstance(data, dict) and data.get("sessionId") == session_id:
                name = data.get("name")
                return " ".join(str(name).split())[:NAME_CHARS] if name else ""
    except OSError:
        pass
    return ""


# Orchestrator ruling 2026-11-22 (review finding 8): "waiting" means a permission prompt or a
# question to Kevin, never the idle prompt Claude Code also raises a minute after each turn.
WAITING_TYPES = {"permission_prompt", "elicitation_dialog"}
NOT_WAITING_TYPES = {"idle_prompt": "idle", "auth_success": None}


def notification_state(event: dict) -> str | None:
    """`waiting`, `idle`, or None (report nothing) for a Notification hook event. By its
    `notification_type`; an event without one is judged by its message ("needs your permission"
    or a question is waiting; "waiting for your input" is idle)."""
    kind = str(event.get("notification_type") or "")
    if kind in WAITING_TYPES:
        return "waiting"
    if kind in NOT_WAITING_TYPES:
        return NOT_WAITING_TYPES[kind]
    message = str(event.get("message") or "").lower()
    if "permission" in message or "approval" in message or "question" in message:
        return "waiting"
    return "idle"


def build_payload(event: dict, cfg: dict, sessions_dir: Path | None = None) -> dict | None:
    state = STATES.get(event.get("hook_event_name") or "")
    if event.get("hook_event_name") == "Notification":
        state = notification_state(event)
    sid = event.get("session_id")
    if not state or not sid or not cfg.get("name"):
        return None
    summary = None
    prompt = event.get("prompt")
    if cfg.get("scope") == "full" and isinstance(prompt, str) and prompt.strip():
        summary = " ".join(prompt.split())[:SUMMARY_CHARS]
    cwd = event.get("cwd") or ""
    name = session_name(str(sid), sessions_dir) if cfg.get("scope") == "full" else ""
    return {
        "machine": cfg["name"],
        "session_id": str(sid),
        "project": os.path.basename(os.path.normpath(cwd)) if cwd else "",
        "state": state,
        "last_prompt_summary": summary,
        "name": name,
        "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def report(text: str, env=None, paths: Paths | None = None,
           sessions_dir: Path | None = None) -> dict | None:
    """Returns the payload it sent (or tried to), None when it did nothing. Never raises."""
    try:
        env = os.environ if env is None else env
        if env.get(WORKER_MARKER):
            return None
        paths = paths or Paths()
        if not paths.config.exists():
            return None
        cfg = load_config(paths)
        if not cfg.get("engine_url") or not cfg.get("token"):
            return None
        payload = build_payload(json.loads(text or "{}"), cfg, sessions_dir)
        if payload is None:
            return None
        try:
            EngineClient(cfg["engine_url"], cfg["token"], timeout=TIMEOUT_S)._json(
                "POST", "sessions/", payload)
        except Exception:
            pass
        return payload
    except Exception:
        return None


# --- settings merge (install.ps1 / uninstall.ps1) -----------------------------------------------


def hook_command(python: str | None = None) -> str:
    py = python or sys.executable
    return f'"{py}" "{os.path.abspath(__file__)}"'


def _is_ours(h) -> bool:
    return isinstance(h, dict) and OURS in str(h.get("command", ""))


def install_settings(path: Path, command: str) -> bool:
    """Merge our hooks in. Returns True if the file changed. Backs up the old file first."""
    path = Path(path)
    data = read_json(path, None) if path.exists() else {}
    if not isinstance(data, dict):
        raise ValueError(f"{path} is not a JSON object; not touching it")
    hooks = data.setdefault("hooks", {})
    changed = False
    for event in STATES:
        groups = hooks.setdefault(event, [])
        if any(_is_ours(h) for g in groups if isinstance(g, dict) for h in g.get("hooks", [])):
            continue
        groups.append({"hooks": [{"type": "command", "command": command, "timeout": 5}]})
        changed = True
    if changed:
        _backup(path)
        write_json_atomic(path, data)
    return changed


def uninstall_settings(path: Path) -> bool:
    path = Path(path)
    data = read_json(path, None)
    if not isinstance(data, dict) or not isinstance(data.get("hooks"), dict):
        return False
    hooks, changed = data["hooks"], False
    for event in list(hooks):
        groups = hooks[event]
        if not isinstance(groups, list):
            continue
        kept = []
        for g in groups:
            if isinstance(g, dict) and isinstance(g.get("hooks"), list):
                inner = [h for h in g["hooks"] if not _is_ours(h)]
                if len(inner) != len(g["hooks"]):
                    changed = True
                    if not inner:
                        continue
                    g = {**g, "hooks": inner}
            kept.append(g)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]
    if not hooks:
        del data["hooks"]
    if changed:
        _backup(path)
        write_json_atomic(path, data)
    return changed


def _backup(path: Path) -> None:
    if path.exists():
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        shutil.copy2(path, path.with_name(path.name + f".bak-{stamp}"))


def main(argv: list[str]) -> int:
    if len(argv) >= 2 and argv[0] == "--install-settings":
        cmd = argv[2] if len(argv) >= 3 else hook_command()
        print("added" if install_settings(Path(argv[1]), cmd) else "already there")
        return 0
    if len(argv) >= 2 and argv[0] == "--uninstall-settings":
        print("removed" if uninstall_settings(Path(argv[1])) else "none found")
        return 0
    try:
        report(stdin_text())
    except BaseException:
        pass
    return 0


if __name__ == "__main__":
    if sys.argv[1:]:
        sys.exit(main(sys.argv[1:]))
    try:
        main([])
    except BaseException:
        pass
    sys.exit(0)
