---
status: accepted
decided: 2026-08-02
decided-by: Kevin
source: "decisions.md 2026-08-02"
tags: [adr]
---

# 13. The file record is work avoidance, not a correctness barrier

## Standing

ACCEPTED.

## Context

`ingested_files` could plausibly be the thing that stops double-counting. It is tempting, and it is the wrong layer: correctness against double-counting belongs to the transaction dedup rule.

## Decision

The file record exists to skip re-processing a statement already seen. Nothing more. Records are never pruned, because absence of a file is not evidence it was deleted.

## Consequences

- `ledger_transactions.sourceFileId` is nullable with no `@ForeignKey`, because the single-file import path has no folder-scan record to point at.
- `contentSha256` is recorded but is not the skip key. It stops duplicates before the parser runs.
- Quarantine is escapable only by explicit user action. Nothing retries a quarantined file automatically.
