"""Deterministic search over LEGION's design docs (`legion_docs`).

Kevin, 2026-11-03: "yes legion docs tool so they can ask technical
questions". A member, Claude Code over `/mcp`, or a recruiter's visit can ask
how LEGION is built - why Django, how the reconciliation gate works, how
tenancy is enforced - and the answer comes from the repo's own docs.

**Keyword retrieval, on purpose.** BM25 over `docs_corpus.json`
(`gen_docs_corpus.py` builds it from an allowlist of public docs): no vector
store, no embedding call, no model in the loop. The same query returns the
same passages every time, which is what lets a test pin "the reconciliation
gate question finds ADR 0006".

Ranking adjustments, each stated so it can be argued with:

- A chunk's header (file path, heading trail, ADR number, title, status) is
  weighted `HEADER_WEIGHT` times its body, so a question naming a decision
  finds that decision's ADR before a log that mentions it in passing.
- A superseded ADR scores `SUPERSEDED_FACTOR` of its raw score, and a dated
  snapshot (`docs/architecture/*-2026-08-24.md` and its siblings) scores
  `SNAPSHOT_FACTOR`: both are history, and the current design should come
  first when both match. Neither is hidden; a result says which it is.
- At most `PER_FILE` passages come from one file, so one long document
  cannot crowd out the others.
"""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from dataclasses import dataclass
from functools import cache
from pathlib import Path

CORPUS = Path(__file__).resolve().parent / "docs_corpus.json"

LIMIT_DEFAULT = 4
LIMIT_MAX = 8
PER_FILE = 2
# What one result may carry, in characters (about 1,600 tokens). A passage
# longer than PASSAGE_CHARS is cut at a paragraph or sentence and marked; a
# passage that would push the total past BUDGET_CHARS is dropped whole.
PASSAGE_CHARS = 1800
BUDGET_CHARS = 6400

HEADER_WEIGHT = 3
SUPERSEDED_FACTOR = 0.6
SNAPSHOT_FACTOR = 0.8
K1 = 1.2
B = 0.75

STOPWORDS = frozenset(
    """
    a an and are as at be been but by can could do does did for from had has have how i if in
    into is it its me my of on or our so than that the their them then there these
    they this those to was we were what when where which who whom why will with would you your
    about tell explain describe work works working use used using get got
    """.split()
)

# A question whose only subject is LEGION itself gets the README's overview.
OVERVIEW_WORDS = frozenset({"legion"})
OVERVIEW_PATH = "README.md"

_WORD = re.compile(r"[a-z0-9]+")
# A light suffix strip, so "tenancy" meets "tenants" and "gated" meets "gate".
SUFFIXES = (
    ("ancy", "ant"),
    ("ency", "ent"),
    ("ies", "y"),
    ("ing", ""),
    ("ed", ""),
    ("es", ""),
    ("s", ""),
)


def _stem(word: str) -> str:
    if word.isdigit():
        return word.lstrip("0") or "0"
    for suffix, repl in SUFFIXES:
        if len(word) > len(suffix) + 3 and word.endswith(suffix):
            return word[: -len(suffix)] + repl
    return word


def tokens(text: str) -> list[str]:
    return [_stem(w) for w in _WORD.findall(text.lower()) if w not in STOPWORDS]


@dataclass(frozen=True)
class Passage:
    path: str
    heading: str
    text: str
    score: float
    adr: dict | None
    snapshot: str | None
    trimmed: bool

    @property
    def label(self) -> str:
        where = f"{self.path} > {self.heading}"
        if self.adr:
            status = self.adr["status"]
            if status == "superseded":
                by = ", ".join(f"ADR {n}" for n in self.adr["superseded_by"]) or "a later ADR"
                status = f"SUPERSEDED by {by}"
            return f"ADR {self.adr['number']} ({status}): {self.adr['title']} - {where}"
        if self.snapshot:
            return f"{where} (dated snapshot, {self.snapshot}; history, not necessarily current)"
        return where

    def as_dict(self) -> dict:
        out = {"path": self.path, "heading": self.heading, "score": round(self.score, 3)}
        if self.adr:
            out["adr"] = self.adr["number"]
            out["status"] = self.adr["status"]
            if self.adr["superseded_by"]:
                out["superseded_by"] = self.adr["superseded_by"]
        if self.snapshot:
            out["snapshot"] = self.snapshot
        return out


