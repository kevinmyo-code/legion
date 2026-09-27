---
map: backend-etl
ticket: "03"
title: "backup_nightly and a drilled restore_backup, to Google Drive"
type: build
status: open
blockers: ["01", "02"]
blocked-by: ["[[01-job-runner-and-freshness]]", "[[02-session-vault-and-login-handover]]"]
tags: [ticket]
---

# Backups

Carries django-engine 06 job 1 whole, adjusted for Cloud Run (no persistent disk) and ruling 4.

- `pg_dump -Fc` of the database to a temp file, uploaded to a `LEGION backups/<date>/` Drive
  folder with the vault's `drive` credential. Media joins once media has a durable store (ADR 0041
  gap); until then the run record says media is not backed up.
- Retention on Drive: 90 daily. Deletes only folders this job created (`drive.file`).
- `restore_backup <date>` downloads into a throwaway database and prints per-table row counts
  against live. **Drilled, not described:** verification runs it.
- `pg_dump` must match the server's major version. The job image installs the matching
  `postgresql-client`; the job checks `SHOW server_version` at runtime and records a mismatch as
  `failed` rather than writing a dump it cannot trust.
- crontab: `0 3 * * * manage.py backup_nightly`.

## Verification

- [ ] A real run lands a dated folder on Kevin's Drive.
- [ ] `restore_backup` on a throwaway database reports every count equal to live.
