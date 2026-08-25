---
map: backend-erp
ticket: "01"
title: "What the backend owns: schema, truth, and the phone's residual role"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What the backend owns: schema, truth, and the phone's residual role

## Question

The root ticket. Decide:

1. The Postgres schema: the engine's four generic tables translated (records as JSONB payload plus
   promoted columns, mirroring Room v37), or per-aspect real tables now that Postgres does DDL
   properly? Recommend the generic shape moves as-is: it is proven, and the metadata layer IS the
   product.
2. What stays phone-only: OBD-live state, wake word, photo files?
3. Room's new role: consumer cache with what freshness contract; which reads may hit the network
   synchronously ("always online") vs cache-first.
4. Writes: the phone writes to Supabase directly (PostgREST) with RecordStore becoming a client of
   the API, or offline-queue-and-push?
5. What of the engine's enforcement (references, delete policies, computed fields) moves into
   Postgres (FKs, triggers, RLS) vs stays client-side vs both.
