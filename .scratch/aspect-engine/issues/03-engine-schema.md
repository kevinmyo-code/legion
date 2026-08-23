---
map: aspect-engine
ticket: "03"
title: "The engine schema: fixed tables, field types, references"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
