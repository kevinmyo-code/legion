"""Interactive Claude Code sessions reporter (map claude-session, ticket 06).

Run by SessionStart, UserPromptSubmit, Notification, Stop and SessionEnd hooks in Kevin's own
`~/.claude/settings.json`. Reads the hook JSON from stdin and POSTs
`{machine, session_id, project, state, last_prompt_summary, at}` to `/api/claude/sessions/`.

- Fire and forget: 2 s timeout, always exits 0, prints nothing (hook stdout can reach the model).
- No config (`%APPDATA%\\legion-claude\\config.json`) means do nothing.
- The prompt summary (first 120 chars) is sent only from a `full` scope machine (ruling 5).
- The worker's own main session is skipped (it sets LEGION_CLAUDE_WORKER, see session.py).

Also `--install-settings PATH` / `--uninstall-settings PATH`, used by install.ps1 / uninstall.ps1
to merge these hooks into a settings file without touching anyone else's hooks.

Stdlib only.
"""
from __future__ import annotations

import datetime
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import Paths, load_config, read_json, write_json_atomic  # noqa: E402
from engine_client import EngineClient  # noqa: E402

WORKER_MARKER = "LEGION_CLAUDE_WORKER"
TIMEOUT_S = 2.0
SUMMARY_CHARS = 120
STATES = {
    "SessionStart": "working",
    "UserPromptSubmit": "working",
    "Notification": "waiting",
    "Stop": "idle",
    "SessionEnd": "ended",
}
OURS = "session_hook.py"


def build_payload(event: dict, cfg: dict) -> dict | None:
    state = STATES.get(event.get("hook_event_name") or "")
    sid = event.get("session_id")
    if not state or not sid or not cfg.get("name"):
        return None
    summary = None
    prompt = event.get("prompt")
    if cfg.get("scope") == "full" and isinstance(prompt, str) and prompt.strip():
        summary = " ".join(prompt.split())[:SUMMARY_CHARS]
    cwd = event.get("cwd") or ""
    return {
        "machine": cfg["name"],
        "session_id": str(sid),
        "project": os.path.basename(os.path.normpath(cwd)) if cwd else "",
        "state": state,
        "last_prompt_summary": summary,
        "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def report(stdin_text: str, env=None, paths: Paths | None = None) -> dict | None:
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
        payload = build_payload(json.loads(stdin_text or "{}"), cfg)
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
        report(sys.stdin.read())
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
