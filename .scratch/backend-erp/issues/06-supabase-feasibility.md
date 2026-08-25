---
map: backend-erp
ticket: "06"
title: "Supabase feasibility on the real free tier"
type: research
status: resolved
status-detail: "Free tier fits two users; only hazard is the 7-day pause (manual resume, no data loss). Findings in research/06."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Supabase feasibility on the real free tier

## Question

Primary sources (supabase.com docs and pricing, current 2026 terms):

1. Free-tier limits: projects, rows/storage, API requests, realtime connections, Auth monthly
   active users, and the pause-after-inactivity policy - the known gotcha. Does a paused project
   lose data? What does resume take? If a week of vacation pauses the household ERP, that changes
   the recommendation - say so honestly.
2. PostgREST from Android: supabase-kt's maturity, or plain ktor/okhttp.
3. RPC transactions for the gate's atomic file commits.
4. RLS model for a household (all members see all rows).
5. SQL migrations for BYO-project bootstrap (CLI? dashboard paste?).
6. Realtime on Android for cross-device freshness.
7. Auth flows on Android, Google OAuth via Supabase specifically.

Findings to `.scratch/backend-erp/research/06-supabase-feasibility.md`, citations mandatory,
NOT ESTABLISHED where sources are silent.

## Answer

Free tier fits two users on every hard number: 500 MB Postgres, unlimited API requests, 50k MAU,
200 realtime connections, 2 active projects. The one hazard is the pause: insufficient DB activity
over 7 days pauses the project (warning email ~1 week ahead). Data is NOT lost; resume is a MANUAL
dashboard click, restorable for 1 year. A vacation week can take the backend down until someone
clicks Resume; a daily keep-alive write defeats it but is a permanent crutch. Free tier has no
backups (reasoned from pricing omission), so the xlsx mirror is the recovery story. supabase-kt
3.7.0 (2026-07-20, active, community-maintained) covers auth+postgrest+realtime on Android minSdk
26. Atomic gate commits map cleanly to one PostgREST RPC (one request = one transaction). Google
OAuth needs BOTH a Web and SHA-1-keyed Android client ID - same clone-and-run friction as the
Drive finding. Bootstrap via CLI `supabase db push` from committed migrations; dashboard SQL paste
works but bypasses history. Cheapest paid fallback: Pro $25/mo (no pausing, daily backups).
