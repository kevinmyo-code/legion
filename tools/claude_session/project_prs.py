"""Project PRs: Kevin's list of projects a helper may push a side branch to and open a pull
request on (worker tickets W1 + W2, Kevin 2026-11-19: "PR with green check").

The list is DATA in `<worker home>/projects.json`, never in this repo (the repo is public):

    {"<name>": {"spoken": ["<what Kevin calls it>", ...], "repo": "<owner>/<repo>",
                "path": "<his own checkout, never edited>", "clone_url": "<https github url>",
                "default_branch": "main", "allow_push_branches": "claude/*", "allow_pr": true}}

No file, an unreadable file or no valid entry: no projects, and every rule in the ask-list hook
is exactly what it was before this module.

Three halves:
- `match` / `project_for` / `task_lines`: the worker ties a task to a project when Kevin's request
  names one (or its parent task had one) and tells the helper the clone, the branch and the two
  commands. The task record keeps `project` = "<owner>/<repo>"; the hook trusts only that record.
- `judge`: the hook's decision for ONE shell command, pure (the repo's config comes in through
  `facts_for`). Two grammars and nothing else pass:
      git -C <abs dir under the task's own folder> push [-u|--set-upstream|-q|-v|--porcelain]
          <remote> HEAD:refs/heads/<prefix><name>
      gh pr create --repo <owner>/<repo> --base <default> --head <prefix><name>
          [--title T] [--body B] [--draft]
  Anything else that pushes or opens a PR, for a task tied to a project, is denied with the
  accepted form in the reason. `gh pr merge`, `gh repo`, `gh api`, `gh secret`, `gh workflow` are
  not this module's business: today's rules decide them.
- `gather`: the facts, from git itself (`remote get-url --push --all` applies pushurl and
  insteadOf), with commands that never refresh the index (so no fsmonitor runs).

Honest limits (a regex hook is not a sandbox, containment.py says the same): the check runs
before the command, so a background process that rewrites `.git/config` between the check and the
push is not seen; a git alias for push (`git p`) is not seen by today's rules either. A `pr.py`
that runs the push itself with pinned arguments (ship.py's shape) would close both.

Stdlib only.
"""
from __future__ import annotations

import fnmatch
import json
import os
import re
import shlex
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from common import is_under

REPO_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+$")
PATTERN_RE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*/)\*$")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*$")
REMOTE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")
GITHUB_URL_RE = re.compile(
    r"^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)"
    r"(?P<repo>[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+?)(?:\.git)?/?$")
GIT_TIMEOUT_S = 5
GH_TIMEOUT_S = 15
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
PROTECTED_RE = re.compile(
    r"^(?:\.github/|\.gitmodules$|\.lfsconfig$)|(?:^|/)(?:\.gitattributes|CODEOWNERS|action\.ya?ml)$",
    re.I)
# What `git clone`, `checkout -b`, `push -u` and `commit` write into a clone's own config. Any other
# key there (credential.helper, core.sshCommand, include.path, url.*, alias.*, hooksPath, pushurl,
# ...) could run a program or redirect the push, so the push is refused.
LOCAL_KEYS_RE = re.compile(
    r"^(?:core\.(?:repositoryformatversion|filemode|bare|logallrefupdates|symlinks|ignorecase"
    r"|precomposeunicode|autocrlf)|extensions\.objectformat|remote\.[^.]+\.(?:url|fetch)"
    r"|branch\..+\.(?:remote|merge)|user\.(?:name|email))$")

PUSH_OPTS = {"-u", "--set-upstream", "-q", "--quiet", "-v", "--verbose", "--porcelain"}
PR_VALUE_OPTS = {"-t": "title", "--title": "title", "-b": "body", "--body": "body",
                 "-B": "base", "--base": "base", "-H": "head", "--head": "head",
                 "-R": "repo", "--repo": "repo"}
PR_FLAG_OPTS = {"-d", "--draft"}

# Engaged only on commands that look like a push or a PR: everything else is not ours.
LOOKS_PUSH_RE = re.compile(r"\bgit\b.*\bpush\b", re.I | re.S)
LOOKS_PR_RE = re.compile(r"\bgh\b.*\bpr\b.*\bcreate\b", re.I | re.S)


