---
map: aspect-engine
ticket: "03"
title: "The engine schema: fixed tables, field types, references"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-23 with Kevin, batched grilling."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The engine schema: fixed tables, field types, references

## Question

The root ticket - everything downstream reads the answer. Design the fixed generic tables that
hold every aspect and every record (charter decision 3), and the field-type vocabulary. Decide:

1. **Tables.** Proposed skeleton to grill against: `aspects` (id, name, icon, color, position),
   `record_types` (id, aspectId, name), `field_defs` (id, recordTypeId, name, type, required,
   config JSON - choice options, reference target, delete policy, computed expression),
   `records` (id, recordTypeId, createdAt, updatedAt, dueAt, payload JSON, plus promoted
   columns). What else is promoted out of the payload: amount-cents? search text? Which indexes?
2. **Field types v1.** text / number / money-cents / date / datetime / boolean / choice /
   reference / photo-ref / computed. What is deliberately absent (multi-select? location?
   duration?) and what does absence cost the fleet/ledger/pantry migration?
3. **Reference semantics** (charter decision 11): per-field delete policy vocabulary, and where
   enforcement lives so every write path (meta-tools, forms, import gate, plugins) goes through
   one door - a single RecordStore write API, nothing writes `records` directly.
4. **Aspect lifecycle basics.** Create/rename/delete an aspect; what delete does to its records
   (soft-delete window? hard delete with confirm?). Plugin-attached aspects defer detail to
   ticket 11, but the schema must not preclude the answer.
5. **Provenance carried over.** IngestMethod tags (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED)
   must survive as first-class on records - the gate (CLAUDE.md sec 4) depends on them. Column or
   payload? Recommend column: the gate queries it.
6. **Room migration shape.** New tables are additive (v28+). The 48-entity data migration is
   ticket 14's problem; this ticket only guarantees the target schema can hold what they contain.

Resolve with /grilling + /domain-modeling; the answer is a schema spec with verbatim CREATE
statements and the v1 field-type table. Opens a build ticket on resolution.

## Answer

Resolved 2026-08-23 (Kevin, batched grilling).

1. **Promoted columns: the standard set.** `records` carries real columns for id, recordTypeId,
   createdAt, updatedAt, dueAt, amountCents, searchText, and provenance. Everything else lives in
   the JSON payload. Provenance is a COLUMN, not payload: the gate queries it.
2. **Field types v1:** text, number, money-cents, date, datetime, boolean, choice,
   multi-select choice, reference, photo, location, rating, computed. Duration deferred to v2.
3. **References:** engine-enforced through the single write door (`RecordStore`); per-field delete
   policy (block / cascade / null); nothing writes `records` directly, ever - meta-tools, forms,
   import gate, and plugins all use the one API.
4. **Aspect delete = archive.** Hidden everywhere, restorable from settings, hard-purged after 30
   days or on manual purge. **Record delete = trash**, same 30-day restore; voice deletes execute
   without confirmation and say what they binned; bulk deletes confirm with the count first.
5. **Schema editing is voice-reachable in v1**, via a **schema generator subagent** (Pro-tier
   model, executor pattern like the clerk): it drafts or amends the aspect definition from the
   spoken ask, and the definition is confirmed before commit.
6. Exact CREATE statements, indexes, and the migration test are build work:
   [Build the engine core](16-build-engine-core.md).
