# Restarting AriaForegroundService after boot - what the platform allows

Researched 2026-08-17, triggered by the [12h run](12h-run-baseline.md), which found the service was
not running while every surface reported On. App targets **SDK 34**; the A25 runs Android 16.

## The rules, all `quoted` from developer.android.com

1. **`ACTION_BOOT_COMPLETED` is on the Android 12 background-FGS-start exemption list** (item 8 of
   14: "After the device reboots and receives `ACTION_BOOT_COMPLETED`,
   `ACTION_LOCKED_BOOT_COMPLETED`, or `ACTION_MY_PACKAGE_REPLACED` in a broadcast receiver").
   Unexempted background starts throw `ForegroundServiceStartNotAllowedException`.
   ([bg-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start))

2. **But `microphone` cannot be started from a boot receiver at target 34.** "The `RECORD_AUDIO`
   runtime permission is subject to while-in-use restrictions. For this reason, you cannot create a
   `microphone` foreground service while your app is in the background and you cannot launch a
   `microphone` foreground service from a `BOOT_COMPLETED` receiver." On the boot-prohibited list
   **since Android 14**. ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types))

3. **`dataSync` and `connectedDevice` are permitted** from BOOT_COMPLETED at target 34. Neither is
   while-in-use restricted; `dataSync`'s runtime prerequisites are "None".

4. **Adding a type later is documented and supported.** "If the foreground service needs new
   permissions after you launch it, you should call `startForeground()` again and add the new
   service types."
   ([fgs-types-required](https://developer.android.com/about/versions/14/changes/fgs-types-required))
   Caveat `inferred`: the while-in-use check applies at the moment `startForeground()` runs, so the
   promotion must happen while the app is user-visible. The docs state the rule but do not spell out
   this exact sequence - `not-documented` for the add-a-type-later case specifically.

5. **The Android 15 `dataSync` 6h/24h cap and `Service.onTimeout()` are gated on TARGET SDK 35+**,
   not on the device's version. At target 34 on Android 16 they do not apply. This corrects nothing
   in [07-scheduling.md](07-scheduling.md) - it confirms it.

6. **`START_STICKY` restart**: documented to recreate the service with a null intent. Whether a
   force-stop or the restricted standby bucket suppresses that restart is **`not-documented`** in
   the pages checked. Do not claim either way.

## Two things this makes true

- **The boot path must start WITHOUT the microphone type**, then promote to
  `dataSync|connectedDevice|microphone` once the app is visible. Today
  `AriaForegroundService.startForegroundCompat()` ORs in `MICROPHONE` whenever `RECORD_AUDIO` is
  granted, with no check on whether the app is in the background - from a boot receiver that
  throws and kills the service on its first line. Same shape as the 2026-08-02
  `IllegalArgumentException: foregroundServiceType 0x00000083 is not a subset` bug already
  documented in that function.

- **Bumping `targetSdk` to 35 breaks the boot path entirely.** `dataSync` joins the boot-prohibited
  list at 35, and the 6h/24h cap activates with a fatal `RemoteServiceException` if `onTimeout` is
  unimplemented. **Neither service implements `onTimeout` today.** A warning comment belongs at the
  `targetSdk` line.
