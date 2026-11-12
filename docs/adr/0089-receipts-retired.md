---
status: accepted
decided: 2026-11-12
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-12 - Receipts retired; daily loops: grades, location reminders, leave-by, budget pace (Kevin)]]"
tags: [adr]
---

# 89. Receipt photos are retired

## Standing

**LEGION no longer ingests receipt photos.** The photo path (camera capture for a receipt, the LLM
vision extraction, its voice tool and the screens that start it) is removed, not hidden. Receipt
rows already stored stay readable, with their provenance and any `UNRECONCILED` label unchanged
(CLAUDE.md section 4 rule 7). Groceries ticks and the purchase log are not receipts and are
untouched.

## Context

Kevin stopped photographing receipts: *"Receipts are being retired. Im not taking photosnof
receipts anymore."* Pantry was the one aspect with no deterministic ingestion path, which is why
section 4 allowed LLM vision there. With no receipts there is nothing for that allowance to cover.

## Decision

Remove the path (Kevin's pick over "hide it, keep the code"). Section 4's gate rules still bind
any future ingestion path; this ADR adds none.

## Consequences

- Pantry's per-item macros from receipts stop arriving; the Body aspect reads what Kevin logs.
- A later return to receipts is a new decision and a new ingestion path behind the gate, not a
  revert.
- Build ticket: `.scratch/daily-loops/issues/01-remove-receipt-path.md`.
