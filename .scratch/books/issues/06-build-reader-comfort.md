---
map: books
ticket: "06"
title: "Build: reader full screen, a one-tap Day/Night toggle that reaches PDFs, and a warm filter"
type: build
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: reader comfort

Kevin, 2026-10-23: *"and also a full screen mode for reading. and a day night dark mode toggle + like a
daylight filter u know that blue light filter thingy we have on our laptops display settings"*

Today (traced): the reader has Day / Sepia / Night in `books/ReaderSettings.kt`, applied to EPUB only.
PDF pages (`PdfRenderer` bitmaps) and comics always render as drawn, so Night does nothing there. No
immersive mode.

## Defaults (orchestrator, told to Kevin as changeable)

1. **Full screen.** Opening a book hides the status and navigation bars (immersive, swipe from an
   edge to peek). **A tap in the middle third of the screen shows or hides the reader's own top and
   bottom bars**; page turns keep their existing edge taps and swipes. Remembered on/off in
   `ReaderSettings`, default on.
2. **Theme toggle in the top bar**, one tap cycles Day -> Sepia -> Night, icon shows the current one,
   content description in words. Still also in the settings sheet.
3. **Night reaches PDFs.** In Night, PDF pages draw through a `ColorMatrix` that inverts lightness
   (white page -> near-black, black text -> light grey), so photos and figures invert too; Sepia tints.
   **Comics are never inverted** (art). Pure function for the matrix, unit-tested.
4. **Warm filter**, the laptop "night light": a slider 0-100% in the settings sheet plus an on/off
   in the top bar. It is an amber overlay (`#FF9A2E`-ish, multiply blend, max ~35% alpha at 100%) drawn
   over the whole reader, every format, independent of theme. Remembered strength and on/off.
   **Auto option:** on from local sunset to sunrise using the sun-times function the nav map already
   has (`navigation/MapLight.kt`); no location fix -> follows the on/off as set.

This is LEGION's own overlay inside the reader, not the system Eye comfort shield; it does not tint
other apps.

## Tests

ColorMatrix values per theme, the warm-filter alpha curve, the Auto window across midnight and with
no fix, settings round-trip. Compose: the middle-third tap toggles chrome, edge taps still turn pages.

## Owed on the A25

Full screen on EPUB, PDF and CBZ; Night on a PDF page; warm filter at 50% and 100% at night.
