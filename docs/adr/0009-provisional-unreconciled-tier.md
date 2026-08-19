---
status: locked
decided: 2026-08-06
decided-by: Kevin
source: "CLAUDE.md §4 rule 7"
tags: [adr]
---

# 9. A source with no anchor may be stored provisionally, never as fact

## Standing

LOCKED. Amends [[0006-reconciliation-gate]] rather than superseding it.

## Context

Bank of America's mid-cycle card CSV export prints nothing to reconcile against. No balances, no total, nothing. Under rules 1 to 6 it can never be ingested at all.

## Decision

Such a document may be ingested provisionally on four conditions, all load-bearing together and none optional: extraction is deterministic, every row is tagged `IngestMethod.UNRECONCILED`, every surface rendering one says so **in words**, and the rows are transient.

## Consequences

- Transient means: when a file that DID pass the gate commits over the same account and date range, the provisional rows in that window are deleted. An unverified row can never outlive or double-count against the verified row that supersedes it.
- Extraction must be deterministic because an LLM adds cost and nondeterminism to rows that are already unverifiable, and cannot manufacture an anchor.
- This narrows what 'commit' means. It does not widen what 'verified' means. The failure guarded against is not storing a weak row, it is storing one that later reads as strong.
