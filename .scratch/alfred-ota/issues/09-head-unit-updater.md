---
map: alfred-ota
ticket: "09"
title: "Head unit updater: check, download, verify, tap to install"
type: build
status: built
status-detail: "built 2026-11-06 on feat/headunit-ota with the Live move to gemini-3.8-live, 39 targeted JVM tests green, fast -Pnokey APK 263101640 built, publisher dry-run passed. Owed: Kevin's one manual install and the Install-unknown-apps grant on the car; the first real publish; the on-device run below"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Head unit updater: check, download, verify, tap to install

Split out of [08](08-later.md)'s "Head unit" bullet. **Kevin, 2026-11-06:** *"can the headunit
also receive updates like this instead of me having to install a new apk everytime"* ... *"yes"*.

## What

The phone's updater (ticket 05), copied into `headunit/` (a separate Gradle project, so no
shared module): `headunit/app/src/main/java/com/kevin/midnightai/update/`.

- **Auth.** Nothing new. `GET /api/releases/latest?app=headunit` and `GET /api/releases/<id>/download`
  go through the head unit's existing `EngineHttp` on the device token saved by Settings, Engine
  (the one `ask_engine`, drive logs and fleet upload use). Both routes need only a household
  member. The signed Cloud Storage URL is fetched with a bare client that never sends the token.
- **Check.** 20 s after `AriaForegroundService` starts, then every 6 h while it runs (no
  WorkManager: the unit only has power with the ignition on). Settings has Check now.
- **Download.** Resumable (Range) into `cacheDir/updates`. **Metered networks are allowed by
  default**, unlike the phone: the unit's internet is the phone's hotspot, which Android reports
  as metered. Settings, System, Updates switches it to Wi-Fi only.
- **Verify, all before install, every failure in words and the file deleted:** sha256 against
  the release, the package is `com.kevin.midnightai` (the phone's APK carries the same debug
  key), signer equals the installed app's and the release's, versionCode strictly higher (never
  a downgrade), and the APK's `lib/<abi>/` folders fit `Build.SUPPORTED_ABIS`.
- **Prompt.** A Cruise chip in the console column, "UPDATE READY · TAP TO INSTALL" with the notes,
  56 dp, Aria tokens, no motion. Hidden while the car moves. Settings, System, Updates shows the
  installed version and ABI, the newest release, Check now, Install, Allow installs and the
  hotspot toggle. Both call the one `UpdateController` (ADR 0035). No voice tool on the head unit.
- **Driving.** `update/DrivingState`: an OBD 010D reading (TelemetryRecorder, every 30 s) or a
  location fix with speed above 5 km/h stamps "moving"; the car counts as driving for 2 minutes
  after. No speed source at all counts as parked (the tap is the parked act). Engine-running is
  not used: it would block every install on a unit that only runs with the ignition on.
- **Install.** A `PackageInstaller` session (no FileProvider needed; the bytes stream into the
  session), `USER_ACTION_NOT_REQUIRED` on API 31+. Manifest: `REQUEST_INSTALL_PACKAGES` and
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. A tap mid-conversation installs 3 s after it ends (lapses
  after 15 min); a moving car or a missing grant refuses in words. Without the grant, the tap opens
  Android's per-app "Install unknown apps" screen. If Android still wants its confirm
  (`STATUS_PENDING_USER_ACTION`), it is shown at once, since it only ever follows a tap.
- **After install.** `MY_PACKAGE_REPLACED`: deletes the APK and starts Cruise (or the beacon
  service in GPS-beacon role), as `BootReceiver` does. As default HOME the system usually
  relaunches Cruise itself.
- **versionCode.** `fast` and `release` take `yyDDDHHmm` UTC, as the phone; `debug` stays 1.
  versionName stays `1.0+<sha>` (`device_logs.app_version_name` reads it). Both ABI splits get
  the same code.
- **API range.** Built and tested for API 26+ (the car is Android 14). Below 26 nothing crashes:
  every newer call is version-guarded; below Android 9 the signer cannot be read, so nothing
  installs.

### Silent updates on Android 14 (from the `setRequireUserAction` docs, 2026-11-06)

Android skips its own confirm only when ALL hold: `USER_ACTION_NOT_REQUIRED` is set; the app
being installed targets API 31+ on Android 14 (the head unit targets 34); the installer is the
installer of record, the update owner, **or is updating itself**; the installer declares
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`; and "Install unknown apps" is granted. A self-update meets
the installer condition even right after a manual sideload, so the first OTA may already be
silent unless the sideloading app turned on update-ownership enforcement (Play does; a file
manager normally does not). The docs say an installer must always be ready for
`STATUS_PENDING_USER_ACTION`, and a vendor ROM may differ: on-device unverified.

**Found for the phone, not changed here:** `app/src/main/AndroidManifest.xml` declares
`REQUEST_INSTALL_PACKAGES` but not `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, so by the same docs
every phone update will show Android's confirm. Adding that one line to the phone manifest is a
follow-up for ticket 07.

## Publish (Kevin runs it via `!`, or the worker after his yes)

The `fast` build is what goes on the car. Both ABI splits are byte-identical today (no native
libraries), so publish the arm64 one. From the repo root:

