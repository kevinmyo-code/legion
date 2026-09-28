---
map: home-launcher
ticket: "06"
title: "A dock of five pinned apps above the talk bar"
type: build
status: built
status-detail: >
  Built 2026-09-27: DockPins/DockPinsStore (ui/apps/DockPins.kt, pure logic
  plus SharedPreferences), AppDrawerCache's own icons reused (no second
  LauncherApps query), AppDock (ui/home/AppDock.kt) between the tile grid and
  the talk bar, pin/unpin from AppsScreen's own long-press menu. Suite green
  (3718 tests), detekt clean for every touched file, screenshots recorded and
  looked at (full dock, empty dock, one not-installed slot, the 360x520
  fallback). Owed on the phone (ticket 05): pin from the drawer, launch from
  the dock, unpin, a work app, an uninstalled app.
blockers: ["03"]
blocked-by: ["[[03-home-is-a-launcher]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# A dock of five pinned apps above the talk bar

**Kevin, 2026-09-27:** *"on the main screen, i want a dynamic shortcut list of apps. like 5 apps
that i open the most. quick shortcuts."* Then, offered launch-counting or Android usage stats:
*"actually just let me manually pin apps to home screen like the usual android home does"*, and the
dock above the talk bar over a row under the today card.

LEGION is the phone's home app (ADR 0050), so this is the ordinary launcher dock. Nothing is
counted or ranked; there is no usage tracking and no new permission.

## Behaviour

- **Five slots, one row, on HOME only**, between the tile grid and the talk bar. Each slot: the
  app's launcher icon (48dp touch, about 44dp icon) and its label under it (`labelSmall`, one line,
  ellipsised - an app label is not a trust disclosure). Tap launches it exactly the way the drawer
  does (same `LauncherApps` call, same failure sentence on a failed start - ADR 0050 rule 4, never a
  dead tap).
- **Pin from the drawer:** long-press an app in `AppsScreen` for a small menu, "Pin to home" /
  "Unpin from home". Full dock: the menu says "Dock is full. Unpin one first." in words; nothing is
  replaced silently.
- **Unpin and reorder from the dock:** long-press a dock icon for "Unpin", "Move left", "Move
  right".
- **Empty dock:** one line in `text2` where the icons would be - "Long-press an app in Apps to pin it
  here." Never a blank strip.
- **Work-profile apps** may be pinned; they carry the same WORK label in words the drawer uses.
- **A pinned app that is no longer installed** (or its profile is paused) stays in its slot, dimmed,
  labelled "Not installed" / "Paused", and a tap says so instead of failing silently. Unpin still
  works.

## Storage

Pins are **device-local config**, not household data: which apps exist is a fact about this phone.
Store an ordered list of `(packageName, userSerial)` (the profile's serial from `UserManager`) in
the app's own preferences. Not Room, not synced, not in any backup-restore meaning beyond what
`SharedPreferences` already gets. A tiny `DockPins` object (or class) owns read/pin/unpin/move with
a max of five, unit-tested as pure logic over an in-memory store.

## Fit

HOME still does not scroll at 384 x 636dp. The dock takes about 72dp; the tile grid gives it up
(rows share the remaining height). Re-record `HomeContentScreenshotTest` states with a full dock,
an empty dock, and one "Not installed" slot, and check the 360 x 520 fallback still scrolls rather
than clips. **Trust-disclosure lines on tiles must still show in full with the dock present** - look
at `home-alerts.png`.

## Boundaries

`ui/apps/` belongs to the home-app work on dev (AppDrawerCache made the drawer fast; keep reading
through it). Add the long-press menu without changing how the list loads. Icons come from the same
source the drawer uses, and the dock must not add a second slow `LauncherApps` query on HOME's
first frame - read icons for at most five packages, off the main thread, and show the label while an
icon loads.

## Verification

Compile, full suite green (XML totals), `DockPins` tests (pin, unpin, max five, move, missing app),
the screenshots above looked at, detekt clean for touched files. Owed on the phone (ticket 05):
pin from the drawer, launch from the dock, unpin, a work app, an uninstalled app.
