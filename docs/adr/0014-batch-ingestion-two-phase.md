---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 14. Ingestion splits on concurrency binding: fetch parallel, parse serial

## Standing

ACCEPTED.

## Context

A 60-file folder scan mixes two different bottlenecks. Fetching bytes and hashing them is network bound. Parsing, gating and calling the LLM is CPU bound and costs money.

## Decision

Phase 1 fetches and hashes with parallelism 4, spilling to `cacheDir`. Phase 2 parses, gates and calls the LLM strictly serially. Rescan on app open is listing-only: no bytes, no parsing, no spend.

## Consequences

- The exact count of new files is known before a single parse runs, which is what lets the spend gate quote an exact number rather than a worst case. See [[0016-llm-spend-gate-after-deterministic]].
- Peak PdfBox memory stays at one document, which matters on a phone.
- The batch is deliberately **not atomic**. 39 good statements commit even if the 40th quarantines.
