"""PreToolUse hook for the worker's own Claude session: the ask list (ruling 6) and scope (ruling 5).

Installed only into the worker's settings file (`worker-settings.json`, passed with `--settings`),
never into Kevin's interactive `~/.claude/settings.json`. Ticket 02 measured that it fires inside
background helpers too, with the helper's `agent_id` in the hook input.

Decisions:
- Ask list (deleting or overwriting Kevin's files, sending, booking or paying, installing software,
  changing system settings): deny with a reason telling the helper to stop and report
  `needs_confirmation` with `--question`. Anything inside the worker's own work folder is not
  Kevin's file, so deletes and overwrites there run.
- A task Kevin said yes to (a `kind: yes` confirmation token the worker issued,
  `tokens/<task>.json`) lets that task's helper through the ask list until the token expires.
  A `kind: email_self` token (ruling 17) unlocks nothing here; it only lets `send_mail.py` send to
  Kevin. `send_mail.py` checks its token itself, so the hook lets it run.
- Work laptop (`scope: listed`): file tools only inside the repos root (reads) or the work folder
  (writes); shell only for read-only git, simple listing/reading commands, and report.py. Those are
  hard denials, not questions.
- **The ship step** (map alfred-builds, ticket 03): `git push`, the engine deploy scripts,
  `release_apk.py` and `gcloud` are denied to every helper, a confirmed one included. They run
  only inside `ship.py` / `undo.py` (this folder's own copies, invoked alone), which check Kevin's
  yes token themselves, the way `send_mail.py` does.
- **Kevin's worker skills** (`work/.claude`) are his files, not scratch: writing one, new or
  existing, asks like any of his files. **The worker's own files** (tokens, config, the agents map,
  the ship history `work/ships.jsonl`) are never a helper's to write: a hard denial, token or not.
- **The daily memory review** (memory-review ticket 16): a `memory_review` task's helper may not
  run git at all, and may not write outside its own task folder, token or not, so Kevin's memory
  text can only land in that folder's two work files.
- **Workflow reports** (worker-workflows ticket 05) land in Kevin's own folder,
  `%USERPROFILE%/.legion/reports/<workflow>/`, outside the work folder, so they are his files
  under the rules above with no rule of their own: a new dated report passes, rewriting or
  deleting an older one asks. A scheduled run has no token, so it can only add.
- **Containment** (architecture upgrade 2, Kevin's Q1 ruling, 2026-11-10; `containment.py` holds
  the rules and their honest limits): a task that read mail, the web or an untrusted file is
  tainted for the rest of it, a resumed session and its follow-ups included: no shell but this
  folder's own report, mail, ship, undo, fetch and xlsx scripts, no new helper, writes only as
  data files in its own task folder (never inside a checkout) or its workflow's report file.
  Shell commands reach the network only to allowlisted hosts, and no shell ever names a file a
  tainted task wrote. Runs before every rule below.
- Anything unexpected inside this hook denies (fails closed).

The hook log records tool, decision, rule and agent id. Never the command, a path, or any text.
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

import json
import os
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _worker_root() -> Path:
    """`common.home()`, without importing common: nothing from this folder is trusted yet."""
    env = os.environ.get("LEGION_CLAUDE_HOME")
    if env:
        return Path(env)
    return Path(os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")) / "legion-claude"


def _deployed(root: Path) -> tuple[bool, str]:
    """Review round 4d: is this folder the deployed code? Standard library only. integrity.py is
    run from the very bytes whose hash matched the manifest, then it checks every other file. On
    any doubt, not deployed."""
    import hashlib
    import types
    try:
        with open(root / "code-manifest.json", encoding="utf-8") as f:
            want = json.load(f)["files"]["integrity.py"]
        data = (HERE / "integrity.py").read_bytes()
        if hashlib.sha256(data).hexdigest() != want:
            return False, "the integrity check itself was changed"
        mod = types.ModuleType("legion_integrity")
        mod.__file__ = str(HERE / "integrity.py")
        exec(compile(data, mod.__file__, "exec"), mod.__dict__)
        return mod.check(types.SimpleNamespace(root=root), HERE)
    except Exception:  # noqa: BLE001 - fail closed
        return False, "no deployed code manifest, or it could not be read"


_CHANGED = ("WORKER CODE CHANGED: the laptop worker's own scripts are not the deployed ones ({}), "
            "so they are not trusted until Kevin checks the laptop. Do not retry or work around "
            "it. Say in your final message that the laptop worker needs attention.")

if __name__ == "__main__":
    # Before anything from this folder is imported: a changed folder could otherwise run its own
    # code inside this hook and answer for it. Every tool call is refused until it is fixed.
    _ok, _why = _deployed(_worker_root())
    if not _ok:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse", "permissionDecision": "deny",
            "permissionDecisionReason": _CHANGED.format(_why)}}))
        sys.exit(0)

sys.path.append(str(HERE))  # this folder's own modules, after the standard library
from common import Paths, is_under, load_config, norm, read_json  # noqa: E402
import containment as C  # noqa: E402

SHELL_TOOLS = ("Bash", "PowerShell")
WRITE_TOOLS = ("Write", "Edit", "MultiEdit", "NotebookEdit")
READ_TOOLS = ("Read", "Glob", "Grep")

# The overwrite rule in two halves: verbs that write a file named somewhere in the command, and a
# shell redirect. A redirect writes only its own target, so a match on the redirect alone is
# judged on the redirect targets alone (2026-11-09, task 7: `python <repo>/build_lock.py ... >
# compile.log` in the helper's own work folder was denied because the script's path is not).
OVERWRITE_LABEL = "overwrite or move a file"
OVERWRITE_VERBS = (
    r"Move-Item|\bmv\b|\bmove\b|\bren(ame)?\b|Rename-Item|Copy-Item\b.*-Force|\bcp\b\s+-\w*f"
    r"|\bcopy\b.*/y|Set-Content|Out-File|Add-Content|shutil\.move|os\.replace|os\.rename"
    r"|\.write_(text|bytes)\(|open\([^)]*['\"][wa]b?\+?['\"]")
REDIRECT_OUT = r"(?<![0-9&=-])>{1,2}\s*(?!&|\$null\b|nul\b|/dev/null)\S"

# Each rule: (spoken label, kind, regex). kind "path" rules only ask when a target is outside the
# work folder; kind "always" rules ask wherever they run.
RULES = [
    ("delete a file", "path",
     r"\brm\b|\brmdir\b|\brd\b|\bdel\b|\berase\b|Remove-Item|\bri\s|os\.remove|os\.unlink|os\.rmdir"
     r"|shutil\.rmtree|\.unlink\(|\.rmdir\(|Clear-Content|Clear-RecycleBin"),
    (OVERWRITE_LABEL, "path", OVERWRITE_VERBS + "|" + REDIRECT_OUT),
    ("install software", "always",
     r"\bpip3?\s+install|-m\s+pip\s+install|\buv\s+(pip\s+)?(install|add)\b|\bwinget\b|\bchoco\b"
     r"|\bscoop\s+install|\bnpm\s+(i|install|add)\b.*(-g|--global)|\bmsiexec\b|Install-Module"
     r"|Install-Package|Add-AppxPackage|\bsetup\.exe\b|\.msi\b|-Verb\s+RunAs|\bsudo\b"),
    ("change a system setting", "always",
     r"\breg(\.exe)?\s+(add|delete|import|copy|restore)\b|(Set|New|Remove)-ItemProperty|"
     r"\bpowercfg\s+[-/](change|x|setactive|s\b|setacvalueindex|setdcvalueindex|h\b|hibernate|delete)"
     r"|\bbcdedit\b|\bnetsh\b.*\b(set|add|delete|reset)\b|(Set|Stop|Start|Restart|New|Remove)-Service"
     r"|\bsc(\.exe)?\s+(config|stop|start|delete|create)\b|\bschtasks\b.*/(create|delete|change)"
     r"|(Register|Unregister|Set|Disable|Enable)-ScheduledTask|Set-ExecutionPolicy|(Set|Add)-MpPreference"
     r"|\bshutdown\b|Restart-Computer|Stop-Computer|\bsetx\b|SetEnvironmentVariable"
     r"|\b(Enable|Disable)-[A-Z]\w+|Set-(Date|TimeZone|NetFirewall\w*)|New-NetFirewallRule|\bwmic\b"
     r"|\btaskkill\b|Stop-Process"),
    ("send something", "always",
     r"smtplib|Send-MailMessage|System\.Net\.Mail|Outlook\.Application|\bgit\s+push\b"
     r"|\bgh\s+(pr|issue|release|gist)\s+(create|comment|merge|edit)"
     r"|\bcurl\b.*(-X\s*(POST|PUT|PATCH|DELETE)|\s-d\s|--data|-F\s|--form)"
     r"|Invoke-(WebRequest|RestMethod)\b.*-Method\s+(Post|Put|Patch|Delete)"
     r"|requests\.(post|put|patch|delete)|urlopen\(.*data="),
]

SEND_MAIL_RE = re.compile(r"send_mail\.py\b", re.I)

# Ticket 03: what only ship.py and undo.py may do. Denied to helpers whatever their token says.
# `git ... push` is matched outside quotes and inside each quoted string on its own, so a commit
# message saying "push the fix" passes while `bash -c "git push"` and `git "push"` do not. The
# tools are matched raw: their path may be quoted.
_GIT_OPTS = r"\bgit(?:\.exe)?(?:\s+(?:-[Cc]\s+\S+|--?[\w.-]+(?:=\S+)?))*\s+"
SHIP_GIT_PUSH = re.compile(_GIT_OPTS + r"push\b", re.I)
SHIP_GIT_QPUSH = re.compile(_GIT_OPTS + r"[\"']push[\"']", re.I)
SHIP_GIT_QEXE = re.compile(r"git(?:\.exe)?[\"'](?:\s+(?:-[Cc]\s+\S+|--?[\w.-]+(?:=\S+)?))*\s+push\b",
                           re.I)  # "C:/Program Files/Git/bin/git.exe" push
QUOTED_RE = re.compile(r'"((?:\\.|[^"\\])*)"' + r"|'([^']*)'")
SHIP_TOOLS = re.compile(
    r"\b(?:deploy|deploy_job|install_schedule)\.py\b|\brelease_apk\.py\b"
    r"|\bgcloud(?:\.cmd)?\b|\bupdate-traffic\b", re.I)
SHIP_SCRIPTS = ("ship.py", "undo.py")
# Writing verbs the delete/overwrite rules do not cover, for a command naming Kevin's skills:
# a copy onto an existing SKILL.md overwrites it without any -f.
SKILL_WRITE_RE = re.compile(
    r"(?<![\w./\\-])(?:cp|copy|cpi|xcopy|robocopy|ni|mkdir|md|tee|touch|unzip|tar)(?:\.exe)?\s"
    r"|Copy-Item|New-Item|Tee-Object|\bsed\b[^|;&]*\s-i|Expand-Archive"
    r"|\bgit\b[^|;&]*\b(checkout|restore|clone|worktree|apply|am)\b|\bpython\S*\s", re.I)
REPORT_RE = re.compile(r"report\.py\b", re.I)

# Things that look like a path inside a command.
ABS_PATH_RE = re.compile(r"""(?:"([^"]+)"|'([^']+)'|([^\s"'|;&<>(),]+))""")
DRIVE_RE = re.compile(r"^(?:[A-Za-z]:[\\/]|/[a-zA-Z]/|\\\\|/)")
REDIRECT_RE = re.compile(r"(?<![0-9&])(?:[0-9])?>{1,2}\s*([^\s|;&]+)")
EMBEDDED_PATH_RE = re.compile(r"(?<![\w/\\])[A-Za-z]:[\\/][^\s\"'|;&<>()]*")
REDIRECT_SAFE ={"&1", "&2", "$null", "nul", "/dev/null", "NUL"}

