---
map: architecture
ticket: "06"
title: "Convert as touched, and when the shim retires"
type: decision
status: open
status-detail: "Rule to write once 02 lands and the EntryPoints.get count is known."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Convert as touched, and when the shim retires

The long tail: everything not covered by ticket 04 converts when its screen or controller is next
edited for another reason. That is a rule only if it is written down with a stop condition, or it
becomes "later" forever.

Decide here, once ticket 02 has the baseline count: the `EntryPoints.get` count at which the shim is
removed in one sweep (proposal: under 10), and whether the count is reported by the `/verify` skill
so it is seen every run rather than remembered.
