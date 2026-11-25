"""Check (g): hand-written page scripts parse.

A syntax error in a plain `<script>` block drops the whole block in the browser, the page keeps
rendering, and nothing in any Python or Kotlin suite sees it. It happened twice: `static/voice.js`
on a branch reported green (Midcon, 2026-11-17), and a tree-wide rename that turned an object key
into two words inside a `.scratch` prototype (2026-11-24). AGENT-RULES already says every repo with
hand-written JS runs `node --check` on it in its own suite; this is that rule for the research
prototypes and the Pages site, where no suite was looking.

What it reads, in the files a change TOUCHES (gate mode) or the whole tree (`--all`):
  FAIL  an inline `<script>` block in a `.html` under `.scratch/` or `docs/` that `node --check`
        rejects, and a `type=...json` block that is not valid JSON;
  FAIL  a standalone `.js` / `.mjs` under those two roots.
A block with `src=` is a reference, not source, and is skipped; so is an empty one. `type=module`
is checked as a module (`.mjs`), anything else as a classic script.

With no `node` on PATH the check WARNs and names every block it could not parse, so a missing
toolchain reads as "unchecked", never as a pass.
"""

from __future__ import annotations

import json
import re
import subprocess
import tempfile
from pathlib import Path

from common import FAIL, WARN, Finding
from gitdiff import Change

NAME = "inline-scripts"
ROOTS = (".scratch/", "docs/")
SCRIPT = re.compile(r"<script([^>]*)>(.*?)</script>", re.S | re.I)
TYPE = re.compile(r"""type\s*=\s*["']?([^"'\s>]+)""", re.I)


def _in_scope(path: str) -> bool:
    return path.startswith(ROOTS) and path.lower().endswith((".html", ".htm", ".js", ".mjs"))


def _line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def _node_check(source: str, module: bool) -> str | None:
    """None when it parses, the first line of node's complaint when it does not,
    and "" when node itself is missing."""
    suffix = ".mjs" if module else ".js"
    with tempfile.NamedTemporaryFile("w", suffix=suffix, encoding="utf-8", delete=False) as fh:
        fh.write(source)
        tmp = fh.name
    try:
        r = subprocess.run(["node", "--check", tmp], capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
    except (OSError, FileNotFoundError):
        return ""
    finally:
        Path(tmp).unlink(missing_ok=True)
    if r.returncode == 0:
        return None
    out = (r.stderr or r.stdout).strip().splitlines()
    for line in out:
        if "SyntaxError" in line or "Error" in line:
            return line.strip()
    return out[-1].strip() if out else "node --check failed"


def _blocks(path: str, text: str):
    """(line number, source, is_module, is_json) for every checkable script in one file."""
    if path.lower().endswith((".js", ".mjs")):
        yield 1, text, path.lower().endswith(".mjs"), False
        return
    for m in SCRIPT.finditer(text):
        attrs, body = m.group(1), m.group(2)
        if "src=" in attrs.lower() or not body.strip():
            continue
        t = TYPE.search(attrs)
        typ = (t.group(1).lower() if t else "")
        yield _line_of(text, m.start(2)), body, typ == "module", "json" in typ


def check(change: Change) -> list[Finding]:
    out: list[Finding] = []
    for path, letter in sorted(change.status.items()):
        if letter == "D" or not _in_scope(path):
            continue
        text = change.read(path)
        if text is None:
            continue
        for line, source, module, is_json in _blocks(path, text):
            if is_json:
                try:
                    json.loads(source)
                except ValueError as e:
                    out.append(Finding(FAIL, NAME, path, line, f"script block is not valid JSON: {e}"))
                continue
            problem = _node_check(source, module)
            if problem == "":
                out.append(Finding(WARN, NAME, path, line, "not parsed: no node on PATH, so this block is unchecked"))
            elif problem is not None:
                out.append(Finding(FAIL, NAME, path, line, f"script block does not parse: {problem}"))
    return out


def count(change: Change) -> tuple[int, int]:
    """(files in scope, checkable blocks) over whatever the change holds."""
    files = blocks = 0
    for path, letter in sorted(change.status.items()):
        if letter == "D" or not _in_scope(path):
            continue
        text = change.read(path)
        if text is None:
            continue
        files += 1
        blocks += sum(1 for _ in _blocks(path, text))
    return files, blocks