LISTED_SHELL_OK = re.compile(
    r"^\s*(?:"
    r"git(\s+-C\s+\S+)?\s+(log|status|diff|show|branch|blame|grep|ls-files|rev-parse|shortlog|describe"
    r"|tag|remote\s+-v)\b"
    r"|cd\b|Set-Location\b|pwd\b|Get-Location\b|ls\b|dir\b|Get-ChildItem\b|gci\b|cat\b|type\b"
    r"|Get-Content\b|gc\b|head\b|tail\b|wc\b|find\b|grep\b|rg\b|findstr\b|Select-String\b"
    r"|Measure-Object\b|Select-Object\b|Sort-Object\b|Where-Object\b|echo\b|Write-Output\b"
    r")",
    re.I,
)


def _is_lone_report(command: str, task: int | None = None, tool: str = "PowerShell") -> bool:
    """This folder's report.py, alone (`_is_our_lone_script`): its free text (a summary) must not
    trip the ask list. Any other report.py, or one with something expandable, is an ordinary
    command (2026-11-22 review F1: the old check stripped quoted text and took any `python`)."""
    return _is_our_lone_script(command, "report.py", task, tool)


def _plain(path: str, bash: bool = False) -> str:
    """A path as TEXT, compared without resolving anything (re-review A1/A2: realpath collapsed
    `x/$(cmd)/..`). ASCII case and slashes only (non-ASCII never gets here, so no Unicode case
    folding such as the Kelvin sign). Git Bash's `/c/X` is `c:/X` ONLY for Bash: PowerShell and
    python.exe read `/c/X` as `<drive>:\\c\\X` (review round 4b)."""
    t = "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in str(path).replace("\\", "/"))
    g = re.match(r"^/([a-z])/(.*)$", t) if bash else None
    return f"{g.group(1)}:/{g.group(2)}" if g else t


