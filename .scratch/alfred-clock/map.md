---
map: alfred-clock
title: "Alfred's clock: alarms, timers, stopwatch and bedtime inside LEGION"
charted: 2026-11-09
charted-by: "Kevin + Opus"
effort: "`.scratch/alfred-clock/`"
tickets: 8
open: 8
status: open
tags: [map]
---

# Alfred's clock

**Kevin, 2026-11-09, rulings:**

- It lives INSIDE LEGION, not as a separate app. Matrix home's big clock opens it instead of the
  system alarm list (`dumb/KeptApps.openAlarms`, ADR 0062's clock line).
- v1 is Alarms, Timers, Stopwatch and Bedtime.
- The wake-up is a plain ring. The dismiss screen has a **Brief me** button that starts Alfred with
  the sitrep in one tap. Never auto-speak: "sitreps stay tap only or via voice activation only"
  (ticket 32, `LiveToolbox` `get_sitrep`).
- Alfred controls all of it by voice: set, cancel and list alarms; start, pause and cancel named
  timers; "how long left"; stopwatch start, stop and lap; bedtime on and off.

Binding decision: **ADR 0079** (`docs/adr/0079-alfreds-clock.md`).

## Shape

```
clock/                  pure model + math (no Android), unit tested
  ClockModel.kt         ClockAlarm, ClockTimer, Stopwatch, Bedtime, DayMask
  AlarmMath.kt          next trigger (repeat days, DST, zone), snooze, timer remaining
  ClockStore.kt         JSON in DEVICE-PROTECTED prefs (direct boot), one file
  ClockController.kt    the one controller: screen, voice tool, receivers all call it
  ClockScheduler.kt     AlarmManager: setAlarmClock (alarms), setExactAndAllowWhileIdle (timers, bedtime)
  ClockReceivers.kt     fire receiver, action receiver, reschedule receiver (boot, locked boot, time, zone)
  RingService.kt        foreground service (systemExempted on 14+), alarm-stream audio, ramp, vibrate
  BedtimeEffects.kt     Quiet + feed block on at bedtime; at the alarm's dismiss Quiet off if IT turned
                        it on; feed block stays on (Kevin 2026-11-09, never off via the clock)
ui/clock/               ClockActivity (4 tabs), RingActivity (over the lock screen)
service/ClockToolbox.kt one `clock` voice tool, action enum
```

## Tickets

| # | Ticket | Type |
|---|---|---|
| 01 | [[01-alarm-engine]] model, math, store, scheduling, re-arm | build |
| 02 | [[02-ringing]] foreground service, full-screen intent, audio, snooze, auto-silence | build |
| 03 | [[03-timers-and-stopwatch]] | build |
| 04 | [[04-bedtime]] | build |
| 05 | [[05-clock-screen]] the screen, Matrix home entry, permission banners | build |
| 06 | [[06-voice-tool]] `clock` tool and payload budget | build |
| 07 | [[07-brief-me]] the dismiss screen's one-tap sitrep | build |
| 08 | [[08-on-the-phone]] Kevin's acceptance run on the A25, and what is deferred | acceptance |

## Defaults chosen, not ruled (Kevin may change)

| Setting | Default |
|---|---|
| Snooze | 9 min |
| Auto-silence | 10 min, then a "missed alarm" notice |
| Volume ramp | 30 s from quiet to the alarm stream's level |
| Alarm stream at 0 when ringing | raised to half for the ring, put back after |
| Bedtime wind-down | 30 min before bedtime |
| Bedtime with no alarm set | stays on until "End bedtime" on its notification or "bedtime off" |

## What exists that this reuses (checked 2026-11-09, dev `0189d1a0`)

- `quiet/QuietMode.enable/disable` (DND rule, lets alarms through). `dumb/DumbModeGraph.feedBlock`
  (ADR 0066 switch). Both branches `fix/quiet-dnd-stuck` and `feat/feed-block-always` are in dev.
- `service/BootReceiver.rearmAfterRestart` (boot + `MY_PACKAGE_REPLACED`).
- `LiveSessionController.requestSpeak(userInitiated = true)` for Brief me.
- Notes reminders keep their own `notes/AlarmScheduler`; nothing there changes except the
  `manage_item.exact` wording (a wake-up alarm is now `clock`).
