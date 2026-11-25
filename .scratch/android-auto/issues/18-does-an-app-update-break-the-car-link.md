---
map: android-auto
ticket: 18
title: "Does updating the app break the car link, so it has to be paired again"
type: bug
status: resolved
status-detail: "2026-11-24 - answered NO by tracing both sides; no code owed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Does updating the app break the car link, so it has to be paired again

## The question

Kevin, 2026-11-24, after a phone app update and a head unit update: does updating the app break the
phone-to-head-unit link, so the two have to be paired again.

## The answer: no

An ordinary install over the top, same package and same signing key, cannot lose the link keys on
either side, and the head unit update does not force a one-time re-pair either.

Why, traced on both sides:

- Both ends keep the link in a plain `SharedPreferences` file, each value sealed with an Android
  Keystore AES-GCM key under a fixed alias. App data and Keystore aliases both survive an update.
- Neither key spec sets `setUserAuthenticationRequired`, `setInvalidatedByBiometricEnrollment`,
  `setIsStrongBoxBacked` or `setUnlockedDeviceRequired`, so a lock-screen change, a fingerprint
  re-enrollment and a StrongBox change have nothing here to invalidate.
- That prefs file is excluded from cloud backup, from device-to-device transfer and from the legacy
  backup rules, on BOTH ends, so no restore can bring back a stale key.
- Neither link package has a version gate, a clear-on-upgrade, or a prefs delete. The only on-load
  migration drops two retired keys that have nothing to do with the link.
- The pairing and hello version constants are 1 on both sides and were 1 at every build involved.
  The head unit diff across this update is additive only and touches neither the manifest nor the
  gradle file, so the package name and signing config did not move. A differently-signed head unit
  APK could not wipe data anyway: the updater refuses to install one.
- Replay state is in memory on both ends, so a restart draws a fresh session rather than tripping a
  replay check.
- The phone restarts the link itself after an update, on the package-replaced broadcast.

## What WOULD make him pair again, ranked

1. **Wanting the Spotify client id on the unit.** It travels only inside the pairing hand-over, and
   the steady-state link protocol has no message that carries it. The head unit build that RECEIVES
   it landed today, so a unit paired before then will never get it. The pairing keeps working
   without it; pairing again is simply the only delivery channel that exists.
2. A Forget on either end, or a revoke in flight. The only path that destroys a committed pairing
   on purpose.
3. Uninstall and reinstall, or Clear storage, on either device.
4. A new phone. Backup and transfer both exclude the file, so a fresh pairing is required.
5. A Keystore read failure, from a keystore wipe or flaky keymaster hardware. Both ends then mint a
   fresh key, the old blobs stop opening, and the phone says the pairing is broken and to pair
   again. Both already retry three times first.

## Owed

Nothing in code. The backup exclusions that carry point 4 are already covered by tests on both
sides. Pinning the key specs themselves, so no auth-bound or StrongBox flag creeps in later, would
belong in those same two test files.
