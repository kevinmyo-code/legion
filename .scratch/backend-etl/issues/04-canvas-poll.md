---
map: backend-etl
ticket: "04"
title: "canvas_poll on the server, every 30 minutes"
type: build
status: open
blockers: ["01", "02"]
blocked-by: ["[[01-job-runner-and-freshness]]", "[[02-session-vault-and-login-handover]]"]
tags: [ticket]
---

# canvas_poll

Absorbs two-clients 03, chief-of-staff 06 and django-engine 06 job 2. **Every binding rule in
`.scratch/two-clients/issues/03-canvas-poller.md` ("Binding rules" and "Where the sub-deadline rule
is enforced") binds here verbatim**: coursework is `kind=task`; `done` comes from `submitted_at`
with `provenance=DETERMINISTIC` and the evidence in `structured_meta`; discussions split into
sub-deadlines and a parent's submission never ticks them; a `manual_completion: true` row is never
touched; match on `structured_meta->>'canvas_assignment_id'`, then `origin_guid`; upstream deletion
is a tombstone; re-running an identical payload writes nothing.

Change from those tickets: auth is the vault's cookie jar (ruling 2), not a token. Canvas's REST API
accepts the web session for GETs. `/api/v1/courses?enrollment_state=active`, then
`/api/v1/courses/{id}/assignments?include[]=submission&per_page=100`, following `Link` pagination.
On 429: back off, record `failed`, never hammer.

Watermark: the max `updated_at` Canvas returned, for the record only. Every run reads all active
assignments, because a submission does not always bump the assignment's `updated_at`.

crontab: `*/30 * * * * manage.py canvas_poll`.

## Verification

- [ ] pytest against recorded Canvas fixtures: first run inserts, second writes zero rows.
- [ ] pytest: a discussion yields parent + sub-deadline rows; parent submitted leaves reply row open.
- [ ] pytest: a 401 records `needs_login`.
- [ ] Live run against Kevin's Canvas, diffed against the 102 existing `DETERMINISTIC` tasks; every
      difference explained.