def _own_python() -> str:
    """The interpreter the worker gives helpers (`worker.helper_python`): this hook's own."""
    exe = Path(sys.executable)
    if exe.name.lower() == "pythonw.exe" and (exe.parent / "python.exe").exists():
        exe = exe.parent / "python.exe"
    return _plain(str(exe))


# An ALLOWLIST, not a list of shell syntax (review round 3, 2026-11-22: `--ta\sk` lost its
# backslash in Bash, and PowerShell's `--%` passed the rest raw). Every character of a contained
# script's command is one of these, in Bash and PowerShell alike plain text:
_PATH_CHARS = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.:/")
# Unquoted words: no comma (a PowerShell array), no `?` (a Bash glob), no `@` (PowerShell splat),
# no `( )` (a PowerShell subexpression).
_UNQUOTED_CHARS = _PATH_CHARS | frozenset("=+")
# Inside one pair of plain quotes, a summary may also use spaces and , ? ! @ ( ).
_QUOTED_CHARS = _UNQUOTED_CHARS | frozenset(" ,?!@()")
_DOTDOT = re.compile(r"(?:^|/)\.\.(?:/|$)")
_TASK_PREFIXES = ("--t", "--ta", "--tas")  # what argparse would once have expanded to --task


def _plain_words(text: str) -> list[str] | None:
    """The command's words, or None when any character is outside the allowlist. Words are split
    on single spaces; a quoted word is a whole word, '...' or "...", non-empty, holding no quote
    or backslash, so both shells (and PowerShell 5.1's re-quoting for a native program) give the
    program exactly these words."""
    if not text.isascii() or "--%" in text:
        return None
    pieces = _quoted_split(text)
    if not pieces:
        return None
    words = []
    for w in pieces:
        if w == "":
            continue
        if len(w) >= 3 and w[0] == w[-1] and w[0] in "'\"":
            inner = w[1:-1]
            if not inner or not set(inner) <= _QUOTED_CHARS:
                return None
            words.append(inner)
        elif set(w) <= _UNQUOTED_CHARS:
            words.append(w)
        else:
            return None
    return words


def _quoted_split(text: str) -> list[str]:
    """Split on spaces, keeping each quoted span whole so `_plain_words` can judge it."""
    out, cur, q = [], [], None
    for c in text:
        if q:
            cur.append(c)
            if c == q:
                q = None
        elif c in "'\"" and not cur:
            q = c
            cur.append(c)
        elif c == " ":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    if q:
        return []  # unclosed
    out.append("".join(cur))
    return out


def _is_our_lone_script(command: str, script: str, task: int | None = None,
                        tool: str = "PowerShell") -> bool:
    """`<worker's python> <this folder>/<script> ...` alone (review F1, re-review A1-A3, round 3).

    - Every character is on the allowlist above (`_plain_words`); a leading PowerShell `&` call
      operator is the one exception. No backslash, `%`, `$`, backtick, `;`, `|`, `&`, `<`, `>`,
      `{`, `}`, `#`, `*`, `~`, `[`, `]`, no non-ASCII, no `--%`.
    - No `..` path step anywhere.
    - The first two words, as plain ASCII text (never resolved), are the worker's own interpreter
      and THIS folder's copy of the script (the worker's install folder, never a task folder).
    - Exactly one `--task` (no `--t`/`--ta`/`--tas`), and for a helper of task N it is N. The
      scripts themselves refuse a repeated or abbreviated --task too (`common.add_task_arg`)."""
    text = re.sub(r"^&( |$)", "", command or "", count=1)
    words = _plain_words(text)
    if words is None or _DOTDOT.search(text.replace("\\", "/")):
        return False
    bash = tool == "Bash"
    if words and len(words) > 1 and words[1] == "-I":
        words = [words[0], *words[2:]]  # python's isolated mode (round 4d): the same run
    if len(words) < 2 or _plain(words[0], bash) != _own_python():
        return False
    if _plain(words[1], bash) != _plain(str(HERE / script)):
        return False
    tasks: list[str] = []
    args = words[2:]
    for i, w in enumerate(args):
        if w in _TASK_PREFIXES or w.startswith(tuple(p + "=" for p in _TASK_PREFIXES)):
            return False
        if w == "--task":
            tasks.append(args[i + 1] if i + 1 < len(args) else "")
        elif w.startswith("--task="):
            tasks.append(w[len("--task="):])
    if len(tasks) != 1 or (task is not None and tasks != [str(int(task))]):
        return False
    return True


