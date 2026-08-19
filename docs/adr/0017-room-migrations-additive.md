---
status: locked
decided: 2026-07-31
decided-by: Kevin
source: "CLAUDE.md §5"
tags: [adr]
---

# 17. Room migrations are additive, verbatim, and exported

## Standing

LOCKED. One documented exception, described below.

## Context

There is no installed base, so the schema started at a fresh v1. That is exactly the condition under which people get sloppy with migrations and pay for it later.

## Decision

Copy the generated SQL verbatim. Additive only. `exportSchema = true` with the JSON committed under `app/schemas/`. No destructive fallback on upgrade.

## Consequences

- Widening an enum stored as TEXT is not a migration. The column has no CHECK constraint, so adding a constant changes no SQL and needs no version bump. Confirm it rather than assume it: read the column's `createSql` and check the schema JSON is byte-unchanged after a kapt run.
- One exception exists, v20 to v21, converting a `cost` REAL column to `costCents` INTEGER. It was permitted only after the column was proven empty first.
- The destructive downgrade fallback was removed on 2026-08-12.
