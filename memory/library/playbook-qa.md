# QA/QC Playbook

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


Accumulated test procedures and device quirks for the qa agent. Maintained by the librarian: the
orchestrator relays SKILL: lines from qa agent reports via a librarian FILE dispatch, which
appends them here.

Midnight AI (historical names in older notes: Moose, Aria, Nightrunner; same app).

## Build & install

- Debug build: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
- Release build: `./gradlew assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`
- Distributable copy lives at repo root as `moose-copilot.apk` (legacy filename, refresh from
  release build after a wave).
- Install: `adb install -r <apk>`; main activity: `com.kevin.midnightai/.ui.MainActivity`.

## Known device quirks (head unit = real target)

- Emulator CANNOT capture mic + play audio simultaneously, full-duplex voice unverifiable on
  emulator; defer audio/voice checks to the head unit.
- Head units typically lack a `RecognizerIntent` provider (no on-device speech-recognition
  activity).
- Head-unit photo picker (`PickVisualMedia`) shows empty Photos/Albums and doesn't index
  Drive-download files.
- Screen is fixed-landscape 6.86"; verify layouts there, not in portrait.
- ADB logcat is blocked on the head unit (dev options hidden on vendor menu); Crashlytics
  breadcrumbs are the observability workaround. See CLAUDE.md sec 14.

## Field-test bugs to regression-check (from 2026-06-28 test)

1. "go to next track" crashes the app.
2. "start playing music" works but crashes right after playback starts.
3. AI says "playing <song>" but nothing actually plays (false success).
4. Subtitles don't appear under the companion in the cruise view.
5. Photo upload can't find a Drive-downloaded car photo.
6. SPEAK buttons (avatar regen / personality menus) do nothing.

## How to capture a crash

`adb logcat -c` then reproduce, then `adb logcat -d *:E AndroidRuntime:E` and grab the stack
trace; identify the throwing class:line. Not usable on the head unit itself, only on an
emulator/AVD or a device with dev options exposed.