```
cd headunit && ./gradlew assembleFast -Pnokey && cd ..
python tools/release_apk.py --app headunit \
    --apk headunit/app/build/outputs/apk/fast/app-arm64-v8a-fast.apk \
    --notes "what this release fixes, one line"
```

with `LEGION_ENGINE_URL`, `LEGION_ENGINE_TOKEN` (an owner's token) and `LEGION_APK_BUCKET` set
(ticket 04). Add `--dry-run` to check the file only.

Dry run, 2026-11-06, against the `-Pnokey` APK built at `6ba0998f` (updater plus the Live move to
`gemini-3.8-live`):

```
signer       fd819ada7fdbf98f4b072b511d2dff33cb3dc68b720b8a9a0ff862ebd03aac01 (matches the pin)
version      263101640 (1.0+6ba0998f)
sha256       81353012f77cc09b9abdbc943888bb7a2f84a6ed47813b507e57291ea8163669
size         16744577 bytes
--dry-run: the file checks above ran; nothing below did.
[skipped] the engine's newest release was not read, so the versionCode is unchecked
[would]   gcloud storage cp headunit\app\build\outputs\apk\fast\app-arm64-v8a-fast.apk gs://<LEGION_APK_BUCKET>/headunit/263101640-81353012f77c.apk
[would]   POST <LEGION_ENGINE_URL>/api/releases/ {'app': 'headunit', 'version_code': 263101640, 'version_name': '1.0+6ba0998f', 'sha256': '81353012f77cc09b9abdbc943888bb7a2f84a6ed47813b507e57291ea8163669', 'size_bytes': 16744577, 'object_name': 'headunit/263101640-81353012f77c.apk', 'notes': 'head unit OTA updater; Live on gemini-3.8-live', 'signer_sha256': 'fd819ada7fdbf98f4b072b511d2dff33cb3dc68b720b8a9a0ff862ebd03aac01'}
```

The signer pin is the phone's (`deploy/release/signer.sha256`): the head unit's `fast` is signed
with the same laptop debug key. Its own `release.jks` signs only `assembleRelease`, which is not
what goes on the car. The publisher now also refuses an APK whose package is not the `--app` named
(a phone APK sent as `headunit`, or the reverse).

## Kevin, once, on the car

1. Copy `app-arm64-v8a-fast.apk` from this branch's build to the car and install it by hand, as
   today. It is the last manual install. The installed build must be version 263101640 or later;
   the car's current build is versionCode 1 and never updates itself.
2. Settings, System, Updates, Allow installs, OPEN ANDROID SETTING: switch on "Allow from this
   source" for Midnight AI.
3. Confirm Settings, Engine shows signed in (the update check uses that token).

If step 1 fails with a signature mismatch, the car's current build was not signed with this
laptop's debug key (reasoned, not checked: there is no adb on the car). Then the only way in is
an uninstall, which wipes the Keystore secrets, sign-ins and the Room database. Stop and decide
before uninstalling.

**Car builds are `-Pnokey` (Kevin, 2026-11-06):** the car has its own Gemini key saved in
Settings, so a car build carries no dev key, and no published APK puts one in the bucket. Without
`-Pnokey` a build bakes the `local.properties` key in as the fallback when none is saved.

## Also on this branch: the phone's models (Kevin, 2026-11-06)

*"seemed the car build had outdated models? can u check"*, so Kevin's one manual install carries
both. Live moved from `gemini-3.1-flash-live-preview` to `models/gemini-3.8-live` with the phone's
3.8 rules (`service/LiveSetup.kt`): every declaration `BLOCKING`, no `thinkingConfig` /
`proactivity` / `enableAffectiveDialog`, root `voiceActivity` ACTIVITY_START cancels the idle
timer. SubAgent keeps `gemini-3.5-flash-lite` (the phone's default too) and retries once on
`gemini-3.8-flash` after a 429 or 500/503. `GeminiKeyValidator` stays on `gemini-3.5-flash-lite`.
Tests: `LiveSetupTest` (6), `SubAgentFallbackTest` (6). On-device owed: 3.8-live accepts the setup
on the car, holds the session and speaks an opener; a tool call (say `get_health`) answers after its
result, not before.

## Verification

- JVM: `UpdateRulesTest` (12), `UpdateControllerTest` (12), `UpdateAndroidTest` (3, Robolectric
  SDK 34: the PackageInstaller session, the engine call path and token, a download that never
  sends the token and resumes with Range). 27 tests, 0 failures, from the JUnit XML.
- Publisher: `tools/tests/test_release_apk.py`, 14 passed (2 new: the head unit on the debug pin,
  the wrong app refused).
- `assembleFast -Pnokey`: both splits 16,744,577 bytes and byte-identical, versionCode 263101640,
  signer `fd819ada...aac01`. No dev key inside: `BuildConfig.GEMINI_API_KEY` is empty in the dex,
  and no zip entry contains the `local.properties` key (the one key-shaped string, in
  `resources.arsc`, is the Firebase key from `google-services.json`).
- **On-device, owed (Kevin):**
  1. The manual install above, the grant, then publish a second build.
  2. Parked, ignition on: within a minute of start the Cruise chip reads UPDATE READY. Tap it.
     Note whether Android shows its own confirm (the silent-update question above).
  3. Cruise comes back on its own after the install; Settings, System, Updates shows the new
     version code.
  4. While driving, the chip is gone; a tap from Settings says "the car is moving".