@dataclass(frozen=True)
class Project:
    name: str
    spoken: tuple[str, ...]
    repo: str
    clone_url: str
    path: str
    default_branch: str
    prefix: str
    allow_pr: bool


@dataclass(frozen=True)
class Facts:
    toplevel: str
    push_urls: tuple[str, ...]
    mirror: bool
    follow_tags: bool
    recurse_submodules: bool
    gitmodules: bool
    hooks: bool
    foreign_config: tuple[str, ...] = ()  # keys in the clone's own config outside LOCAL_KEYS_RE
    # HEAD changes a protected path (PROTECTED_RE: CI workflows, .gitattributes, .gitmodules,
    # CODEOWNERS, local actions, .lfsconfig) against GitHub's REAL default-branch tip (asked of the
    # API, never the clone's own movable origin ref), or that could not be told. A pushed branch's
    # workflow runs on its PR with the repo's token and may ask for write access to the default
    # branch, so a branch that changes one is never pushed from here.
    ci_change: bool = False
    base_missing: bool = False  # GitHub's tip is not in the clone: fetch first


@dataclass(frozen=True)
class Verdict:
    decision: str  # "allow" or "deny"
    kind: str      # "push" or "pr"
    reason: str = ""


# --------------------------------------------------------------------------- the list

def parse_projects(data) -> list[Project]:
    """Valid entries only; a bad entry is skipped, never half-trusted."""
    if not isinstance(data, dict):
        return []
    out = []
    for name, e in data.items():
        if not isinstance(e, dict):
            continue
        repo = str(e.get("repo") or "")
        pat = PATTERN_RE.match(str(e.get("allow_push_branches") or "claude/*"))
        default = str(e.get("default_branch") or "main")
        clone = str(e.get("clone_url") or f"https://github.com/{repo}.git")
        url = GITHUB_URL_RE.match(clone)
        if (not REPO_RE.match(repo) or not pat or not NAME_RE.match(default)
                or default.startswith(pat.group(1)) or not url
                or url.group("repo").lower() != repo.lower() or not clone.startswith("https://")):
            continue
        spoken = tuple(str(s) for s in (e.get("spoken") or []) if isinstance(s, str) and s.strip())
        out.append(Project(name=str(name), spoken=spoken, repo=repo, clone_url=clone,
                           path=str(e.get("path") or ""), default_branch=default,
                           prefix=pat.group(1), allow_pr=e.get("allow_pr") is True))
    return out


