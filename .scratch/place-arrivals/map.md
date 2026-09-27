---
map: place-arrivals
title: "Arrival reminders are silently not armed"
charted: 2026-09-27
tags: [map]
---

# Arrival reminders are silently not armed

Found 2026-09-27 while checking whether moving background location off GPS would hurt arrival
reminders. It doesn't: arrivals run on Google's geofencing (`location/GeofenceManager.kt`), not on
`LocationController`'s stream. But the geofences themselves are refused, so arrivals aren't working
at all.

| # | Type | What |
|---|---|---|
| 01 | build | Why Google refuses LEGION's geofences, and saying so when it does |
