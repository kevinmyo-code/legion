---
map: django-engine
ticket: "07"
title: "Where it runs"
type: decision
status: resolved
status-detail: "Decided 2026-09-05 (Kevin), amended the same night: database = the existing Supabase Postgres (pooler), Django the only writer; compute = Google Cloud Run service + Cloud Run Job on Cloud Scheduler, the pattern already running in midconerpdash; media = Cloudflare R2 or GCS; Cloudflare in front for the domain. Home box dropped. REOPENED 2026-09-08 (Kevin) after the Cloud Run cold start measured 5.4 s: an always-on Oracle Cloud A1 VM on a Pay As You Go tenancy runs the compose full profile - Postgres, Django, worker, Caddy - and Cloud Run and Supabase retire thirty days after cutover. Ruling: web-and-households ticket 12; the move: ticket 13."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Where it runs

Replaces two-clients ticket 02, whose constraints mostly carry over, with one change: **the phone
now depends on this host to write.** Room keeps reads alive when it is down; every write waits in
the outbox. So uptime matters more than it did.

## Constraints

1. Reachable from Kevin's phone anywhere, her iPhone anywhere, the desk.
2. BYO: a stranger stands up their own on their own account or hardware.
3. Runs `docker compose`. Holds `.env`. Has a disk for `/data`.
4. TLS. The phone will not talk to a plain-HTTP origin, by policy: `ServerConfig` refuses `http://`.

## Candidates

| Host | Reach | Cost | Uptime | Against |
|---|---|---|---|---|
| **Home box + Tailscale** (recommended) | Tailscale on both phones and the desk. No public URL at all; the API is unreachable from the internet | Power. Tailscale free for a household | Kevin's electricity and ISP. A house move takes it down for a day | Exposure is zero, so RLS-grade defence is not needed, which is why it is recommended. Her iPhone needs the Tailscale app installed once |
| Hetzner CX22 or equivalent | Public URL behind Caddy | About 4 EUR/mo, a card | Theirs, 99.9 | The API is on the internet. Token auth and rate limits are the only wall. Fine, but it is a wall that has to be kept |
| Fly.io / Railway | Public URL | Card, usage floor | Theirs | Same exposure, more platform to learn, volumes are the awkward part for `/data` |

Not candidates: anything Kevin runs for other people (fails BYO), anything serverless (the worker
and `MEDIA_ROOT` want a disk).

## What deciding produces

- One line in this ticket's `status-detail`.
- `deploy/README.md`: the exact steps from a clean box to a running stack, including Tailscale
  install and `tailscale serve` for TLS if that is the pick, or the Caddy DNS setup if it is not.
- The `ServerConfig` URL the Android agent bakes as the default hint in ticket 09.

## Recommendation

Home box + Tailscale, today. Move to Hetzner the first time the box being down costs something,
and the move is `rclone` the backup, `docker compose up`, change one URL on three devices.


## Decided 2026-09-05

Kevin: *"i guess best is supabase > django > android/pwa?"* Database: the Supabase Postgres the data is
already in, free tier, session pooler, Django the only writer, so ticket 10 has no data migration.
Compute: a home box (old laptop or Pi 5) running Django + worker in compose, reached through
Cloudflare Tunnel on Kevin's domain with Access in front (two emails); Oracle A1 as fallback. Media:
Cloudflare R2, so ticket 05 repoints there. Backups: nightly `pg_dump` to R2 (ticket 06), which also
keeps the free project from pausing. Full reasoning and the rejected alternatives in
`memory/library/decisions.md`.

## Amended 2026-09-05, same night: compute is Cloud Run, not a home box

Kevin: *"my github has the midconerpdash project which i put on cloudflare... it runs even if my
laptop is off"* - and a shallow clone showed what it actually is: a Python container on **Google
Cloud Run** (`deploy.py`), a **Cloud Run Job** (`deploy_job.py`) fired by **Cloud Scheduler**
(`install_schedule.py`), Firebase Hosting for the static page, Cloudflare in front as DNS. Its own
Dockerfile states the reason: *"the figures have to keep refreshing when no workstation is switched
on."* That is the Django engine's requirement word for word. Kevin: *"yes cloud run, update ticket 07."*

| Layer | Decision |
|---|---|
| Database | Unchanged: the Supabase Postgres the data is in, free tier, session pooler, Django the only writer |
| Web | **Cloud Run service** running the `server/Dockerfile` image, `--min-instances=0`, region `us-south1` (Dallas) or `us-central1`; free tier 2M requests, 180k vCPU-s, 360k GiB-s per month |
| Worker | **Cloud Run Job** from the same image, one entrypoint per task, fired by **Cloud Scheduler** (3 jobs free). Replaces the `supercronic` container in compose; compose keeps it for local dev only |
| Media | Cloudflare R2 (10 GB, no egress fee) or GCS (5 GB, same account). R2 unless the GCS path is simpler with `deploy.py`'s existing bucket code |
| Domain and edge | Cloudflare DNS pointing at the Cloud Run URL, Access optional; Cloud Run has its own HTTPS |
| Auth for the phone | Unchanged: device token header, Django-side. `--allow-unauthenticated` at the Cloud Run layer, exactly as midconerpdash does |
| Dev loop | `docker compose up` on the laptop with a `local` profile (web + worker, no postgres). Docker Desktop stays useful but is not the deployment |

**Why this beats the home box:** up when every household machine is off, no hardware, no power
settings, no tunnel daemon, and Kevin already operates this exact shape. **Cost accepted:** a cold
start of a second or two after idle, and Google's free tier is a monthly allowance, not a promise;
`deploy.py`'s pattern of a max-instances cap keeps a runaway bill impossible.

**What ticket 01 changes:** the compose file gains a `local` profile; `deploy/` gains a
`cloudrun/` folder with the service and job definitions, modelled on midconerpdash's `deploy.py` and
`deploy_job.py` rather than written fresh. `gcloud` is not installed on this machine yet.