def _unquoted(command: str) -> str:
    return re.sub(r'"(?:\\.|[^"\\])*"' + r"|'[^']*'", '""', command)


def _pushes(command: str) -> bool:
    if (SHIP_GIT_PUSH.search(_unquoted(command)) or SHIP_GIT_QPUSH.search(command)
            or SHIP_GIT_QEXE.search(command)):
        return True
    return any(SHIP_GIT_PUSH.search(a or b) for a, b in QUOTED_RE.findall(command))


def _scratch(path, paths: Paths) -> bool:
    """Inside the worker's work folder and not one of the things in it that are not scratch."""
    return (is_under(path, paths.work) and not is_under(path, paths.skills)
            and not is_under(path, paths.ships))


def _worker_internal(path, paths: Paths) -> bool:
    """The worker's own files: everything under its root outside the work folder, plus the ship
    history. Tokens live here, so a helper writing one would be forging Kevin's yes. Also the
    worker's own code (this folder): the hook trusts scripts here by path, so no helper may
    write or plant one (review round 3)."""
    return (is_under(path, paths.ships) or is_under(path, HERE) or is_under(path, _shadow(HERE))
            or (is_under(path, paths.root) and not is_under(path, paths.work)))


def _shadow(folder: Path) -> str:
    """Where PowerShell or python.exe would put Git Bash's spelling of [folder]: `/c/Users/x` read
    there is `C:\\c\\Users\\x` (review round 4b). No helper may write there either."""
    drive, rest = os.path.splitdrive(str(folder))
    letter = drive.rstrip(":").lower()
    return f"{drive}{os.sep}{letter}{rest}" if letter else str(folder)


def _code_spellings() -> tuple[str, ...]:
    """The worker code folder as text, in every spelling a command could name it."""
    here = _plain(str(HERE))
    drive, rest = here.split(":", 1) if ":" in here else ("", here)
    out = [here, f"/{drive}{rest}" if drive else "", _plain(_shadow(HERE))]
    # Round 4c: `~/x`, `$HOME/x`, `$env:USERPROFILE/x`, `%USERPROFILE%/x` all end in the part of
    # the path below the home folder.
    try:
        out.append("/" + _plain(str(HERE.relative_to(Path.home()))))
    except (ValueError, RuntimeError, KeyError, OSError):  # no home folder in this environment
        pass
    return tuple(x for x in out if x and x != "/")


def _names_worker_code(command: str, cwd: str, tool: str = "PowerShell") -> bool:
    """Orchestrator ruling 2026-11-22 (review round 4b): a shell command that names the worker's
    code folder, or runs inside it, is denied to every helper, tainted or not; Kevin changes the
    worker through git. The one exception is the worker's own python running one existing script
    of that folder as its first two words (a lone contained script was allowed above; build_lock.py
    and warm_worktree.py runs go on to the normal rules), and then only if nothing after those two
    words names the folder again.

    Round 4c: a relative word counts too, resolved against every folder the command could be
    working in: the helper's own, each `cd` / `Set-Location` / `pushd` in the command, and
    build_lock.py's `--cwd`. A regex still cannot see a path built at run time (`$HOME`, `~`,
    `Join-Path`); `integrity.py` catches what that leaves, before any contained script runs."""
    text = re.sub(r"^\s*&\s+", "", command or "")
    lead = re.match(r'^\s*("[^"]*"|\S+)\s+(?:-I\s+)?("[^"]*"|\S+)', text)
    if lead:
        py, script = (w.strip('"') for w in lead.groups())
        bash = tool == "Bash"
        if (_plain(py, bash) == _own_python() and re.fullmatch(r"[A-Za-z0-9_./:-]+\.py", script)
                and _plain(script, bash).rsplit("/", 1)[0] == _plain(str(HERE))
                and (HERE / script.replace("\\", "/").rsplit("/", 1)[-1]).is_file()):
            text = text[lead.end():]
    flat = text.replace("\\", "/").lower()
    if any(x in flat for x in _code_spellings()):
        return True
    if cwd and (is_under(cwd, HERE) or is_under(cwd, _shadow(HERE))):
        return True
    if any(t is not None and (is_under(t, HERE) or is_under(t, _shadow(HERE)))
           for t in _targets(text, cwd)):
        return True
    return _relative_into_code(text, cwd)


_WORD_RE = re.compile(r"[^\s\"'`;&|<>(){},=]+")
_CWD_FLAG_RE = re.compile(r"--cwd(?:\s+|=)(?:\"([^\"]+)\"|'([^']+)'|(\S+))")


def _relative_into_code(text: str, cwd: str) -> bool:
    """Any relative word of [text] that, from any folder the command may be working in, lands in
    the worker code folder (or its `C:\\c\\` mirror)."""
    bases = [cwd] if cwd else []
    for m in list(CD_RE.finditer(text)) + list(_CWD_FLAG_RE.finditer(text)):
        d = _to_os_path(next(g for g in m.groups() if g is not None))
        if d.startswith(("~", "$", "%")):
            continue
        if DRIVE_RE.match(d) or os.path.isabs(d):
            bases.append(d)
        else:
            bases += [os.path.join(b, d) for b in list(bases)]
    roots = (str(HERE), _shadow(HERE))
    for w in _WORD_RE.findall(text):
        if w.startswith("-") or DRIVE_RE.match(w) or w.startswith(("~", "$", "%")) or "://" in w:
            continue
        for base in bases:
            try:
                target = os.path.join(base, _to_os_path(w))
                if any(is_under(target, r) for r in roots):
                    return True
            except (OSError, ValueError):
                return True
    return False


def _code_tampered(paths: Paths) -> dict | None:
    """Review round 4c: the deny for a contained script when this folder is not the deployed code
    (`integrity.check`), else None. Asked only on the allow path of a contained script."""
    ok, why = _deployed(Path(paths.root))
    return None if ok else _deny(_CHANGED.format(why))


