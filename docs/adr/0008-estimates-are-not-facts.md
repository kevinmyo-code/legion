---
status: locked
decided: 2026-07-30
decided-by: Kevin
amended: 2026-08-02
source: "CLAUDE.md §4 rule 5"
tags: [adr]
---

# 8. Anything the document does not state is an estimate

## Standing

LOCKED. Extended 2026-08-02 from a labelling rule to a layout rule.

## Context

Pantry shows calories, protein, carbs and fat per item. A receipt has never printed any of those. They are the model guessing from a product name.

## Decision

A value the source document does not state cannot be gated and must never be presented as fact. It is excluded from the reconciliation check and labelled an estimate, in the tool description and in every user-facing string.

## Consequences

- Labelling was not enough. As of 2026-08-02 estimates are **physically segregated** into their own block, headed `ESTIMATED, NOT ON THE RECEIPT`, with a sentence saying receipts do not print nutrition.
- Colour alone never carries this meaning. The sentence is load-bearing, because a glance reads an inline number as equally solid regardless of its hue.
- The doubled vertical cost was accepted deliberately. The goal is that confusing a guess for a fact is structurally impossible, not merely discouraged.
