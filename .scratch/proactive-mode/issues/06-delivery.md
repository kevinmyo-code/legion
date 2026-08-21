---
map: proactive-mode
ticket: 06
title: "Speak, notify, or wait"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls; speaks through the day, own channel, never both"
blockers: ["07"]
blocked-by: ["[[07-scheduling-research]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Speak, notify, or wait

## Question

`ProactiveGate.speakIfIdle` speaks aloud **only when a session is idle**. A goal nudge at 10pm with
the phone in a pocket therefore says nothing at all - the raise is silently dropped.

Blocked by [scheduling research](07-scheduling-research.md), because what a background process may
actually do in 2026 Android constrains every option here.

Decide:

1. **Which raises speak, which notify, which wait for the next session.** Probably a mix, and
   probably a property of the category rather than the individual raise.
2. **What a notification looks like.** LEGION already posts on three channels - `aria_channel`
   (foreground service, LOW), `ledger_ingest_channel` (LOW), `reminders_channel` (HIGH, the only
   noisy one). **Does proactive get its own channel?** A channel is also a second kill switch the OS
   gives Kevin for free, which cuts both ways: it is a real escape hatch, and it is a way for him to
   silence LEGION without the app knowing.
3. **What happens to a raise nobody heard.** Dropped, retried, or queued for the next session. **A
   silently dropped safety warning is the worst outcome on this map** - and the current behaviour
   drops everything when the phone is idle-but-locked.
4. **Speaking aloud when the phone is not in the car.** Every existing raise assumes a driving
   context. A 10pm rest nudge does not. **Does Alfred ever speak aloud outside a car session**, or is
   voice strictly a driving surface and everything else a notification?
5. **The echo hazard**, already found: `ReminderAlarmReceiver` speaks a fired reminder AND posts a
   notification for the same item in the same method. Whatever is decided here must not double up.

## Resolution - 2026-08-21 (Kevin, 4 calls)

### 1 and 4. It speaks aloud THROUGH THE DAY, not only in a car

Kevin, verbatim: *"speak to me even when im not driving. thats the whole point of something like
jarvis or alfred right, throughout the day keeps me on track."*

This overrides the recommendation, which would have restricted voice to an already-open session. It
is also the decision that most changes what LEGION is: **every one of the eleven existing raises
assumes a driving context, and none of them will after this.** It is the delivery-layer counterpart
to the concierge reframe (CLAUDE.md section 1) - the same correction, applied to when it may talk
rather than to how it addresses him.

**The gate on speaking aloud: the screen is on, AND the calendar says no event is running right now.**

- Screen on means he is demonstrably with the phone. Cheap, no permission, no guessing.
- The calendar check downgrades a raise to a notification during a live meeting, reusing
  `CalendarProvider.eventsInWindow` - the same read `calendar/OpenerCalendarBriefing.kt` already does.

**Two honest limits, both accepted:**

- **It depends on the calendar being accurate about where he is.** An unbooked conversation, a call
  that is not on the calendar, a meeting that ran long - none of those are visible. The screen-on
  gate and the 3-a-day cap carry what the calendar misses.
- **No calendar permission must not read as "no meetings".** Same trap `OpenerCalendarBriefing`
  already splits: an unreadable calendar means the check could not be performed, and the safe reading
  is to treat it as unknown and fall back to a notification, never to assume he is free.

### 2. Proactive gets its own notification channel

One channel for all proactive delivery, alongside `aria_channel`, `ledger_ingest_channel` and
`reminders_channel`.

**The cost is named rather than discovered later:** an OS channel is a second kill switch, so Kevin
can silence proactive without the app knowing it was silenced. That is a real escape hatch and worth
giving him - and it means **nothing may ever report that a notification was delivered or seen.**
CLAUDE.md section 7's outcome-verb rule already covers this; this is a new place it applies.

Per-category channels were rejected: five OS switches mirroring five in-app switches is two sets of
controls that can disagree.

### 3. A raise that cannot be spoken is NOTIFIED, always

Nothing is ever silently dropped. Today's behaviour drops everything when the phone is idle-but-
locked, and this ticket calls a silently dropped safety warning the worst outcome on the map.

This is also what makes [ticket 05](05-quiet-hours-and-budget.md)'s cap safe: **the cap governs
whether a raise is SPOKEN, and this decision governs what happens to one that is not.** Capped,
quiet-houred, screen-off, mid-meeting - every one of those becomes a notification rather than a
silence.

### 5. The echo hazard: one delivery per raise, never both

`ReminderAlarmReceiver` currently speaks a fired reminder **and** posts a notification for the same
item in the same method. That becomes: spoke successfully means no notification; could not speak
means notify.

**The cost, stated:** a spoken reminder leaves nothing on the lock screen to find afterwards.
Accepted - one event, one delivery - and it is a change to existing reminder behaviour, so it is a
thing to notice on the phone rather than a pure addition.

**This is only expressible because of [ticket 10](10-what-a-raise-may-say.md)'s typed raise object.**
"Spoke successfully" has to be a real return value, not an assumption; `ProactiveBus.speakIfAllowed`
already returns whether it raised, and the notification fallback hangs off exactly that boolean.
