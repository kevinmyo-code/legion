---
map: matrix-open
ticket: "02"
title: "Build: All apps on Matrix HOME"
type: build
status: open
blockers: ["01"]
tags: [ticket]
---

# Build: All apps on Matrix HOME

An "All apps" row on Matrix HOME, after Books, opening a Matrix-styled drawer: text-only alphabetical
list of launchable apps, blocked ones removed (01's decision function, one source). Reuse
`ui/apps/AppDrawerCache` for the package list. No icons. Footer line: "N apps blocked. Change in
Matrix settings."

Verify: filter test (blocked never listed). On the A25: the drawer opens and lists the bank app, not
Instagram.
