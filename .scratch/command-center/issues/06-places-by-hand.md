---
map: command-center
ticket: "06"
title: "Saved places you can manage"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Saved places you can manage

Survey: `tag_place`, `forget_place`, `get_current_location`, `open_navigation` all voice-only, and
`SavedPlacesScreen` renders labels as one Text blob with no rows. A misheard `forget_place` is
permanent.

## Build

Rework `SavedPlacesScreen`: one row per place (label, coords/address), delete with confirm (same
`PlaceController.forgetPlace`), "tag current location" button (same `tagPlace`, label typed),
a current-location readout (same source `get_current_location` reads), and a navigate icon per
place firing the same intent shape `open_navigation` uses.

## Rules

- ADR 0035: same `PlaceController`/`LocationController` functions. Location permission absent is an
  honest state naming the grant, not a blank screen.

## Verification

- Suite green both ways. On the phone: tag here, see it listed, navigate to it, delete it.
