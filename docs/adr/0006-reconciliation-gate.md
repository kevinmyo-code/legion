---
status: locked
decided: 2026-07-30
decided-by: Kevin
amended: 2026-08-06
supersedes: [0005-no-llm-extraction]
source: "CLAUDE.md §4"
tags: [adr]
---

# 6. LLM extraction, allowed only behind a deterministic gate

## Standing

LOCKED, and AMENDED 2026-08-06 by [[0009-provisional-unreconciled-tier]]. This is the core architectural rule of the project.

## Context

Reversing [[0005-no-llm-extraction]] needed an answer to the question that motivated the ban: how do you know the extracted rows are the rows on the page? Bank statements answer it themselves. They print their own total.

## Decision

LLM extraction is allowed only behind a deterministic reconciliation gate. Deterministic parsing runs first where a parser recognises the layout; the LLM is the fallback. Extracted rows must sum **exactly** to the document's own stated total, or the entire document quarantines and nothing is written.

## Consequences

- Rule 6 closes the gate against itself: a check that passes when nothing parsed is not a gate. Inside a recognised section every non-total line must parse, or the document quarantines. BofA's interest rows silently failed all four and reconciled zero against a printed $0.00; it held only because interest was zero that month.
- Pantry has no deterministic path at all, because receipts are photographed rather than born-digital. LLM vision is primary there by necessity, not preference, and the gate still applies.
- Every row carries provenance: `DETERMINISTIC` or `LLM_RECONCILED`. Both passed the same gate; provenance is not a trust discount.
