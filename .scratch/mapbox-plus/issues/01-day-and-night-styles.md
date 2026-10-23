---
map: mapbox-plus
ticket: "01"
title: "Build: day and night map styles that are easy to read, switching like Google's"
type: build
status-detail: "Built 2026-10-23 on Mapbox Standard (Maps SDK 11.32) lightPreset, switched in place with no style reload. Pure sunrise/sunset picker unit-tested. Owed on the phone: day and night screenshots, a mid-trip Auto/Day/Night switch on the drive (ticket 02), route line, puck and banner at 3:1 on both looks, and Standard style speed on the A25."
status: built
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: day and night map styles

Kevin, 2026-10-23: *"right now its always night mode. hard to see sometimes. i like google maps day and night
mode styles theyre easy to see."*

**Cause (traced):** `ui/navigation/NavMap.kt` loads `Style.DARK` unconditionally (lines 236 and 253).

## Rulings

1. **Automatic by default, like Google:**
   - Day style from sunrise to sunset.
   - Night style otherwise.
   - Sunrise and sunset come from the current location fix. With no fix, follow the system dark theme, and say so nowhere (it is a style, not a fact).
2. **Manual override:** a setting with Auto (default), Day, or Night, reachable from the nav screen.
3. **Readability:**
   - Use Mapbox's navigation-tuned styles: the Mapbox Standard style with its `lightPreset` (day / dusk / dawn / night) if the SDK in the build supports it, else Navigation Day and Night.
   - The route line, the puck and the banners must hold at least 3:1 contrast on both.
   - Pick whichever the SDK version in `libs.versions.toml` supports, and say which in the commit.
4. **Switching:**
   - Switch while guiding without reloading the route or losing the camera.
   - A style change must not drop the route layer: re-add it on style load if needed.
5. **Matrix mode:** the map keeps these styles. It does not turn green, because readability wins over theme on a map.

## Verification

- Unit-test the pure sunrise/sunset to preset choice: across midnight, polar edge cases, and no fix.
- Compile and run the full suite.
- On the device: daytime and night-time screenshots, plus a mid-trip switch. Those are owed to Kevin's drive (ticket 02).
