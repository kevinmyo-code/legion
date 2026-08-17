# 12-hour screen-off unplugged run - BASELINE

Started 2026-08-17 by Stark at Kevin's request, on the A25 (SM-A256U).
Gates all ten on-device items in [scheduling research](07-scheduling.md) §8.

## What this is testing

**Does `AriaForegroundService` survive 12 hours of screen-off, unplugged, undisturbed?**
Everything in the proactive-mode map is theory until that answer exists.

## A defect found while setting it up - and it matters more than the test

**The service was NOT RUNNING when this began, and `assistant_ignition.xml` said `enabled = true`.**

The only callers of `AssistantIgnition.start()` are in `SettingsScreen` - the toggle's own handler.
Nothing starts the service on app launch, and `BootReceiver`'s documented job is "re-arming
scheduled reminders" and deliberately nothing else. **So after any reboot or process death the
assistant stays dead while every surface reports On.** The Settings row read "On - tap to talk strip
is showing" with the service absent from `dumpsys`.

This is very likely the standing `MEMORY.md` line "OBD, wake word, proactives never run".

It was started for this run by toggling the switch off and back on. **It ended in the same state it
started: enabled = true.**

## Baseline

| Reading | Value |
|---|---|
| Device clock at start | Mon Aug 17 00:14:07 CDT 2026 |
| App pid | 20976 |
| Process starttime (jiffies) | 10750861 |
| Device uptime at start (s) | 108809.86 |
| Battery level | 92% |
| Charging | **No** - AC, USB and wireless all false |
| Standby bucket | 10 (10 = ACTIVE) |
| Battery-optimisation allowlist | **NOT allowlisted** |
| Service | `AriaForegroundService`, isForeground=true, foregroundId=1, types=0x91 (connectedDevice\|dataSync\|microphone) |

## How to read it tomorrow

```
adb shell pidof com.kevin.legion                  # same pid  = survived
adb shell cat /proc/<pid>/stat | awk '{print $22}'  # same starttime = never restarted
adb shell dumpsys activity services com.kevin.legion
adb shell am get-standby-bucket com.kevin.legion  # dropped from 10 = throttled
adb shell dumpsys battery | grep level            # drain over the window
```

**A DIFFERENT pid means it was killed and something restarted it. A missing service means it was
killed and nothing did** - which, given the ignition defect above, is the likely outcome.

**No logcat stream was left running deliberately** - an open adb session can hold the CPU awake and
would invalidate the very thing being measured. Wireless ADB may drop when WiFi sleeps; reconnect
via `adb mdns services` as usual.
