---
map: proactive-mode
ticket: 05
title: "Quiet hours, and how often Alfred may speak at all"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls; 3/day, Safety uncapped, per-category windows"
blockers: ["03"]
blocked-by: ["[[03-compulsion-test]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Quiet hours, and how often Alfred may speak at all

## Question

Blocked by [the compulsion test](03-compulsion-test.md) because a nudge budget IS an anti-compulsion
mechanism, and its size should follow from that rule rather than from taste.

**The trap this ticket exists for:** "It's past 10pm, perhaps rest is in order" is *itself* a
late-night line. **Quiet hours cannot simply mute everything at night - the rest nudge lives
there.** So quiet hours and the nudge it was invented to permit are in direct tension.

Decide:

1. **What is silenced when.** A window, per category, or per category per window.
2. **What may always speak.** Safety is the obvious candidate (an NWS warning at 3am is the whole
   point of Safety) - **but settled decision 2 says the master kill switch has no exemptions.**
   Those are compatible only if "always speaks" means "within quiet hours while the master is on".
   **Say that explicitly**, because it is exactly the kind of distinction that erodes.
3. **The daily cap.** How many times a day may Alfred speak unprompted at all? A hard cap is the
   cheapest anti-annoyance mechanism and the strongest anti-compulsion guarantee - **and it is
   testable**, unlike tone. Pick a number and say what happens to the raise that exceeds it: dropped,
   deferred, or queued for the next session.
4. **Does a declined nudge count against the budget?** And is a declined nudge remembered at all -
   which is fog on this map today, because it needs somewhere to store the refusal.
5. **Interaction with Doze.** A quiet-hours window that a sleeping phone would have enforced anyway
   is not a feature. [Scheduling](07-scheduling-research.md) establishes what the OS already does
   before this ticket claims credit for it.

## Resolution - 2026-08-21 (Kevin, 4 calls)

### 1. Quiet hours are per category, per window

The tension this ticket was written for is real and is resolved head-on rather than by exception:
**the rest nudge lives at night**, so a window that mutes the night kills the line this whole map
came from.

One night window. **Wellbeing is allowed to speak inside it; Digest, Fleet and Timing are not.**
Safety is governed by call 3 below, not by the window.

The alternative - one global window with an exempt list - was rejected because exempt lists grow, and
every addition is invisible at the moment it happens.

### 2. "What may always speak" means WITHIN the master switch, and it is stated here so it cannot erode

Safety may speak at 3am. **Safety may not speak when the master is off.** Settled decision 2 has no
exemptions and this does not create one: "always speaks" is always shorthand for *always, while the
master is on*.

Any future sentence of the form "X is exempt" must be read against this paragraph.

### 3. Three unprompted lines a day. Safety is outside the cap.

**Three**, chosen so each one has to earn its slot. A cap you never hit is not a cap.

**Safety is uncapped** - a coolant overheat must never lose a slot to a rest nudge, and a budget that
can silence a real warning is worse than no budget. Uncapped still means inside the master switch and
inside call 2 above.

The cap is the strongest guarantee on this map because it is the only anti-compulsion mechanism that
is **countable**. Tone is not testable; three is.

### 4. Over the cap: dropped, not queued

A nudge nobody heard is not owed back later. Queueing produces *"here are the four things I saved
up"*, which is precisely the pile-on the cap exists to prevent, and deferring to the next day lets one
rule fire twice for one event, which is close to nagging.

**This interacts with [ticket 06](06-delivery.md)'s "notify when it cannot speak", and the resolution
is: the cap governs SPEECH, not existence.** A capped raise is not destroyed - it becomes a
notification, which is [ticket 06](06-delivery.md)'s answer to "a raise nobody heard". Dropped means
*not spoken aloud*, never *lost without trace*. A silently dropped safety warning remains the worst
outcome on this map and this decision does not create one.

### 5. A brush-off counts against the budget, and is inferred from the reply

It spoke, so it spent a slot. That is what a budget means, and the opposite rule - only accepted
nudges spend budget - lets a run of ignored nudges cost nothing and keep talking, which is the exact
failure the cap exists to prevent.

The live model already sees the reply, so [ticket 02](02-trigger-engine.md)'s `proactive_raise` row
records `declined` from it. **Inference is imperfect and is not pretended otherwise:** a grunt may or
may not be a no. What a wrong inference costs is bounded - [ticket 08](08-proactive-register.md)
suppresses that rule for a fixed window - so a false decline loses one nudge, and a missed decline
means the rule returns on schedule. Neither failure is loud, which is why inference is acceptable
here and would not be for anything the app asserts aloud.

### 6. Doze

[Ticket 07](07-scheduling-research.md) already established what the OS does on its own. This ticket
claims no credit for it: quiet hours exist so the app behaves correctly on a phone that is awake at
2am, not to duplicate what Doze would have enforced on one that is not.
