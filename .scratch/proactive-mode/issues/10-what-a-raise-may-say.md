---
map: proactive-mode
ticket: 10
title: "What an unsolicited prompt may contain"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What an unsolicited prompt may contain

## Question

[Ticket 01](01-one-gate-not-three.md) settled **who may speak**. [Ticket 02](02-trigger-engine.md)
asks **what decides there is something worth saying**. Neither covers **what the raise is allowed to
put in the prompt once it has decided to speak** - and that gap has now produced a real invented
fact on the phone.

### The evidence, on-device, 2026-08-21 (Kevin)

The startup opener told Kevin he had **lunch with Sam**. There is no Sam in his life and no such row
anywhere in his data.

`ai/AriaBrain.kt`'s fact clause already forbids exactly this in the system prompt: *"NEVER state a
fact about the user's own record unless a tool call in THIS conversation returned it."* It did not
hold, and the reason is structural rather than a model failure. The opener's own turn-level prompt
said:

> If something notable is coming up or genuinely needs their attention, work it in briefly

while `buildOpenerSituation()` supplied time, place, weather, and car - **and no schedule at all**.
An instruction to mention what is coming up, paired with nothing to mention, is not neutral. It is a
request for content with no source, and the nearer instruction beat the global one. Same shape as
the invented "dentist appointment at 3" of 2026-08-18, which the fact clause was written for.

**Fixed for the opener** (`calendar/OpenerCalendarBriefing.kt`, 7 tests): the calendar is now read
deterministically and stated three ways that a model cannot fill in - no permission (say nothing at
all about the subject), readable-and-empty (say there is nothing), readable-with-events (these
exactly, and nothing not on this list exists). The invitation sentence is gone.

**What is NOT fixed is the general rule**, and that is this ticket.

### The raise-site survey - verified 2026-08-21, not remembered

Eleven live raise sites. Ten carry their own fact; one asked for content it had not supplied.

| Raise | Supplies its fact? |
|---|---|
| Startup opener | **NO, until tonight.** Asked for "something notable coming up" with no schedule attached |
| NHTSA recalls | Yes - count and components |
| New trouble code | Yes - the code strings |
| Coolant overheat | Yes - the temperature |
| Place arrival | Yes - the place and the reminder list |
| Two-hour break nudge | Yes - the drive duration |
| Rough weather | Yes - the description |
| Odometer milestone | Yes - the mileage floor |
| Fired reminder | Yes - the item text and list name |
| Incoming call | Yes - the number |
| Ambient reaction | Yes, in a different sense - **an LLM wrote the whole line** (see below) |

So the failure was one site, not a pattern. That is the argument FOR writing the rule down now,
while it is cheap: a twelfth raise site added by anyone, ever, inherits nothing today, and this map
will add several.

### Also surfaced by the survey, and it bears on ticket 02

**`service/AmbientListener.kt` is already shape (b).** [Ticket 02](02-trigger-engine.md) frames the
periodic-LLM-pass option as a hypothetical whose failure mode is that "it can invent a reason to
speak". It is not hypothetical - a `SubAgent` reads the overheard transcript, decides `SILENT` or
not, and **writes the spoken line itself**, which the raise then passes through verbatim. The map's
"what exists today" table does not say this. Whatever 02 decides has a live (b)-shaped precedent to
either bless or retire.

## Decide

1. **The contract, as a rule a raise site must satisfy.** Proposed, to accept or redraw: *an
   unsolicited prompt may ask the model to mention a subject only if the same prompt states the
   facts of that subject; where it has no facts, it must forbid the subject in words rather than
   stay silent about it.* Silence is what the opener did, and silence is what got filled.
2. **Is that enforceable, or only sayable?** The gate in `ProactiveBus.speakIfAllowed` sees a
   `String` and cannot know whether it carries its facts. Options: leave it a convention with a
   comment (cheap, breaks quietly), give the bus a typed raise object with a `facts` field
   (structural, churns 11 call sites), or a lint/test that fails on a raise prompt matching
   open-ended phrasing (weird, but it is the shape `AriaBrainHonestyClauseTest` already uses).
3. **Unreadable versus empty must never collapse.** `OpenerCalendarBriefing` splits them because a
   `ContentResolver` query returns an empty list for a refused permission AND for a clear day.
   Every future raise reading a permissioned source has the same trap. Is this a stated rule of the
   contract, or a per-source detail?
4. **What happens to the ambient listener under this contract.** It cannot satisfy rule 1 - the
   model authors the line, so there are no facts to state. Is it exempt as driver-adjacent chatter,
   is it retired, or does it need a different guard entirely (it currently has none beyond the
   `SILENT` convention)?
5. **Does a proactive raise get tools at all?** If a raise could call `read_calendar` itself,
   pre-fetching would be unnecessary. Worth settling explicitly rather than by accident: pre-fetch
   is deterministic and free of a round trip, tool-calling is flexible and can hang mid-greeting.

## Notes

- The fix that landed tonight assumed answer (1) above and pre-fetch for (5). Both are reversible.
- **This is a prompt rule, not a gate, and it is honestly weaker than one.** Nothing inspects the
  spoken audio - the same limit `CANNOT_CLAUSE`'s doc comment names. It removes the *reason* to
  invent and supplies real data instead; it cannot make obedience certain.
