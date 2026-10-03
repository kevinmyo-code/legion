---
map: web-revamp
ticket: 15
title: Push notifications
type: build
status: built
status-detail: "both halves built; owed: VAPID keys, schedule installed, Mia subscribes on her iPhone, a real push arrives"
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

- [x] pytest with a fake sender and frozen clock: list batch after 2 min, grouped per list per
      creator, never sent to the creator; a reminder fires once per occurrence incl. repeats and
      skips; private events remind only their owner; the morning message once per local day and
      nothing when nothing is due; 410 deletes the subscription; 5 failures delete it; one-tap off
      stops that kind.
- [x] pytest: `copy.py` strings contain none of "haven't", "miss", "streak", "days since", "come back".
- [x] vitest: iOS non-standalone shows the install card; subscribe POSTs the subscription.
      (`-notifications.test.tsx`; the worker's push and click handlers as plain functions in
      `push-sw.test.ts`; web half built 2026-10-03.)
- [x] In a real Chromium (`npm run push-sw`, `e2e/push-sw.spec.ts`): the BUILT `sw.js` activates as a
      classic worker, a push delivered through the DevTools protocol shows the notification, the
      "Turn these off" click handler POSTs `/api/push/preferences/off` and then says what the engine
      said, and `/api/` is never answered from or written to a cache. At the swap the precache was
      the same 72 entries (modulo hashes) the generated worker had.
- [x] Shots in `research/shots/15/`: every notifications state (on, off, denied, iOS install card,
      unsupported browser, not set up, unreachable, everything off), 390x844 and 1440x900, light and
      dark.
- [ ] Impossible without a phone, owed on live (Kevin): the "Turn these off" button itself, pressed by
      the OS. Reasoned, not seen: MDN lists notification `actions` as limited availability (not
      Baseline) and I did not see the Safari cell, so on an iPhone the button may not render and the
      one-tap silence there would be the master off on this page. The click handler is tested; the OS
      pressing it is not.
- [ ] Owed on live (Kevin): VAPID keys in env, `install_schedule.py` run for the new line, Mia
      subscribes, a Groceries add and a 30-minute reminder both reach her phone.
