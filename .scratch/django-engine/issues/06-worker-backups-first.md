---
map: django-engine
ticket: "06"
title: "The worker: a nightly dump with a drilled restore, then Canvas, then WebAssign"
type: build
status: open
blockers: ["02"]
blocked-by: ["[[02-models-column-exact]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The worker

The `worker` container from ticket 01 runs `supercronic` over `deploy/crontab`. Every job is a
management command, so it also runs by hand from a laptop, which is the outage test from ADR 0043
kept alive: nothing here is the only way to do anything.

## Job 1, and it ships before any other: `backup_nightly`

- `pg_dump -Fc` of the whole database plus `tar` of `MEDIA_ROOT`, into `/data/backups/<date>/`.
- Copied off the box the same night with `rclone` to the household's own Google Drive folder
  (BYO: the rclone remote is configured on the household's box, never committed). Fourteen days
  kept locally, ninety on Drive.
- `restore_backup <date>` restores into a throwaway database and runs a row count per table
  against the live one. **This is drilled, not described:** the verification below runs it.
- The 2026-08-25 Supabase feasibility note said the free tier has no backups and the xlsx mirror
  carried recovery; the mirror was retired; `DatabaseSnapshot`'s Drive guard has refused every
  upload since a wipe (MEMORY.md). This job is the first real recovery path LEGION has had since
  the mirror went. It ships first for that reason.

## Job 2: `canvas_poll` (absorbs two-clients ticket 03)

Every rule one-today ticket 08 states about discussions and `submitted_at` binds here verbatim.
Canvas token from the environment. Writes through ticket 04's `events` endpoint or the ORM inside a
transaction, never both. A run that cannot reach Canvas logs and exits 0; the next run retries.

## Job 3: `webassign_read` (absorbs two-clients ticket 05)

Same shape. Session credential from the environment. Deferred until job 2 has run a week.

## `deploy/crontab`

```
0 3 * * *     python manage.py backup_nightly
*/30 * * * *  python manage.py canvas_poll
0 6 * * *     python manage.py webassign_read
```

## Verification

- [ ] `backup_nightly` runs from the worker container and a `<date>` directory appears on the
      household Drive within the hour.
- [ ] `restore_backup <date>` on a throwaway database reports every table count equal to live.
- [ ] Kill the worker container: the phone and the web app are unaffected.
- [ ] `canvas_poll` against the real Canvas produces the same rows as `tmp/canvas_reconcile.py`
      did by hand on 2026-09-04.
