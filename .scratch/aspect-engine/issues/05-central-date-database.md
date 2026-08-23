---
map: aspect-engine
ticket: "05"
title: "The central date database: LEGION owns time"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
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
