---
map: google-account-integration
ticket: 08
title: "What do mail and calendar look like on the deck?"
type: grilling
status: resolved
status-detail: ""
blockers: ["04"]
blocked-by: ["[[04-what-happens-to-local-timed-items]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What do mail and calendar look like on the deck?

## Question

The cyberdeck UI is decided and being built: MILSPEC grammar, amber = data, green = good, red =
needs you; hard-key row shell; modules per domain; staleness worded, never implied. See
`.scratch/cyberdeck-ui/map.md` and the 2026-08-07/08 entries in `memory/library/decisions.md`.

1. **Is calendar a module, or a panel inside an existing one?** The deck home leads on an INTAKE
   hero with a fixed order and no charts; Today already summarises the day and links into Notes.
   Google-owned events either move into that Today summary or claim their own surface.
2. **What replaces the local agenda view?** `notes-lists-calendar` ticket 08 decided agenda, no
   month grid, series expanded into the visible window only. Does that view survive with a Google
   backing store, or is it rebuilt?
3. **Does Gmail get a surface at all**, or is it voice-only? It is pull-only and read-only; a
   permanent inbox panel would be a screen Kevin already has a better app for. Voice-only is a real
   option and probably the cheap right answer - argue it.
4. **Staleness and offline.** The deck words staleness. What does a calendar panel say when the last
   successful read was hours ago? (Ticket 10 owns the mechanism; this owns the words.)
5. **Where the grant is surfaced** on these surfaces, if anywhere - ticket 06 owns the Setup side.

Prototype if the answer is not obvious from the grammar. `/prototype` is available and the deck has
enough existing screens to copy from.

## Answer

**No new module and no new screen. Google events become a second source on the agenda that already
exists. Gmail gets no surface at all - it is voice-only.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin. Ticket 04's split
(Google owns appointments, LEGION owns reminders, nothing in both) is what makes this small.

1. **Calendar is a source, not a module.** The agenda view built for `notes-lists-calendar` ticket 08
   already renders a time-ordered window; it gains a second query and merges. Today's summary picks
   up Google events the same way it picks up local reminders. **No CALENDAR key on the hard-key row**
   - the deck home has a fixed order and a whole module for something Kevin already has a better app
   for is the wrong trade.
2. **The local agenda view survives and is not rebuilt.** Ticket 02 found the provider's `Instances`
   time-range URI is the same query shape the agenda already chose - local, offline, series expanded
   for us. The merge is: local timed `ListItem`s (skips subtracted during expansion, as decided) plus
   `Instances` over the same window, sorted by start.
3. **Source is visible, in the deck's existing grammar, and in words where it matters.** Amber is
   data; a Google event is data, so amber. A LEGION reminder that is overdue or MISSED is red,
   because red is "needs you" and only reminders can need you - Google's events notify through
   Google. **An event row says it came from the calendar.** Kevin must be able to tell at a glance
   which rows LEGION will nag him about and which it will not - that is the visible half of ticket
   04's split, and getting it wrong means silently trusting an alarm that was never armed.
4. **Gmail is voice-only. No panel, no module, no inbox list.** It is pull-only and read-only, and
   Gmail is already installed on the same phone. Any panel LEGION draws is a worse Gmail that costs a
   permanent slot on a fixed-order home. The argument for a panel would be ambient awareness, and
   ambient awareness is exactly the proactive path settled decision 4 ruled out. **The only Gmail
   pixels in the app are the Setup row from ticket 06.**
5. **Staleness does not apply to calendar.** The provider is local, so there is no last-successful-
   read to word. What replaces it is a permission state: if `READ_CALENDAR` is not granted, the
   agenda says so in words and offers the grant, and never renders an empty day that reads as "you
   have nothing on". That is the same failure MEMORY.md's L15 note is about - individually correct,
   wrong in aggregate.

### Verification, binding on whoever builds this (CLAUDE.md §8, L11)

**Render the agenda with both sources present before building anything on top of it**, including a
day with a Google event and an overdue local reminder in the same window. Ticket 07 of the
`ledger-drive-ingestion` map was reported built with its render step unmet, and the exact bug it
existed to catch shipped. The colour call in (3) is the same class of thing: it cannot be verified by
reading code.
