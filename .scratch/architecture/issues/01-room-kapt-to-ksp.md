---
map: architecture
ticket: "01"
title: "Room moves from kapt to KSP"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Room moves from kapt to KSP

First, so Hilt does not add a second annotation processor on top of one that should already be gone.
Room 2.8 supports KSP. `exportSchema` stays on; the generated schema JSON under `app/schemas/` must
be byte-identical before and after (kapt and KSP generate the same SQL for the same entities, and
`CarDatabaseSchemaVersionTest` plus every `MigrationNNToMMTest` diff against the live JSON, so a
drift shows as red). The KDoc `\uXXXX` escape that broke kapt on 2026-09-01 is irrelevant under KSP;
leave the reworded comments alone anyway.

Verify: `compileDebugKotlin -Pnokey`, full suite from the JUnit XML, `git diff --stat app/schemas`
empty, and measure clean-build time before and after, recorded in this ticket.
