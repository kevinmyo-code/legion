---
map: alfred-ota
ticket: "05"
title: "Phone updater: check, download, verify, ask, install"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-ota-phone, JVM suite green. Owed: Kevin's grants and first OTA (ticket 07)"
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

## Built (2026-11-05, branch `feat/alfred-ota-phone`)

- `update/UpdateRules.kt` (pure: version compare, Wi-Fi gate, verify, install gate, ask gate, the
  offer window), `UpdateModels.kt` (release JSON), `UpdateController.kt` (check, download, verify,
  ask, install over fakeable seams), `UpdateAndroid.kt` (engine backend, OkHttp Range download
  that never sends the engine token, PackageManager facts, PackageInstaller session with
  `USER_ACTION_NOT_REQUIRED` on 31+, prefs), `UpdateGraph.kt` (the one controller, notification
  with Install/Later, the spoken ask, receivers, daily `UpdateCheckWorker`).
- Never installs in Matrix or with the car connected (refused in words); a yes given
  mid-conversation is held and installs 3 s after the conversation ends (lapses after 15 min).
  A voice yes counts only 2 s to 3 min after the update was offered (code, not only prompt).
- The spoken ask is a proactive raise in a new category, `UPDATES` ("App updates"), default on,
  once per release, never mid-conversation, in Matrix or in the car, and it listens 20 s for the
  yes. The notification is always posted as well (the map asks for both).
- `MY_PACKAGE_REPLACED` (`PackageReplacedReceiver`) runs what `BootReceiver` runs, now shared as
  `rearmAfterRestart`, and deletes the downloaded APK.
- `REQUEST_INSTALL_PACKAGES` in the manifest; Kevin grants "Install unknown apps" once
  (Settings, Updates, Allow installs).
- Signer checks need Android 9+ (`GET_SIGNING_CERTIFICATES`); below that nothing verifies, so
  nothing installs. The A25 is Android 14.
- Verification: version compare, the Wi-Fi gate, sha256 mismatch, signer mismatch, ask gating:
  JVM (`UpdateRulesTest`, `UpdateControllerTest`). The PackageInstaller wrapper: Robolectric
  (`AndroidPackageInstallerGatewayTest`, the session shadow). On-device: owed, ticket 07,
  including the first install's Android confirm and the ART first-launch cost.
