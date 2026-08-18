# Session 2026-08-17: Meal-logging hang investigation and fix (feat/mission-control)

## Status: LIVE

Whole-session field notes: USB/wireless debugging procedures, device verification steps, operational gotchas.

---

## The hang and its three fixes

See `decisions.md` section "2026-08-17 - Meal-logging hang" for the investigation chain, root cause (nested blocking HTTP with inert timeouts per L29), and the three commits (18e0582, 57ed400, 170a76c). Lessons L29 and L30 graduate the rules this session discovered.

---

## Reboot verification of 80a1758 (wireless adb recovery)

**Context:** Commit 80a1758 (2026-08-17 08:15) was reported to start AriaForegroundService correctly on boot. This note verifies the claim.

**Test:** deliberate phone reboot on the real A25 (Samsung Galaxy SM-A256U).

### Finding 1: Boot-started FGS survives the platform window

The A25 was rebooted while LEGION was not in the foreground. AriaForegroundService came up from BootReceiver: the ServiceRecord showed allow-start reason tempAllowListReason BOOT_COMPLETED (reasonCode BOOT_COMPLETED, duration 20000, callingUid 1000), not the PROC_STATE_BFGS an app launch produces. startForegroundCount=1 and isForeground=true. No ForegroundServiceStartNotAllowedException fired. 

**Implication:** The fix in 80a1758 survives a real reboot. [on-device]

### Finding 2: Boot-started FGS microphone type is unverifiable

The boot-started record showed types=0x00000091 (dataSync|connectedDevice|microphone). The 80a1758 commit message claimed: "BootReceiver's start omits the microphone FGS type." The claim is unverifiable from this test because LEGION was open and on screen when the type was inspected - MainActivity.onResume's documented promotion (startForegroundService → startForeground with types re-declared) may have added the microphone type. Distinguishing needs a reboot where the app never opens. [reasoned, not on-device]

### Finding 3: Latent crash - 123-second startForegroundDelayMs

**Critical finding never predicted:** The boot-started record showed startForegroundDelayMs:123489 - **123 seconds between startForegroundService() and startForeground().**

The platform window for ForegroundServiceDidNotStartInTimeException is **10 seconds**. It survived this time, plausibly on the boot temp-allowlist. This is a latent crash that will fire when the allowlist expires.

**Why this matters:** the boot service is now guaranteed-present, but not guaranteed-fast. Any code that depends on AriaForegroundService being set up within a fixed time (startup sequences, initialization locks, crash handlers) will hang or deadlock if the 123-second delay recurs without the allowlist.

**Status:** NOT YET INVESTIGATED, ticket filed at `.scratch/proactive-mode/issues/09-fgs-start-delay.md`. No root cause known. Fix candidates: (a) onCreate is doing work, (b) startForeground is being called from a background thread, (c) the service itself is blocked waiting for something at startup.

---

## Operational procedures: wireless adb, database pulling

### Wireless adb does not survive reboot

Samsung Galaxy A25 turns off Wireless debugging across reboots. After reboot, `adb devices` shows nothing, but the phone's settings confirm debugging is on.

**Recovery procedure:** `adb kill-server && adb start-server` re-runs mDNS discovery and re-picks up the device.
- mDNS name: `adb-R5CX132X5ZL-rO7ERY` (device-specific, changes every reboot)
- Port: changes every connection
- `adb reconnect` did NOT work where kill+start did

[on-device]

### Pulling Room database with WAL

WAL (write-ahead log) is essential. The main database file can be stale while `-wal` holds unckeckpointed writes.

**Procedure:**
```
adb exec-out run-as com.kevin.legion cat /data/data/com.kevin.legion/databases/legion_database > legion_database
adb exec-out run-as com.kevin.legion cat /data/data/com.kevin.legion/databases/legion_database-wal > legion_database-wal
adb exec-out run-as com.kevin.legion cat /data/data/com.kevin.legion/databases/legion_database-shm > legion_database-shm
```

Check modification times on the device first (`adb shell ls -l /data/data/com.kevin.legion/databases/`). If `-wal` is newer than the main file, the main file is stale.

**Desktop verification:**
```
python3 sqlite3 legion_database
```

python3's sqlite3 module replays the WAL automatically. Sizes matched byte-for-byte against device `ls -l` output (no corruption in transfer). [on-device]

---

## Verification tags this session

- **`on-device`:** Tested on real A25 (SM-A256U), pull verified against `ls -l` byte-for-byte
- **`traced`:** Readable in commit diffs (timeout layers, HTTP timeouts, code paths)
- **`reasoned`:** Inferred but not executed (boot-started microphone type, latent crash root cause)
- **`built`:** Compiled cleanly, `./gradlew testDebugUnitTest` green (1485 tests, 2 pre-existing failures)
- **`tested`:** Unit test coverage confirmed (L30's Phase.THINKING reference counting, 5 new tests)
