"""What the review-finding checks read from git: the files a change touches and the lines it adds.

Stdlib only. Every check takes a `Change` (built from git, or by hand in tests), so the rules stay
pure functions and only this module runs git.
"""

from __future__ import annotations

import os
import re
import subprocess
from dataclasses import dataclass, field

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


@dataclass
class Change:
    """status: path -> git letter (A, M, D; renames are split into D + A). added: path -> [(line no, text)].
    read(path) -> the file's text at head, or None if absent. read_base(path) -> text at base, or None."""

    status: dict[str, str] = field(default_factory=dict)
    added: dict[str, list[tuple[int, str]]] = field(default_factory=dict)
    head_text: dict[str, str] = field(default_factory=dict)
    base_text: dict[str, str] = field(default_factory=dict)
    head_files: list[str] = field(default_factory=list)  # every tracked path at head

    def read(self, path: str) -> str | None:
        return self.head_text.get(path)

    def read_base(self, path: str) -> str | None:
        return self.base_text.get(path)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True,
                          encoding="utf-8", errors="replace").stdout


class _CatFile:
    """One `git cat-file --batch` process for all reads: a `git show` per file is minutes on Windows."""

    def __init__(self) -> None:
        self.proc: subprocess.Popen | None = None
        self.cwd = ""

    def read(self, rev: str, path: str) -> str | None:
        if self.proc is not None and self.cwd != os.getcwd():  # another repo (tests chdir)
            self.proc.kill()
            self.proc = None
        if self.proc is None:
            self.cwd = os.getcwd()
            self.proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE)
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(f"{rev}:{path}\n".encode())
        self.proc.stdin.flush()
        header = self.proc.stdout.readline().decode().split()
        if len(header) != 3:  # "<spec> missing"
            return None
        size = int(header[2])
        data = self.proc.stdout.read(size)
        self.proc.stdout.read(1)  # trailing newline
        if header[1] != "blob" or b"\0" in data[:8000]:
            return None
        return data.decode("utf-8", errors="replace")


_CAT = _CatFile()


def show(rev: str, path: str) -> str | None:
    """The file's text at `rev`, or None if absent, not a blob, or binary."""
    return _CAT.read(rev, path)


def parse_added(diff_text: str) -> dict[str, list[tuple[int, str]]]:
    """Added lines per file from `git diff -U0` output."""
    out: dict[str, list[tuple[int, str]]] = {}
    path = None
    line_no = 0
    for raw in diff_text.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:]
            path = target[2:] if target.startswith("b/") else None
            if path is not None:
                out.setdefault(path, [])
            continue
        if raw.startswith("--- ") or raw.startswith("diff --git"):
            continue
        m = HUNK.match(raw)
        if m:
            line_no = int(m.group(1))
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            out[path].append((line_no, raw[1:]))
            line_no += 1
    return out


class LazyText(dict):
    """path -> text at a revision, read from git on first use."""

    def __init__(self, rev: str):
        super().__init__()
        self.rev = rev

    def get(self, path, default=None):  # type: ignore[override]
        if path not in self:
            self[path] = show(self.rev, path)
        v = self[path]
        return default if v is None else v


def from_git(base: str, head: str = "HEAD") -> Change:
    """The change base...head (from the merge base). Raises CalledProcessError if a rev is unknown."""
    merge_base = git("merge-base", base, head).strip()
    status: dict[str, str] = {}
    for line in git("diff", "--name-status", "--no-renames", merge_base, head).splitlines():
        letter, _, path = line.partition("\t")
        if path:
            status[path] = letter[:1]
    added = parse_added(git("diff", "-U0", "--no-renames", "--no-color", merge_base, head))
    return Change(status=status, added=added, head_text=LazyText(head), base_text=LazyText(merge_base),
                  head_files=tracked_files(head))


def tracked_files(rev: str = "HEAD") -> list[str]:
    return [p for p in git("ls-tree", "-r", "--name-only", rev).splitlines() if p]


def whole_tree(rev: str = "HEAD") -> Change:
    """Every tracked file as if newly added: for the counts-only `--all` mode."""
    files = tracked_files(rev)
    text = LazyText(rev)
    return Change(status={p: "A" for p in files}, added=_AllLines(text), head_text=text, base_text={},
                  head_files=files)


class _AllLines(dict):
    """path -> every line of the file at head, numbered, read lazily."""

    def __init__(self, text: LazyText):
        super().__init__()
        self.text = text

    def get(self, path, default=None):  # type: ignore[override]
        if path not in self:
            t = self.text.get(path)
            self[path] = [] if t is None else list(enumerate(t.splitlines(), start=1))
        return self[path]
