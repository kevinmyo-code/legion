---
map: aspect-engine
title: "Map: The aspect engine - every aspect an instance of one module"
charted: 2026-08-23
charted-by: "Kevin + Fable"
effort: "`.scratch/aspect-engine/`"
tickets: 21
open: 6
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

- [Can the app write files back into a Drive folder through SAF?](issues/01-drive-folder-write-back.md) -
  works-with-caveats: create and write are real and reach the cloud, but truncation is unreliable,
  so writes are `rwt` full-rewrite plus read-back hash verify with mirror quarantine on mismatch.
  The local-folder-sync fallback is dead. On-A25 probe owed (ticket 20 carries it).
- [xlsx on Android: library, size, and embedded validation](issues/02-xlsx-on-android.md) -
  fastexcel, bare-JVM testable; money as integer cents in number cells; embedded validation is
  decoration (no source establishes Sheets mobile enforcing it), the import gate carries all
  integrity.
- [The engine schema](issues/03-engine-schema.md) - standard promoted set (incl. amountCents,
  searchText, provenance as a column); 13 field types v1 (duration deferred); references through
  the single `RecordStore` door; aspect delete = archive, record delete = trash, 30-day purge;
  schema editing voice-reachable via a Pro-tier generator subagent with confirm-before-commit.
- [Computed fields](issues/04-computed-fields.md) - aggregations plus same-record arithmetic, no
  formula language; materialized on write; errors in words, never a silent zero.
- [The central date database](issues/05-central-date-database.md) - a built-in Dates aspect on the
  engine; Google Calendar imports store everything including invites (tagged, one-way, deletions
  mirror); agenda is a query; exact alarms; every reminder passes the compulsion test.
- [The meta-tool surface](issues/06-meta-tool-surface.md) - nine meta-tools including
  create_aspect/update_aspect via the generator subagent; unconfirmed single deletes into trash,
  bulk confirms with count; the 97-tool inventory and voice_guide rethink owed in ticket 17.
- [The widget contract](issues/08-widget-contract.md) - all eight widget types in v1; layouts
  per-device and deliberately unsynced; error/empty states in words on every widget.
- [Generated screens](issues/10-generated-screens.md) - list/detail/form per record type, the
  ADR 0035 hands path; plugin detail override with the generated screen as permanent fallback;
  provenance in words; mission-control tokens.
- [The capability plugin API](issues/11-capability-plugin-api.md) - partially editable: plugin
  required fields locked and badged, everything else user-ownable; the gate is engine
  infrastructure every ingestion write passes; deleting a plugin aspect detaches and disables,
  data archives, defaults reinstallable.
- [The xlsx mirror and its import gate](issues/12-xlsx-mirror-import-gate.md) - workbook per
  aspect, sheet per record type; debounced export with staleness stated in words; reconciled rows
  read-only in the mirror; the gate carries all integrity.
- [Two-phone sync under the engine](issues/13-two-phone-sync.md) - **Kevin: the xlsx files ARE
  the sync channel**, journal and appDataFolder declined; binding condition: row-level merge
  keyed by record id plus updatedAt, never whole-file replace.
- [Freeze the superseded tickets across the other maps](issues/15-freeze-superseded-tickets.md) -
  swept all six maps: only hands-and-senses 29 (service-history/clock unification) was open and
  superseded (kiv, decision 3); GAI map annotated for decision 6; built tickets keep their
  phone-run debt; everything else already resolved or terminal.
- [Migration order](issues/14-migration-order.md) - new ground first (Dates + one user aspect),
  then notes/lists/places, pantry, ledger, fleet last; cutover per aspect; Drive export before
  each wave and old tables retained until on-device verification.

- [The aspect clerk: prototype and latency](issues/07-aspect-clerk-prototype.md) - Flash runs
  the clerk (1.1-2.5s median, zero hallucinations in 30 runs); Pro reserved for schema
  generation; **Kevin: hold silent, filler only past 4s**; the clerk must be handed the grounded
  current date (owed in ticket 17).

- [Dashboard grid mechanics: staged prototype](issues/09-grid-mechanics-prototype.md) - Kevin on
  the A25: **"feels like a dashboard."** Edit mode kept; stage 2 (true 2D occupancy-map grid,
  8-12 days) authorized and folded into Build the widget pager.

## Not yet specified

- **Aspect templates / sharing.** An aspect definition is data, so it could export/import. Unasked.
- **CompanionSync's fate.** Record sync moves to the xlsx channel; whether companion profiles keep
  their own appDataFolder path is reviewed at migration time (ticket 20 notes it).
- **Duration field type** and any v2 field types. Deferred at ticket 03.

## Out of scope

- **User-authored native capabilities.** No authoring Bluetooth stacks, parsers, or plugins from
  the phone. The two-layer split is the boundary (charter decision 2).
- **Live two-way Sheets sync / Sheets API.** Ruled out at charting: OAuth clone-and-run threat, no
  bytes to read via SAF. The xlsx mirror + import gate is the shipped shape.
- **Commercial anything.** Unchanged from the pivot.
