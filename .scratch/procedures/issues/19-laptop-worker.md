---
map: procedures
ticket: "19"
title: "Laptop worker: procedures for the Claude session"
type: grilling
status: open
status-detail: ""
blockers: ["12"]
blocked-by: ["[[12-build-runner-and-tool]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Laptop worker: procedures for the Claude session

## Question

Kevin: "for everything in legion ... laptop". On dev there is no laptop worker: the Claude session
map (`.scratch/claude-session/`) is parked (`kiv`, "dont build it yet", 2026-10-29) and ADR 0067
lives only on `feat/claude-session*`. **Cross-map blocker: this ticket also waits on that map
unparking**; the tracker cannot express it, so it is written here.

## Candidate shapes (to decide when it unparks)

- **A. The worker is an MCP client**: its main Claude session calls `run_procedure` on `/mcp`
  with its device token, so "what's due on the car" asked of the laptop gets the same answer as
  the phone. Cheap; no new step kind.
- **B. A `laptop_task` step kind**: a procedure step that enqueues a Claude session task
  (`POST /api/claude/tasks/`) and returns `waiting` until `tasks/{id}/finish/`. Lets a procedure
  like "find the AW4 wiring PDF and attach it to the car record" span phone and laptop. Bound by
  Claude session rulings 2 and 6 (named or yes; the ask list) and 8 (results 30 days).
- **C. Distil Claude session traces** (`claude_tasks` turns) into procedures. Later, if ever.

## Recommendation

A when it unparks; B only after the first procedures have run for a month.
