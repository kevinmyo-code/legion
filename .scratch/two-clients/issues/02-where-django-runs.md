---
map: two-clients
ticket: "02"
title: "Where Django runs"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Where Django runs

ADR 0043 ruling 3: hosting is a decision, not an accident. This ticket is that decision, and it is
OPEN. Nothing below is ruled; the candidates and the constraints are laid out so Kevin can rule in one
sitting.

## What the choice must satisfy

1. **The phone never depends on it.** ADR 0043: if Django is down, Canvas state goes stale and
   nothing else happens. Any candidate satisfies this by construction as long as no RPC the phone
   calls is proxied through Django. That is a rule on the code, not on the host.
2. **BYO.** A stranger who clones the repo and wants Django stands up their own instance on their
   own account. The host must be something one person can sign up for, or own outright.
3. **A scheduler.** The Canvas poller ([[03-canvas-poller]]) and the WebAssign read
   ([[05-webassign-completion-read]]) run on a cadence. Cron, a scheduled job, or a long-running
   process - the host has to offer one.
4. **Reaches Supabase.** Outbound Postgres over the session pooler on port 5432. Every candidate does.
5. **Holds secrets.** The Postgres role password, a Canvas token, a WebAssign session. Environment
   variables at minimum; nothing in the repo.

## Candidates

| Host | Scheduler | Cost shape | Against it |
|---|---|---|---|
| Fly.io | `fly machines` on a cron schedule, or a scheduled machine | Pay-as-you-go, small machines are cents a day | Free allowance has been withdrawn for new accounts; a card is required |
| Railway | Cron jobs are a first-class service type | Usage-based with a small monthly floor | Same floor whether or not the poller runs |
| A home box | System cron, or a `systemd` timer | Hardware already owned; power only | Residential IP, uptime is Kevin's problem, and a house move takes it down. Fits "Kevin-hosted" literally, which ADR 0043 allows because the phone does not need it |

Not candidates, and why, so they are not proposed again:

- **Supabase edge functions.** Ticket 08's original answer. Deno, no Django, and the scheduler is
  `pg_cron` calling an HTTP endpoint - a second runtime to maintain for one poller. ADR 0043
  absorbed this into Django on purpose.
- **A VPS Kevin maintains for other people.** The phone-does-not-depend-on-it test passes, but the
  BYO test fails: a stranger cannot clone Kevin's VPS.

## What deciding produces

A one-line ruling in this ticket, a `deploy/` directory or a `fly.toml` / `railway.json` in the
Django repo, and [[03-canvas-poller]] unblocked. Where the Django repo itself lives (inside this repo
under `django/`, or its own repo) is part of the same ruling: one repo keeps `supabase/migrations/`
and the `inspectdb` models in one commit, which is the argument for inside.
