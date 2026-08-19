---
map: hands-and-senses
ticket: 18
title: "Inbox intelligence: packages and travel, from mail LEGION can already read"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
   this ticket define them, or does [the brief](08-morning-brief.md) own delivery and this ticket
   only own extraction? Do not build two paths.
6. **Tool budget.** One tool, two, or a parameter on the existing Gmail search tool? Write the
   descriptions.
