---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §4 rule 3"
tags: [adr]
---

# 7. Money is Long cents, never Double

## Standing

LOCKED. The canonical example of a deliberate deviation in this repo.

## Context

`BuildEntry` and `ServiceRecord` store cost as `Double`. They are a personal spend log and nobody is checking them against anything.

## Decision

Ledger and pantry money is `Long` minor units. This is a deliberate deviation from the neighbouring convention in the same database.

## Consequences

- The gate in [[0006-reconciliation-gate]] turns on `actualTotal == statedTotal`, an exact equality that binary floating point breaks.
- `data/local/LedgerTransaction.kt` carries a doc comment explaining this, specifically so nobody tidies it into a `Double` for consistency.
- Any new money field anywhere near ingestion inherits this without discussion.
