---
map: notes-lists-calendar
ticket: 12
title: "What does a fired reminder actually do?"
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "03"]
blocked-by: ["[[01-entity-model-and-cartask-migration]]", "[[03-android-alarm-mechanism]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What does a fired reminder actually do?

Graduated from the map's fog 2026-08-07. It was not specifiable while the alarm mechanism was
unknown; ticket 03 settled that, so it is now a real question. **This is the last open decision on
the map.**

## Question

An alarm fires. Ticket 03 decided how it is scheduled and ticket 04 decided how a series re-arms.
Neither decided what the driver actually experiences.

### What must be decided

1. **The notification itself.** What it says, and which channel it uses. Two `IMPORTANCE_LOW`
   channels already exist (`AriaForegroundService.kt:841`, `LedgerIngestService.kt:148`) - a reminder
   almost certainly needs its own at a higher importance, because `IMPORTANCE_LOW` does not make a
   sound and a silent reminder is not a reminder.
2. **Tapping it.** Opens the item, opens the list, opens the calendar, or does nothing.
3. **Whether it can be snoozed**, and if so from where - the notification's own action, or only
   in-app.
4. **Whether firing changes the item.** Does a fired reminder auto-tick? Almost certainly not for a
   recurring one, since ticket 04 made those untickable at all - so decide what "fired" even means
   as state, and whether it is stored.
5. **The phone was off when it was due.** Ticket 03 established that boot recovery recomputes
   forward from now, so a missed one-off is genuinely missed. Decide whether the driver is told it
   was missed, or whether it silently vanishes. **Silently vanishing is the failure this app has a
   documented history of** - a reminder that never arrives and never explains itself is exactly the
   shape of the `sync/` and categorization bugs.
6. **Does Alfred say anything?** A reminder firing while a live session is active could be spoken
   rather than posted. Decide whether that is in scope or a distraction.
7. **`POST_NOTIFICATIONS` refused.** Already requested at runtime as step 1 of `ui/SettingsScreen.kt`'s
   permission chain, so it may already be granted - but decide what a reminder does when it is not.
   It must not fail silently.
8. **Place-triggered reminders** (the absorbed `PlaceReminder` behaviour) fire on arrival rather
   than on a clock. Decide whether they share all of the above or differ.

### Constraints

- Ticket 03's finding stands: when exact-alarm permission is refused the app **downgrades to inexact
  and says so in words on the item**. Whatever is decided here must not contradict that.
- The one-hour lateness bound for `setAndAllowWhileIdle` is **unverified** (map fog). Nothing decided
  here may promise the driver a delivery time until it is measured on the device.

## Answer

Resolved 2026-08-07. **This was the last open decision on the map.**

### The notification

**Its own channel, at `IMPORTANCE_DEFAULT` or higher.** The two channels that already exist
(`AriaForegroundService.kt:841`, `LedgerIngestService.kt:148`) are both `IMPORTANCE_LOW`, which makes
no sound - a silent reminder is not a reminder. Do not reuse either.

Content: the item's text, and the list it belongs to. Nothing clever.

### Firing changes nothing on the item

**A fired reminder stays open until you tick it.** Firing is a notification, not an action - "call
the plumber" reminding you does not mean you called them. Tapping the notification **opens that
item** so you can tick it there.

Rejected: auto-ticking on fire (it conflates "you were told" with "you did it", and a list that
claims you called the plumber when you did not is worse than no list) and a third fired-but-not-done
state (a permanent third state per item for a distinction the timestamp already carries).

Recurring items are untickable anyway (ticket 04), so for those the notification is purely
informational and has no DONE action.

### Snooze, from the notification

**A single fixed-interval SNOOZE action on the notification itself**, plus DONE for one-off items.
No menu, no picker. "Not now, in a bit" is the most common real reaction to a reminder and it needs
to cost one tap; putting it in-app only means you unlock, navigate, and in practice just dismiss.

Snooze reschedules the alarm through the same path as any other schedule, so it inherits ticket 03's
rules - including the downgrade-to-inexact behaviour when exact permission is refused.

**Do not label the snooze interval with false precision.** With `setAndAllowWhileIdle`'s real
lateness still unmeasured (map fog), "1h" is an intention, not a guarantee.

### A missed reminder is reported, never silent

Ticket 03 established that boot recovery **recomputes forward from now**, so a one-off due while the
phone was off is genuinely gone - it will never fire.

**So say so.** A MISSED section on next open, listing what was due and when, with a dismiss action.

Rejected: firing late at boot (a 7am reminder pinging at 6pm is worse than useless, and you cannot
tell whether it is due now or was due yesterday) and silent vanishing.

**Silent vanishing was rejected on this repo's own history, not on taste.** It is the exact failure
shape of `sync/` (structurally unreachable, passed every test) and of categorization (fully built,
never wired, a month reading "uncategorized" with no way to act). A reminder that quietly does not
happen and never says so is the third instance of the same bug. Whatever "missed" state this needs
must be stored, because it cannot be recomputed after the fact.

### Alfred speaks it during a live session

If a live session is active when a reminder fires, **Alfred mentions it in character**, and the
notification still posts so nothing is lost if the words are missed.

This is the product working as described - an assistant you talk to - and it matters most while
driving, where a posted notification is effectively invisible. The hook is far cheaper to design in
now than to thread through a working notification path later.

Constraint: he mentions it at a turn boundary, never mid-sentence over the driver.

### Settled on recommendation, not put to Kevin

- **`POST_NOTIFICATIONS` refused:** reminders must not fail silently. An item carrying a trigger says
  in words that notifications are off, and offers the settings route. Already requested as step 1 of
  `ui/SettingsScreen.kt`'s chain, so it is often already granted - but "often" is not "always".
- **Place-triggered reminders** (the absorbed `PlaceReminder` behaviour) share all of the above,
  except "missed": a place trigger has no due time, so it cannot be missed, only unvisited. It never
  appears in the MISSED section.
