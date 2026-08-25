---
status: accepted
decided: 2026-08-25
decided-by: Kevin
supersedes: [0037-the-aspect-engine-is-the-spine]
source: "decisions.md 2026-08-25"
tags: [adr]
---

# 39. Per-aspect typed tables; the generic engine retires

## Standing

ACCEPTED, NOT YET BUILT. The engine is still the spine in running code and remains so until phase 4 of `.scratch/backend-erp/issues/05-migration-path.md` retires it aspect by aspect. Read [[0037-the-aspect-engine-is-the-spine]] for what it does today; it is superseded in direction, not yet in fact.

## Context

[[0037-the-aspect-engine-is-the-spine]] was accepted and device-verified on 2026-08-24, one day before this. It put every domain record behind one generic `records` table with a JSON payload and promoted columns, with schema living in rows rather than in code, so a new aspect never needed a migration.

[[0038-byo-supabase-is-the-system-of-record]] changed what the storage layer has to do. Once several clients write to one Postgres, enforcement can no longer live in whichever client remembers to apply it, and Postgres can only enforce what it has real columns for. A jsonb payload permanently caps referential integrity, CHECK constraints and triggers at whatever the calling code chooses to do.

## Decision

Postgres gets per-aspect real tables with typed columns, foreign keys, CHECKs and RLS. The phone follows the same shape rather than translating between two, so the generic engine retires: `records`, `record_types`, `field_defs`, the generated forms, the generated validation and the computed-field machinery all go.

The Notes `Item` type merges into the Dates `Event` type as part of this, since a due thing is a dated thing and there is one dated record type rather than two.

## Consequences

- Enforcement moves server-side and stops being bypassable by a future consumer surface. That is the argument that won, and it is the same one that put the reconciliation gate's arithmetic in the commit RPC.
- Adding an aspect becomes a Postgres migration plus a Room migration plus hand-written UI, where it used to be a metadata row. The metadata layer stops being the product. This is the accepted cost and it is real.
- 9,518 production lines and 6,367 test lines exist only because the shape is generic. **But the engine retired zero legacy tables**, so most of the Room-side work is repointing writes back to typed tables that still exist rather than building new ones. `ledger_transactions`, `pantry_receipts`, `vehicles`, `service_records` and `places` are the destination, not drop candidates.
- Notes and Dates are the exception: there is no legacy `events` table, because Dates was born engine-native. That target is built new, and it is the largest single step in the arc.
- The xlsx mirror goes with the engine. It was entirely generic-shape dependent, writing a `_definitions` sheet precisely because the schema lived in rows.
- The commit RPC gets much cheaper. Three full-table reads filtered in Kotlin over JSON payloads become ordinary SQL predicates, and `RecordStore`'s per-row fan-out disappears. The generic shape was the reason the atomic commit was expensive to move.
- Deletion is separated from retirement throughout the migration, because every rollback depends on the code deleted at the end still existing during the middle.
