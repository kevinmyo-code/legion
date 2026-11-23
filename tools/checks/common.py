"""Shared shapes for the review-finding checks: a finding, file-type helpers, the allowlist reader."""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass
from pathlib import Path

FAIL = "FAIL"
WARN = "WARN"

BINARY_EXT = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".svg", ".mp4", ".webm", ".mp3", ".wav", ".ogg",
    ".onnx", ".tflite", ".bin", ".jar", ".zip", ".gz", ".pdf", ".ttf", ".otf", ".woff", ".woff2",
    ".keystore", ".jks", ".apk", ".aab", ".db", ".sqlite",
}


@dataclass(frozen=True)
class Finding:
    level: str  # FAIL or WARN
    check: str
    path: str
    line: int  # 0 = whole file
    message: str

    def where(self) -> str:
        return f"{self.path}:{self.line}" if self.line else self.path


def ext(path: str) -> str:
    return Path(path).suffix.lower()


def is_binary(path: str) -> bool:
    return ext(path) in BINARY_EXT


def load_allowlist(name: str) -> list[str]:
    """Glob patterns, one per line, from tools/checks/<name> (# comments and blank lines ignored)."""
    f = Path(__file__).with_name(name)
    if not f.exists():
        return []
    out = []
    for raw in f.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            out.append(line)
    return out


def matches(path: str, patterns: list[str] | tuple[str, ...]) -> bool:
    return any(fnmatch.fnmatchcase(path, p) for p in patterns)


HASH_EXT = {".py", ".sh", ".ps1", ".yml", ".yaml", ".toml", ".cfg", ".ini", ".rb", ".r"}
SLASH_EXT = {".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".cjs", ".gradle", ".css", ".go", ".rs", ".swift"}


def _outside_strings_index(line: str, token: str) -> int:
    """Index of the first `token` outside a '...', "..." or `...` string on this line, or -1."""
    quote = None
    i = 0
    while i < len(line):
        c = line[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in ("'", '"', "`"):
            quote = c
        elif line.startswith(token, i):
            return i
        i += 1
    return -1


def split_comment(path: str, line: str) -> tuple[str, str]:
    """(code, comment) for one line of a code file: the comment starts at `#` or `//` outside a string,
    or is the whole line when it starts with `*` or `/*` (a block comment). Not a code file: (line, '')."""
    e = ext(path)
    if e in HASH_EXT:
        i = _outside_strings_index(line, "#")
    elif e in SLASH_EXT:
        if line.lstrip().startswith(("*", "/*")):
            return "", line
        i = _outside_strings_index(line, "//")
    else:
        return line, ""
    return (line, "") if i < 0 else (line[:i], line[i:])
