---
map: web-and-households
ticket: "12"
title: "Hosting: an always-on Oracle Cloud VM runs the whole stack in compose; Cloud Run and Supabase retire"
type: decision
status: resolved
status-detail: "REVERSED 2026-09-10 (Kevin): 'kill oracle vm decision'. Cloud Run stays, no VM. Three availability domains in us-chicago-1 all answered 'Out of capacity for shape VM.Standard.A1.Flex' - AD-1, AD-2 and AD-3, with a fully correct form. The Ampere A1 free-tier shortage is not something a retry loop fixes on a schedule, and the engine already runs. The trade Kevin accepted: a 5.4s cold start at min-instances 0, because the alternative is roughly $40-50/month to keep one instance warm. What the VM was ALSO going to buy - Postgres off Supabase's 500MB free tier, and durable media - is answered separately: the database stays on Supabase for now, and receipt photos turn out not to need persisting at all (see below). Original ruling, 2026-09-08, kept for its reasoning:  'we are reopening the oracle VM. new hosting decisions.' Then, asked the two forks: Postgres moves onto the VM in compose (the full profile), and the tenancy upgrades to Pay As You Go. Reopens django-engine ticket 07 (Cloud Run, 2026-09-05). Build: ticket 13."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Hosting: an always-on Oracle VM

**Kevin, 2026-09-08:** *"why cold start? wont the linux vm we host on be always on"* - then, told the
engine was on Cloud Run at min-instances 0 with a measured 5.4 s cold start - *"we are reopening the
oracle VM. new hosting decisions."* Asked the two forks it opens, he chose **Postgres on the VM in
compose** and **upgrade the tenancy to Pay As You Go**.

## What was decided, in one table

| Layer | Was (django-engine 07, 2026-09-05) | Now |
|---|---|---|
| Compute | Cloud Run service, min-instances 0, plus a Cloud Run Job on Cloud Scheduler | One always-on **Oracle Cloud VM**, `VM.Standard.A1.Flex` (Ampere, arm64), running `deploy/docker-compose.yml`'s `full` profile: postgres + web + worker + caddy |
| Database | Supabase's free Postgres over the session pooler | **Postgres in compose on the VM.** No 500 MB ceiling, no idle pause, no internet hop per query. Backups become ours: django-engine 06 moves up the queue and gates the cutover |
| TLS and domain | Cloud Run's `run.app` certificate; custom domain unresolved | **Caddy** on the VM with an automatic Let's Encrypt certificate for Kevin's domain; Cloudflare stays DNS-only |
| Worker | Cloud Run Job | The `worker` compose service (supercronic), as the file already says for a compose-only install |
| Tenancy | - | **Pay As You Go**, with a budget alert. Oracle halved the Always-Free A1 allowance to 2 OCPU / 12 GB on 2026-06-15 and reclaims idle Always-Free instances (7-day window, CPU 95th percentile under 20 %, network under 20 %, memory under 20 %); a PAYG tenancy keeps 4 OCPU / 24 GB at $0 inside the free limits and is reported, not documented, as exempt from reclamation |
| Cold start | 5.4 s measured | None |

## Why this is the right reopen, and what it costs

The doc's argument for a VM was "no cold starts, no multi-server management, zero hosting fees",
and every one of those is true here. What the 2026-09-05 survey held against Oracle - the halved
allowance and reclamation - is real and is answered by the PAYG upgrade, which the survey did not
consider. What is given up: Google running the box. From now on the household is the sysadmin
(patching, disk, Docker upgrades, backups), which is exactly ADR 0044 rule 5's "the household hosts
its own" read literally.

**Clone-and-run is unchanged and gets simpler:** the BYO path was always `docker compose up`; the
deployed shape now IS the BYO path, so there is one shape to keep working instead of two.

## Consequences on this map

- Ticket 08's CD half becomes: build on push, deploy to the VM over SSH (rewritten there).
- Ticket 09 becomes: the arm64 image, whitenoise, Caddy and the domain; no cold-start section.
- Ticket 13 is the move: provision, migrate the data, cut over, retire Cloud Run and Supabase.
- `deploy/cloudrun/` is retired by ticket 13, not before: Cloud Run stays up at min-instances 0
  (which costs nothing idle) as the rollback for thirty days after cutover.
