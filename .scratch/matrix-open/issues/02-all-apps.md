---
map: matrix-open
ticket: "02"
title: "Build: All apps on Matrix HOME"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: All apps opens from Matrix HOME after Books, lists the banking app and not Instagram, an app opens from it without a bounce, the footer count matches Blocked apps, and tapping the footer opens Matrix settings."
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
