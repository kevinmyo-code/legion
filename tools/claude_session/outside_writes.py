"""Files a helper may create or change outside its own task folder (review 11-19, round 4, R4-1).

A STOPGAP. The real fix is running the Claude session as a separate low-privilege Windows user,
so the operating system stops writes the hook cannot see. Until then:

- File tools (Write, Edit, MultiEdit, NotebookEdit): inside the task's own folder, anything. A NEW
  file anywhere else asks (needs Kevin's yes), except the workflow's own report file (the worker
  names it) and the warm worktree while this task holds its lease. An EXISTING file elsewhere keeps
  the hook's older rules (another task's scratch passes, Kevin's files ask). Protected places ask
  even when the file exists, and HARD places (what the hook itself runs on: the worker python's
  install tree and user site, the hook's folder, the gh install) are refused, yes or not.
- Shell (Bash, PowerShell): a command with a write verb (a redirect, cp, mv, ln, mklink, tee,
  New-Item, Set-Content, Out-File, curl -o, Invoke-WebRequest -OutFile, python open(...), ...) whose
  target is written out literally under a protected or hard place: refused or asked the same way.
  `git config --global` writes asks.

What this does NOT stop: a script. `python <work>/x.py` that builds a path at run time and writes it
is invisible here. A helper that has not read outside content can still do that today.

Stdlib only.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

from common import Paths, is_under, norm, read_json

HOOK_DIR = Path(__file__).resolve().parent
HARD, ASK, WORKER = "hard", "ask", "worker"


def _home() -> Path:
    return Path(os.environ.get("USERPROFILE") or os.environ.get("HOME") or Path.home())


def _appdata() -> Path:
    return Path(os.environ.get("APPDATA") or _home() / "AppData" / "Roaming")


def _local() -> Path:
    return Path(os.environ.get("LOCALAPPDATA") or _home() / "AppData" / "Local")


def hard_roots() -> list[Path]:
    """What the hook runs on: refused even with Kevin's yes (a `.pth` or sitecustomize here runs
    inside the hook and could switch every rule off)."""
    py = Path(sys.executable).resolve().parent
    return [py, Path(sys.prefix), Path(sys.base_prefix), HOOK_DIR, _appdata() / "Python",
            _local() / "Programs" / "Python", _local() / "Microsoft" / "WinGet"]


def ask_roots() -> list[Path]:
    """Places that change how the laptop, the shell, git, gh or Claude behave: always asked."""
    h, a, loc = _home(), _appdata(), _local()
    windows = Path(os.environ.get("SystemRoot") or "C:/Windows")
    program_data = Path(os.environ.get("ProgramData") or "C:/ProgramData")
    roots = [a / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup",
             program_data / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "StartUp",
             windows / "System32" / "Tasks", windows / "Tasks",
             h / ".ssh", h / ".gitconfig", h / ".config" / "git", h / ".config" / "gh",
             a / "GitHub CLI", loc / "GitHub CLI", h / ".claude", h / ".legion" / "scripts",
             h / ".bashrc", h / ".bash_profile", h / ".bash_login", h / ".profile", h / ".inputrc"]
    for docs in (h / "Documents", Path(os.environ["OneDrive"]) / "Documents" if os.environ.get("OneDrive") else None):
        if docs is not None:
            roots += [docs / "WindowsPowerShell", docs / "PowerShell"]
    return roots


def _under_any(target: str, roots: list[Path]) -> bool:
    return any(is_under(target, r) for r in roots)


def _worker_internal(target: str, paths: Paths) -> bool:
    return is_under(target, paths.root) and not is_under(target, paths.work)


def place(target: str, paths: Paths) -> str | None:
    """WORKER (the worker's own files), HARD, ASK or None for a path, existing or not."""
    if _worker_internal(target, paths):
        return WORKER
    if _under_any(target, hard_roots()):
        return HARD
    if _under_any(target, ask_roots()):
        return ASK
    return None


_LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "[::1]"}


def canonical(target: str) -> str | None:
    """A file-tool path in plain drive form, or None when it names a share elsewhere (refused).

    R5-1 (review round 5): realpath keeps a `\\\\?\\` or `\\\\.\\` prefix and never maps
    `\\\\localhost\\C$\\...` to `C:\\...`, so those spellings slipped past the folder checks. The
    long-path and device prefixes are stripped; an admin share on this machine (localhost,
    127.0.0.1, its own name) becomes its drive; any other `\\\\` or `//` path is not this laptop's
    plain file system and is refused."""
    t = (target or "").replace("/", "\\")
    for prefix in ("\\\\?\\UNC\\", "\\\\.\\UNC\\"):
        if t.upper().startswith(prefix):
            t = "\\\\" + t[len(prefix):]
            break
    else:
        for prefix in ("\\\\?\\", "\\\\.\\"):
            if t.startswith(prefix):
                t = t[len(prefix):]
                break
    if t.startswith("\\\\"):
        parts = t[2:].split("\\")
        hosts = _LOCAL_HOSTS | {h.lower() for h in (os.environ.get("COMPUTERNAME"), os.environ.get("HOSTNAME")) if h}
        m = re.match(r"^([A-Za-z])\$$", parts[1]) if len(parts) > 1 else None
        if parts[0].lower() not in hosts or not m:
            return None
        t = f"{m.group(1)}:\\" + "\\".join(parts[2:])
    if t.startswith("\\"):  # `\\?\` leftovers or a rootless path: not a plain drive path
        return None
    return t


