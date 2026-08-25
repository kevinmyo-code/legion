---
map: backend-erp
ticket: "04"
title: "The xlsx mirror and the local cache in a backend world"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-what-the-backend-owns]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The xlsx mirror and the local cache in a backend world

## Question

The mirror's SYNC role dies (Supabase is the channel). Decide what survives: the audit/export
surface Kevin valued ("a relational database in sheet form that users can audit") - exported from
Supabase (server-side, or any consumer generates it), or from the phone cache as today; whether
hand-edit-and-reimport survives at all or the Supabase table editor replaces it; retention of the
Drive folder. Also the local cache's shape: a full replica of the household's records (small data,
recommend yes) vs windowed.
