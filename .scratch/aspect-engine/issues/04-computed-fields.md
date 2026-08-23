---
map: aspect-engine
ticket: "04"
title: "Computed fields: vocabulary and evaluation"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Computed fields: vocabulary and evaluation

## Question

Charter decision 12: totals, averages, and friends are in the contract. Decide how far the
vocabulary goes and who evaluates it:

1. **Vocabulary v1.** Aggregations over child records via a reference (sum, avg, count, min, max,
   latest), arithmetic between fields on one record, or both? Where is the line that keeps this
   from becoming a formula language nobody asked for?
2. **Evaluation.** On read (always fresh, costs a query per widget render) vs materialized on
   write (fast reads, invalidation complexity when a child changes)? Recommend and justify with
   the dashboard's render pattern in mind - a page of widgets is many computed reads per glance.
3. **Type safety.** sum over money-cents is money-cents; avg over money is... what? Integer
   division rules, and what the xlsx mirror shows for computed columns (read-only, ticket 02's
   protection findings apply).
4. **Failure.** A computed field whose expression references a deleted field: error state shown
   in words, never a silent zero (the sec 4 rule 6 posture applied to arithmetic).
