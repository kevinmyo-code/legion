---
map: backend-erp
ticket: "03"
title: "The reconciliation gate when the truth lives remote"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The reconciliation gate when the truth lives remote

## Question

Ingestion (statements, receipts) runs on the phone today - parsers, vision, the gate, then an
atomic multi-row commit. Decide: does the atomic file-commit become a Postgres transaction via a
single RPC (recommend: a Supabase RPC per file commit, preserving nothing-partial exactly), or
does the phone commit locally and sync? Provenance column survives verbatim. Rule-7 supersession
runs inside the same transaction. What a mid-commit network failure reports (worded, per the
outcome-verb rule). Whether UNRECONCILED transience needs server-side enforcement.
