---
map: memory-and-vault
ticket: "02"
title: "Memory tools for members and the worker"
type: build
status: built
status-detail: "engine built 2026-11-05 on feat/memory-vault-engine, suite green; owed: engine deploy (Kevin), then the phone tickets"
blockers: ["01"]
blocked-by: ["[[01-member-memory-table-and-rest]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Memory tools for members and the worker

## What

Four `EngineTool`s in `server/engine_mcp/tools.py`, so `/mcp` and the web assistant both carry
them:

| Tool | Writes | What it does |
|---|---|---|
| `recall_memory {query?, limit?}` | no (rehearses) | Top matches by the v1 score, each with id, date, kind, shared or private, and the instruction to build on what it returns and call anything else a guess |
| `list_memories {kind?, limit?}` | no | Newest first, for "what do you remember about me" |
| `remember {text, kind?, importance?, durable?, shared?, supersedes?}` | yes | Saves for the caller; source is `claude` on `/mcp`, `web` on the web door |
| `forget_memory {id}` | yes, destructive | Hard delete, said as "deleted for good" |

- This **reverses** engine-mcp ticket 06 sub-question 2 (Kevin 2026-10-02) for v2, by Kevin's
  ruling 2026-11-05 (ADR 0075). The three legacy tables stay excluded from `read_records` and
  `write_record`.
- Recruiters: all four in `RECRUITER_EXCLUDED`, with a test.
- Work laptop: refused by map decision G, with a test.
- Web prompt (`server/assistant/prompt.py` `WEB_FRAME`) stops saying it has no memory.

## Verification

- pytest: each tool's result, the recruiter refusal, the `listed` token refusal, the household
  leak test covers the four tools, the web prompt sentence.
