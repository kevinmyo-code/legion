"""Read a Kotlin string constant out of the Android source, as text.

web-assistant ticket 03 (Kevin, 2026-10-04): one prompt source for every
client. The Kotlin side is not rewired to the generated copy yet (ticket 09's
side of the work), so until it is, the only way to catch drift between
`server/assistant/shared_clauses.txt` and `ai/AriaBrain.kt` is to read the
Kotlin file and evaluate the constants it declares. This module is that
reader, and it is deliberately tiny: it understands exactly the shapes the
prompt layer uses and refuses anything else in words, so a refactor of the
Kotlin side fails a test loudly rather than being half-read.

Understood:

- `val NAME =` (with or without `internal` / `private`), followed by an
  expression of `"..."` literals and other such names joined by `+`,
  with `//` line comments and whitespace anywhere between terms;
- the Kotlin escapes `\\"`, `\\\\`, `\\n`, `\\t`, `\\'`, `\\$`;
- a `\"\"\"...\"\"\".trimIndent()` raw string (the persona clauses).

Refused: a string template (`$name`, `${...}`), any other expression.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_SRC = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "kevin" / "legion"

_ESCAPES = {'"': '"', "\\": "\\", "n": "\n", "t": "\t", "'": "'", "$": "$"}


class KotlinReadError(Exception):
    pass


def _skip_space_and_comments(src: str, i: int) -> int:
    while i < len(src):
        if src[i].isspace():
            i += 1
        elif src.startswith("//", i):
            end = src.find("\n", i)
            i = len(src) if end == -1 else end + 1
        else:
            break
    return i


def _read_literal(src: str, i: int) -> tuple[str, int]:
    """`src[i]` is the opening quote. Returns (value, index after the close)."""
    out = []
    i += 1
    while True:
        if i >= len(src):
            raise KotlinReadError("unterminated string literal")
        ch = src[i]
        if ch == "\\":
            esc = src[i + 1]
            if esc not in _ESCAPES:
                raise KotlinReadError(f"unsupported escape \\{esc}")
            out.append(_ESCAPES[esc])
            i += 2
            continue
        if ch == "$" and (i + 1 < len(src)) and (src[i + 1] == "{" or src[i + 1].isalpha()):
            raise KotlinReadError("a string template is not a constant")
        if ch == '"':
            return "".join(out), i + 1
        out.append(ch)
        i += 1


def trim_indent(raw: str) -> str:
    """Kotlin's `String.trimIndent()`: drop a blank first and last line,
    remove the smallest common indent of the non-blank lines, and turn a
    blank line into an empty one."""
    lines = raw.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    cut = min(indents) if indents else 0
    return "\n".join("" if not line.strip() else line[cut:] for line in lines)


def _read_raw(src: str, i: int) -> tuple[str, int]:
    """`src[i:i+3]` is the opening triple quote; `.trimIndent()` must follow."""
    end = src.find('"""', i + 3)
    if end == -1:
        raise KotlinReadError("unterminated raw string")
    body = src[i + 3 : end]
    if "$" in body:
        raise KotlinReadError("a raw string with a `$` may be a template")
    i = end + 3
    if not src.startswith(".trimIndent()", i):
        raise KotlinReadError("a raw string is only read when it is .trimIndent()-ed")
    return trim_indent(body), i + len(".trimIndent()")


def _declaration(src: str, name: str) -> int:
    modifiers = r"(?:(?:internal|private)\s+)?(?:const\s+)?"
    match = re.search(rf"(?m)^\s*{modifiers}val\s+{re.escape(name)}\s*=", src)
    if match is None:
        raise KotlinReadError(f"no `val {name} =` in the file")
    return match.end()


def evaluate_expression(src: str, i: int, resolve) -> tuple[str, int]:
    """Evaluate `term (+ term)*` from `src[i]`. Returns (value, end index)."""
    parts = []
    while True:
        i = _skip_space_and_comments(src, i)
        if src.startswith('"""', i):
            value, i = _read_raw(src, i)
        elif src.startswith('"', i):
            value, i = _read_literal(src, i)
        else:
            ident = re.match(r"[A-Za-z_][A-Za-z0-9_]*", src[i:])
            if ident is None:
                raise KotlinReadError(f"cannot read a term at {src[i : i + 40]!r}")
            value = resolve(ident.group(0))
            i += ident.end()
        parts.append(value)
        after = _skip_space_and_comments(src, i)
        if src.startswith("+", after):
            i = after + 1
            continue
        return "".join(parts), i


def read_constant(path: Path, name: str) -> str:
    """The value of `val name = ...` in `path`, other constants resolved."""
    src = path.read_text(encoding="utf-8")
    seen: set[str] = set()

    def resolve(other: str) -> str:
        if other in seen:
            raise KotlinReadError(f"{other} refers to itself")
        seen.add(other)
        value, _ = evaluate_expression(src, _declaration(src, other), resolve)
        return value

    return resolve(name)


def read_named_argument(path: Path, object_name: str, argument: str) -> str:
    """`argument = <string expression>` inside `val object_name = Persona(...)`."""
    src = path.read_text(encoding="utf-8")
    start = _declaration(src, object_name)
    match = re.compile(rf"\b{re.escape(argument)}\s*=").search(src, start)
    if match is None:
        raise KotlinReadError(f"{object_name} has no `{argument} =`")

    def resolve(other: str) -> str:
        raise KotlinReadError(f"{object_name}.{argument} refers to {other}, not a literal")

    value, _ = evaluate_expression(src, match.end(), resolve)
    return value
