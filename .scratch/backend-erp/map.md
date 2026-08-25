---
map: backend-erp
title: "Map: The backend IS the ERP - Supabase as source of truth, apps as consumers"
charted: 2026-08-25
charted-by: "Kevin + Fable"
effort: "`.scratch/backend-erp/`"
tickets: 6
open: 6
status: open
tags: [map]
---
# Map: The backend IS the ERP - Supabase as source of truth, apps as consumers

## Destination

**The ERP's source of truth moves to a backend (Supabase, likely) and the Android app becomes one
consumer among several** - a Windows/laptop surface is explicitly coming, with authentication for
two users now and more later. Kevin, 2026-08-25: *"backend (supabase maybe) is the ERP and the
android app consumes the data from ERP, there will be more consumer surfaces like my laptop etc
(windows app etc.) so authentication needed (2 users for now but more can be added etc.)"*
Destination is DECISIONS locked plus the migration path charted; building follows per decision.

## Notes

**Domain:** LEGION (CLAUDE.md binds where not explicitly superseded here). This map REOPENS, at
Kevin's own initiative, parts of the locked no-backend rule (CLAUDE.md sec 2) beyond what the
hardening map already reopened for sync: the backend is no longer just a sync channel but the
system of record. What is NOT reopened: no KEVIN-HOSTED anything - a BYO Supabase project per
household preserves clone-and-run the same way the Gemini key does. The engine's data model
(aspects/record_types/field_defs/records, provenance, the gate) is the asset being relocated, not
redesigned. **Supersedes when built:** hardening ticket 02's sync-channel-only scope (this is
strictly wider); local-Room-primary (Room becomes a consumer cache); the xlsx mirror's sync role
(the audit/export role survives - decided in ticket 04).

### Standing constraints carried forward

- The reconciliation gate and provenance discipline apply wherever the write path lands (ticket 03).
- Outcome-verb honesty binds every surface, new ones included.
- Two adults, BYO everything: auth is real accounts but never roles/tenancy/approval workflows.
- A backend outage degrades to the local cache, said in words - "always online" is Kevin's premise,
  not a license to show blanks.

## Decisions so far

## Not yet specified

- **Todo lists into Google Calendar** (Kevin, same message, via the LEGION::v1 description blocks
  just merged): whether the Notes aspect's dated items migrate INTO Google Calendar as their store,
  which reshapes Notes/Dates before or alongside the backend move. Needs its own decision once
  ticket 01 settles what the backend owns.
- The Windows app's actual shape (native? web? Tauri?) - after the API exists.
- Whether voice (Gemini Live) ever runs on a non-phone surface.

## Out of scope

- Kevin-hosted infrastructure. BYO project only.
- Commercial anything, roles, tenancy, approval workflows.
- Redesigning the record schema - it moves, it does not get reinvented.
