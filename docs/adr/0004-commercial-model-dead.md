---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2"
tags: [adr]
---

# 4. There is no commercial model

## Standing

LOCKED. The prohibition itself is the standing rule.

## Context

Midnight AI was a commercial head-unit product with tiers and a subscription. The pivot killed the premise, but dead commercial reasoning has a habit of walking back in as 'what if users'.

## Decision

No billing, tiers, broker, trial, store listing, pricing, or positioning work. `billing/` was deleted. Do not reason about conversion or retention.

## Consequences

- The ledger has no access gate. The only gate in the ingestion path is the reconciliation gate, which is about truth, not entitlement.
- There are no users, there is Kevin and a second phone. Feature arguments that appeal to a user base are appealing to nobody.
- This is upstream of the compulsion-mechanics ban in [[0025-warmth-allowed-compulsion-banned]]: with no retention to optimise, engagement mechanics have no argument left.
