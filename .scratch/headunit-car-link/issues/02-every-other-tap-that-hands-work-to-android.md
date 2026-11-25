---
map: headunit-car-link
ticket: 02
title: "Every other tap that hands work to Android can kill the launcher too"
type: bug
status: built
status-detail: "fixed on fix/hu-crash-doors-1124; owes taps in the car"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Every other tap that hands work to Android can kill the launcher too

## What happens

Ticket 01 fixed three taps on one screen. The blind review of that fix
(`.legion/reports/hu-pairnew-crash-1124-review.md`, "Base problems, not this change") found the same
bug class at other doors, already on `dev`. Two of those it read directly, the rest traced. The
GRANT ACCESS chip on Cruise is not from that list: it turned up grepping the app for the same shape,
which is the argument for a grep check rather than a list someone keeps by hand (see the follow-up
below). The whole set:

| Door | Where | Why it throws |
|---|---|---|
| APPS grid | `ui/AppTray.kt` | Launches a third-party app from a package list read once in a `LaunchedEffect`, so the list is stale by construction. Highest odds of the lot, and it is a tap on the home screen itself |
| GRANT ACCESS chip | `ui/MusicDeck.kt` on Cruise | `ACTION_NOTIFICATION_LISTENER_SETTINGS`, an optional Settings activity |
| Media access, Enable | `ui/ControlPanelScreen.kt` | A second, independent copy of that same intent |
| OPEN NOTIFICATION ACCESS SETTINGS | `media/MediaDiagnostics.kt` | A third |
| Photo pickers (4) | `ui/ControlPanelScreen.kt`, `ui/LogbookScreen.kt` | `PickVisualMedia` on API 26 to 29 falls back to `ACTION_OPEN_DOCUMENT` / `GET_CONTENT`, and this ROM may have no DocumentsUI |
| Mixtape IMPORT | `ui/LogbookScreen.kt` | `OpenMultipleDocuments`, the same shape with no fallback at all |
| TAKE PHOTO (3) | `ui/CameraCapture.kt` | A camera app this unit need not have; a misconfigured FileProvider throws here too |
| Logbook SHARE | `util/LogbookExporter.kt` | The chooser, on a detached IO coroutine with no catch of its own |
| Home-app setting, OS escape | `service/LauncherSettings.kt` | Caught `ActivityNotFoundException` only, while the OS escape starts an EXPLICIT component of another launcher, which is the case that throws `SecurityException` |
| GRANT PERMISSIONS (4) | `ui/ObdDeviceScreen.kt` | Two of them fire from a `LaunchedEffect`, so a ROM that cannot show a permission dialog killed the launcher on screen ENTRY, not on a tap |
| Startup permission request | `ui/MainActivity.kt` | Same, on boot |

Three findings from that review are also open: a stale `lastResult` surviving a later success (F2),
a non-fatal that names `RuntimeException` instead of the real exception class because the recorder
wraps it (F3), and VENDOR APP still a dead tap when its launch intent is null (F5).

## Why

Ticket 01's guard lives on `PhoneLinkManager`, is named for system screens, and writes its words
into Bluetooth state. None of the doors above is a Bluetooth screen and none has that state, so the
guard could not be reused where it was needed. The review said so in its own debt note: it "wants to
be the head unit's one door for every tap that hands work to Android, which is where it should move
when the second caller arrives."

## Fix

`util/ExternalDoor.attempt(screen, named, open)` is that door. It owns no words and no state: it
runs the launch, catches `ActivityNotFoundException`, then `SecurityException`, then
`RuntimeException` as the net, records a non-fatal naming the door and the real exception class, and
returns the sentence for what did not happen, or null. `ExternalScreen` moves out of `bluetooth/`
with it and grows an entry per door, because an app in the tray is not a Bluetooth screen.

Each caller decides where its sentence goes, because only the screen holding the tap knows where one
fits: under the button in Settings, in stamp red on the logbook's paper pages, along the bottom of
the app tray, in place of the Cruise caption. `PhoneLinkManager.openSystemScreen` becomes a caller
like any other and clears `lastResult` on success, closing F2; the door's tag closes F3; the vendor
app's null intent now goes through the door instead of returning, closing F5.

Two behaviours are deliberate, not oversights. The tray stays OPEN on a failure and names the app,
because a tray that closes on a tap that did nothing is the dead tap this is all about. The GRANT
ACCESS chip disappears with its words, because offering a second tap at a screen this ROM lacks is
the same sin.

## Verification

- [x] `AppTrayDoorTest` renders the real tray at the unit's 800x456, puts one launchable app in the
      shadow package manager, and taps it in the two states: its launcher activity gone (the stale
      list), and a context that refuses the start. Both assert the words and that the tray did not
      close. Run red on the pre-fix code: the first on the silent dead tap, the second on the
      uncaught `SecurityException`.
- [x] `NotificationAccessDoorTest` covers the door behind all three notification-access buttons with
      `checkActivities(true)`: missing, refused, and present. Run red on the pre-fix code with the
      real `ActivityNotFoundException` and `SecurityException`.
- [x] `PhoneLinkSystemScreenTest` (ticket 01's) still passes unchanged: the two sentences it asserts
      are byte for byte what they were.
- [x] Head unit CI green on the pushed commit.
- [ ] **Owed in the car:** tap an app in APPS, tap GRANT ACCESS on Cruise, tap SHARE in the logbook,
      and open Settings, Your car, OBD adapter. Not provable off the hardware: the unit has no ADB,
      and which of these screens this particular ROM has is not knowable from here.
- [ ] **Follow-up, not this ticket:** a grep check in `tools/checks/run_all.py` that fails a new bare
      `startActivity` or launcher `launch` in `headunit/`, so the next door cannot be added
      unguarded. The review asked for one; it is a RED change (it edits the checks the CI gate runs)
      and so wants its own ticket.

## Not in scope

- The paths that already catch broadly and return a typed result for their caller to render
  (`ui/DeveloperToolsScreen.kt`, `ui/ReverseCameraLauncher.kt`, `media/SpotifyWebApi.kt`,
  `update/UpdateGraph.kt`, `service/LiveToolbox.kt`). They are not crash risks; folding them into the
  one door is a refactor, and a refactor is its own commit with its own review.
- `service/BootReceiver.kt` and `service/LiveSessionController.kt` launch this app's OWN declared
  activities, not an optional system screen, and neither is a tap. There is also nowhere to put a
  sentence at boot.
- `startForegroundService` on this app's own service (the Cruise talk tap, Settings test voice).
  A missing ROM activity is not the failure mode there; a foreground-service start restriction is,
  and that is Android 12+ while this unit is Android 9.
