---
map: location-intelligence
ticket: 8
title: "Garage on approach, as an offer"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-geofences]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Garage on approach, as an offer

## What to build

Geofence entry at home while the drive Phase is active -> the assistant **asks** *"open the garage?"*
-> one word from Kevin -> the Shelly relay fires.

## Why it is never automatic

Settled decision 10. **The relay is a single-button toggle that cannot report door state** - CLAUDE.md
already forbids saying "opening" or "closing" for exactly that reason. An automatic trigger on a
location guess could therefore close a door on something, with nobody having decided and no way to
know which way it went. A GPS drift or a drive-past is enough.

**Silence does nothing.** No answer is not consent - consistent with the decline detector, where
silence is neither a refusal nor an agreement. He opens it the normal way.

## Rules

- Reuses the existing `activate_garage` confirmation path, which already requires a yes in the
  immediately preceding turn. **Do not build a second confirmation mechanism.**
- The offer itself is a raise and goes through the gate like everything else. Category: **Timing**.
- Never says "opening" or "closing". "Triggering" or "hitting" the garage.

## Verification

- **On the phone, in the car**, and this one cannot be faked: arriving home actually produces the
  offer, and saying nothing actually does nothing.