def load(path: Path) -> list[Project]:
    try:
        return parse_projects(json.loads(Path(path).read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return []


def by_repo(projects: list[Project], repo: str | None) -> Project | None:
    if not repo:
        return None
    return next((p for p in projects if p.repo.lower() == str(repo).lower()), None)


def _words(text: str) -> str:
    return " " + " ".join(re.sub(r"[^a-z0-9]+", " ", text.lower()).split()) + " "


def match(request: str, projects: list[Project]) -> Project | None:
    """The one project the request names (a spoken name, the repo name or owner/repo, as whole
    words); None for none or for more than one."""
    said = _words(request or "")
    hits = []
    for p in projects:
        names = list(p.spoken) + [p.repo, p.repo.split("/", 1)[1]]
        if any(_words(n).strip() and _words(n) in said for n in names):
            hits.append(p)
    return hits[0] if len(hits) == 1 else None


def project_for(request: str, parent_project: str | None, projects: list[Project]) -> str | None:
    """What the worker records on a task: the project its request names, else its parent's
    (a follow-up such as "yes, go on"), and only while that project is still on the list."""
    p = match(request, projects) or by_repo(projects, parent_project)
    return p.repo if p else None


def task_lines(p: Project, work: Path) -> list[str]:
    """What the task message tells the main window (and it copies into the helper's prompt)."""
    w = Path(work).as_posix()
    clone = f"{w}/{p.repo.split('/', 1)[1]}"
    lines = [
        f"Project on Kevin's list: {p.name} (GitHub {p.repo}, default branch {p.default_branch}). "
        f"Work in a fresh clone in this task's folder: git clone {p.clone_url} {clone}"
        + (f" (never edit his own copy at {Path(p.path).as_posix()})." if p.path else "."),
        f"Branch {p.prefix}<short-name> only. Run the tests, commit, then push exactly: "
        f"git -C {clone} push -u origin HEAD:refs/heads/{p.prefix}<short-name> "
        "(alone, nothing chained, no other form passes the hook).",
    ]
    if p.allow_pr:
        lines.append(
            f"Then open the pull request exactly: gh pr create --repo {p.repo} --base "
            f"{p.default_branch} --head {p.prefix}<short-name> --title \"<title>\" --body \"<three "
            "short lines>\" (double quotes, no $, backticks or double quotes inside). Then "
            f"gh pr checks {p.prefix}<short-name> --repo {p.repo} --watch, last.")
    return lines


# --------------------------------------------------------------------------- the decision

def _safe(command: str) -> bool:
    """No chaining, redirection, substitution, globbing, comments or escapes outside quotes, and
    no `$` or backtick outside single quotes (they expand inside double quotes). Newlines only
    inside quotes. When in doubt, False."""
    quote = None
    for ch in command:
        if quote == "'":
            if ch == "'":
                quote = None
            continue
        if quote == '"':
            if ch in "$`\\":
                return False
            if ch == '"':
                quote = None
            continue
        if ch in "'\"":
            quote = ch
        elif ch in ";&|<>()$`\\\n\r{}*?[]~#!":
            return False
    return quote is None


def _os_path(tok: str) -> str:
    g = re.match(r"^/([a-zA-Z])/(.*)$", tok)
    return f"{g.group(1)}:/{g.group(2)}" if g and os.name == "nt" else tok


def _abs(tok: str) -> bool:
    return bool(re.match(r"^(?:[A-Za-z]:[\\/]|/[a-zA-Z]/)", tok))


def _branch_ok(name: str, p: Project) -> bool:
    return (name.startswith(p.prefix) and len(name) > len(p.prefix) and bool(NAME_RE.match(name))
            and fnmatch.fnmatchcase(name, p.prefix + "*") and ".." not in name
            and not name.endswith(".lock") and name != p.default_branch)


def github_repo(url: str) -> str | None:
    m = GITHUB_URL_RE.match(url or "")
    return m.group("repo") if m else None


def _push_form(p: Project) -> str:
    return (f"git -C <the clone in this task's folder> push -u origin "
            f"HEAD:refs/heads/{p.prefix}<short-name>, alone")


def _pr_form(p: Project) -> str:
    return (f"gh pr create --repo {p.repo} --base {p.default_branch} --head "
            f"{p.prefix}<short-name> --title \"...\" --body \"...\", alone")


def _deny(kind: str, why: str, p: Project) -> Verdict:
    form = _push_form(p) if kind == "push" else _pr_form(p)
    return Verdict("deny", kind, f"PROJECT {kind.upper()}: {why}. The only form that passes is: "
                                 f"{form}. Fix the command and run it once more; if it cannot "
                                 "take that form, report what you have with --status failed.")


def _judge_push(argv: list[str], own_work: Path, p: Project,
                facts_for: Callable[[str, str, str], Facts | None]) -> Verdict:
    deny = lambda why: _deny("push", why, p)  # noqa: E731
    if argv[:2] != ["git", "-C"] or len(argv) < 4 or argv[3] != "push":
        return deny("git push runs as `git -C <clone> push`, no other git options")
    d = argv[2]
    if not _abs(d) or not is_under(_os_path(d), own_work):
        return deny("the clone must be named by its absolute path inside this task's folder")
    rest = argv[4:]
    opts = [a for a in rest if a.startswith("-") or a.startswith("+")]
    pos = [a for a in rest if a not in opts]
    if any(o not in PUSH_OPTS for o in opts):
        return deny("that push option is not allowed")
    if len(pos) != 2:
        return deny("name exactly one remote and one refspec")
    remote, spec = pos
    if not REMOTE_RE.match(remote):
        return deny("the remote must be a remote's name, not a URL or a path")
    m = re.match(r"^HEAD:refs/heads/(.+)$", spec)
    if not m or not _branch_ok(m.group(1), p):
        return deny(f"the refspec must be HEAD:refs/heads/{p.prefix}<short-name>, never "
                    f"{p.default_branch}")
    f = facts_for(_os_path(d), remote, p.default_branch)
    if f is None:
        return deny("the clone's git config could not be read")
    if not is_under(f.toplevel, own_work):
        return deny("the repository is not inside this task's folder")
    if not f.push_urls or any((github_repo(u) or "").lower() != p.repo.lower() for u in f.push_urls):
        return deny(f"remote {remote} does not push to {p.repo} only")
    if f.mirror or f.follow_tags or f.recurse_submodules or f.gitmodules:
        return deny("the clone's config would push more than the one branch")
    if f.hooks:
        return deny("the clone has git hooks; push from a clean clone")
    if f.base_missing:
        return deny("GitHub's default branch tip is not in the clone; run git -C <clone> fetch "
                    "origin first")
    if f.ci_change:
        return deny("the branch changes CI or repo-control files (.github/, .gitattributes, "
                    ".gitmodules, CODEOWNERS, action.yml, .lfsconfig) against GitHub's default "
                    "branch, or that could not be checked; leave those files alone")
    if f.foreign_config:
        return deny("the clone's own git config has settings a fresh clone does not "
                    f"({', '.join(f.foreign_config[:3])}); push from a clean clone")
    return Verdict("allow", "push")


def _judge_pr(argv: list[str], p: Project) -> Verdict:
    deny = lambda why: _deny("pr", why, p)  # noqa: E731
    if not p.allow_pr:
        return deny(f"pull requests are not allowed on {p.repo}")
    if argv[:3] != ["gh", "pr", "create"]:
        return deny("gh pr create runs bare, no global options")
    seen: dict[str, str] = {}
    i, args = 0, argv[3:]
    while i < len(args):
        a = args[i]
        key, val = (a.split("=", 1) + [None])[:2] if a.startswith("--") and "=" in a else (a, None)
        if key in PR_FLAG_OPTS and val is None:
            i += 1
            continue
        field = PR_VALUE_OPTS.get(key)
        if field is None or field in seen:
            return deny(f"option {key} is not allowed here")
        if val is None:
            if i + 1 >= len(args):
                return deny(f"option {key} needs a value")
            val, i = args[i + 1], i + 1
        seen[field] = val
        i += 1
    if seen.get("repo", "").lower() != p.repo.lower():
        return deny(f"--repo must be {p.repo}")
    if seen.get("base") != p.default_branch:
        return deny(f"--base must be {p.default_branch}")
    if not _branch_ok(seen.get("head", ""), p):
        return deny(f"--head must be {p.prefix}<short-name>")
    return Verdict("allow", "pr")


def judge(command: str, *, tool: str, own_work: Path | None, project: Project | None,
          facts_for: Callable[[str, str, str], Facts | None]) -> Verdict | None:
    """None: not a project push or PR (today's rules decide). Else allow or deny."""
    if tool != "Bash" or project is None or own_work is None:
        return None
    command = command or ""
    kind = "push" if LOOKS_PUSH_RE.search(command) else "pr" if LOOKS_PR_RE.search(command) else None
    if kind is None:
        return None
    if not _safe(command):
        return _deny(kind, "the command chains, redirects or substitutes", project)
    try:
        argv = shlex.split(command, posix=True)
    except ValueError:
        return _deny(kind, "the command could not be parsed", project)
    if not argv:
        return None
    if argv[0] == "git":
        if _git_subcommand(argv) != "push":
            return None  # `git commit -m "push the fix"`: not a push
        return _judge_push(argv, Path(own_work), project, facts_for)
    if argv[0] == "gh":
        if "pr" not in argv[1:3] or "create" not in argv[1:4]:
            return None  # gh pr merge, gh repo ...: today's rules
        return _judge_pr(argv, project)
    return _deny(kind, "the command must start with git or gh itself", project)


GIT_VALUE_OPTS = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
                  "--config-env", "--super-prefix", "--list-cmds"}


def _git_subcommand(argv: list[str]) -> str:
    i = 1
    while i < len(argv) and argv[i].startswith("-"):
        i += 2 if argv[i] in GIT_VALUE_OPTS else 1
    return argv[i] if i < len(argv) else ""


# --------------------------------------------------------------------------- the facts

def _git(d: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-c", "core.fsmonitor=false", "-C", d, *args],
                          capture_output=True, text=True, timeout=GIT_TIMEOUT_S,
                          creationflags=0x08000000 if os.name == "nt" else 0)


def gather(d: str, remote: str, default_branch: str = "main") -> Facts | None:
    """The clone's facts, or None when they cannot be read (the caller denies). Asks GitHub for
    the default branch's tip (one API call, about a second)."""
    try:
        top = _git(d, "rev-parse", "--show-toplevel")
        if top.returncode != 0:
            return None
        toplevel = top.stdout.strip()
        urls = _git(d, "remote", "get-url", "--push", "--all", remote)
        push_urls = tuple(u.strip() for u in urls.stdout.splitlines() if u.strip()) \
            if urls.returncode == 0 else ()
        cfg = _git(d, "config", "--list", "-z")
        if cfg.returncode != 0:
            return None
        conf: dict[str, list[str]] = {}
        for item in cfg.stdout.split("\0"):
            if item:
                k, _, v = item.partition("\n")
                conf.setdefault(k.lower(), []).append(v.strip().lower())
        local = _git(d, "config", "--local", "--list", "-z")
        if local.returncode != 0:
            return None
        foreign = tuple(sorted({k for k in (i.partition("\n")[0].lower()
                                            for i in local.stdout.split("\0") if i)
                                if not LOCAL_KEYS_RE.match(k)}))
        repo = github_repo(push_urls[0]) if push_urls else None
        tip = remote_tip(repo, default_branch) if repo else None
        base_missing, ci_change = False, True
        if tip and SHA_RE.match(tip):
            have = _git(d, "cat-file", "-e", f"{tip}^{{commit}}")
            base_missing = have.returncode != 0
            if not base_missing:
                diff = _git(d, "diff-tree", "-r", "--name-only", "--no-renames", tip, "HEAD")
                ci_change = diff.returncode != 0 or any(
                    PROTECTED_RE.search(n.strip()) for n in diff.stdout.splitlines())
        hooks_dir = _git(d, "rev-parse", "--git-path", "hooks")
        if hooks_dir.returncode != 0:
            return None
        hp = Path(hooks_dir.stdout.strip())
        if not hp.is_absolute():
            hp = Path(d) / hp
        live_hooks = hp.is_dir() and any(not f.name.endswith(".sample") for f in hp.iterdir())
    except (OSError, subprocess.SubprocessError, ValueError):
        return None
    on = lambda k: any(v in ("true", "yes", "on", "1") for v in conf.get(k, []))  # noqa: E731
    return Facts(
        toplevel=toplevel, push_urls=push_urls,
        mirror=on(f"remote.{remote.lower()}.mirror"),
        follow_tags=on("push.followtags"),
        recurse_submodules=on("submodule.recurse") or any(
            v not in ("no", "false", "0", "off") for v in conf.get("push.recursesubmodules", [])),
        gitmodules=(Path(toplevel) / ".gitmodules").exists(),
        hooks=bool(conf.get("core.hookspath")) or live_hooks, foreign_config=foreign,
        ci_change=ci_change, base_missing=base_missing)


def _gh_remote_tip(repo: str, branch: str) -> str | None:
    """GitHub's own answer for the branch tip, over gh's API (no git config, so no insteadOf
    or pushurl can redirect it). None on any failure; the caller denies."""
    try:
        r = subprocess.run(["gh", "api", f"repos/{repo}/git/ref/heads/{branch}", "--jq", ".object.sha"],
                           capture_output=True, text=True, timeout=GH_TIMEOUT_S,
                           creationflags=0x08000000 if os.name == "nt" else 0)
    except (OSError, subprocess.SubprocessError):
        return None
    sha = r.stdout.strip().lower()
    return sha if r.returncode == 0 and SHA_RE.match(sha) else None


# Module-level so a test can stand in for GitHub.
remote_tip: Callable[[str, str], str | None] = _gh_remote_tip
