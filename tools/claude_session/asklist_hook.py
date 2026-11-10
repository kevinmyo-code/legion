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
- Anything unexpected inside this hook denies (fails closed).

The hook log records tool, decision, rule and agent id. Never the command, a path, or any text.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, is_under, load_config, norm, read_json  # noqa: E402

HERE = Path(__file__).resolve().parent

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
_LEAD_RE = re.compile(
    r'^\s*&?\s*(?:"[^"]*python[0-9.]*(?:\.exe)?"|\S*python[0-9.]*(?:\.exe)?)\s+'
    r'(?:"(?P<q>[^"]+)"|(?P<s>\S+))(?P<rest>.*)$', re.I | re.S)
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


def _is_lone_script(command: str, script: str) -> bool:
    """`python .../<script> ...` and nothing chained to it. Its free text (a summary, a mail body)
    must not trip the ask list, so it is judged with quoted strings removed."""
    bare = re.sub(r'"(?:\\.|[^"\\])*"' + r"|'[^']*'", '""', command)
    pat = r"^\s*&?\s*\S*python(?:\.exe)?\S*\s+\S*" + re.escape(script) + r"\b"
    if not re.match(pat, bare, re.I):
        return False
    return not re.search(r"[;&|`<>]|\$\(", bare.split(script, 1)[1])


def _is_lone_report(command: str) -> bool:
    return _is_lone_script(command, "report.py")


def _is_our_lone_script(command: str, script: str) -> bool:
    """`python <this folder>/<script> ...` alone: the script must be THIS folder's copy (a helper
    could write its own ship.py), and nothing may be chained to it. Free text in its arguments
    (the ship notes) is judged with quoted strings removed."""
    m = _LEAD_RE.match(command or "")
    if not m:
        return False
    p = m.group("q") or m.group("s")
    if norm(_to_os_path(p)) != norm(HERE / script):
        return False
    return not re.search(r"[;&|`<>\n]|\$\(", _unquoted(m.group("rest")))


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
    history. Tokens live here, so a helper writing one would be forging Kevin's yes."""
    return is_under(path, paths.ships) or (is_under(path, paths.root)
                                           and not is_under(path, paths.work))


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
        if _is_lone_report(command):
            return None
        if _is_lone_script(command, "send_mail.py"):
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


def decide(event: dict, paths: Paths, cfg: dict, now: float | None = None) -> tuple[dict | None, str]:
    """Returns (hook output or None to let normal permissions decide, rule label for the log)."""
    now = time.time() if now is None else now
    tool = event.get("tool_name", "")
    inp = event.get("tool_input") or {}
    cwd = event.get("cwd") or ""
    task = _agent_task(paths, event.get("agent_id"))

    if task is not None and _task_kind(paths, task) == "memory_review":
        out = _memory_review(tool, inp, task, paths)
        if out:
            return out, "memory review"

    if cfg.get("scope") == "listed":
        out = _listed_scope(tool, inp, cwd, cfg, paths)
        if out:
            return out, "scope"

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
        if _is_lone_report(command):
            return None, ""
        if any(_is_our_lone_script(command, s) for s in SHIP_SCRIPTS):
            return None, "ship"  # ship.py / undo.py check Kevin's yes token themselves
        if _pushes(command) or SHIP_TOOLS.search(command):
            return _deny(_ship_reason()), "ship only"
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