def _worker_code_reason() -> str:
    return ("WORKER CODE: the worker's own scripts are Kevin's to change, through git, never a "
            "helper's, tainted or not. A shell command may name that folder only to run one of "
            "its scripts with the worker's python. Do not retry or work around it.")


COPY_RE = re.compile(r"(?:^|[;&|\n(])\s*(?:cp|copy|Copy-Item|cpi)(?:\.exe)?\s+([^;&|\n]+)", re.I)


def _copy_overwrites(command: str, cwd: str, paths: Paths) -> bool:
    """`cp a b` / `Copy-Item a b` with no force flag still overwrites an existing b (review round 4b
    debt): ask when the destination (the last word) is an existing file outside the scratch
    folders. A destination that is a folder, or does not exist yet, makes a new file."""
    for m in COPY_RE.finditer(command or ""):
        words = [w.strip("\"'") for w in m.group(1).split() if not w.startswith("-")]
        if len(words) < 2:
            continue
        dest = _to_os_path(words[-1])
        if not os.path.isabs(dest):
            dest = os.path.join(cwd or ".", dest)
        try:
            if os.path.isfile(dest) and not _scratch(dest, paths):
                return True
        except (OSError, ValueError):
            return True
    return False


def _worker_files_reason() -> str:
    return ("WORKER FILES: the worker's own files (confirmation tokens, config, the agents map, the "
            "ship history) are never a helper's to write, and this cannot be confirmed. Do not "
            "retry or work around it. Report what you needed with --status failed.")


def _ship_reason() -> str:
    return ("SHIP STEP: pushing, deploying, publishing and gcloud run only through ship.py (undo.py "
            "for an undo), after Kevin's yes to the ship question; no helper runs them directly, "
            "even with a confirmation. Use the ship skill: ship.py ask, then report "
            "needs_confirmation with the question it prints; on the yes, ship.py run --token. Do not "
            "retry or work around it.")


def _deny(reason: str) -> dict:
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                   "permissionDecisionReason": reason}}


def _ask_reason(label: str) -> str:
    return (f"ASK LIST: this would {label}. Kevin has not said yes to that. Do not retry it or work "
            "around it. Stop and report: report.py --task N --status needs_confirmation "
            "--question \"<one short question for Kevin>\" (the question goes in --question).")


def _path_tokens(command: str) -> list[str]:
    out = []
    for m in ABS_PATH_RE.finditer(command):
        tok = next(g for g in m.groups() if g is not None)
        if DRIVE_RE.match(tok) or tok.startswith("~") or ".." in tok or "$env:" in tok.lower() \
                or "%" in tok or "$HOME" in tok or tok.startswith("$"):
            out.append(tok)
    # Paths inside larger quoted text, e.g. python -c "os.remove('C:/Users/kevin/x')".
    for m in EMBEDDED_PATH_RE.finditer(command):
        tok = m.group(0).rstrip(".,")
        if tok not in out:
            out.append(tok)
    return out


def _to_os_path(tok: str) -> str:
    m = re.match(r"^/([a-zA-Z])/(.*)$", tok)  # Git Bash /c/Users/...
    if m and os.name == "nt":
        return f"{m.group(1)}:/{m.group(2)}"
    return tok


def _targets(command: str, cwd: str) -> list:
    """Every path the command names (redirect targets too), or its cwd when it names none. None
    stands for a path that cannot be resolved here (`~`, `$env:`, `%VAR%`, `..`)."""
    toks = _path_tokens(command)
    for m in REDIRECT_RE.finditer(command):
        t = m.group(1).strip("\"'")
        if t not in REDIRECT_SAFE:
            toks.append(t if DRIVE_RE.match(t) else os.path.join(cwd or ".", t))
    if not toks:
        return [cwd or None]
    return [None if tok.startswith(("~", "$", "%")) or ".." in tok else _to_os_path(tok)
            for tok in toks]


def _targets_outside(command: str, cwd: str, paths: Paths) -> bool:
    """True if any path the command names, or its cwd when it names none, is not scratch."""
    return any(t is None or not _scratch(t, paths) for t in _targets(command, cwd))


# `> target`, `>> "target"`, `2> target`; `2>&1` and friends never match (the target cannot start
# with `&`). A `cd`/`Set-Location`/`pushd` earlier in the command moves where a relative target lands.
REDIRECT_TARGET_RE = re.compile(
    r"(?<![0-9&=-])(?:[0-9])?>{1,2}\s*(?:\"([^\"]+)\"|'([^']+)'|([^\s|;&<>()]+))")
CD_RE = re.compile(
    r"(?:^|&&|\|\||;|\n)\s*(?:cd|Set-Location|sl|pushd|Push-Location)\s+(?:-Path\s+)?"
    r"(?:\"([^\"]+)\"|'([^']+)'|([^\s|;&]+))", re.I)


def _redirect_targets(command: str, cwd: str) -> list:
    """Where each output redirect in the command writes. None for a target that cannot be resolved
    here (`~`, `$env:`, `%VAR%`, `..`, or relative to an unresolvable `cd`)."""
    out = []
    for m in REDIRECT_TARGET_RE.finditer(command):
        t = next(g for g in m.groups() if g is not None)
        if t in REDIRECT_SAFE or t.lower() in ("nul", "$null"):
            continue
        base = cwd or None
        for c in CD_RE.finditer(command[:m.start()]):
            d = _to_os_path(next(g for g in c.groups() if g is not None))
            if d.startswith(("~", "$", "%")) or ".." in d:
                base = None
            elif DRIVE_RE.match(d):
                base = d
            elif base is not None:
                base = os.path.join(base, d)
        if t.startswith(("~", "$", "%")) or ".." in t:
            out.append(None)
        elif DRIVE_RE.match(t):
            out.append(_to_os_path(t))
        else:
            out.append(os.path.join(base, t) if base else None)
    return out


def _redirect_only(command: str) -> bool:
    """The overwrite rule matched through a redirect and through nothing else."""
    return (re.search(REDIRECT_OUT, command, re.I) is not None
            and re.search(OVERWRITE_VERBS, command, re.I) is None)


