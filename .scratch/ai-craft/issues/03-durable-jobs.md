---
map: ai-craft
ticket: "03"
title: "Durable jobs: agent work that survives process death"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Durable jobs: agent work that survives process death

## Question

A checkpointed job table + runner for multi-step agent work (an ingestion sweep, a research
errand) that resumes after process death instead of vanishing with the coroutine. State machine
per job (steps, status, last checkpoint, worded failure), WorkManager or the FGS as the runner,
outcome-verb rule on every claim about a job. Scope v1 to ONE real consumer (candidate: the
mirror's export/import cycle, or a multi-file ledger sweep) rather than a framework in search of
users. Deliberately after 01/02 unless a real need pulls it forward.
