---
title: Docs
tags: [docs]
---

# Docs

Architecture, standing decisions, and vocabulary. Written for Kevin and for the agents, not for a
stranger. `README.md` at the repo root is the public face; this is the working reference.

## What is here

| | Answers |
|---|---|
| [[c1-context\|Architecture: context]] | What LEGION talks to, and what it deliberately does not |
| [[c2-containers\|Architecture: containers]] | What runs inside the one Android process |
| [[c3-voice-loop\|Architecture: voice loop]] | One utterance end to end, and which piece sits where |
| [[c3-ingestion\|Architecture: ingestion]] | The reconciliation gate, drawn |
| [[c3-data\|Architecture: data]] | Room, the entity roster, the 28 controllers, sync |
| [[adr-index\|Decisions (ADRs)]] | What is binding right now, and what superseded what |
| [[glossary]] | The vocabulary, with pointers to where each term is actually defined |

## What is NOT here, and where it lives instead

This repo has six documentation surfaces and they do not overlap. Putting a thing in the wrong one
is how a project ends up with two answers to the same question.

| Question | Surface |
|---|---|
| What are the rules? | `CLAUDE.md`. The single source of truth. Docs link into it, never restate it |
| What is happening right now? | `memory/MEMORY.md` |
| What happened, and when? | `memory/library/` shelves, chiefly `memory/library/decisions.md` |
| What is planned? | `.scratch/*/map.md` and its tickets, surfaced at `vault/Board.md` |
| What compiles and what is tested? | `README.md` at the repo root |
| **How does it fit together, and what is binding?** | **here** |

If `CLAUDE.md` and a doc in here disagree, **CLAUDE.md wins** and the doc is a bug. Report it.

## The two decision stores

An **ADR** says what is binding now. The **log** says what happened when.

- `docs/adr/NNNN-slug.md` - one file per standing decision. Superseded ADRs keep their original
  text and get `status: superseded`, so you can see what was believed before and why it changed.
- `memory/library/decisions.md` - append-only, dated, 198 entries, mostly frozen Midnight AI
  history. Every decision still gets an entry here. Only standing ones also get an ADR.

Format and the rule for when to write one: `.claude/skills/domain-modeling/ADR-FORMAT.md`.

## Keeping it honest

L24 in `memory/library/lessons.md` says the repo is ahead of its docs and you must grep the premise
before drafting. That lesson is now a check:

```
python tools/docs_check.py
```

It fails if any source path named in `docs/` no longer exists, if an ADR is missing required
frontmatter, if a supersession link is one-sided, or if a wikilink points at nothing. Run it after
any change that moves or renames code that the docs name.

Diagrams are Mermaid in Markdown, not Canvas. Mermaid renders in Obsidian and on GitHub, and it
diffs in a pull request. The `.canvas` files under `.scratch/` are a different thing: those are
generated ticket dependency graphs, not architecture.
