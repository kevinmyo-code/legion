---
map: web-revamp
ticket: 15
title: Push notifications
type: build
status: open
status-detail: ""
blockers: ["05", "08", "14"]
blocked-by: ["[[05-join-signup-and-settings]]", "[[08-repeat-exceptions-on-the-engine]]", "[[14-reminder-lead-time-on-events]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Push notifications

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D7.

## Build

- `server/push/` app, models in `TENANT_TABLES`, routes, `copy.py`, `manage.py push_dispatch`,
  `deploy/crontab` line `*/5 * * * * python manage.py push_dispatch`, `pywebpush` pinned, VAPID env
  documented in `deploy/.env.example`. Missing keys = push off, said in words.
- Frontend: vite-plugin-pwa `injectManifest` with `src/sw.ts` (keep NetworkOnly `/api`,
  `skipWaiting`, `clientsClaim`; add `push` and `notificationclick`). `/settings/notifications`:
  the iOS install card when not standalone, subscribe/unsubscribe, three kind toggles, morning time.

## Verification

- [ ] pytest with a fake sender and frozen clock: list batch after 2 min, grouped per list per
      creator, never sent to the creator; a reminder fires once per occurrence incl. repeats and
      skips; private events remind only their owner; the morning message once per local day and
      nothing when nothing is due; 410 deletes the subscription; 5 failures delete it; one-tap off
      stops that kind.
- [ ] pytest: `copy.py` strings contain none of "haven't", "miss", "streak", "days since", "come back".
- [ ] vitest: iOS non-standalone shows the install card; subscribe POSTs the subscription.
- [ ] Owed on live (Kevin): VAPID keys in env, `install_schedule.py` run for the new line, Mia
      subscribes, a Groceries add and a 30-minute reminder both reach her phone.
