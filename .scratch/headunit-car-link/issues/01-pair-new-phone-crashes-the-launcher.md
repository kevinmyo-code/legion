---
map: headunit-car-link
ticket: 01
title: "PAIR NEW PHONE crashes the launcher"
type: bug
status: built
status-detail: "fixed on fix/hu-pair-new-crash-1124; owes a tap in the car"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# PAIR NEW PHONE crashes the launcher

## What happens

Reported from the car, 2026-11-24: on the head unit build installed in the car, tapping **Pair new
phone** crashes the app. The app is the car's home screen, so the crash takes the whole launcher
with it.

Settings, Phones & audio, Phones is the screen (`ui/BluetoothManagerScreen.kt`); PAIR NEW PHONE is
its first footer button.

## Why

The tap asks Android to open a system screen and nothing catches the case where this ROM does not
have one:

```kotlin
onPairNew = { discoverableLauncher.launch(PhoneLinkManager.pairNewPhoneIntent()) },
```

`pairNewPhoneIntent()` is `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE`. That activity is an
optional part of Android, living in the stock Settings app. The unit is a MediaTek AOSP build whose
Bluetooth phones are owned by a vendor app, and whose Settings is cut down; with no activity to
resolve to, `launch` throws `ActivityNotFoundException` straight out of the tap, through Compose's
click handler, and nothing above it catches. An uncaught exception on the main thread is the
launcher dying.

That path is reproduced, not reasoned: with the guard removed, the test below fails with
`ActivityNotFoundException` thrown from `ComponentActivity.startActivityForResult` inside the
launcher's own `launch`. What is inferred is that this is the crash reported from the car, because no
uploaded drive log holds a stack for it, which is what the non-fatal below is for.

Two more taps on the same screen had the same unguarded shape: SYSTEM SETTINGS
(`Settings.ACTION_BLUETOOTH_SETTINGS`) and VENDOR APP.

`PhoneLinkManager`'s own contract already says "Nothing here can crash the app: every reflective or
platform Bluetooth call is wrapped". The three intent launches sat in the UI, outside that boundary,
which is why the invariant held everywhere except here.

## Fix

One door, inside the boundary that already promises not to crash:
`PhoneLinkManager.openSystemScreen(screen, open)` runs the launch and turns a throw into one line of
`PhoneLinkState.lastResult`, which the screen already renders under the phone list. `ExternalScreen`
names the three screens and carries the words for each, so a tap that cannot be honoured says which
screen this unit does not have and what to do instead.

**It also records a non-fatal naming the screen and the exception class**
(`MidnightEvents.recordError`), because the crash itself was never seen: the uploaded drive logs hold
no stack for it and the unit has no ADB, so which exception this ROM throws is a diagnosis to read
off the next drive log rather than a guess to bake into a narrow catch. That is why the catch is
`ActivityNotFoundException`, `SecurityException` and then `RuntimeException`, not only the first:
being wrong about the class would mean the launcher still dies and another OTA round trip to the car
to find out.

## Verification

- [x] `PhoneLinkSystemScreenTest` renders the real screen in Robolectric at the unit's 800x456 with
      `checkActivities(true)` (which makes Robolectric refuse an unresolvable intent the way a device
      does), taps PAIR NEW PHONE and SYSTEM SETTINGS, and asserts the words. It fails on the code
      before this fix: the tap throws.
- [ ] **Owed in the car:** tap PAIR NEW PHONE on the next build installed on the unit and see the
      sentence instead of a crash. Not provable off the hardware: the unit has no ADB.
- [ ] **Follow-up, not this ticket:** nothing else in the app pairs a phone, so the words are the
      whole answer for now. Pairing a phone from this screen on a ROM that cannot show the system
      dialog would be a feature ticket, not this fix.

## Not in scope

`BLUETOOTH_ADVERTISE` is undeclared, which on Android 12+ would make
`ACTION_REQUEST_DISCOVERABLE` throw `SecurityException`. The unit is Android 9 so it is not this
crash, and the fix above already says it in words rather than crashing. Declaring the permission is
a manifest change for hardware we do not have.
