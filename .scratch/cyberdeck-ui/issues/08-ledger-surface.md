# Ledger surface: resource monitor

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

Ledger has the deepest data (statements back in time, categories, budgets, provisional rows). As a
resource monitor: spend over time (per month, per category), budget burn-down within the month,
category breakdown viz, balance trajectory across accounts, where the existing drilldown and
recategorize flows live in the new structure. What stays a list (the transaction stream is good as
a stream), what becomes a chart, and how UNRECONCILED/provisional rows are worded inside any
aggregate figure they touch (§4 rule 7 - every surface, in words).

## Answer

Grilled with Kevin, 2026-08-07/08. **The plumbing collapses to one OPS row** - full-sections
inline declined; the module reads as a resource monitor first.

1. **Panels, fixed order, BIO grammar** (sparkline panel -> drilldown):
   - BURN: spent-vs-budget hero, meter + pace tick -> drilldown: per-category budget-vs-actual
     bars, month selector, burn-down line.
   - BALANCES: per-account rows -> drilldown: balance trajectory chart.
   - FLOW: 6-month spend bars -> drilldown: per-category monthly trends.
   - OPS: one checklist row (`SCAN OK · 0 PENDING · 2 GUESSES · 0 QUARANTINED`), exception-
     tagged, tap-through to the full ops panel (scan detail, account mappings, guess review).
     Quarantine turns this row red - the arresting moment by design.
   - STREAM: inline at the bottom, stays a list. Category drilldown and recategorize flows keep
     hanging off stream rows and category bars - re-skinned, not re-designed.
2. **UNRECONCILED/provisional rows**: amber inverted tag on the row; any aggregate containing
   one carries its worded label (§4 rule 7 - BURN and BALANCES are the aggregates in question).
3. Money right-aligned mono; description truncates, the number never does (carried from
   Instrument - it was correct).
