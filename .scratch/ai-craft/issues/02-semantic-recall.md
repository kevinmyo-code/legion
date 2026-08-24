---
map: ai-craft
ticket: "02"
title: "Semantic recall: embeddings in a Room column"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Semantic recall: embeddings in a Room column

## Question

Embed on write, cosine on read, no vector DB. Gemini embedding endpoint on Kevin's key.

1. Where: `companion_memories` recall (the live gap: "that thai place i liked" fails on keywords)
   and engine `records` search (query_records' searchText is literal). Additive columns
   (BLOB/float array + model-version tag), verbatim migration, migration test.
2. Brute-force cosine in Kotlin at read time; personal scale makes this milliseconds. No index.
3. Degrade gracefully: no network or no key = keyword recall exactly as today, said in no words
   (silent fallback is fine here - recall quality, not a fact claim).
4. Backfill existing rows once, idempotent, completion-flagged, folding every failure (the wave
   lessons apply).
5. Model-version pinning: vectors tagged with the embedding model id; a model change invalidates
   and re-embeds lazily. The map's fog note lands here.
