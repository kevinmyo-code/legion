---
map: dumb-phone
ticket: "06"
title: "Build: Matrix code rain (Home A background, live wallpaper, screensaver)"
type: build
status: built
status-detail: "Suite green 2026-10-22. Rendering is owed on the A25: the rain on Home A (speed, scrim readability), the live wallpaper on home and lock (Samsung may refuse lock), the charging screensaver, battery with the screen off"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## What was decided while building

- **Speed and density are judgement calls** (11 rows a second, drops busy 60 percent of the time,
  trails of 10 to 28 rows); they are all in `RainTuning` and are the first thing to adjust on the phone.
- **Palette and scrim**: text, body and dim greens each clear 4.5:1 against the brightest rain under the
  72 percent black scrim (unit-tested with the WCAG formula); the Leave, Digest and Bounce screens use
  the same greens on plain black without rain.
- **Pause**: the home background stops when the app leaves RESUMED; the wallpaper and the screensaver
  draw only while visible/attached. With system animations off, one still frame.
- The wallpaper row does not claim the lock screen works.
