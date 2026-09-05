---
map: django-engine
ticket: "10"
title: "Cutover: one evening, counted, reversible for thirty days"
type: build
status: open
blockers: ["03", "04", "05", "06", "07", "09"]
blocked-by: ["[[03-the-gate-in-python]]", "[[04-domain-api-and-changes-feed]]", "[[05-media-photos-and-audio]]", "[[06-worker-backups-first]]", "[[07-where-it-runs]]", "[[09-android-http-backends]]"]
open-blockers: 6
ready: false
tags: [ticket]
---

# Cutover

Until this ticket, Supabase is live and the server is a parallel build. This is the one evening
where truth moves. Every step is reversible until step 8, and step 8 waits thirty days.

## Steps

1. **Freeze.** Both phones: open the app, let the outbox drain, confirm `EventsOutbox` and every
   sibling are empty (a debug screen already shows this; if not, `adb shell` the Room table).
   Then close the app on both. Her web app does not exist yet against Supabase, so nothing else writes.
2. **Dump.** `pg_dump --data-only --schema=public` from Supabase. `supabase storage` bucket
   downloaded to `MEDIA_ROOT/receipts/` with the same object paths.
3. **Users.** On the server: create the two users with `id` = their Supabase `auth.uid` (from
   `auth.users`), emails as they are. Passwords set fresh; there is no way to carry a hash across
   and no reason to.
4. **Load.** Into the server's Postgres, migrated by ticket 02. Row count per table, all 41, equal
   to the dump. Photo count equal to the bucket.
5. **Gate parity.** Ticket 03's corpus run against the loaded server: same outcomes as the last
   SQL run.
6. **Flip.** `ServerConfig` URL on both phones; sign in; `GET /api/auth/me` green; a full pull.
   Room row counts equal server row counts for every synced table. One write each way with the
   web app.
7. **Supabase read-only.** In the dashboard: revoke `insert, update, delete` from `authenticated`
   on every table. Not deleted. Thirty days.
8. **Retire, after thirty clean days.** Delete the Supabase project. Delete
   `.github/workflows/supabase-keepalive.yml`. Move `supabase/` to `server/legacy/supabase/` with
   a `FROZEN.md` banner. `tools/sql_check.py` and `gate_corpus_sql.py` point at the archive or go.
   `README.md`'s build status updated.

## Rollback, before step 8

Set the URL back on both phones and re-grant on Supabase. Rows written to the server in between
are re-entered by hand; the window is one evening, and the count is on the debug screen.

## Verification

- [ ] Step 4 counts, all 41 tables, in this ticket's status-detail.
- [ ] Step 6 counts, Room vs server, in this ticket's status-detail.
- [ ] Ticket 06's `backup_nightly` has run once against the loaded server and `restore_backup`
      drilled against it before step 7.
- [ ] Thirty days later: step 8 done, and `grep -r supabase app/src server/ --exclude-dir=legacy` empty.
