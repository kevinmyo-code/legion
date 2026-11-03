---
map: procedures
ticket: "14"
title: "Build: branch report and run inspector on the web desk"
type: build
status: open
status-detail: ""
blockers: ["07", "13"]
blocked-by: ["[[07-recruiter-visits]]", "[[13-build-run-traces]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Build: branch report and run inspector on the web desk

## What

- **Run inspector** (web, desk tree, members only; recruiters per ticket "Recruiter visits"): list
  of runs (procedure, version, caller, status, ms), a run's steps as a vertical timeline with
  args, trimmed results, branch taken, who chose it (code / model / user), and the steps that did
  NOT run, said in words.
- **Branch report** per procedure version over a window: for each branch, runs that took it, runs
  that failed in it, and **branches that never ran**, with the count of runs it covers. Plain
  table, status in icon + word (CLAUDE.md chart rules from recruiter-demo ticket 14).
- **"Run this procedure" button** (ADR 0035 hands path) calling the same `run_procedure` through
  the tool door, ask steps rendered as buttons.
- Reads through REST views scoped by `household_of`; recruiter routes added to
  `household.recruiter_routes` only for their own visit.

## Verify

- vitest per screen state (runs, empty, unreadable, recruiter).
- Screenshots at 1440 light and dark.
