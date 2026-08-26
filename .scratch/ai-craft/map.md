---
map: ai-craft
title: "Map: AI craft dividends - evals, semantic recall, durable jobs"
charted: 2026-08-24
charted-by: "Kevin + Fable"
effort: "`.scratch/ai-craft/`"
tickets: 4
open: 3
status: open
tags: [map]
---
# Map: AI craft dividends - evals, semantic recall, durable jobs

## Destination

**Three capabilities lifted from the wider AI ecosystem, shipped where they genuinely pay:** an
on-demand eval harness that turns prompt-rule obedience into numbers per prompt version; embedding
-backed recall for memories and records; durable, checkpointed background jobs. Ruled at charting
(Kevin, 2026-08-24): vector-DB infrastructure is NEVER wanted - embeddings live in a Room column,
brute-force cosine, personal scale. Execution in scope; evals first.

## Notes

**Domain:** LEGION (CLAUDE.md rules bind; BYO key, no backend, on-device). Evals run ON DEMAND on
Kevin's key, never CI - key spend is his. Prior art to reuse: ticket 07's clerk prototype
(`.scratch/aspect-engine/research/clerk-prototype/`) is the eval shape; the hands-and-senses doc
-vault research already ruled RAG/chunking out at LEGION's document sizes.

## Decisions so far

- [The eval harness](issues/01-eval-harness.md) - shipped and run the same night; four suites at
  100 percent, tone_judge WEAK at 73 on a real clause-(d) ambiguity flagged for Kevin.

## Not yet specified

- Whether eval scores gate anything (a pre-merge prompt-change checklist?) or stay advisory.
- Embedding model/version pinning and what happens to stored vectors when the model changes.

## Out of scope

- Vector database infrastructure (charter ruling).
- Agent frameworks. LEGION's orchestrator/subagent/clerk patterns stay hand-rolled.
- Fine-tuning anything.
