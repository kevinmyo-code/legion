---
map: aspect-engine
ticket: "10"
title: "Generated screens: list, detail, form"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Generated screens: list, detail, form

## Question

Charter decision 10: widgets are windows; behind them, three conventional screens per record
type, generated from field definitions - the guaranteed hands path (ADR 0035). Decide:

1. **List screen.** Columns/rows shown for an arbitrary record type (first N fields? a
   user-designated title field?), sort, filter, search, pagination for 400-row ledgers.
2. **Form generation.** Per field type: text/number/money/date/choice/reference (a picker over
   target records) /photo. Required-field enforcement, validation errors in words, and how a
   quarantining write is shown (the gate speaks here too).
3. **Detail screen.** Fields + computed fields + child records via references + provenance tag
   (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED **in words** - sec 4 rule 7 applies to
   generated surfaces exactly as to hand-built ones).
4. **Escape hatch.** Can an aspect override a generated screen with a native one (plugin-provided
   detail for a car with live OBD)? Recommend yes via the plugin API, and the generated one
   remains the fallback so the hands path never vanishes.
5. **Theming.** Mission-control skin tokens applied to generated components - one visual
   language, no second design system.
