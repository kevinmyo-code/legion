"""Check (b): code files over 600 lines (AGENT-RULES code rules: split a file past about 600 lines).

Review finding it automates: new modules landing at 700 to 1500 lines, and old ones growing further.

Gate mode (--base):
  - FAIL: a NEW code file over 600 lines (a new TEST file over 600 only warns);
  - WARN: an existing file that crosses 600, or is already over 600 and grows (existing debt never fails).
  A pure move (git rename detection) is compared with the file it moved from, never called new.
--all: counts the code files over 600 in the tree, never fails.
Only code counts (see CODE_EXT); generated and data files are skipped, as is anything in file_size_allow.txt.
"""

from __future__ import annotations

from common import FAIL, WARN, Finding, ext, load_allowlist, matches
from gitdiff import Change

NAME = "file-size"
LIMIT = 600
CODE_EXT = {
    ".py", ".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".cjs", ".sh", ".ps1", ".css", ".go", ".rs",
}


def line_count(text: str | None) -> int:
    return 0 if not text else len(text.splitlines())


TEST_PATTERNS = ("*/tests/*", "*/src/test/*", "*/src/androidTest/*", "*.test.ts", "*.test.tsx", "*.spec.ts",
                 "*/test_*.py", "test_*.py")


def is_test(path: str) -> bool:
    return matches(path, TEST_PATTERNS)


def is_code(path: str) -> bool:
    return ext(path) in CODE_EXT


def check(change: Change, allow: list[str] | None = None) -> list[Finding]:
    allow = load_allowlist("file_size_allow.txt") if allow is None else allow
    out = []
    for path, letter in sorted(change.status.items()):
        if letter == "D" or not is_code(path) or matches(path, allow):
            continue
        after = line_count(change.read(path))
        if after <= LIMIT:
            continue
        moved_from = change.moved_from.get(path)
        if letter == "A" and moved_from is None:
            level = WARN if is_test(path) else FAIL
            out.append(Finding(level, NAME, path, 0, f"new {'test ' if level == WARN else ''}file is {after} lines"
                               f" (limit {LIMIT}): split it"))
            continue
        before = line_count(change.read_base(moved_from or path))
        if before <= LIMIT:
            out.append(Finding(WARN, NAME, path, 0, f"crossed {LIMIT} lines ({before} -> {after})"))
        elif after > before:
            out.append(Finding(WARN, NAME, path, 0, f"over {LIMIT} and grew ({before} -> {after})"))
    return out


def over_limit(change: Change, allow: list[str] | None = None) -> list[tuple[str, int]]:
    """(path, lines) for every code file over the limit: the --all count."""
    allow = load_allowlist("file_size_allow.txt") if allow is None else allow
    rows = []
    for path in sorted(change.status):
        if is_code(path) and not matches(path, allow):
            n = line_count(change.read(path))
            if n > LIMIT:
                rows.append((path, n))
    return rows
