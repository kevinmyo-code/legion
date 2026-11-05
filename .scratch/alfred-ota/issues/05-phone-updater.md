---
map: alfred-ota
ticket: "05"
title: "Phone updater: check, download, verify, ask, install"
type: build
status: open
status-detail: "charted 2026-11-05; after 04 and decision 3"
blockers: ["04"]
blocked-by: ["[[04-version-and-publisher]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Phone updater: check, download, verify, ask, install

## What

**Check.** `update/UpdateChecker.kt` checks `/api/releases/latest?app=phone`:
- on app start
- once a day, via WorkManager
- on demand (ticket 06)

A `version_code` newer than `BuildConfig.VERSION_CODE` means an update is available.

**Download.**
- Wi-Fi only by default, because the APK is about 180-220 MB. A setting allows mobile data.
- The signed URL downloads into the app cache, resumably (Range requests).
- The file's sha256 must equal the release's.

**Verify.**
- The signer SHA-256 from `PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` must equal the installed app's own signer.
- On a mismatch the file is discarded and never installed, and Alfred says so in words.

**Ask (decision 3: always).**
- A notification says "LEGION update ready: <notes>. Install?", with Install and Later.
- Alfred also says it once at the next natural pause: never mid-conversation, never in Matrix, never while the car is connected.
- Kevin says yes by voice or taps Install.

**Install.**
- A `PackageInstaller` session, with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on API 31+.
- The first update after an adb install still shows Android's own confirm, because adb is the installer of record. After that LEGION is the installer of record and no Android dialog appears; Kevin's yes is the consent.
- The manifest declares `REQUEST_INSTALL_PACKAGES`. Kevin grants "Install unknown apps" once.

**After install.**
- A `MY_PACKAGE_REPLACED` receiver restarts the foreground service and the wake word.
- The HOME and ASSISTANT roles survive, as they do with `adb install -r`.
- An app cannot ask for a speed compile, so the first launch is slower until ART compiles it in the background. The notes say so, and ticket 07 measures it.

**Never downgrade.** A rollback is a new build of the old code with a higher versionCode.

## Verification

- JVM tests:
  - version compare
  - the Wi-Fi gate
  - a sha256 mismatch refuses
  - a signer mismatch refuses
  - ask gating: no prompt mid-conversation or in Matrix
- Robolectric covers the PackageInstaller wrapper boundary, with a fake session.
