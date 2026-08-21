---
map: proactive-mode
ticket: 09
title: "The boot-started service takes 123s to call startForeground, against a 10s window"
type: bug
status: resolved
status-detail: "2026-08-21 - closed as a misreading, settled from AOSP source"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The boot-started service takes 123s to call startForeground, against a 10s window

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

Read at the time as **123 seconds between `startForegroundService()` and the service actually
calling `startForeground()`**, against a documented **10 second** window after which the system
throws `ForegroundServiceDidNotStartInTimeException`. **See the section below - a second
measurement makes that reading doubtful.**

## A SECOND MEASUREMENT THAT UNDERCUTS THE READING ABOVE (2026-08-17, same evening)

A later `dumpsys` on a healthy, app-open, foreground service reported:

```
startForegroundDelayMs:554912
```

**554 seconds, on a service that is demonstrably running and was never killed**, with the app TOP
on screen and `isForeground=true`. If this field meant "time between `startForegroundService()` and
`startForeground()`" against a 10-second window, that service could not exist. So the field
probably does NOT mean what this ticket first assumed, and the "latent fatal
`ForegroundServiceDidNotStartInTimeException`" framing below is **an inference, not a finding.**

Both numbers are real and were read off the device. What they MEAN is open. Establish that first,
from AOSP source or platform documentation, before spending any effort on a fix. If the field turns
out to be benign (a cumulative deferral counter, a time-since-last-promotion, anything other than
the start-to-startForeground gap) then this ticket closes as a misreading and the only thing worth
keeping is the lesson about it.

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

## Resolution - 2026-08-21: the field does not mean what this ticket assumed. Closed, no fix.

The ticket's own instruction was to establish what `startForegroundDelayMs` means from primary
sources before spending any effort on a fix. Done, from AOSP.

**It is a benign diagnostic breadcrumb, not a timeout.** It is written in
`ActiveServices.setServiceForegroundInnerLocked()` and appended as free text to
`mInfoAllowStartForeground`, which is the same blob that carries the `tempAllowListReason:<...>`
string this ticket also quoted. There is no such member field and no proto field - it reaches
`dumpsys` only as text. [AOSP-source]

```java
if (!r.fgRequired) {
    final long delayMs = SystemClock.elapsedRealtime() - r.createRealTime;
    if (delayMs > mAm.mConstants.mFgsStartForegroundTimeoutMs) {
        ...
        final String temp = "startForegroundDelayMs:" + delayMs;
```

**Three things settle it, and the third is decisive.**

1. **The zero point is `ServiceRecord` creation, not the `startForegroundService()` call.** It is a
   real duration, `elapsedRealtime() - createRealTime`, measured when `startForeground()` is
   processed - and it is **sticky**, so a value read at `dumpsys` time is a historical measurement
   rather than a live counter. That alone explains 554 seconds on a healthy service.
2. **Two different constants were being conflated.** `mFgsStartForegroundTimeoutMs` is 10s and its
   only effect is to trigger a BFSL restriction re-check and print this string - no ANR, no kill.
   The ANR clock is a different constant, `mServiceStartForegroundTimeoutMs` at **30s**, plus a
   further 10s delay before the ANR fires. The "10 second window" this ticket was written against is
   the harmless one. [AOSP-source]
3. **The two paths are mutually exclusive by construction.** The string is emitted only when
   `!r.fgRequired` - a service started with plain `startService()`. The ANR timer is armed only at
   `if (r.fgRequired && !r.fgWaiting)`. **A record that can print `startForegroundDelayMs` is a
   record whose ANR timer was never armed.** The feared `ForegroundServiceDidNotStartInTimeException`
   was not merely unlikely here; it was structurally impossible on that code path. [AOSP-source]

Also settled, since the ticket asked: **the BOOT_COMPLETED temp allowlist affects allow-START only.**
Nothing in `scheduleServiceForegroundTransitionTimeoutLocked()` or `serviceForegroundTimeout()` reads
the allowlist. The `duration:20000` is the window for permitting the start and has nothing to do with
any `startForeground` deadline. And it is **not** notification deferral either - that mechanism uses
`fgDisplayTime` and `mFgsNotificationDeferred` and never produces this string. [AOSP-source]

### Point 4 was already true before the ticket was written

`startForegroundCompat()` is the **second statement of `onCreate`**
(`service/AriaForegroundService.kt:136`), after only `createNotificationChannel()`, and
`onStartCommand` calls it again as its first act. There was never any slow work in front of it to
hoist. Had anyone read the startup path, the 123-second reading would have been obviously
impossible from app code, which is the cheaper of the two ways to have caught this. [traced]

### The lesson, which is the only thing worth keeping

**A number read off `dumpsys` is a string, not a measurement.** This ticket built a fatal-crash
theory on a field name that read like a duration against a constant that read like its deadline, and
both readings were wrong. The ticket did the right thing at the time - it recorded the second,
contradictory reading and said in writing that the framing was "an inference, not a finding" - and
that is what stopped a fix being built for a bug that did not exist. **Filed to `lessons.md`.**

Nothing owed on the phone. Nothing to fix.
