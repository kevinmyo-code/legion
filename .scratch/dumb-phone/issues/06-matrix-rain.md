---
map: dumb-phone
ticket: "06"
title: "Build: Matrix code rain (Home A background, live wallpaper, screensaver)"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: Matrix code rain

Kevin, 2026-10-22, replacing grayscale: green code rain at CLASSIC movie speed.

- Home A background: animated rain on Canvas, a dark scrim behind the text list, Matrix green on black
  palette, text at least 4.5:1. Paused when the app is not resumed.
- Live wallpaper (`WallpaperService`), same renderer, for home and lock. A setup row opens the system
  live-wallpaper picker. Samsung may restrict live wallpapers on the lock screen: said in words, not
  claimed.
- Charging screensaver (`DreamService`), same renderer, and a setup row that opens Screen saver settings.
- One shared renderer and one place for the speed and density constants.

Verify: pure model tests (column stepping, trail fade, seeded RNG). Rendering is owed on the device.
