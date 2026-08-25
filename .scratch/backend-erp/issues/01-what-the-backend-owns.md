---
map: backend-erp
ticket: "01"
title: "What the backend owns: schema, truth, and the phone's residual role"
type: grilling
status: claimed
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# What the backend owns: schema, truth, and the phone's residual role

## Question

The root ticket. Decide:

1. The Postgres schema: the engine's four generic tables translated (records as JSONB payload plus
   promoted columns, mirroring Room v37), or per-aspect real tables now that Postgres does DDL
   properly? Recommend the generic shape moves as-is: it is proven, and the metadata layer IS the
   product.
2. What stays phone-only: OBD-live state, wake word, photo files?
3. Room's new role: consumer cache with what freshness contract; which reads may hit the network
   synchronously ("always online") vs cache-first.
4. Writes: the phone writes to Supabase directly (PostgREST) with RecordStore becoming a client of
   the API, or offline-queue-and-push?
5. What of the engine's enforcement (references, delete policies, computed fields) moves into
   Postgres (FKs, triggers, RLS) vs stays client-side vs both.

## Grilling in progress (2026-08-25, resume here)

Rulings so far, Kevin's answers in order:

1. **Supabase is the ONE master.** The calendar-projection idea (todos projected into Google
   Calendar) was chosen first, then SUPERSEDED by a sharper ruling: **LEGION keeps its OWN
   calendar** - the Dates aspect moves to Supabase with everything else, every consumer renders
   its own calendar view over the existing agenda query, and Google Calendar demotes to the
   already-built one-way import feed. The LEGION::v1 description blocks (merged 2026-08-25) stay
   useful read-side for imported events only; there is no projection.
2. **Undated todos get due=tomorrow AS AN INFERRED FACT** - tagged source:inferred, rendered as
   "showing tomorrow (no date set)", rolls forward silently, NEVER goes overdue or nags. Only a
   stated date may nag. (The provenance discipline applied to defaults.)
3. Calendar-delete semantics: DISSOLVED - Kevin will not edit or delete in Google Calendar, and
   with the own-calendar ruling there is no projection to edit.

**PENDING - Question 4, awaiting Kevin's answer:** where do todos live?
- A (recommended, strongly): Notes records with dueAt, shown THROUGH the calendar view via the
  agenda query - keeps recurrence/skips/place-triggers/tick history that already survived two
  cutovers.
- B: todos become Dates events - purity, but rebuilds all that machinery on the event type.
Plus a rider to confirm: Google import stays as a feed (classes keep flowing in).

Remaining after Q4: the original ticket questions 1-5 (schema shape, phone-only residue, cache
freshness, write path, enforcement location). Related context landed the same day: the llm-wiki
notes research (research/wiki-notes-second-brain.md) and cross-interface memory - both in the
map's Not yet specified.
