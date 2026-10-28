---
map: conversation-records
ticket: "06"
title: "Build: Developer tools on the head unit: open developer options and wireless debugging, ADB status, root enable attempt; Kevin 2026-10-28"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/headunit-conversation-logs. Head unit 771 tests, 0 failures (14 new in DeveloperToolsTest); assembleDebug builds. Owed: install on the unit and record which rows open what, the unit's API level, and whether su exists."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: Developer tools on the head unit

Kevin, 2026-10-28, verbatim: *"open devoptions directly as well as the wireless debugging row from
our ui if possible. all of it"*. The unit's skin hides Android Settings and the unit has no ADB, so
the hidden screens and ADB over Wi-Fi need a way in from the app. Off this map's destination; filed
here because it rode the same branch.

## What was built

Settings > System > **Developer tools** (`headunit/.../ui/DeveloperToolsScreen.kt`; decisions and
wording in the pure `diag/DeveloperTools.kt`, tested by `DeveloperToolsTest`):

- **Open Android Settings** (`Settings.ACTION_SETTINGS`), **Open Developer options**
  (`ACTION_APPLICATION_DEVELOPMENT_SETTINGS`, subtitle explains the Build number seven taps),
  **Open About device** (`ACTION_DEVICE_INFO_SETTINGS`). A start Android refuses says so in words
  under the row; nothing crashes.
- **Open Wireless debugging**, API 30+ only. Tries `com.android.settings/.Settings$WirelessDebuggingActivity`,
  then `android.settings.WIRELESS_DEBUGGING_SETTINGS`, then Developer options with
  `:settings:show_fragment_args` = { `:settings:fragment_args_key` = `toggle_adb_wireless` }.
  **The first two do not exist in AOSP** (packages/apps/Settings manifest and Settings.java,
  android11-release through main, and frameworks Settings.java android11-release, fetched
  2026-10-28); they stay only for a vendor build that adds them. The third is what AOSP supports;
  the row then says where Wireless debugging is.
- **ADB status**, read on open and on REFRESH: Android version and API, device, short build
  fingerprint, `adb_enabled`, `adb_wifi_enabled` (API 30+, "not present" when absent), the
  Wi-Fi/Ethernet IPv4 from `ConnectivityManager` link properties, and a 300 ms probe of
  127.0.0.1:5555. Below it `adb connect <ip>:5555`, long-press to copy ("Copied").
- **Try to enable ADB over Wi-Fi (needs root)**: TRY, then RUN AS ROOT to confirm. Runs
  `su -c "setprop service.adb.tcp.port 5555 && stop adbd && start adbd"` and on API 30+
  `su -c "settings put global adb_wifi_enabled 1"`, 5 s cap each, then re-probes the port. The
  verdict comes from the probe, never from the exit code alone.
- The note: ADB over Wi-Fi is open to anyone on the same network while it is on.

Hands-only: no voice tool, deliberately.

## Owed (on the unit only)

1. Which rows actually open a screen on this skin, and whether Developer options needs the seven taps.
2. The unit's real API level (the repo says AOSP 8 to 10; below 30 the Wireless debugging row is hidden).
3. Whether `su` exists. Without root, the legacy port-5555 path cannot be turned on from the app.
