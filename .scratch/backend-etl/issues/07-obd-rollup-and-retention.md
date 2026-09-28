---
map: backend-etl
ticket: "07"
title: "obd_samples: roll up per drive, keep 90 days raw"
type: build
status: open
blockers: ["03"]
blocked-by: ["[[03-backup-nightly-to-drive]]"]
tags: [ticket]
---

# OBD roll-up and retention

Ruling 5. Nightly, after `backup_nightly`: for every drive whose samples are complete, write
per-drive aggregates onto `drives` (existing columns first; any new column is an additive
migration, with the matching Room change owed by the Android terminal). Then delete raw
`obd_samples` older than 90 days, **only if the latest `backup` run is `ok` and newer than the rows
being deleted**. Otherwise record `skipped` with that reason.

crontab: `30 4 * * * manage.py obd_rollup`.

## Verification

- [ ] pytest: with no recent ok backup, nothing is deleted.
- [ ] pytest: aggregates are identical across two runs.
