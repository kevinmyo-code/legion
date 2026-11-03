"""`legion_docs`: how LEGION is built, from its own public docs (Kevin, 2026-11-03).

- The committed corpus is exactly what `gen_docs_corpus.py` makes from the
  repo's docs today (a stale copy fails here), and it holds nothing from
  outside the allowlist: never `memory/`, `.scratch/`, `vault/`, `deploy/`.
- Real interview questions find the ADR that answers them.
- An empty question is refused in words; a question the docs do not cover
  says so; a result never exceeds its budget.

Everything above the last section reads files only; no database.
"""

from __future__ import annotations

import json

import pytest

from assistant import docs_search, gen_docs_corpus
from assistant.docs_search import BUDGET_CHARS, LIMIT_MAX, PASSAGE_CHARS, search
from assistant.surface import RECRUITER_TOOLS, WEB_TOOL_NAMES
from engine_mcp.tools import TOOLS_BY_NAME, run_tool
from tests.test_engine_mcp import call, mcp_on, mcp_read  # noqa: F401 - fixtures

DOCS = gen_docs_corpus.REPO_ROOT / "docs"


def _corpus() -> dict:
    return json.loads(gen_docs_corpus.OUTPUT.read_text(encoding="utf-8"))


# =============================================================================
# The corpus
# =============================================================================


@pytest.mark.skipif(
    not DOCS.is_dir(),
    reason="the repo root's docs are not here (the engine image carries server/ only)",
)
def test_the_committed_corpus_is_current():
    committed = gen_docs_corpus.OUTPUT.read_text(encoding="utf-8")
    assert committed == gen_docs_corpus.render(), (
        "server/assistant/docs_corpus.json is stale; run: python tools/build_docs_corpus.py"
    )


def test_every_chunk_comes_from_the_allowlist():
    corpus = _corpus()
    allowed = gen_docs_corpus.ALLOWED_PREFIXES
    assert corpus["files"] and corpus["chunks"]
    for path in corpus["files"]:
        assert path.startswith(allowed), path
    for chunk in corpus["chunks"]:
        assert chunk["path"] in corpus["files"], chunk["path"]
        assert chunk["text"].strip(), chunk["id"]
    paths = {c["path"] for c in corpus["chunks"]}
    for banned in (".scratch/", "memory/", "vault/", "deploy/", ".env", ".claude/", "CLAUDE.md"):
        assert not any(banned in p for p in paths), banned
    assert "docs/adr/adr-index.md" not in paths


def test_the_generator_never_opens_a_file_outside_the_allowlist(tmp_path):
    """A planted private file next to every allowed one is never read."""
    for rel in (
        "README.md",
        "docs/glossary.md",
        "docs/adr/0001-x.md",
        "docs/architecture/c1.md",
        "server/README.md",
    ):
        (tmp_path / rel).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / rel).write_text("# Public\n\npublic words\n", encoding="utf-8")
    for rel in (
        "memory/MEMORY.md",
        ".scratch/m/issues/01.md",
        "vault/Board.md",
        "deploy/.env",
        "docs/adr/adr-index.md",
        "docs/private.md",
        "CLAUDE.md",
        "TEAM.md",
    ):
        (tmp_path / rel).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / rel).write_text("# Private\n\nkevsecret\n", encoding="utf-8")
    built = gen_docs_corpus.build(tmp_path)
    assert sorted(built["files"]) == [
        "README.md",
        "docs/adr/0001-x.md",
        "docs/architecture/c1.md",
        "docs/glossary.md",
        "server/README.md",
    ]
    assert "kevsecret" not in json.dumps(built)


def test_adr_chunks_carry_number_title_and_status():
    adrs = {c["adr"]["number"]: c["adr"] for c in _corpus()["chunks"] if "adr" in c}
    assert adrs["0057"]["status"] == "accepted"
    assert adrs["0006"]["title"].startswith("LLM extraction")
    assert adrs["0002"]["status"] == "superseded"
    assert adrs["0002"]["superseded_by"] == ["0043"]


def test_no_chunk_exceeds_the_split_size():
    for chunk in _corpus()["chunks"]:
        assert len(chunk["text"]) <= gen_docs_corpus.CHUNK_CHARS, chunk["id"]


# =============================================================================
# Search: the questions an interviewer asks
# =============================================================================


def _top(query: str, n: int = 1) -> list[str]:
    return [p.adr["number"] if p.adr else p.path for p in search(query)[:n]]


