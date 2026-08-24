---
map: hardening
ticket: "02"
title: "Sync channel v2: op-log journal or a BYO backend"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Sync channel v2: op-log journal or a BYO backend

## Question

The xlsx LWW channel is fine by scale, not by construction. Kevin reopened the no-backend rule
(2026-08-24, his initiative) for THIS problem only. Decide among:

A. Per-device append-only op-log files beside the xlsx in the Drive folder (journal = sync
   truth, xlsx = human view). No backend, no new accounts; closes the same-row window to
   op-level granularity.
B. BYO backend, one per household, preserving clone-and-run the same way the Gemini key does
   (a stranger creates their own free project, pastes URL + key): Supabase free tier (Postgres,
   REST out of the box, row-level security, realtime, real transactions/CAS) or Turso free tier
   (SQLite dialect matching Room's mental model, embedded replicas = local-first by design).
C. Azure SQL free tier (Kevin explored): workable but worst Android DX - no REST layer out of
   the box, TDS from a phone is not a real pattern, so it forces building an API tier on top.
   Assess honestly against B.

Constraints that survive regardless: local Room stays primary; the mirror xlsx stays as the
audit/export surface; offline-tolerant even though Kevin ruled always-online (a backend outage
must degrade to local, said in words). Resolution needs: the pick, the CAS/conflict story, the
second-phone bootstrap, and what happens to MirrorSync's merge role.
