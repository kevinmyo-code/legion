---
map: aspect-engine
ticket: "05"
title: "The central date database: LEGION owns time"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-23: built-in Dates aspect, store everything imported, exact alarms."
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The central date database: LEGION owns time

## Question

Charter decision 6, superseding google-account-integration's "Google owns timed events". LEGION
keeps a central date store; Google Calendar imports into it; reminders fire from it; every record
carries a due/remind date. Decide:

1. **Shape.** Is a "date" its own record type in a built-in Dates aspect (eating our own engine),
   or a dedicated table? Recommend built-in aspect: it gets voice CRUD, widgets, screens, and the
   xlsx mirror for free, and proves the engine can carry a core feature.
2. **Google import mechanics.** Read-through vs stored rows - stored, per the charter, but tagged
   with source + Google event id so re-import updates rather than duplicates. Deletion on the
   Google side: mirrored or kept? One-way import only, or does a LEGION-created event export back?
   Recommend one-way in v1.
3. **The third-party-content rule.** CLAUDE.md sec 7 forbids persisting what others wrote TO
   Kevin. Calendar events others invited him to sit exactly on that line - decide explicitly
   whether an accepted invite is "Kevin chose to import" (storable) or third-party content
   (read-through only). This was the old ruling's justification; it needs a written answer, not
   an assumption.
4. **Due dates across aspects.** The records table already has dueAt. Does the Dates aspect
   *contain* those, or does the agenda view *query* across records + dates? Recommend query - one
   fact, one place (the three-bugs-one-shape lesson).
5. **Firing.** Deferred detail to the build, but decide the mechanism class now: exact alarms
   (permission on Android 14+) vs WorkManager windows, and how a fired reminder honors the
   compulsion test (anchored, actionable, silenceable, never absence-referencing).

## Answer

Resolved 2026-08-23 (Kevin, batched grilling).

1. **Shape: a built-in Dates aspect on the engine itself.** Events are records; voice CRUD,
   widgets, generated screens, and the mirror come free, and the engine proves it can carry a
   core feature.
2. **Google import: store everything imported, invites included.** Connecting the calendar is a
   deliberate import choice, same posture as dropping a statement in the ledger folder. Rows are
   tagged source=google plus event id; re-import updates in place; Google-side deletions mirror.
   One-way import in v1, no export back.
3. **The third-party rule is answered in writing:** an accepted calendar invite counts as
   "Kevin chose to import", storable. Mail keeps its read-through-only posture unchanged.
4. **Agenda is a query**, across the Dates aspect plus every record's dueAt column. Due dates are
   not copied into the Dates aspect; one fact, one place.
5. **Firing: exact alarms** (SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM requested once). Every fired
   reminder passes the compulsion test: anchored, actionable, silenceable, never
   absence-referencing.

Build work: [Build the Dates aspect](19-build-dates-aspect.md).
