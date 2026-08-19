#!/usr/bin/env python3
"""Fail when the documentation has drifted away from the code.

L24 says the repo is ahead of its docs and you must grep the premise before
drafting. This turns that lesson into a check rather than a hope. It verifies:

1. Every source path named in `docs/` still exists on disk.
2. Every ADR carries the frontmatter `ADR-FORMAT.md` requires.
3. Every `supersedes` / `superseded-by` link resolves, and both ends agree.
4. Every wikilink in `docs/` resolves to a real note in the vault.
5. Code fences are balanced, so a stray fence cannot swallow a page.

    python tools/docs_check.py            # report and exit nonzero on drift
    python tools/docs_check.py --quiet    # only print failures
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
PKG = ROOT / "app/src/main/java/com/kevin/legion"

# `ai/Personas.kt`, `service/GeminiLiveSession.kt`, `data/local/CarDatabase.kt`.
# The project writes these package-relative, not repo-relative.
PKG_RELATIVE = re.compile(r"^[a-z][a-z0-9]*(?:/[a-z][a-zA-Z0-9]*)*/[A-Z][A-Za-z0-9]*\.kt$")
# `app/src/main/...`, `tools/x.py`, `memory/library/decisions.md`
REPO_RELATIVE = re.compile(r"^(?:app|tools|memory|docs|vault|\.scratch|\.claude)/[\w./*-]+$")
# A bare class name with a line number, e.g. `Personas.kt:159`
WITH_LINE = re.compile(r"^(.*\.(?:kt|py|md|json|kts)):\d+(?:-\d+)?$")

REQUIRED_ADR_KEYS = {"status", "decided", "tags"}
VALID_STATUS = {"accepted", "amended", "superseded", "locked", "proposed"}


def code_spans(text: str) -> list[str]:
    """Backtick spans outside fenced code blocks."""
    out, fenced = [], False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        out.extend(re.findall(r"`([^`\n]+)`", line))
    return out


def resolve(span: str) -> Path | None:
    """Map a documented reference to a path on disk, or None if not a path."""
    m = WITH_LINE.match(span)
    if m:
        span = m.group(1)
    span = span.rstrip("/")
    if PKG_RELATIVE.match(span):
        return PKG / span
    if "NNNN" in span or "<" in span:  # template placeholder, not a real path
        return None
    if REPO_RELATIVE.match(span) and "*" not in span:
        return ROOT / span
    return None


def frontmatter(text: str) -> dict[str, str]:
    if not text.startswith("---\n"):
        return {}
    block = text.split("\n---\n", 1)[0][4:]
    out = {}
    for line in block.split("\n"):
        key, sep, value = line.partition(":")
        if sep and not key.startswith((" ", "-")):
            out[key.strip()] = value.strip()
    return out


def adr_links(value: str) -> list[str]:
    """`[0003-no-llm-extraction, 0004-x]` -> ['0003-no-llm-extraction', ...]"""
    return [s.strip().strip("\"'[]") for s in value.split(",") if s.strip().strip("\"'[]")]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if not DOCS.is_dir():
        print("no docs/ directory", file=sys.stderr)
        return 1

    failures: list[str] = []
    checked_paths = 0

    docs = sorted(DOCS.rglob("*.md"))

    # Obsidian resolves a wikilink by basename, or by vault-relative path when the
    # basename is ambiguous. Fifteen files are called map.md, so the path form is
    # the only thing that works for them. Accept both.
    def indexable():
        for pattern in ("*.md", "*.canvas", "*.base"):
            for f in ROOT.rglob(pattern):
                if ".claude/worktrees" in f.as_posix() or "/build/" in f.as_posix():
                    continue
                yield f

    vault_notes: set[str] = set()
    for f in indexable():
        rel = f.relative_to(ROOT).as_posix()
        vault_notes.add(f.stem)
        vault_notes.add(f.name)
        vault_notes.add(rel)
        vault_notes.add(rel.rsplit(".", 1)[0])

    # Wikilinks are checked across every surface a person clicks, not just docs/.
    linked = docs + sorted((ROOT / "vault").rglob("*.md"))

    # 1 + 5: source paths and fence balance
    for doc in docs:
        text = doc.read_text(encoding="utf-8")
        rel = doc.relative_to(ROOT).as_posix()

        if text.count("\n```") % 2 != 0:
            failures.append(f"{rel}: unbalanced code fence")

        for span in code_spans(text):
            target = resolve(span)
            if target is None:
                continue
            checked_paths += 1
            if not target.exists():
                failures.append(f"{rel}: `{span}` does not exist")



    # 4: wikilinks, across docs/ and vault/
    for doc in linked:
        rel = doc.relative_to(ROOT).as_posix()
        for link in re.findall(r"\[\[([^\]|#]+)", doc.read_text(encoding="utf-8")):
            name = link.strip().rstrip(chr(92)).strip()
            if name and name not in vault_notes and name.removesuffix(".md") not in vault_notes:
                failures.append(f"{rel}: wikilink [[{name}]] resolves to nothing")

    # 2 + 3: ADR frontmatter and supersession integrity
    adr_dir = DOCS / "adr"
    adrs = {p.stem: p for p in sorted(adr_dir.glob("[0-9]*.md"))} if adr_dir.is_dir() else {}
    fms = {}
    for slug, path in adrs.items():
        fm = frontmatter(path.read_text(encoding="utf-8"))
        fms[slug] = fm
        rel = path.relative_to(ROOT).as_posix()
        missing = REQUIRED_ADR_KEYS - fm.keys()
        if missing:
            failures.append(f"{rel}: missing frontmatter {sorted(missing)}")
        if fm.get("status") and fm["status"] not in VALID_STATUS:
            failures.append(f"{rel}: status '{fm['status']}' not in {sorted(VALID_STATUS)}")

    for slug, fm in fms.items():
        rel = adrs[slug].relative_to(ROOT).as_posix()
        for other in adr_links(fm.get("supersedes", "")):
            if other not in fms:
                failures.append(f"{rel}: supersedes unknown ADR '{other}'")
            elif slug not in adr_links(fms[other].get("superseded-by", "")):
                failures.append(f"{rel}: supersedes {other}, but {other} does not name it back")
        for other in adr_links(fm.get("superseded-by", "")):
            if other not in fms:
                failures.append(f"{rel}: superseded-by unknown ADR '{other}'")
            elif fm.get("status") != "superseded":
                failures.append(f"{rel}: has superseded-by but status is '{fm.get('status')}'")

    if not args.quiet:
        print(f"{len(docs)} docs, {len(linked)} linked pages, {checked_paths} source references, {len(adrs)} ADRs")
    if failures:
        print(f"\n{len(failures)} problem(s):")
        for f in failures:
            print(f"  {f}")
        return 1
    if not args.quiet:
        print("no drift")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
