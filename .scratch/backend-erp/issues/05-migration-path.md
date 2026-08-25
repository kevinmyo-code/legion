---
map: backend-erp
ticket: "05"
title: "The migration path: 569 records to Postgres without a bad day"
type: grilling
status: open
status-detail: ""
blockers: ["01", "02", "03"]
blocked-by: ["[[01-what-the-backend-owns]]", "[[02-auth-and-identity]]", "[[03-the-gate-server-side]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The migration path: 569 records to Postgres without a bad day

## Question

The cutover-arc playbook, aimed at the network: schema up (SQL migrations in the repo, so a
stranger runs one script against their fresh project - clone-and-run); guid-keyed idempotent
upload of the engine records; per-aspect cutover of the WRITE path (Room keeps reading until
verified); the second phone and its divergent data (guid merge, updatedAt rule); the rollback
story; what the eval harness and screenshot tests owe this arc. Sequencing vs the soak follow-ups
already on the board: the legacy-table drops WAIT until after this arc - dropping local history
before the backend is proven would be the bad day.