def _names_skills(command: str, cwd: str, paths: Paths) -> bool:
    return any(t is not None and is_under(t, paths.skills) for t in _targets(command, cwd))


def _targets_internal(command: str, cwd: str, paths: Paths) -> bool:
    return any(t is not None and _worker_internal(t, paths) for t in _targets(command, cwd))


def _agent_task(paths: Paths, agent_id: str | None) -> int | None:
    if not agent_id:
        return None
    agents = read_json(paths.agents, {}) or {}
    t = agents.get(agent_id)
    if t is None:  # a helper's own subagent (containment: its reads taint the task)
        t = (read_json(paths.subagents, {}) or {}).get(agent_id)
    return int(t) if t is not None else None


def _confirmed(paths: Paths, task: int | None, now: float) -> bool:
    if task is None:
        return False
    for f in paths.tokens.glob("*.json"):
        tok = read_json(f, None)
        if not tok or tok.get("expires_at", 0) < now:
            continue
        if tok.get("kind") != "yes":  # an email_self token covers one mail to Kevin, nothing else
            continue
        if task in (tok.get("task"), tok.get("parent")):
            return True
    return False


GIT_RE = re.compile(r"(^|[\s;&|(`'\"])git(\.exe)?(\s|$|[;&|)`'\"])", re.I)


def _task_kind(paths: Paths, task: int | None) -> str:
    if task is None:
        return ""
    tasks = (read_json(paths.state, {}) or {}).get("tasks") or {}
    t = tasks.get(str(task))
    return str(t.get("kind") or "") if isinstance(t, dict) else ""


def _review_reason(what: str) -> str:
    return (f"MEMORY REVIEW: {what} is not allowed for the memory review task. Memory text stays "
            "in the task folder's context.json and changes.json. Carry on without it, or report "
            "--status failed.")


def _memory_review(tool: str, inp: dict, task: int, paths: Paths) -> dict | None:
    work = paths.task_work(task)
    if tool in WRITE_TOOLS:
        target = inp.get("file_path") or inp.get("notebook_path") or ""
        if not target or not is_under(target, work):
            return _deny(_review_reason("writing outside the task folder"))
    if tool in SHELL_TOOLS and GIT_RE.search(inp.get("command", "") or ""):
        return _deny(_review_reason("git"))
    return None


def _listed_scope(tool: str, inp: dict, cwd: str, cfg: dict, paths: Paths) -> dict | None:
    repos = cfg.get("repos_root")
    work = paths.work
    no = ("WORK LAPTOP: only reading inside the repos folder is allowed here. This cannot be "
          "confirmed from this machine. Report what you could do with --status failed or "
          "needs_confirmation.")
    if tool in READ_TOOLS:
        target = inp.get("file_path") or inp.get("path") or cwd
        if not target or not (is_under(target, work) or (repos and is_under(target, repos))):
            return _deny(no)
        return None
    if tool in WRITE_TOOLS:
        target = inp.get("file_path") or inp.get("notebook_path") or ""
        if not target or not is_under(target, work):
            return _deny(no)
        return None
    if tool in SHELL_TOOLS:
        command = inp.get("command", "") or ""
        if _is_lone_report(command, tool=tool):
            return _code_tampered(paths)
        if _is_our_lone_script(command, "send_mail.py", tool=tool):
            return None  # ruling 18: send_mail.py itself allows only text to Kevin from here
        for seg in re.split(r"&&|\|\||;|\|", command):
            if not seg.strip():
                continue
            if not LISTED_SHELL_OK.match(seg) or REDIRECT_RE.search(seg):
                return _deny(no)
            for tok in _path_tokens(seg):
                p = _to_os_path(tok)
                if tok.startswith(("~", "$", "%")) or ".." in tok or not (
                        is_under(p, work) or (repos and is_under(p, repos))):
                    return _deny(no)
        return None
    if tool in ("WebFetch", "WebSearch"):
        return _deny(no)
    return None


# This folder's own scripts a tainted task may still run, alone: each checks its own limits
# (report.py its fields, send_mail.py its token and Kevin on every send, ship.py and undo.py
# Kevin's yes to the question they print, fetch_file.py its download hosts, to_xlsx.py and
# to_pdf.py their task folder; to_pdf.py also prints with no network and loads nothing).
CONTAINED_SCRIPTS = ("report.py", "send_mail.py", "ship.py", "undo.py", "fetch_file.py",
                     "to_xlsx.py", "to_pdf.py", "sessions.py")
# Kevin's other Claude sessions (alfred-sessions 1122): only through sessions.py, which reads a
# bounded excerpt (tainting the task) and sends only on a token bound to the card Kevin saw.
SESSIONS_SCRIPT = "sessions.py"
PEER_TOOLS = ("SendMessage", "ListAgents")


def _peer_reason() -> str:
    return ("CLAUDE SESSIONS: the worker never messages or lists Claude sessions itself. Use "
            "sessions.py list or read, and for a message sessions.py ask, then report "
            "needs_confirmation with the question it prints. Do not retry or work around it.")


SESSIONS_RE = re.compile(r"sessions\.py", re.I)


def _reads_a_session(command: str) -> bool:
    """Any command naming sessions.py with `read`, ours or not, alone or not (review finding 1,
    the way any run of fetch_file.py reads the web): a transcript is read-through."""
    return bool(SESSIONS_RE.search(command) and re.search(r"\bread\b", command, re.I))


def _sessions_reason() -> str:
    return ("CLAUDE SESSIONS: sessions.py runs only alone, exactly as the rule shows: the worker's "
            "python, its path, this task's own --task, nothing piped, redirected or added. Do not "
            "retry or work around it.")


