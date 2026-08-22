---
map: hands-and-senses
ticket: 18
title: "Inbox intelligence: packages and travel, from mail LEGION can already read"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-22 (Kevin). Mail only. Build ticket 25."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Inbox intelligence: packages and travel, from mail LEGION can already read

## Question

`gmail.readonly` is granted and `GmailToolLogic` passes a `q` query through unchanged, so two
everyday JARVIS answers are close to free:

- **"Where's my package?"** Carrier + tracking number live in shipping mail. Extract, then either
  report what the mail itself says (delivery estimate, last update) or hit a carrier API.
- **"When's my flight?" / trip view.** Airline and hotel confirmations live in mail: dates, times,
  confirmation numbers, addresses.

Both are read-only, pull-shaped, and need no new auth. Decide:

1. **Mail-only, or carrier APIs too?** Reading the mail is free and honest but stale ("shipped
   Tuesday"); live status needs UPS/FedEx/USPS developer keys (each its own signup, BYO shape) or
   an aggregator. Is stale-but-free enough? If a carrier key is wanted, that is a research ticket
   this one graduates, not a decision to guess at here.
2. **The extraction question, and it is a §4 question.** Pulling "tracking number 1Z999..." or
   "flight UA328, 6:15am" out of prose is LLM extraction with no printed total to reconcile
   against. Rule 5 governs: it is an estimate of what the mail says, and a wrong flight time is a
   missed flight. Does Alfred always name the mail it read ("your United confirmation from
   Tuesday says 6:15am") so Kevin can check it? Is anything ever stored, or is it strictly
   read-through like every other mail path (google-account ticket 07)?
3. **Calendar collision.** Flights usually land in Google Calendar automatically already
   (`CalendarProvider` reads it). If the calendar has the flight, mail extraction is redundant and
   worse. **Check what the calendar already knows before building a mail path** - this ticket may
   shrink to packages only.
4. **Trip assembly.** Does a multi-leg trip become an object (flights + hotel + car in one view),
   or does Alfred answer questions one at a time from live reads? An object means storage, which
   means the read-through rule bends - argue it or drop it.
5. **Morning brief overlap.** "Package arriving today", "flight tomorrow" are brief modules. Does
   this ticket define them, or does [the sitrep](08-morning-brief.md) own delivery and this ticket
   only own extraction? Do not build two paths.
6. **Tool budget.** One tool, two, or a parameter on the existing Gmail search tool? Write the
   descriptions.

---

## Resolved 2026-08-22 (Kevin)

| # | Question | Ruling |
|---|---|---|
| 1 | Mail-only, or carrier APIs? | **Mail only.** Kevin, verbatim: *"inbox > mail only."* No UPS/FedEx/USPS keys, no aggregator, no research ticket graduated. Stale-but-honest beats another BYO signup. |
| 2 | Extraction under §4 | It is LLM extraction with **no printed total to reconcile against**, so §4 rule 5 governs absolutely: it is an estimate of what the mail says, never a fact. **A wrong flight time is a missed flight**, which is why rule 5 is doing real work here rather than being a formality. |
| 3 | Does it name the mail it read? | **Always, and this is mandatory rather than a nicety.** *"Your United confirmation from Tuesday says 6:15am."* Naming the source is the ONLY thing that makes an unverifiable extraction checkable by Kevin - it converts "trust me" into "go look". Without it, rule 5's label is decoration. |
| 4 | Is anything stored? | **Nothing. Strictly read-through**, like every other mail path (google-account ticket 07). No Room row, no `CompanionMemory`, no `EpisodicTurn`, **not even a summary**. |
| 5 | Calendar collision | **The calendar is the source of truth for travel where it has the event.** Airlines already push flights into Google Calendar, and `read_calendar` already answers from it - deterministically, with no extraction and no estimate. Mail fills the GAPS: packages, which never reach a calendar, and trips that were never added. Two paths answering one question with different confidence is how a deterministic answer gets overwritten by a guessed one. |

**The shape that falls out of 1 and 4 together:** this is a read-only, pull-shaped query over mail
LEGION can already read, with no new auth, no new storage and no new dependency. That is why it is
cheap. It is also why every honesty rule has to be carried by SPEECH rather than by a gate - there
is no gate available, and pretending otherwise would be the failure.
