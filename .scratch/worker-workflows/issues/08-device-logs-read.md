---
map: worker-workflows
ticket: "08"
title: "Engine: device logs readable by the worker (logcat and crash over the API)"
type: build
status: kiv
status-detail: "parked until feat/phone-log-upload merges (it adds the logcat and crash reasons); planning only until Kevin says build"
size: S
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Device logs readable by the worker

**Blocked by a branch, not a ticket:** `feat/phone-log-upload` adds device-log reasons `logcat`
and `crash` and `tools/phone_audit_engine.py`. On dev the reasons are drive_end, startup, manual,
periodic, conversation.

## Why

`phone_audit_engine.py` reads prod through `gcloud run jobs execute` and `gcloud logging read`.
The ask-list hook denies `gcloud` to every worker helper, token or not (`asklist_hook.py`, the
ship rule). That is right and stays. So "audit my logs" on the worker needs the same lines over the
engine API, with the worker's device token, GET only, the way `fix-alfred` already reads
conversation records.

## What

- `GET /api/device-logs/?reason=logcat&reason=crash&since=&until=&device=` (the list that exists,
  widened to the two reasons; household-scoped as today).
- `GET /api/device-logs/merged?reason=logcat&reason=crash&since=&until=` returns the merged,
  time-ordered threadtime text the operator command prints (`manage.py device_logs --raw`),
  gzipped, capped (say 20 MB), so a helper does not reassemble batches itself.
- `tools/phone_audit_engine.py --engine` reads through this API with a token from the worker
  config instead of gcloud. The audits it runs (`voice_audit.py`, slow tools, crashes, nav report)
  are unchanged.
- No new hook exception. Nothing writes.

## Verification

- [ ] pytest: reasons filter, time window, household scope, merge order, cap, recruiter token
      refused.
- [ ] `phone_audit_engine.py --engine` against the fake engine gives the same report as `--file`.