def _reads_worker_transcripts(tool: str, inp: dict, cwd: str, paths: Paths) -> bool:
    """A Read of one of the worker's own transcripts, or a Grep or Glob rooted at the transcripts
    folder or above it (it would walk them), by `path`, an absolute `pattern`, or the cwd."""
    targets = [inp.get("file_path") or inp.get("path") or ""]
    pattern = str(inp.get("pattern") or "")
    if tool == "Glob" and DRIVE_RE.match(pattern):
        targets.append(re.split(r"[*?\[{]", pattern, maxsplit=1)[0])
    if tool in ("Grep", "Glob") and not (inp.get("path") or ""):
        targets.append(cwd)
    if any(C.worker_transcript(paths, t) for t in targets if t):
        return True
    return tool in ("Grep", "Glob") and any(C.holds_transcripts(t) for t in targets if t)


def _write_targets(inp: dict) -> list[str]:
    t = inp.get("file_path") or inp.get("notebook_path") or ""
    return [t] if t else []


def _write_content(tool: str, inp: dict) -> str:
    if tool == "Write":
        return str(inp.get("content") or "")
    if tool == "Edit":
        return str(inp.get("new_string") or "")
    if tool == "MultiEdit":
        return "\n".join(str(e.get("new_string") or "") for e in inp.get("edits") or []
                         if isinstance(e, dict))
    if tool == "NotebookEdit":
        return str(inp.get("new_source") or "")
    return ""


def _in_checkout(target: str, own_work: Path) -> bool:
    """A path inside a git checkout under the task folder (`<work>/wt`): code, not data."""
    try:
        stop = norm(own_work)
        p = Path(norm(target)).parent
        while is_under(p, stop):
            if (p / ".git").exists():
                return True
            if norm(p) == stop:
                break
            p = p.parent
    except (OSError, ValueError):
        return True
    return False


def _source(tool: str, inp: dict, cwd: str, paths: Paths, trec, cfg: dict,
            event_agent: str | None = None) -> str | None:
    """The read-through source this call is, or None."""
    if tool.startswith(C.GMAIL_PREFIX):
        return "gmail"
    if tool in C.WEB_TOOLS:
        return tool
    if tool in C.FILE_SOURCE_TOOLS:
        target = inp.get("file_path") or inp.get("path") or cwd
        if C.untrusted_file(paths, target, trec, cfg):
            return "untrusted_file"
    if tool in SHELL_TOOLS:
        command = inp.get("command", "") or ""
        if _reads_a_session(command) or C.names_transcripts(command, paths)[0]:
            return "session_transcript"
        if re.search(r"fetch_file\.py", command, re.I):
            return "fetch_file"  # any run of it reads the web, ours or not
        if C.fetches(command):
            return "shell_fetch"
    return None


def _containment(event: dict, paths: Paths, cfg: dict, task: int | None,
                 now: float) -> tuple[dict | None, str, bool]:
    """(hook output, rule, final). `final` skips the other rules (a read source that passed)."""
    tool = event.get("tool_name", "")
    inp = event.get("tool_input") or {}
    cwd = event.get("cwd") or ""
    agent = event.get("agent_id")
    tasks = (read_json(paths.state, {}) or {}).get("tasks") or {}
    trec = tasks.get(str(task)) if task is not None else None
    own_work = paths.task_work(task) if task is not None else None
    is_tainted = C.tainted(paths, task, agent, tasks)
    command = (inp.get("command", "") or "") if tool in SHELL_TOOLS else ""
    ours = bool(command) and any(_is_our_lone_script(command, s, task, tool)
                                 for s in CONTAINED_SCRIPTS)
    if ours:
        tampered = _code_tampered(paths)
        if tampered:
            return tampered, "integrity", True

    if not agent and (tool in WRITE_TOOLS or (tool in SHELL_TOOLS and not ours)):
        # The main window only dispatches, and it reads every helper's final message (derived
        # from whatever that helper read): it runs this folder's scripts alone and writes nothing.
        return _deny(C.MAIN_SHELL_REASON), "main window", True
    if is_tainted:
        if tool in C.AGENT_TOOLS or tool in C.TAINT_DENIED_WRITES:
            return _deny(C.TAINT_REASON), "read_through", True
        if tool in SHELL_TOOLS and not ours:
            return _deny(C.TAINT_REASON), "read_through", True
        if tool in WRITE_TOOLS:
            report_file = trec.get("report_file") if isinstance(trec, dict) else None
            for target in _write_targets(inp) or [""]:
                if not C.write_allowed_tainted(target, own_work, report_file) or (
                        own_work is not None and _in_checkout(target, own_work)):
                    return _deny(C.TAINT_REASON), "read_through", True
                try:
                    C.record_written(paths, target, now)
                except OSError:
                    return _deny(C.MARK_FAILED_REASON), "read_through", True
        if tool in READ_TOOLS:
            target = inp.get("file_path") or inp.get("path") or cwd
            if target and ((is_under(target, paths.root) and not is_under(target, paths.work))
                           or C.CREDENTIAL_NAMES.search(str(target))):
                return _deny(C.CRED_REASON), "read_through", True

    if command and not ours:
        if C.names_blocked(command, cwd, C.written_paths(paths)):
            return _deny(C.WRITTEN_REASON), "read_through written", True
        if C.names_blocked(command, cwd, C.blocked_paths(paths)):
            return _deny(C.NET_REASON), "net script", True
        why = C.network_denial(command, cfg)
        if why:
            return _deny(why), "network", True

    if tool in WRITE_TOOLS and not is_tainted:
        content = _write_content(tool, inp)
        for target in _write_targets(inp):
            if C.content_net_script(target, content, cfg):
                try:
                    C.record_net_script(paths, target, now)
                except OSError:
                    return _deny(C.MARK_FAILED_REASON), "net script", True

    source = _source(tool, inp, cwd, paths, trec, cfg, agent)
    if source:
        if not agent:
            return _deny(C.MAIN_SOURCE_REASON), "main window", True
        try:
            C.mark(paths, task, agent, source, now)
        except Exception:
            return _deny(C.MARK_FAILED_REASON), "read_through mark failed", True
        # A shell source still goes through the ask list below; a read has nothing more to check.
        return None, "read_through", tool not in SHELL_TOOLS
    return None, "", False


