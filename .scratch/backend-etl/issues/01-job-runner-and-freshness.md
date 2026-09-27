---
map: backend-etl
ticket: "01"
title: "Job runner, ingest_runs, freshness endpoint, deploy/crontab"
type: build
status: built
status-detail: "Built 2026-09-27 (c6d0608), server suite 790 tests / 0 failures / 42 skipped (JUnit). Owed: migrate ingest 0001 on live, redeploy Cloud Run, run install_schedule.py for real, then see a heartbeat row land."
blockers: []
blocked-by: []
tags: [ticket]
---

# Job runner and freshness

Every pipeline in this map is a management command wrapped by one runner, so each run is recorded,
cannot overlap itself, and runs by hand from a laptop exactly as it runs on schedule.

## Build

- **Model `ingest.IngestRun`** (Django-managed, household-scoped, added to
  `household.tenancy.TENANT_TABLES`, covered by the tenancy leak test): `household`, `source`
  (TextChoices: `canvas`, `webassign`, `drive_statements`, `backup`, `obd_rollup`, `heartbeat`),
  `started_at`, `finished_at`, `outcome` (`ok` / `failed` / `needs_login` / `skipped_locked` /
  `skipped`), `rows_written`, `rows_unchanged`, `watermark` (text, source-defined), `error` (text,
  never containing a secret).
- **`ingest/jobs.py`: `run_job(source, household, fn)`**. Takes `pg_try_advisory_lock` on a key
  derived from `(source, household_id)`; if held, records `skipped_locked` and returns. Records the
  row before and after, catches everything, stores the message. A job whose upstream is down
  records `failed` and the command exits 0 (the next run retries).
- **Every job command loops households** that have the source configured. None configured is a
  recorded no-op, never a crash: a stranger's clone without Canvas gets nothing, quietly.
- **`GET /api/freshness`**: per source, for the caller's household: `last_ok_at`, `last_outcome`,
  `last_error`, `stale` (bool, per-source threshold in code: canvas 2h, webassign 36h,
  drive_statements 36h, backup 36h, obd_rollup 36h), and `sentence`, the words a surface shows
  ("Canvas last synced 3 hours ago", "Canvas needs you to log in again"). Surfaces render the
  sentence and never compose their own. OpenAPI regenerated.
- **`deploy/crontab`** created, with one line: `*/30 * * * * manage.py heartbeat`, a command that
  only records that the scheduler fires. Tickets 03-07 add their own lines.

## Verification

- [x] pytest: two concurrent `run_job` on one source, the second records `skipped_locked`.
- [x] pytest: a job raising records `failed` with the message; the command exits 0.
- [x] pytest: freshness for household A never shows household B's runs.
- [x] `python deploy/cloudrun/install_schedule.py --dry-run --job legion-worker` lists the line.

## Built (2026-09-27, `feat/backend-etl`)

- Tests: `tests/test_ingest_runs.py` (runner, command, freshness words) and
  `tests/test_tenancy.py::test_freshness_never_shows_another_households_runs`.
- The dry run needs a project to name: `--project <any-id>` or `GOOGLE_CLOUD_PROJECT`
  (`require_project` refuses without one, dry run included).
- Decided in the build, not by this ticket: the crontab line is `python manage.py heartbeat`
  (supercronic cannot run a bare `manage.py`); `heartbeat` appears in freshness with a 1h
  threshold; `skipped` means "not set up for this household" and is never stale;
  `skipped_locked` and in-progress runs never become `last_outcome`.
