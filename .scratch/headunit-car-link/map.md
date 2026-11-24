---
map: headunit-car-link
title: "Map: the head unit's phones, Bluetooth and car link"
charted: 2026-11-24
charted-by: ""
effort: "`.scratch/headunit-car-link/`"
tickets: 1
open: 1
status: open
tags: [map]
---
# Map: the head unit's phones, Bluetooth and car link

## Destination

**Every way the head unit reaches the phone works on the unit Kevin actually owns, and when the
hardware cannot do a thing it says so.** Two surfaces, both under Settings, Phones & audio:

- **Phones** (`ui/BluetoothManagerScreen.kt`, `bluetooth/PhoneLinkManager.kt`): which phone carries
  audio and calls, pair a new one, forget one, reset the stack.
- **Car link** (`ui/PairingScreen.kt`, `link/`, protocol in
  `.legion/reports/headunit-link-protocol-1120.md`): the QR-plus-six-digits pairing that hands the
  unit its engine sign-in, the Mapbox token and the phone's GPS fixes.

## Notes

**Domain:** the head unit app, `headunit/` (package `com.kevin.midnightai`, its own Gradle project).
Read `headunit/CLAUDE.md` for its rules and the root `CLAUDE.md` for everything repo-wide.

**The hardware is the constraint, not the API.** The unit is a MediaTek AOSP build (Android 9,
32-bit armeabi-v7a) with pieces of Android missing: its own GPS kills the
radios (`headunit/CLAUDE.md` section 14), a vendor app owns the Bluetooth phones, and optional system
activities simply are not installed. There is no ADB on it, so a crash is only ever seen as a drive
log the unit uploads itself, or as a report from the car.

**A launcher may not crash and may not go quiet.** The head unit app is the car's home screen, so a
tap that throws leaves the driver with nothing. Root `CLAUDE.md`: a home app "owes the user every
other app and never a dead tap". Every tap that hands work to Android therefore needs an answer in
words for the case where this ROM cannot do it.

## Tickets

| # | Title | Type | Status |
|---|---|---|---|
| 01 | [PAIR NEW PHONE crashes the launcher](issues/01-pair-new-phone-crashes-the-launcher.md) | bug | built |
