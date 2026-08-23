---
map: aspect-engine
ticket: "16"
title: "Build the engine core"
type: task
status: claimed
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Build the engine core

## Question

Build what tickets 03 and 04 locked. Room v28+ additive migration, verbatim SQL, exportSchema,
migration test:

1. Tables: `aspects`, `record_types`, `field_defs`, `records` with the standard promoted set
   (id, recordTypeId, createdAt, updatedAt, dueAt, amountCents, searchText, provenance) plus
   `widget_instances` (per-device layout, ticket 08) and trash/archive state.
2. `RecordStore`: the single write door. Reference enforcement (exists on write, per-field
   block/cascade/null on delete), archive/trash with 30-day purge, computed-field
   materialization on write (aggregations + same-record arithmetic), provenance tagging.
3. Field types v1: text, number, money-cents, date, datetime, boolean, choice, multi-select
   choice, reference, photo, location, rating, computed. Money is Long cents.
4. The reconciliation gate rehomed as engine infrastructure per ticket 11: quarantine, provenance,
   rule-7 provisional path, unsatisfiable-by-empty checks preserved.
5. Unit tests: reference policies, trash/purge, computed invalidation, gate behaviors. Suite green
   both key ways.
