---
map: hardening
ticket: "03"
title: "Automatic backup before any schema migration"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Automatic backup before any schema migration

## Question

The mirror is a backup by accident. Make it deliberate: on app start, when the stored schema
version differs from CarDatabase.SCHEMA_VERSION, copy the DB files (db + wal + shm) to a
timestamped local backup directory BEFORE Room opens and migrates; keep the last N=3; surface
the latest backup's existence in words on the Drive/mirror screen. Restore stays manual and
documented (a doc note, not a UI flow, v1). No network dependency - local copy only.
