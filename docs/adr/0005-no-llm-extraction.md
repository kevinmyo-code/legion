---
status: superseded
decided: 2026-05-01
decided-by: Kevin
superseded-by: [0006-reconciliation-gate]
source: "Midnight AI era, pre-pivot"
tags: [adr]
---

# 5. No LLM extraction from financial documents

## Standing

SUPERSEDED by [[0006-reconciliation-gate]] on 2026-07-30. Kept for the reasoning, which was sound.

## Context

Midnight AI needed to read bank statements. An LLM will read any layout, and will also silently invent a row, drop a row, or transpose two digits, with no signal that it did.

## Decision

Do not use an LLM to extract financial data. Write a deterministic parser per layout, or do not support the layout.

## Consequences

- Correct about the failure mode. Wrong about the remedy: it capped supported layouts at however many parsers anyone felt like writing.
- The reversal did not decide the LLM was trustworthy. It found a way to make trust unnecessary.
