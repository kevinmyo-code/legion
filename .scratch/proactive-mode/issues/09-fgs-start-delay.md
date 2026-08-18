# The boot-started service takes 123s to call startForeground, against a 10s window

Type: bug
Status: open
Blocked by: -

## What was measured

The A25 was rebooted 2026-08-17 to verify `80a1758` (start the assistant when the flag already
says it is on). The boot path works: the `AriaForegroundService` record carries
`tempAllowListReason:<... android.intent.action.BOOT_COMPLETED/u0, reasonCode:BOOT_COMPLETED,
duration:20000, callingUid:1000>`, `startForegroundCount=1`, `isForeground=true`, and nothing
threw. That much is `on-device`.

The same `dumpsys activity services com.kevin.legion` line also reported:

```
startForegroundDelayMs:123489
```

**123 seconds between `startForegroundService()` and the service actually calling
`startForeground()`.** The documented platform window is **10 seconds**, after which the system
throws `ForegroundServiceDidNotStartInTimeException` and kills the process.

## Why it did not crash this time, probably

`reasonCode:BOOT_COMPLETED, duration:20000` is a 20-second temp-allowlist entry, which is about
allow-*start*, not about the start-timeout. Why a 123s delay survived is **not established**. Do
not close this ticket on the theory that boot is exempt without a primary source saying so.

## Why it matters

This is the service that `80a1758` exists to bring back after a reboot. If the delay ever lands
outside whatever grace saved it here, the assistant does not merely fail to start - the process
dies with a fatal exception, and per the same commit's own finding every surface will still read
"On". That is the exact failure mode `80a1758` was written to end, reintroduced through a
different door.

Note `.scratch/android-auto/issues/15-*.md` and ticket 07 here already carry the related finding
that neither service implements `onTimeout`.

## What to establish

1. **What is actually taking 123 seconds** between the start request and `startForeground()`.
   Boot contention, a blocking DB open, the system prompt build, key vault unseal, or waiting on
   something in `MidnightApplication.onCreate`. Measure it, do not reason about it.
2. Whether the 10-second window is suspended, extended, or simply not enforced for a
   `BOOT_COMPLETED`-allowlisted start at target SDK 34 on Android 16. Primary sources only.
3. Whether the same delay appears on the ordinary app-launch path (the earlier record that day
   showed a start with no such delay reported - confirm rather than assume).
4. Whether `startForeground()` can be hoisted to the top of `onStartCommand` with a minimal
   notification, and the slow work moved after it. That is the standard shape and it makes the
   question moot.

## Verification

- Reboot with LEGION **not** opened afterwards, then read `startForegroundDelayMs` again. The same
  reboot answers the still-open question from `80a1758` about whether the boot start omits the
  microphone FGS type, since opening the app promotes it and destroys that evidence.
- Confirm no `ForegroundServiceDidNotStartInTimeException` in logcat across several reboots.
