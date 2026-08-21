---
map: proactive-mode
ticket: 10
title: "What an unsolicited prompt may contain"
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - contract accepted, enforced by a typed raise object"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Resolution - 2026-08-21 (Kevin, 1 call; points 3-5 settled by ticket 02)

### 1. The contract, accepted as proposed

> An unsolicited prompt may ask the model to mention a subject only if the same prompt states the
> facts of that subject; where it has no facts, it must forbid the subject in words rather than stay
> silent about it.

Silence is what the opener did, and silence is what got filled with a lunch appointment with a person
who does not exist. Added to the map as **settled decision 7**.

### 2. Enforced by a TYPED RAISE OBJECT, not by a convention

A raise stops being a `String`. It becomes an object carrying at minimum an id, a category, the rule
that fired it, and its **facts** - and `ProactiveBus` refuses one whose facts field is empty for a
subject it invites the model to mention.

The two cheaper options were both rejected for the same reason: a convention with a comment is
**exactly what [ticket 01](01-one-gate-not-three.md) found had already failed once** - three copies of
the same gate, two of which quietly diverged - and a test over the prompt strings catches phrasing but
can never catch an actual absence of facts.

**It churns all eleven call sites, and that cost buys three things at once**, which is what makes it
worth paying: this contract, [ticket 02](02-trigger-engine.md)'s `proactive_raise` row, and
[ticket 06](06-delivery.md)'s "spoke successfully, so do not also notify" all need the same object.
Build it once.

**Honest limit:** the gate can check that a facts field is non-empty. It cannot check that the facts
are true, relevant, or complete. This converts a silent failure into a refusable one; it does not make
the prompt correct.

### 3. Unreadable versus empty is a RULE of the contract, not a per-source detail

Every permissioned source has the trap: a `ContentResolver` returns an empty list for a refused
permission and an empty list for genuinely nothing, and rendering the first as the second tells Kevin
he is free when the app cannot see. `OpenerCalendarBriefing` splits them into three outcomes -
`NO_PERMISSION`, `NOTHING_SCHEDULED`, and the real list.

**Any raise reading a permissioned source states which of the three it is.** This generalises beyond
the calendar to contacts, health, notifications, and anything added later.

[Ticket 06](06-delivery.md) immediately depends on it: the mid-meeting check downgrades a raise to a
notification, and "no calendar permission" must resolve to *unknown, so notify*, never to *no meeting,
so speak*.

### 4. The ambient listener: retired

It could not satisfy this contract even in principle - the sub-agent authored the spoken line, so
there were no facts for the prompt to state. [Ticket 12](12-retire-ambient-listening.md).

### 5. A proactive raise does NOT call tools; it pre-fetches

Settled by [ticket 02](02-trigger-engine.md)'s hybrid: deterministic evaluation decides whether to
speak and gathers the facts, and the model receives them already assembled. Letting a raise call
`read_calendar` mid-greeting adds a round trip that can hang, and hands the model back the decision
about what to look at - which is the half the hybrid exists to keep away from it.
