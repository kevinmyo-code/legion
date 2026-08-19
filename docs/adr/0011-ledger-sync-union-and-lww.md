---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 11. Ledger tables sync; the append-only blocker was false

## Standing

ACCEPTED. Recorded partly as a caution about how a false blocker survived.

## Context

Three blockers were on record: Drive has no compare-and-swap, last-write-wins will silently lose rows, and sync must therefore become append-only. Reading `sync/SyncEngine.kt` and `sync/SyncMerge.kt` contradicted all three. Append-only was already in use by eight tables.

## Decision

`ledger_transactions` syncs `Mode.UNION` on `syncId`, because a committed transaction is immutable. `ingested_files` syncs `Mode.LWW` on `driveFileId`, because it is a state machine that legitimately changes.

## Consequences

- A blocker recorded from doc comments outlived the code that disproved it, for months. This is L24 in `memory/library/lessons.md`: grep the premise before drafting.
- The real remaining blocker is much narrower: after `MAX_CONFLICT_RETRIES` the error handling is silent, because no crash reporter is wired.
- Every claim here is traced from source and none is tested, because `sync/` has never run in this app.