def decide(event: dict, paths: Paths, cfg: dict, now: float | None = None) -> tuple[dict | None, str]:
    """Returns (hook output or None to let normal permissions decide, rule label for the log)."""
    now = time.time() if now is None else now
    tool = event.get("tool_name", "")
    inp = event.get("tool_input") or {}
    cwd = event.get("cwd") or ""
    task = _agent_task(paths, event.get("agent_id"))

    if tool in PEER_TOOLS:
        return _deny(_peer_reason()), "peer sessions"
    if tool in READ_TOOLS and _reads_worker_transcripts(tool, inp, cwd, paths):
        return _deny(C.OWN_TRANSCRIPT_REASON), "worker transcripts"
    if tool in SHELL_TOOLS:
        command = inp.get("command", "") or ""
        if C.names_transcripts(command, paths)[1]:
            return _deny(C.OWN_TRANSCRIPT_REASON), "worker transcripts"
        if SESSIONS_RE.search(command) and not _is_our_lone_script(command, SESSIONS_SCRIPT,
                                                                   task, tool):
            # Review findings 1 and 1b: a pipe, a redirect, another task's --task, any other
            # spelling never runs (it would read untainted, or plant a plan on another card).
            return _deny(_sessions_reason()), "sessions"

    if task is not None and _task_kind(paths, task) == "memory_review":
        out = _memory_review(tool, inp, task, paths)
        if out:
            return out, "memory review"

    if cfg.get("scope") == "listed":
        out = _listed_scope(tool, inp, cwd, cfg, paths)
        if out:
            return out, "scope"

    out, rule, final = _containment(event, paths, cfg, task, now)
    if out or final:
        return out, rule

    if tool in WRITE_TOOLS:
        target = inp.get("file_path") or inp.get("notebook_path") or ""
        if target and _worker_internal(target, paths):
            return _deny(_worker_files_reason()), "worker files"
        if target and is_under(target, paths.skills):  # new or existing: Kevin's skills
            if _confirmed(paths, task, now):
                return None, "confirmed"
            return _deny(_ask_reason("change one of Kevin's worker skills")), "skills"
        if target and Path(target).exists() and not _scratch(target, paths):
            if _confirmed(paths, task, now):
                return None, "confirmed"
            return _deny(_ask_reason("overwrite one of Kevin's existing files")), "overwrite"
        return None, ""

    if tool in SHELL_TOOLS:
        command = inp.get("command", "") or ""
        if _is_lone_report(command, task, tool):
            tampered = _code_tampered(paths)
            return tampered, "integrity" if tampered else ""
        if any(_is_our_lone_script(command, s, task, tool) for s in SHIP_SCRIPTS):
            tampered = _code_tampered(paths)
            # ship.py / undo.py check Kevin's yes token themselves
            return tampered, "integrity" if tampered else "ship"
        if _is_our_lone_script(command, SESSIONS_SCRIPT, task, tool):
            tampered = _code_tampered(paths)
            # sessions.py send checks its card-bound token itself
            return tampered, "integrity" if tampered else "sessions"
        if re.search(r"integrity", command, re.I) and re.search(
                r"--write|write_manifest", command, re.I):
            # Round 4d: blessing the folder is the deploy step's alone, never a helper's.
            return _deny(_worker_code_reason()), "worker code"
        if _pushes(command) or SHIP_TOOLS.search(command):
            return _deny(_ship_reason()), "ship only"
        if _names_worker_code(command, cwd, tool):
            return _deny(_worker_code_reason()), "worker code"
        if _copy_overwrites(command, cwd, paths):
            if _confirmed(paths, task, now):
                return None, "confirmed"
            return _deny(_ask_reason(OVERWRITE_LABEL)), OVERWRITE_LABEL
        if _names_skills(command, cwd, paths) and (SKILL_WRITE_RE.search(command) or any(
                re.search(pat, command, re.I) for _l, kind, pat in RULES if kind == "path")):
            if _confirmed(paths, task, now):
                return None, "confirmed"
            return _deny(_ask_reason("change one of Kevin's worker skills")), "skills"
        for label, kind, pat in RULES:
            if not re.search(pat, command, re.I):
                continue
            if label == "send something" and SEND_MAIL_RE.search(command) and not re.search(
                    r"smtplib|Send-MailMessage|System\.Net\.Mail", command, re.I):
                continue  # send_mail.py refuses without the worker's confirmation token
            if label == OVERWRITE_LABEL and _redirect_only(command):
                # Only the redirect writes: judge its targets, not the interpreter or the script
                # the command runs (those are read, not written).
                targets = _redirect_targets(command, cwd)
                if any(t is not None and _worker_internal(t, paths) for t in targets):
                    return _deny(_worker_files_reason()), "worker files"
                if targets and all(t is not None and _scratch(t, paths) for t in targets):
                    continue
            elif kind == "path" and _targets_internal(command, cwd, paths):
                return _deny(_worker_files_reason()), "worker files"
            if kind == "path" and not _targets_outside(command, cwd, paths):
                continue
            if _confirmed(paths, task, now):
                return None, "confirmed"
            return _deny(_ask_reason(label)), label
    return None, ""


def _log(paths: Paths, tool: str, decision: str, rule: str, agent: str | None) -> None:
    try:
        paths.logs.mkdir(parents=True, exist_ok=True)
        with open(paths.logs / "hook.log", "a", encoding="utf-8") as f:
            f.write(json.dumps({"at": round(time.time()), "tool": tool, "decision": decision,
                                "rule": rule, "agent": agent}) + "\n")
    except OSError:
        pass


def main() -> int:
    paths = Paths()
    tool, agent = "?", None
    try:
        event = json.load(sys.stdin)
        tool, agent = event.get("tool_name", "?"), event.get("agent_id")
        out, rule = decide(event, paths, load_config(paths))
    except Exception:  # fail closed
        out, rule = _deny("The ask-list check failed. Stop and report needs_confirmation with "
                          "--question."), "hook-error"
    _log(paths, tool, "deny" if out else "pass", rule, agent)
    if out:
        print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
