"""Generate `docs_corpus.json` from the repo's public design docs (`legion_docs`).

    python tools/build_docs_corpus.py          # write (repo root; calls this)
    python server/assistant/gen_docs_corpus.py --check  # exit 1 if the copy is stale

Kevin, 2026-11-03: "yes legion docs tool so they can ask technical
questions". The engine image is built from `server/` alone, so the repo-root
docs are not in it; this script copies the chosen ones, chunked by Markdown
heading, into one JSON file inside `server/` that the image does carry.

**The corpus is an allowlist, not a walk.** `SOURCES` names every file that
may be read, by pattern from the repo root, and nothing else is opened:
never `memory/`, `.scratch/`, `vault/`, `deploy/` or any `.env`.
`tests/test_legion_docs.py` fails on a chunk from anywhere else, and on a
committed copy that differs from what this script makes now.

Stand-alone on purpose, as `gen_shared_clauses.py` is: no Django, no
third-party import, so it runs from a bare checkout.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
OUTPUT = HERE / "docs_corpus.json"

# Repo-root-relative globs, in order. `docs/adr/adr-index.md` is generated
# from the ADRs' own frontmatter and says nothing they do not.
SOURCES: tuple[str, ...] = (
    "README.md",
    "server/README.md",
    "docs/glossary.md",
    "docs/working-with-agents.md",
    "docs/architecture/*.md",
    "docs/adr/[0-9]*.md",
)
ALLOWED_PREFIXES: tuple[str, ...] = (
    "README.md",
    "server/README.md",
    "docs/glossary.md",
    "docs/working-with-agents.md",
    "docs/architecture/",
    "docs/adr/",
)

# A heading section longer than this is split at paragraph breaks, so one long
# section of a dated carve log cannot fill a whole answer on its own.
CHUNK_CHARS = 2400

_HEADING = re.compile(r"^(#{1,4})\s+(.*?)\s*#*\s*$")
_FENCE = re.compile(r"^\s*(```|~~~)")
_WIKILINK = re.compile(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?\]\]")
_ADR_FILE = re.compile(r"^(\d{4})-")
_DATED = re.compile(r"(\d{4}-\d{2}-\d{2})\.md$")


def source_paths(root: Path = REPO_ROOT) -> list[Path]:
    paths: set[Path] = set()
    for pattern in SOURCES:
        paths.update(p for p in root.glob(pattern) if p.is_file())
    out = sorted(paths, key=lambda p: p.relative_to(root).as_posix())
    for p in out:
        rel = p.relative_to(root).as_posix()
        if not rel.startswith(ALLOWED_PREFIXES):  # a glob can never widen the allowlist
            raise SystemExit(f"{rel} is outside the docs allowlist")
    return out


def _frontmatter(text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---\n"):
        return {}, text
    end = text.find("\n---\n", 4)
    if end == -1:
        return {}, text
    meta: dict[str, str] = {}
    for line in text[4:end].splitlines():
        key, sep, value = line.partition(":")
        if sep and key and not key.startswith((" ", "-")):
            meta[key.strip()] = value.strip().strip('"')
    return meta, text[end + 5 :]


def _plain(text: str) -> str:
    """Wikilinks to their words; runs of blank lines to one."""
    text = _WIKILINK.sub(lambda m: m.group(2) or m.group(1), text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _sections(body: str) -> list[tuple[list[str], str]]:
    """(heading trail, text) per heading, headings inside code fences ignored."""
    out: list[tuple[list[str], str]] = []
    trail: list[tuple[int, str]] = []
    lines: list[str] = []
    fenced = False

    def close():
        text = "\n".join(lines).strip()
        if text:
            out.append(([h for _, h in trail], text))
        lines.clear()

    for line in body.splitlines():
        if _FENCE.match(line):
            fenced = not fenced
        match = None if fenced else _HEADING.match(line)
        if match:
            close()
            level = len(match.group(1))
            trail = [(lv, h) for lv, h in trail if lv < level]
            trail.append((level, _plain(match.group(2))))
        else:
            lines.append(line)
    close()
    return out


def _split(text: str) -> list[str]:
    if len(text) <= CHUNK_CHARS:
        return [text]
    parts: list[str] = []
    current = ""
    for para in text.split("\n\n"):
        while len(para) > CHUNK_CHARS:  # one huge paragraph or table: cut at a line
            cut = para.rfind("\n", 0, CHUNK_CHARS)
            cut = cut if cut > 0 else CHUNK_CHARS
            if current:
                parts.append(current)
                current = ""
            parts.append(para[:cut].strip())
            para = para[cut:].strip()
        if current and len(current) + 2 + len(para) > CHUNK_CHARS:
            parts.append(current)
            current = para
        else:
            current = f"{current}\n\n{para}" if current else para
    if current:
        parts.append(current)
    return [p for p in parts if p]


def _adr(rel: str, meta: dict[str, str], title: str) -> dict | None:
    if not rel.startswith("docs/adr/"):
        return None
    number = _ADR_FILE.match(Path(rel).name)
    if number is None:
        return None
    by = [b.strip() for b in meta.get("superseded-by", "").strip("[]").split(",") if b.strip()]
    return {
        "number": number.group(1),
        "title": re.sub(r"^\d+\.\s*", "", title),
        "status": meta.get("status", "unknown"),
        "superseded_by": [b[:4] for b in by],
        "decided": meta.get("decided", ""),
    }


def chunks_for(path: Path, root: Path = REPO_ROOT) -> list[dict]:
    rel = path.relative_to(root).as_posix()
    meta, body = _frontmatter(path.read_text(encoding="utf-8"))
    sections = _sections(body)
    title = next((trail[0] for trail, _ in sections if trail), meta.get("title", rel))
    adr = _adr(rel, meta, title)
    dated = _DATED.search(rel)
    out: list[dict] = []
    for trail, text in sections:
        heading = " > ".join(trail) if trail else title
        for part in _split(_plain(text)):
            chunk = {
                "id": f"{rel}#{len(out)}",
                "path": rel,
                "heading": heading,
                "text": part,
            }
            if adr is not None:
                chunk["adr"] = adr
            if dated:
                chunk["snapshot"] = dated.group(1)
            out.append(chunk)
    return out


def build(root: Path = REPO_ROOT) -> dict:
    files = source_paths(root)
    chunks = [c for p in files for c in chunks_for(p, root)]
    return {
        "generated_by": "server/assistant/gen_docs_corpus.py (python tools/build_docs_corpus.py)",
        "files": [p.relative_to(root).as_posix() for p in files],
        "chunks": chunks,
    }


def render(root: Path = REPO_ROOT) -> str:
    """The file's exact text: one chunk per line so a diff names the section."""
    corpus = build(root)
    head = {k: v for k, v in corpus.items() if k != "chunks"}
    lines = [json.dumps(c, ensure_ascii=False, sort_keys=True) for c in corpus["chunks"]]
    head_text = json.dumps(head, ensure_ascii=False, sort_keys=True)[:-1]
    return head_text + ',"chunks":[\n' + ",\n".join(lines) + "\n]}\n"


def write() -> Path:
    with OUTPUT.open("w", encoding="utf-8", newline="\n") as f:
        f.write(render())
    return OUTPUT


def main(argv: list[str]) -> int:
    if "--check" in argv:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current != render():
            print(f"{OUTPUT.name} is stale; run: python tools/build_docs_corpus.py")
            return 1
        print(f"{OUTPUT.name} is current")
        return 0
    path = write()
    corpus = json.loads(path.read_text(encoding="utf-8"))
    print(
        f"wrote {path.relative_to(REPO_ROOT).as_posix()}: {len(corpus['files'])} files, "
        f"{len(corpus['chunks'])} chunks, {path.stat().st_size} bytes"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
