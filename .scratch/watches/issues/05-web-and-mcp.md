---
map: watches
ticket: "05"
title: "Web assistant and /mcp watch tools"
type: grilling
status: open
status-detail: "needs Kevin: may a Claude worker or /mcp ever set a watch? The routes refuse Claude tokens today"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Web assistant and /mcp watch tools

The routes are there; engine tools over them were left out of this build on purpose. `/mcp` has
no turn concept and the Claude worker (which may read mail) uses it, so a watch write there would
not hold the read-through rule. The web assistant has turns (round 3); set/resume there must call
`read_through.remember_refusal`-style checks. Recruiters: default deny.
