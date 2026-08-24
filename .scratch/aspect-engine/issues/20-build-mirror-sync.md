---
map: aspect-engine
ticket: "20"
title: "Build the mirror and sync"
type: task
status: open
status-detail: ""
blockers: ["16"]
blocked-by: ["[[16-build-engine-core]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build the mirror and sync

## Question

Build what tickets 12 and 13 locked, on tickets 01 and 02's research:

1. **First: the on-A25 probe ticket 01 left owed.** Create, rewrite (`rwt` full-rewrite), and
   read-back-hash-verify an xlsx in a real Drive folder on the phone, and watch it reach the
   cloud. If write-back fails on-device, STOP and surface - the sync channel decision rests on it.
2. fastexcel-based export: one workbook per aspect, sheet per record type, definitions sheet,
   protected id/provenance/computed columns, generated validation rules (decoration), integer
   cents cells. Debounced plus on-background export; staleness stated in words.
3. The import gate: row-level validation, reference existence, quarantine UX (fixable,
   re-importable), reconciled rows rejected as read-only.
4. Sync: the xlsx files are the channel. Row-level merge keyed by record id plus updatedAt,
   never whole-file replace; import on foreground and after export; change detection by
   lastModified plus content hash. appDataFolder SyncEngine retires for record data;
   CompanionSync reviewed.
5. Robolectric only if the fastexcel bare-JVM claim fails in practice (research says it should
   not).
