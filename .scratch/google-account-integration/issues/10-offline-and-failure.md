---
map: google-account-integration
ticket: 10
title: "What does the app do when Google is unreachable?"
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What does the app do when Google is unreachable?

**Mostly collapsed 2026-08-13 by [ticket 02](02-calendar-api-choice.md), exactly as question 1
anticipated.** `CalendarContract` is an on-device provider, so calendar reads, writes and
conflict-checks all work offline with no cache, no staleness and no queue. **Questions 1, 2 and 3
are answered for calendar and only need confirming in writing.** What survives is Gmail-only: Gmail
is a network call and question 4 (what Alfred says when a tool call fails mid-session, distinguishing
offline / not authorized / quota / API error) plus question 5 (quota, which ticket 03 found is not a
real ceiling) still need deciding. Retitle this in your head as "what does Alfred say when Gmail
fails", and consider whether it is now small enough to fold into ticket 05 or 06 rather than stand
alone.

## Question

CLAUDE.md §7: network calls degrade gracefully offline. Until now that has been cheap, because every
domain's data was already on the device. Google-owned events are not.

1. **Caching.** Is there a local cache of upcoming events, and if so what is its window and its
   invalidation rule? Ticket 04 decides what the local table stores; this decides whether that is a
   cache at all. If `CalendarContract` wins ticket 02 this question may collapse to nothing - the
   provider is already local - so answer it after 02, not before.
2. **What the deck shows** with no network and no cache: an empty panel, the last known state with a
   staleness line, or an explicit "cannot reach Google". Never a silent empty state that reads as
   "you have nothing on".
3. **Writes made offline.** Alfred creates an event with no connection. Queued, refused, or written
   locally and pushed later? Queued-and-pushed is the answer that can silently lose data, which is
   the failure shape this repo keeps hitting - argue it properly or refuse the write in words.
4. **Errors mid-session.** A tool call fails while Alfred is talking. What does he say? Distinguish
   offline / not authorized / quota exceeded / genuine API error - the `DriveConnectResolver` lesson
   was that collapsing distinct failures into one message costs a day.
5. **Quota exhaustion**, if ticket 03 finds a ceiling worth defending against.

## Answer

**Calendar has no offline story to write. Gmail refuses in words, four distinct ways, and never
queues anything.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin. Kept as its own ticket
rather than folded into 05 or 06, because the failure wording is a decision the build must not
improvise.

1. **Caching: none, and none needed.** `CalendarContract` is on-device (ticket 02) and ticket 04
   stores nothing about a Google event, so calendar reads work offline with no cache, no window, no
   invalidation rule. Gmail is not cached either - a stale inbox read out as current is worse than
   no answer, and there is no surface holding one (ticket 08: Gmail is voice-only).
2. **What the deck shows offline: the agenda, complete.** Nothing about calendar degrades. The only
   empty-state that matters is the permission one, decided in ticket 08: never an empty day that
   reads as "you have nothing on".
3. **Writes made offline: they are not offline.** A voice-created appointment is a
   `CalendarContract` insert, which is a local write flagged dirty; Google's sync adapter uploads it
   when there is a connection. Kevin is never blocked and nothing is queued *by LEGION*. **This is
   the whole reason ticket 02 went this way** - the queued-and-pushed design that silently loses
   data is the one LEGION never has to write. A reminder is a local row and was always offline.
4. **Errors mid-session: four messages, never one.** Alfred says which it was, plainly, and does not
   retry silently.
   | Cause | What Alfred says |
   |---|---|
   | No network | "I can't reach Gmail - no connection." |
   | Grant lapsed or revoked | "Gmail needs re-authorising. It's in Setup, under Google." |
   | Never granted | "You haven't given me access to Gmail yet." |
   | Quota, or any other API error | "Gmail returned an error - I'll not guess at what's in there." |
   Collapsing these into one message is the exact defect that cost a day on 2026-08-03, when
   `DEVELOPER_ERROR` and Kevin tapping cancel were indistinguishable. Ticket 06's
   `GoogleGrantResolver` produces the middle two; this ticket is what obliges the caller to use them.
5. **Quota needs no defence.** Ticket 03 measured ~405 units a briefing against a 6,000/min/user
   ceiling - roughly 14 briefings a minute before anything complains. The row above exists so the
   failure is legible if it ever happens, not because it is expected.

**The one rule underneath all of this: LEGION never answers a mail question from anything but a
successful live read.** No cache, no queue, no partial answer, no "here's what I had earlier". If it
cannot read, it says so.
