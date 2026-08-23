---
map: aspect-engine
title: "Map: The aspect engine - every aspect an instance of one module"
charted: 2026-08-23
charted-by: "Kevin + Fable"
effort: "`.scratch/aspect-engine/`"
tickets: 15
open: 15
status: open
tags: [map]
---
# Map: The aspect engine - every aspect an instance of one module

## Destination

**LEGION's spine is a runtime aspect engine, and every current aspect runs on it.** A user can
create, edit, and delete aspects on the phone - freeform field names, typed columns, computed
totals - and every aspect gets, for free: CRUD by voice through generic meta-tools, generated
list/detail/form screens, and widgets on a launcher-style pager where home is page one and every
aspect is a page. Fleet, ledger, and pantry are migrated onto the engine, their native code
(OBD, parsers, vision) reattached as capability plugins. Execution is in scope: decisions get
locked here first, then the team builds straight through to a shipped, phone-verified app.
Kevin, 2026-08-23: *"we plan now, then i leave u to build with the team."*

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v27), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Skills:** `/grilling` + `/domain-modeling` for decision tickets, `/prototype` for the clerk and
the grid, `/research` for AFK facts. CLAUDE.md sections 4 (reconciliation gate), 5 (migrations),
7 (guardrails) bind everything here.

### Locked at charting (Kevin, 2026-08-23)

These are charter decisions, not resolved tickets. Tickets refine them; they do not reopen them.

1. **Full engine (option B), not a registry.** Users author brand-new aspects at runtime. Existing
   aspects migrate onto the engine rather than sitting beside it.
2. **Two-layer split.** Engine owns record types, fields, CRUD, generic voice tools, widgets -
   user-ownable. Native capabilities (OBD stack, statement parsers, pantry vision, music, comms)
   are Kotlin plugins that attach to aspects - never user-authorable from the phone.
3. **One central store, full migration.** All 48 typed entities migrate INTO fixed generic tables
   ("like an enterprise ERP stack"). Fixed tables + JSON payload + promoted hot columns, inside
   Room. **No runtime DDL** - Kevin offered to unblock dynamic SQL and then chose fixed tables.
4. **Offline requirement dropped for this effort.** Kevin: *"phone will always have internet."*
   The local store stays primary for speed, not for offline correctness.
5. **xlsx mirror, not Sheets API, not CSV.** One .xlsx per table in a normal user-visible Drive
   folder (SAF, no OAuth). Data-validation rules generated into the file from field definitions.
   Hand edits return through a validating import gate that quarantines what fails - never live
   two-way sync. A native Google Sheet is not a file and is out.
6. **LEGION owns time.** A central date database is the truth; Google Calendar imports into it as
   rows; reminders fire from it. Every record in every aspect carries a remind/due date column.
   **Supersedes google-account-integration's "Google owns timed events" ruling.**
7. **Meta-tools (option B), not per-aspect generated tools.** A fixed small set (~8): list_aspects,
   describe_aspect, query_records, create_record, update_record, delete_record, plus a clerk.
   Capability plugins may still register bespoke native tools for verbs (clear DTCs, play music).
8. **The clerk is an executor, not a router.** Tool *selection* stays with the live model; a
   SubAgent-pattern clerk runs multi-step CRUD off-thread and reports what it actually wrote,
   preserving the outcome-verb rule.
9. **The whole app is a widget pager.** Home is page one; every aspect is a page; new aspect =
   new page. Grid mechanics staged: reorderable cards first, free launcher-grid second.
   Mission-control look becomes the default arrangement, not a competing home.
10. **Widgets are windows (option A).** Tapping drills into engine-generated conventional screens:
    record list, record detail, add/edit form - generated from the same field definitions the
    meta-tools read. Ingestion UIs (camera, import flows) stay native with their plugins.
11. **References enforced by the engine, not SQL.** A `reference` field type stores the target
    record id; the engine enforces existence on write, a per-field delete policy
    (block/cascade/null), and quarantines dangling references at the import gate.
12. **Computed fields are in the contract** (totals, averages over child records). Derived, so
    excluded from the reconciliation gate and read-only in the xlsx mirror.
13. **Active maps freeze where superseded.** Shipped code migrates like any other code; unbuilt
    tickets that this engine replaces freeze with a pointer here (ticket 15).

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Bring him forks with real cost or taste; decide
  implementation without asking.
- **The reconciliation gate is not negotiable per-feature.** Migration re-plumbs it; nothing may
  weaken it. Money stays Long cents inside JSON payloads.
- **Anything that resolves to buildable-with-no-open-decisions gets built** (Kevin's standing
  2026-08-21 rule). A resolved decision ticket that authorises code opens its build ticket in the
  same commit.
- **A ticket's verification steps are gates** (L11). The migration of real data off 48 entities is
  the highest-stakes Room work this repo has done; nothing lands without its migration test.

## Decisions so far

<!-- one line per resolved ticket -->

## Not yet specified

- **Per-aspect migration specifics.** How fleet's OBD live data, ledger's gate plumbing, and
  pantry's photo store each carve into engine records + plugin. Sharpens after the schema
  (ticket 03) and the plugin API (ticket 11) land.
- **Reminders firing.** AlarmManager vs WorkManager, notification shape, and how the proactive-mode
  compulsion test applies to due-date nudges. Waits on the date database (ticket 05).
- **The proactive layer and the date DB.** OpenerCalendarBriefing currently reads Google directly;
  it should read the central date table once that exists.
- **Docs and generators.** voice_guide.py assumes 97 hand-written tools; the meta-tool world needs
  a rethink of what the user guide even lists. Same for the README voice-surface block.
- **Aspect templates / sharing.** An aspect definition is data, so it could export/import. Unasked.
- **What "delete the fleet aspect" does to the plugin.** Detach? Disable? Sharpen in ticket 11.

## Out of scope

- **User-authored native capabilities.** No authoring Bluetooth stacks, parsers, or plugins from
  the phone. The two-layer split is the boundary (charter decision 2).
- **Live two-way Sheets sync / Sheets API.** Ruled out at charting: OAuth clone-and-run threat, no
  bytes to read via SAF. The xlsx mirror + import gate is the shipped shape.
- **Commercial anything.** Unchanged from the pivot.
