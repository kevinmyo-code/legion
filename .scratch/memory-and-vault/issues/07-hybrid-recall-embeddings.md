---
map: memory-and-vault
ticket: "07"
title: "Hybrid recall: embeddings"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Hybrid recall: embeddings

## What

Map decision A's next step.

- Embed each memory on write with Gemini (`gemini-embedding-2`, household key); store it in
  `embedding real[]` plus the model name. Cost is cents a month (estimate, research notes).
- If `select 1 from pg_available_extensions where name = 'vector'` holds, a guarded migration adds
  a `vector` column and an index; otherwise cosine runs in Python over the member's rows, which is
  fast at this size.
- Recall blends cosine into the relevance term, the term the phone built to swap
  (`AriaBrain.kt:432`); lexical stays the fallback when the key or the call fails.
- Backfill command for existing rows.

## Verification

- pytest with a fake embedder: a paraphrase recalls a memory lexical misses; no key falls back to
  lexical.
