---
map: worker-workflows
ticket: "07"
title: "Web desk: workflows with last run and Run"
type: build
status: open
status-detail: "Deferred 2026-11-10: not nearly free (the web needs node, vitest and a schema.d.ts regeneration). Endpoints it needs are built (GET workflows/, POST workflows/run/)."
size: S
blockers: ["03"]
blocked-by: ["[[03-engine-catalog-and-trigger]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Web desk

On `/claude` (desk rail "Claude", built in claude-session ticket 09): a Workflows panel from
`GET workflows/`. Per workflow: name, `says`, schedule in words ("about once a day, when the
laptop is idle"), last run and status in words, "next due" or "waiting on your yes", and a Run
button (`POST workflows/run/`) with an optional params box. Scheduled tasks in the task list carry
a "Scheduled" label. Hidden on a recruiter visit, like the rest of `/claude`.

## Verification

- [ ] Web tests: panel renders from a fixture catalog; Run posts the name; hidden for recruiter.
