"""Recall's score: the phone's formula, moved to the engine, with two decay classes (ADR 0075).

The phone (`ai/AriaBrain.kt:452-537`, traced 2026-11-05) scores each row as

    recency + importance / 10 + relevance

with recency `0.99 ** hours_since_last_access`, relevance the share of the query's terms the row
contains (lower case, split on anything not a letter or digit, words under three letters and
stopwords dropped), a hard gate that a row with no matching term is never returned when the query
has terms, and the top six taken. An empty query makes relevance 1.0 for every row, so the newest
and most important come back.

Memory v2 keeps all of that and changes one thing: **the decay rate depends on the memory's
`decay_class`** (research notes, "per-category decay"):

- `episodic`: 0.99 an hour, the phone's rate. Half-life about 69 hours.
- `durable`: 0.999 an hour. Half-life about 29 days. An estimate, not a measurement: it is the
  research notes' figure, chosen so a standing fact barely fades against a week of chatter.

Last access is `last_recalled_at`, falling back to `created_at` (the phone's `lastAccessedAt = 0`
rule). Recall rehearses what it returns (`service.recall`), as the phone does.

Lexical only in v1 (map decision A). Embeddings join the relevance term in ticket 07.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime

from member_memory.models import DECAY_DURABLE

RECALL_LIMIT_DEFAULT = 6
RECALL_LIMIT_MAX = 20
# Rows scored per recall, newest first. The phone scans 200; one member's store is small.
SCAN_MAX = 1000

DECAY_PER_HOUR = {DECAY_DURABLE: 0.999}
EPISODIC_DECAY_PER_HOUR = 0.99

RECENCY_WEIGHT = 1.0
IMPORTANCE_WEIGHT = 1.0
RELEVANCE_WEIGHT = 1.0

_SPLIT = re.compile(r"[^a-z0-9]+")
# The phone's list (`AriaBrain.kt:913-917`) in spirit: function words and the words people use to
# ask about memory itself, which would otherwise match every row.
STOPWORDS = frozenset(
    """
    the and for are but not you your yours with this that these those was were have has had
    from what when where which who whom why how about into onto over under than then them they
    their there here his her hers him she its our ours out off all any can could would should
    will just also very much more most some such only own same too did does doing done been
    being remember recall know tell said say says please again ever like want need
    """.split()
)


def terms(text: str) -> list[str]:
    """The query's or a row's terms, in order, deduplicated."""
    seen: dict[str, None] = {}
    for word in _SPLIT.split(text.lower()):
        if len(word) >= 3 and word not in STOPWORDS:
            seen.setdefault(word, None)
    return list(seen)


def relevance(query_terms: list[str], text: str) -> float:
    """Share of the query's terms the text contains. 1.0 for an empty query (the phone's rule)."""
    if not query_terms:
        return 1.0
    lowered = text.lower()
    return sum(1 for term in query_terms if term in lowered) / len(query_terms)


def recency(decay_class: str, last_access: datetime, now: datetime) -> float:
    hours = max(0.0, (now - last_access).total_seconds() / 3600.0)
    rate = DECAY_PER_HOUR.get(decay_class, EPISODIC_DECAY_PER_HOUR)
    return rate**hours


@dataclass(frozen=True)
class Scored:
    memory: object
    score: float
    relevance: float


def score_rows(rows, query: str, now: datetime) -> list[Scored]:
    """Every row that passes the relevance gate, best first. Ties go to the newer row."""
    query_terms = terms(query or "")
    scored = []
    for row in rows:
        rel = relevance(query_terms, row.text)
        if query_terms and rel <= 0:
            continue
        last = row.last_recalled_at or row.created_at
        value = (
            RECENCY_WEIGHT * recency(row.decay_class, last, now)
            + IMPORTANCE_WEIGHT * (row.importance / 10.0)
            + RELEVANCE_WEIGHT * rel
        )
        scored.append(Scored(row, value, rel))
    scored.sort(key=lambda s: (s.score, s.memory.created_at), reverse=True)
    return scored
