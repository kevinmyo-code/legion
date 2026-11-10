---
map: watches
ticket: "01"
title: "Engine: registry, evaluator, endpoints"
type: build
status: built
status-detail: "built 2026-11-10 on feat/watches; tests/test_watches.py green. Owed: engine deploy with watches 0001 and the evaluate_watches schedule line"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Engine: registry, evaluator, endpoints

## What

- `server/watches/`: `Watch` and `WatchFiring` (born tenanted, private to the member, owner guard),
  the closed registry, the evaluator, `/api/watches/` routes, `manage.py evaluate_watches`.
- Built-ins seeded per member on first read: a charge of $500 or more, Canvas work due within 24 h,
  car service due within 14 days or 500 miles. Removing one disables it for good.
- `deploy/crontab`: `7,22,37,52 * * * * python manage.py evaluate_watches`.

## Verification

- `tests/test_watches.py`: registry refusals in words, no backfill, pending-to-posted is one fact,
  card deposits excluded, balance edge trigger, grade baseline, service date fact vs miles estimate,
  one-shot spent, member privacy, household tenancy, recruiter and Claude token refused, mail_reply
  never evaluated on the engine, crontab line.
- On the deployed engine: run `evaluate_watches` once and read its line (owed, Kevin's deploy).
