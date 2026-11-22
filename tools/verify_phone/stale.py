"""Maintain verification: which checks a branch diff may have made stale.

Each check names the app files and symbols it relies on (`Check.deps`): a label it reads on screen,
a log line it greps, the function that makes the behaviour. A check is flagged when a diff deletes
or renames one of its files, touches a line or a hunk's function context that names one of its
symbols, or touches a file it maps with no symbols. Flagged means "re-read this check", not "broken".
It only lists; it never fails a build (CI has no phone to re-run anything on).
"""
from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_HUNK = re.compile(r"^@@ [^@]* @@ ?(.*)$")


@dataclass
class FileDiff:
    path: str
    status: str = "M"  # A, M, D, R (old path of a rename is reported as D)
    lines: list[str] = field(default_factory=list)  # changed lines' text and hunk contexts


def parse_diff(name_status: str, patch: str) -> dict[str, FileDiff]:
    files: dict[str, FileDiff] = {}
    for row in name_status.splitlines():
        parts = row.split("\t")
        if len(parts) < 2:
            continue
        st = parts[0][:1]
        if st == "R" and len(parts) == 3:
            files[parts[1]] = FileDiff(parts[1], "D")
            files[parts[2]] = FileDiff(parts[2], "A")
        else:
            files[parts[1]] = FileDiff(parts[1], st)
    cur: FileDiff | None = None
    for line in patch.splitlines():
        if line.startswith("diff --git "):
            cur = None
        elif line.startswith("+++ "):
            p = line[4:].strip()
            cur = files.get(p[2:]) if p.startswith("b/") else None
        elif line.startswith("--- "):
            p = line[4:].strip()
            if p.startswith("a/") and files.get(p[2:]) and files[p[2:]].status == "D":
                cur = files[p[2:]]
        elif cur is not None:
            m = _HUNK.match(line)
            if m:
                cur.lines.append(m.group(1))
            elif line[:1] in "+-" and not line.startswith(("+++", "---")):
                cur.lines.append(line[1:])
    return files


def git_diff(base: str, head: str = "HEAD", repo: Path = REPO) -> dict[str, FileDiff]:
    def git(*args):
        return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", check=True).stdout
    rng = f"{base}...{head}"
    return parse_diff(git("diff", "--name-status", "-M", rng), git("diff", "-U0", "--no-color", "-M", rng))


def flags(checks, diff: dict[str, FileDiff], repo: Path = REPO) -> list[tuple[str, str]]:
    """(check id, why) for every check the diff may have made stale."""
    out = []
    for c in checks:
        why = []
        for path, symbols in c.deps.items():
            fd = diff.get(path)
            if fd is None:
                continue
            if fd.status == "D":
                why.append(f"{path} deleted or renamed")
                continue
            if not symbols:
                why.append(f"{path} changed")
                continue
            text = (repo / path).read_text(encoding="utf-8", errors="replace") if (repo / path).exists() else ""
            hit = [s for s in symbols if any(s in ln for ln in fd.lines)]
            gone = [s for s in symbols if s not in text]
            if gone:
                why.append(f"{path}: no longer has {', '.join(gone)}")
            live = [s for s in hit if s not in gone]
            if live:
                why.append(f"{path}: touched {', '.join(live)}")
        if why:
            out.append((c.id, "; ".join(why)))
    return out


def missing(checks, repo: Path = REPO) -> list[str]:
    """The map's own rot: a dep file or symbol that is not in the tree now."""
    out = []
    for c in checks:
        for path, symbols in c.deps.items():
            f = repo / path
            if not f.exists():
                out.append(f"{c.id}: {path} does not exist")
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            out += [f"{c.id}: {path} has no '{s}'" for s in symbols if s not in text]
    return out
