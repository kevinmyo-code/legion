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
- A broad question about the whole system (`is_overview`) gets
  `OVERVIEW_PICKS` first: a curated boost, stated here because it is one.
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

# A broad question about the whole system - "Explain to me the codebase",
# "how does it work", "architecture" - gets the overview passages first, in
# this order: the README's at-a-glance table and architecture sketch (every
# client, the Django engine, the head unit), then C1 and C2. Audit 2026-11-03:
# BM25 alone ranked ADR 0040's web-vs-Android split first for "codebase",
# which left out the engine and the head unit. (path, heading) of each;
# a pick the corpus no longer has is skipped.
OVERVIEW_PICKS = (
    ("README.md", "LEGION > At a glance"),
    ("README.md", "LEGION > Architecture"),
    ("docs/architecture/c1-context.md", "C1: System context"),
    ("docs/architecture/c2-containers.md", "C2: Containers"),
)
# Words (after stemming) that name the system as a whole and nothing in it. A
# question made only of these, with at least one that is not "legion", is a
# broad one.
BROAD_WORDS = frozenset(
    _w
    for _w in """
    legion codebase code base architecture architectural overview system structure built build
    design whole overall big picture high level project repo repository stack tech technical
    app application fit together put piece part component components main
    walk through me us give quick tour rundown summary summarize run down high-level s its
    """.split()
)
# "How does it work?" is all stopwords; asked of LEGION, it is the broadest
# question there is.
BROAD_PHRASE = re.compile(r"\bhow\b.*\b(work|works|built|fits?)\b")

# Two questions recruiters ask that BM25 ranks badly (audit 2026-11-03): "how
# does the voice pipeline work" hit a dated carve snapshot, and "how does he
# keep the AI from making up numbers" hit nothing about the gate, which is the
# answer. Each topic is (pattern on the lowercased query, picks). The first
# topic that matches puts its picks first, as `OVERVIEW_PICKS` does.
TOPIC_PICKS: tuple[tuple[re.Pattern, tuple[tuple[str, str], ...]], ...] = (
    (
        re.compile(
            r"\bvoice\b.*\b(pipeline|loop|work|works|flow|architecture|stack|built|latency)\b"
            r"|\bgemini live\b|\bspeech pipeline\b"
        ),
        (
            ("docs/architecture/c3-voice-loop.md", "C3: The voice loop"),
            ("docs/architecture/c3-voice-loop.md", "C3: The voice loop > Where each named piece actually sits"),
            ("docs/architecture/c3-voice-loop.md", "C3: The voice loop > Two constraints that bite"),
        ),
    ),
    (
        re.compile(
            r"\b(hallucinat\w*|fabricat\w*|making up|make up|made up|makes up)\b"
            r"|\b(accura\w*|trust\w*|reliab\w*|correct\w*|verif\w*)\b.*\b(numbers?|figures?|data|money|amounts?)\b"
        ),
        (
            ("docs/architecture/c3-ingestion.md", "C3: Ingestion and the gate"),
            (
                "docs/architecture/c3-ingestion.md",
                "C3: Ingestion and the gate > The gate, stated exactly (still binding, pantry included)",
            ),
            ("docs/adr/0006-reconciliation-gate.md", "6. LLM extraction, allowed only behind a deterministic gate > Decision"),
            ("docs/adr/0031-speech-honesty-clause.md", "31. The assistant may not assert an outcome it did not observe > Decision"),
        ),
    ),
)


def topic_picks(query: str) -> tuple[tuple[str, str], ...]:
    """The curated picks for the first topic `query` names, or none."""
    q = query.lower()
    return next((picks for pattern, picks in TOPIC_PICKS if pattern.search(q)), ())


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


def is_overview(query: str) -> bool:
    """True for a broad question about the whole system (see `OVERVIEW_PICKS`)."""
    words = set(tokens(query))
    if not words:
        return bool(BROAD_PHRASE.search(query.lower()))
    broad = {_stem(w) for w in BROAD_WORDS}
    return words <= broad and not words <= OVERVIEW_WORDS


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
    curated = OVERVIEW_PICKS if is_overview(query) else topic_picks(query)
    if curated:
        # The curated picks first, then whatever BM25 found, without repeats.
        picks: list[int] = []
        for path, heading in curated:
            found = next(
                (
                    i
                    for i, c in enumerate(index.chunks)
                    if c["path"] == path and c["heading"] == heading
                ),
                None,
            )
            if found is not None:
                picks.append(found)
        rest = [(score, i) for score, i in scored if i not in picks]
        top = rest[0][0] if rest else 0.0
        scored = [(top + len(picks) - k, i) for k, i in enumerate(picks)] + rest

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