def _header(chunk: dict) -> str:
    parts = [chunk["path"], chunk["heading"]]
    adr = chunk.get("adr")
    if adr:
        parts += ["adr", adr["number"], adr["title"], adr["status"]]
    return " ".join(parts)


@dataclass(frozen=True)
class _Index:
    chunks: list[dict]
    tfs: list[Counter]
    lengths: list[int]
    avg_length: float
    df: Counter


@cache
def _index() -> _Index:
    chunks = json.loads(CORPUS.read_text(encoding="utf-8"))["chunks"]
    tfs, lengths, df = [], [], Counter()
    for chunk in chunks:
        tf = Counter(tokens(chunk["text"]))
        for word, n in Counter(tokens(_header(chunk))).items():
            tf[word] += n * HEADER_WEIGHT
        tfs.append(tf)
        lengths.append(sum(tf.values()))
        df.update(tf.keys())
    return _Index(chunks, tfs, lengths, sum(lengths) / max(len(lengths), 1), df)


def _trim(text: str) -> tuple[str, bool]:
    if len(text) <= PASSAGE_CHARS:
        return text, False
    head = text[:PASSAGE_CHARS]
    cut = head.rfind("\n\n")
    if cut < PASSAGE_CHARS // 2:
        cut = max(head.rfind(". "), head.rfind(".\n"))
        cut = cut + 1 if cut >= PASSAGE_CHARS // 2 else PASSAGE_CHARS
    return head[:cut].rstrip() + " [...]", True


def search(query: str, limit: int = LIMIT_DEFAULT) -> list[Passage]:
    """The best passages for `query`, best first, within `BUDGET_CHARS`.
    Empty when no searchable word in the query appears anywhere."""
    words = set(tokens(query))
    index = _index()
    n = len(index.chunks)
    scored: list[tuple[float, int]] = []
    for i, tf in enumerate(index.tfs):
        score = 0.0
        for word in words:
            f = tf.get(word)
            if not f:
                continue
            idf = math.log(1 + (n - index.df[word] + 0.5) / (index.df[word] + 0.5))
            norm = f + K1 * (1 - B + B * index.lengths[i] / index.avg_length)
            score += idf * f * (K1 + 1) / norm
        if score <= 0:
            continue
        chunk = index.chunks[i]
        adr = chunk.get("adr")
        if adr and adr["status"] == "superseded":
            score *= SUPERSEDED_FACTOR
        if chunk.get("snapshot"):
            score *= SNAPSHOT_FACTOR
        scored.append((score, i))
    if words and words <= OVERVIEW_WORDS:
        # "What is LEGION?": every doc names LEGION, so BM25 has nothing to
        # rank on. The README is the overview; give it in its own order.
        scored = [
            (1.0 - i / n, i) for i, c in enumerate(index.chunks) if c["path"] == OVERVIEW_PATH
        ]
    scored.sort(key=lambda s: (-s[0], index.chunks[s[1]]["id"]))

    out: list[Passage] = []
    per_file: Counter = Counter()
    used = 0
    for score, i in scored:
        if len(out) >= max(1, min(limit, LIMIT_MAX)):
            break
        chunk = index.chunks[i]
        if per_file[chunk["path"]] >= PER_FILE:
            continue
        text, trimmed = _trim(chunk["text"])
        passage = Passage(
            chunk["path"], chunk["heading"], text, score, chunk.get("adr"),
            chunk.get("snapshot"), trimmed,
        )  # fmt: skip
        cost = len(passage.label) + len(text) + 8
        if out and used + cost > BUDGET_CHARS:
            break
        out.append(passage)
        per_file[chunk["path"]] += 1
        used += cost
    return out