@pytest.mark.parametrize(
    ("query", "adr", "within"),
    [
        ("How does the reconciliation gate work?", "0006", 1),
        ("Is Plaid bank data treated as fact?", "0057", 1),
        ("Does Plaid go through the reconciliation gate?", "0057", 2),
        ("ADR 0057", "0057", 1),
        ("ADR 57", "0057", 1),
        ("What can the recruiter role do?", "0068", 1),
        ("Does each recruiter visit get its own household?", "0069", 1),
        ("How is tenancy enforced?", "0045", 2),
        ("Households are tenants", "0045", 1),
        ("Why Django?", "0044", 1),
    ],
)
def test_real_questions_find_their_adr(query, adr, within):
    assert adr in _top(query, within), (query, _top(query, 4))


def test_voice_questions_reach_the_voice_docs():
    hits = [p.path for p in search("how does the voice loop work")]
    assert "docs/architecture/c3-voice-loop.md" in hits


def test_a_superseded_adr_is_labelled_superseded():
    hits = [p for p in search("no Kevin-hosted anything", LIMIT_MAX) if p.adr]
    old = next(p for p in hits if p.adr["number"] == "0002")
    assert "SUPERSEDED by ADR 0043" in old.label
    assert old.as_dict()["superseded_by"] == ["0043"]


def test_search_is_deterministic():
    first = [(p.path, p.heading, p.score) for p in search("reconciliation gate", LIMIT_MAX)]
    assert first == [(p.path, p.heading, p.score) for p in search("reconciliation gate", 8)]


def test_results_stay_inside_the_budget():
    """A query that matches most of the corpus, at the maximum limit."""
    hits = search("the engine household ledger gate voice phone server", LIMIT_MAX)
    assert hits
    used = sum(len(p.label) + len(p.text) + 8 for p in hits)
    assert used <= BUDGET_CHARS
    for p in hits:
        assert len(p.text) <= PASSAGE_CHARS + len(" [...]")
        if p.trimmed:
            assert p.text.endswith("[...]")
    paths = [p.path for p in hits]
    assert all(paths.count(x) <= docs_search.PER_FILE for x in paths)


def test_a_tiny_budget_drops_whole_passages_but_keeps_one(monkeypatch):
    monkeypatch.setattr(docs_search, "BUDGET_CHARS", 10)
    hits = search("reconciliation gate", LIMIT_MAX)
    assert len(hits) == 1


def test_a_question_the_docs_do_not_cover_finds_nothing():
    assert search("kubernetes terraform") == []


# =============================================================================
# The tool: result wording and classification
# =============================================================================


def _run(args: dict):
    return run_tool(None, "legion_docs", args)


@pytest.mark.parametrize("query", ["", "   ", "how does it work?", "?!"])
def test_an_empty_question_is_refused_in_words(query):
    result = _run({"query": query})
    assert result.is_error
    assert result.text.startswith("Nothing was")


def test_an_uncovered_question_says_the_docs_do_not_cover_it():
    result = _run({"query": "kubernetes terraform"})
    assert not result.is_error
    assert "do not cover" in result.text
    assert result.structured["passages"] == []


def test_the_result_cites_and_carries_the_contract():
    result = _run({"query": "how does the reconciliation gate work", "limit": 3})
    assert not result.is_error
    assert "Answer only from these passages" in result.text
    assert "superseded" in result.text.lower()
    assert "[1] ADR 0006 (locked)" in result.text
    assert "docs/adr/0006-reconciliation-gate.md" in result.text
    first = result.structured["passages"][0]
    assert first["adr"] == "0006" and first["status"] == "locked"
    assert len(result.structured["passages"]) <= 3
    assert len(result.text) <= BUDGET_CHARS + 1000  # the contract line on top


def test_limit_is_bounded_by_the_schema():
    assert _run({"query": "gate", "limit": 9}).is_error
    assert _run({"query": "gate", "limit": 0}).is_error
    schema = TOOLS_BY_NAME["legion_docs"].input_schema["properties"]["limit"]
    assert schema["maximum"] == LIMIT_MAX


def test_the_tool_is_on_every_surface():
    assert "legion_docs" in TOOLS_BY_NAME  # /mcp lists the registry
    assert "legion_docs" in WEB_TOOL_NAMES  # members' web assistant
    assert "legion_docs" in RECRUITER_TOOLS  # a recruiter's visit
    assert not TOOLS_BY_NAME["legion_docs"].writes


@pytest.mark.django_db
def test_mcp_answers_from_the_docs(mcp_read):  # noqa: F811 - the fixture
    is_error, text, structured = call(mcp_read, "legion_docs", {"query": "ADR 0068"})
    assert not is_error
    assert "ADR 0068" in text
    assert structured["passages"][0]["adr"] == "0068"