def file_verdict(target: str, paths: Paths, task: int | None, trec: dict | None) -> str | None:
    """For a file tool: WORKER, HARD, ASK, or None (the hook's older rules decide)."""
    target = canonical(target)
    if target is None:
        return HARD
    # A helper not yet in the agents map has no task: the whole work folder is its own then (the
    # older rule), and protected places still apply. A follow-up may also use its parent's folder.
    own = [paths.task_work(task)] if task is not None else [paths.work]
    parent = (trec or {}).get("parent")
    if task is not None and parent:
        own.append(paths.task_work(int(parent)))
    if any(is_under(target, f) for f in own):
        return None
    kind = place(target, paths)
    if kind:
        return kind
    if Path(norm(target)).exists():
        return None
    report = (trec or {}).get("report_file")
    if report and norm(report) == norm(target):
        return None
    if task is not None and _holds_warm(paths, task) and is_under(target, paths.work / "warm" / "wt"):
        return None
    return ASK


def _holds_warm(paths: Paths, task: int) -> bool:
    import warm_worktree  # its lease rules, not a copy of them
    return str(warm_worktree.holder(paths)) == str(task)


# --------------------------------------------------------------------------- shell commands

WRITE_VERB_RE = re.compile(
    r"(?<![\w-])(?:cp|mv|ln|mklink|tee|touch|install|xcopy|robocopy|copy|move|ren|rename|ni|md|mkdir)"
    r"(?:\.exe)?(?=\s)"
    r"|New-Item|Set-Content|Add-Content|Out-File|Copy-Item|Move-Item|Rename-Item|Tee-Object"
    r"|New-ItemProperty|Export-\w+|Expand-Archive|Start-BitsTransfer|-OutFile\b|--output\b|\s-o\s"
    r"|\bopen\(|write_text|write_bytes|shutil\.|os\.(?:rename|replace|symlink|link)|\bsed\s[^|;&]*-i",
    re.I)
REDIRECT_TARGET_RE = re.compile(r"(?<![0-9&])(?:[0-9])?>{1,2}\s*(\"[^\"]+\"|'[^']+'|[^\s|;&<>]+)")
TOKEN_RE = re.compile(r"\"([^\"]+)\"|'([^']+)'|([^\s\"'|;&<>(),]+)")
EMBEDDED_RE = re.compile(
    r"(?:[A-Za-z]:[\\/]|~[\\/]|\$(?:env:)?\w+[\\/]|\$\{\w+\}[\\/]|%\w+%[\\/])[^\"'|;&<>()]*")
GIT_GLOBAL_WRITE_RE = re.compile(r"\bgit\b[^|;&]*\s--global\b(?![^|;&]*\s(?:--get\b|--get-all\b|--list\b|-l\b|--get-regexp\b))",
                                 re.I)


def _expand(tok: str) -> str:
    h = str(_home())
    names = {"home": h, "userprofile": h, "appdata": str(_appdata()), "localappdata": str(_local()),
             "programdata": os.environ.get("ProgramData") or "C:/ProgramData",
             "systemroot": os.environ.get("SystemRoot") or "C:/Windows"}
    t = tok.strip().strip("'\"")
    if t.startswith("~"):
        t = h + t[1:]
    m = re.match(r"^(?:\$env:(\w+)|\$\{(\w+)\}|\$(\w+)|%(\w+)%)(.*)$", t, re.I)
    if m:
        name = next(g for g in m.groups()[:4] if g).lower()
        if name in names:
            t = names[name] + m.group(5)
    g = re.match(r"^/([a-zA-Z])/(.*)$", t)  # Git Bash /c/Users/...
    if g and os.name == "nt":
        t = f"{g.group(1)}:/{g.group(2)}"
    return t


def _candidates(text: str) -> list[str]:
    out = []
    for m in TOKEN_RE.finditer(text):
        tok = next(g for g in m.groups() if g is not None)
        out.append(tok)
        out += [e.rstrip(".,") for e in EMBEDDED_RE.findall(tok)]
    return out


def _is_python(path: str) -> bool:
    try:
        return norm(path) in (norm(sys.executable), norm(Path(sys.executable).with_name("python.exe")))
    except (OSError, ValueError):
        return False


def shell_verdict(command: str, cwd: str, paths: Paths) -> tuple[str, str] | None:
    """(HARD or ASK, the place in words) for a command that writes into a protected place it names
    literally; None otherwise."""
    command = command or ""
    worst = None
    targets: list[str] = []
    if WRITE_VERB_RE.search(command):
        targets += _candidates(command)
    targets += [t for t in REDIRECT_TARGET_RE.findall(command)]
    for raw in targets:
        t = _expand(raw)
        if not t or t.startswith("-") or _is_python(t):
            continue
        if not os.path.isabs(t):
            if not cwd or not re.match(r"^[\w.~-]", t) or "/" not in t and "\\" not in t and not t.startswith("."):
                continue
            t = os.path.join(cwd, t)
        try:
            kind = place(t, paths)
        except (OSError, ValueError):
            kind = ASK
        if kind in (HARD, WORKER):
            return kind, Path(t).name or t
        if kind == ASK:
            worst = (ASK, Path(t).name or t)
    if worst is None and GIT_GLOBAL_WRITE_RE.search(command):
        worst = (ASK, "git's global config")
    return worst
