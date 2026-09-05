---
map: django-engine
ticket: "07"
title: "Where it runs"
type: decision
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
