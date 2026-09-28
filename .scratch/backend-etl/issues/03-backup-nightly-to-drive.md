---
map: backend-etl
ticket: "03"
title: "backup_nightly and a drilled restore_backup, to Google Drive"
type: build
status: built
status-detail: "Built 2026-09-27, server suite 880 / 0 failures / 43 skipped (JUnit; the new skip is the opt-in real-dump drill). Owed: LEGION_VAULT_KEY on Cloud Run, Kevin runs connect_session.py drive --backup, a real run lands on Drive, restore drill against live, and Cloud Build proving postgresql-client-17 resolves."
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

## Built (2026-09-27, `feat/backend-etl`)

- `ingest/backup.py` (the job, the Drive layout, retention, the restore drill's parts),
  `ingest/drive.py` (Drive v3 over `urllib`: token refresh, list, folder, resumable upload,
  trash, download), commands `backup_nightly` and `restore_backup <date> [--target NAME]`.
  Tests: `tests/test_backup.py`; the `--backup` switch in
  `tests/test_connect_session.py`.
- `deploy/crontab`: `0 3 * * * python manage.py backup_nightly` (the `python` is required, as
  for `heartbeat`). `server/Dockerfile`: `ARG PG_MAJOR=17`, installs
  `postgresql-client-${PG_MAJOR}`; 17 is the live server's major, read with
  `SHOW server_version` (17.6) on 2026-09-27. `deploy/.env.example`: `LEGION_BACKUP_SCHEMAS`,
  `LEGION_PG_BIN_DIR`.
- **Drilled against the test database, not live:** `LEGION_BACKUP_DRILL=1` runs
  `test_a_real_dump_restores_into_a_throwaway_database_with_equal_counts` with the real
  pg_dump/pg_restore 17.6: dump, CREATE DATABASE, restore, counts (55 tables, all equal), DROP.
  pg_restore reported one ignored error, `schema "public" already exists`, which is expected on
  a fresh database and is printed rather than hidden.
  Opt-in because it creates a database on the shared server. It proves the mechanics; both
  boxes above still need the real thing.
- Decided in the build, not by this ticket:
  - **One real run, under the operator household** (`resolve_default_household`), and a
    mirrored row for every other household, so each one's freshness reads the 36h threshold
    truthfully. Mirrors never carry the operator's Drive ids or error text; a failure or dead
    login reads there as "did not complete", since only the operator can fix it.
  - **Only the operator household's Drive, and only with `config.backup == true`** (set by
    `tools/connect_session.py drive --backup`; `--no-backup` turns it off; left out, kept).
    The dump holds every household's rows (ADR 0045), so another household's Drive never
    gets it. Otherwise every household records `skipped`.
  - **What is dumped:** schemas `django,public,private` (`LEGION_BACKUP_SCHEMAS`). Supabase's
    platform schemas (auth, storage, vault, ...) are left out and each run's `watermark` names
    them. Consequence: `household_members -> auth.users` cannot be restored as a constraint
    (its rows still are).
  - **Job-made means appProperties** (`legion_backup` = root/day/dump, `legion_backup_date`),
    filtered in the query and re-checked in code. Retention keeps the 90 newest day folders by
    date (a failed week does not eat backups) and TRASHES (30-day recoverable), never deletes.
    A second run on one day trashes the earlier dump after the new one is up.
  - A Drive **403 is a failure, not a dead login** (rate limits, quota); `invalid_grant` or a
    401 is `needs_login` and stamps `invalid_since`.
  - `/api/freshness` for `backup` appends "Photos and recordings are not in it yet." after
    any successful run; each `ok` run's `watermark` says the same at length.
  - `restore_backup` refuses a target equal to the live name (any case) before touching
    anything, and `drop_database` refuses it again. It exits non-zero on any count difference,
    naming which side has more, and on a restore that produced no tables.
- Owed: a real run to Kevin's Drive (after `connect_session.py drive --backup` and
  `LEGION_VAULT_KEY` on Cloud Run); the restore drill against live. Cloud Run's `/tmp` is
  memory, and the job has 512Mi (`deploy/cloudrun/deploy_job.py`): watch the dump size.
  `restore_backup` downloads into memory before writing, fine on a laptop, not for a
  multi-GB dump. The image build (Debian `postgresql-client-17` resolving) is reasoned, not
  built: Docker is not installed here.
