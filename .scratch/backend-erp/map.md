---
map: backend-erp
title: "Map: The backend IS the ERP - Supabase as source of truth, apps as consumers"
charted: 2026-08-25
charted-by: "Kevin + Fable"
effort: "`.scratch/backend-erp/`"
tickets: 6
open: 0
status: resolved
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

- [Supabase feasibility on the real free tier](issues/06-supabase-feasibility.md) - fits the
  household ERP on every hard limit; the one hazard is the 7-day inactivity pause (data survives,
  manual resume) - a daily keep-alive defeats it, Pro at $25/mo removes it and adds backups. The
  free tier has no backups, so the xlsx mirror carries recovery. supabase-kt is mature; gate
  commits map to atomic RPCs; Google OAuth repeats the SHA-1 clone-and-run friction.

- [What the backend owns](issues/01-what-the-backend-owns.md) - eleven rulings. Postgres gets
  per-aspect REAL tables and the phone goes typed with them, so the generic engine retires
  (9,518 production + 6,367 test lines, but it retired zero legacy tables, so most of the Room-side
  work is repointing writes back). Todos merge into Dates events. Google Calendar is dropped, in a
  binding order: widen the importer first, then cut. Writes direct with no offline queue; reads
  cache-first. All sequencing deferred to ticket 05.
- [Auth and identity](issues/02-auth-and-identity.md) - email + password (Google OAuth needs an
  SHA-1-keyed client id; magic link rides a 2-msg/hour not-for-production email service). Household
  RLS, all users see all rows, no roles ever. Both accounts made in the dashboard, no signup or
  invite flow. Personas and memories bind to the user, not the device. The session lives in
  KeyVault and fails closed.

- [The gate when truth lives remote](issues/03-the-gate-server-side.md) - the file commit becomes
  ONE atomic Supabase RPC, idempotent on the file's content hash so a lost ack can be retried
  rather than narrated. Gate arithmetic runs server-side with the phone pre-checking. **The
  deterministic statement parsers retire**: a statement goes through the user's OWN LLM, which
  masks sensitive data and emits a CSV in a LEGION-defined format carrying THREE anchors (printed
  total, opening, closing) so a self-consistent hallucination cannot pass. Account identity is
  last-4 plus a nickname. Rows tag `LLM_RECONCILED`. Rule-7 supersession stays inside the same
  transaction. Amends CLAUDE.md §4 rule 1, and amends ticket 01's ruling 10 to let receipt photos
  reach Supabase Storage.

- [Mirror and cache fate](issues/04-mirror-and-cache-fate.md) - the xlsx mirror is **retired
  entirely** (~2,200 lines), and the grounding pass justified it: the import half never
  round-tripped on a device, and `MirrorSyncActivity` has no in-app navigation at all. Hand-edit
  reimport dies with it, closing a real hole where a spreadsheet could mint records. The local
  cache is a **full replica** (569 records, roughly 285 KB). **Recovery moves to a SCHEDULED
  `DatabaseSnapshot` with a restore actually exercised** - and the mirror must not be deleted until
  both are done, or there is a window with no recovery at all.

- [The migration path](issues/05-migration-path.md) - the capstone. **`SyncEngine` retires
  PER-TABLE**, each table leaving the Drive registry in the same commit its writes move to Postgres,
  so no table is ever in two sync channels. The **Item-into-Event merge lands directly in the
  Supabase schema** (forced, not chosen: there is no legacy `events` table to fall back to). Kevin
  chose schema-and-auth-first over a thin vertical slice, so aspects run **smallest first** and
  Places is the de-facto proving run. Seven phases, seven hard constraints, and deletion is
  deliberately separated from retirement because **every rollback depends on the code deleted at
  the end still existing during the middle.**

## Not yet specified

- **The wiki as the notes system** (Kevin, 2026-08-25). Identified: Ben Holmes's "LLM Knowledge
  Bases" talk (I3bpdgFJCUY) - the llm-wiki pattern: raw dictated notes, a nightly agent enrichment
  pass (tags from a registry, wikilinks by grep, idempotency stamp), a weekly wiki-compilation
  pass. "Obsidian is the IDE; the LLM is the programmer; the wiki is the codebase." Full triage
  and the LEGION mapping (a Note record type on the Notes aspect, enrichment as a SubAgent batch,
  provenance-separated from dictated text): research/wiki-notes-second-brain.md. Becomes a ticket
  once ticket 01 settles where enrichment runs (the Supabase move makes it surface-independent -
  exactly the failure that forced Holmes to cloud scheduling).
- **Agent memory persistent across interfaces** (Kevin, 2026-08-25): companion memories must
  follow the user, not the device - Alfred on the Windows app remembers what Alfred on the phone
  learned. Implies companion_memories (and the audit trail?) migrate to Supabase with the records.

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
