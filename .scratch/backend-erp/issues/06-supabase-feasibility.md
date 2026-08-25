---
map: backend-erp
ticket: "06"
title: "Supabase feasibility on the real free tier"
type: research
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-what-the-backend-owns]]"]
open-blockers: 1
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
