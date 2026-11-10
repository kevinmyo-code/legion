---
status: amended
decided: 2026-11-09
decided-by: Kevin
amended: 2026-11-10
supersedes: []
source: "[[decisions#2026-11-09 - Alfred's clock inside LEGION (Kevin)]]"
tags: [adr]
---

# 79. Alfred's clock lives inside LEGION, rings on its own, and briefs only on a tap

## Standing

**AMENDED 2026-11-10 by [[0082-proactive-speech-uncapped-and-briefs]] (Kevin).** A tap Dismiss of a
morning alarm (04:00 to 11:00, once a day) now also has Alfred speak the brief unprompted, through
the proactive bus; Brief me stays as the replay. The ring screen also shows an emergency alert's
ring (Safety, ALARM urgency) with one Dismiss. The "plain ring" paragraph below is the original
ruling, kept as written; the ring itself still never touches the network or the Live session, and
the brief runs only after the dismiss.

**LEGION has its own clock: Alarms, Timers, Stopwatch and Bedtime** (Kevin, 2026-11-09, map
`.scratch/alfred-clock/`). It is a screen inside LEGION, not a separate app. Matrix home's big clock
opens it; it no longer hands off to the system alarm list (this replaces ADR 0062's "the big clock
opens the system's alarms" line, and `SET_ALARM` leaves the manifest with it).

**The wake-up is a plain ring.** Alfred never speaks on his own when an alarm goes off. The ring
screen has Dismiss, Snooze and **Brief me**; Brief me dismisses and starts Alfred with the sitrep in
one tap. Sitreps stay "tap only or via voice activation only" (ticket 32).

**Alfred controls all of it by voice** through ONE `clock` tool with an action enum, calling the same
controller as the screen (ADR 0035).

**It works offline and without Gemini.** Storage, scheduling and ringing are on the phone. Nothing
in the ring path touches the network, the engine or the Live session.

### Exact alarms: `USE_EXACT_ALARM`, declared

`.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md` rejected `USE_EXACT_ALARM` "on proportionality" for
reminders, which tolerate an hour of slack. **A wake-up alarm does not, and that reverses the
proportionality for this feature:**

- `setAlarmClock` needs an exact-alarm permission at all.
- `SCHEDULE_EXACT_ALARM` is denied by default on Android 14 for this target, and revoking it kills
  the process and cancels every pending exact alarm. For a reminder that is a downgrade; for a
  wake-up it is the exact failure the clock exists to prevent.
- `USE_EXACT_ALARM` is granted at install and cannot be revoked. Its Play restriction (clock and
  calendar apps only) is a review gate a sideloaded app never meets, and this IS a clock app.

Notes reminders are unchanged: inexact by default, exact only when asked, the downgrade path kept for
Android 12 (where `USE_EXACT_ALARM` does not exist). `SCHEDULE_EXACT_ALARM` stays declared for that.

### Ringing

- Alarms: `AlarmManager.setAlarmClock` (status-bar icon, Doze-exempt). Timers and bedtime:
  `setExactAndAllowWhileIdle`.
- The fire starts a foreground service of type **`systemExempted`** on Android 14+ (allowed for an
  app holding an exact-alarm permission, by the platform's own list), `mediaPlayback` below 14.
- **Full-screen intent:** `USE_FULL_SCREEN_INTENT` is declared and, sideloaded, granted at install.
  On 14+ `NotificationManager.canUseFullScreenIntent()` is checked; when it is off the ring still
  arrives as a heads-up notification with Dismiss and Snooze, and the Clock screen says so in words
  with a button to the system page.
- Audio on the alarm stream (`USAGE_ALARM`, which Quiet mode lets through), ramped, with vibration.
- **Direct boot:** alarms live in device-protected storage and the ring path is direct-boot aware,
  so an alarm rings after a reboot before the first unlock. The Application defers its normal start
  until the user unlocks.
- Re-armed on locked boot, boot, time set, time zone change, app update, the exact-alarm grant
  changing, and app start.
- **Battery:** the Clock screen detects battery optimisation and background restriction and asks
  in words, with the system page. Samsung's "sleeping apps" cannot be read; the ask covers it.

### Bedtime

At bedtime, Quiet mode and the ADR 0066 feed block come on. **Bedtime records whether IT turned
Quiet on, and at the next alarm's dismiss turns off only that Quiet.** Quiet Kevin had on before
bedtime stays on.

**Bedtime never turns the feed block off** (Kevin, 2026-11-09: "when the morning alarm is dismissed
after bedtime, the feed blocker stays on"). Bedtime may turn it on; it stays on through the
dismiss, End bedtime, voice "bedtime off" and the schedule being turned off. Kevin turns it off
himself through ADR 0066's gate (60 second wait and typed sentence). There is no exception to that
gate: `BedtimeEffects` has no feed-off at all. This replaces the build's first reading ("off at the
next alarm's dismiss", flagged as a bypass of the gate).

### Defaults (chosen in the build, Kevin may change)

Snooze 9 min. Auto-silence 10 min, then a "missed alarm" notice. Volume ramp 30 s. An alarm stream
at 0 is raised to half for the ring and put back. Wind-down 30 min before bedtime. Bedtime with no
alarm set stays on until "End bedtime" or "bedtime off".

## Context

Kevin, 2026-11-09: the clock should be Alfred's, inside LEGION. Matrix home already showed a big
clock that opened Samsung's Clock (ADR 0062), so setting an alarm meant leaving LEGION, and Alfred
could not set one at all. Reminders (`manage_item` with `exact`) were the nearest thing and they are
notifications, not alarms.

## Consequences

- Kevin grants on the phone: battery optimisation off for LEGION (asked on the Clock screen), and
  checks that "Full screen notifications" is on for LEGION (asked if not). Exact alarms need no grant.
- `manage_item`'s `exact` example ("wake me up at exactly 6am") routed wake-ups to reminders; it now
  points at `clock`. That trim paid for most of the new declaration (`LiveSetupPayloadSizeTest`).
- The Application's start is split: nothing credential-encrypted is read before the first unlock.
  A component added later that is direct-boot aware must hold the same line.
- Never verified on hardware at the time of writing: the ring over the lock screen, the locked-boot
  ring, and Samsung's battery behaviour. Ticket 08 is the run.
