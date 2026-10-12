---
map: backend-erp
ticket: "32"
title: "Engine retirement repoints: five aspects back on their own tables"
type: build
status: resolved
status-detail: "Recorded 2026-10-11. Built 2026-08-27/28 in five commits before this ticket existed; step 6 cancelled by ticket 18."
blockers: ["15"]
blocked-by: ["[[15-engine-retirement-sequence]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Engine retirement repoints: five aspects back on their own tables

**A record, not a plan.** Ticket 15 sequenced the rewire and was built directly, without a build
ticket in between, so CLAUDE.md section 12's check could not see it. This ticket exists so it can.

## What was built

| Step | Commit | |
|---|---|---|
| 1 Places | `0551ad9` | one store serves both paths |
| 2 Pantry | `7a50aa2` | repointed, anchors carried |
| 3 Fleet | `65e1f68`, `f155198` | Vehicle already legacy-primary; service history became ticket 16 |
| 4 Notes and Dates | `ab6ec1b`, `8aecdc7` | one local events table, ids preserved (`b17bc88`) |
| 5 Ledger | `f5dcdee` | off the engine, gate order intact |
| 6 Delete `engine/` | none | cancelled by ticket 18 (`f418318`) |

## Caveat carried from ticket 15

Ticket 15's own closing line: none of the five repoints was exercised on the A25; they were compile
and suite green only, and the unconfigured path is the clone-and-run path no device here has run.
The Django move (`.scratch/django-engine/issues/09-android-http-backends.md` and `10-cutover.md`)
rewires the phone's backends again, so the cutover's step 6 counts are where these paths next get
checked on hardware.
