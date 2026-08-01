# What do the fleet and pantry screens show?

Type: prototype
Status: open
Blocked by: 02

## Question

Scope is the full app shell plus all three aspects, so fleet and pantry need real screens too, not
placeholders. Both have working data layers and no UI.

Keep this deliberately basic. The goal is that each aspect is reachable and shows its own data, not
that either is finished.

1. **Fleet.** Richest data layer in the app: OBD live values, trends, maintenance schedule, DTCs,
   service history, build entries, recaps. Decide the minimum that makes it useful, and note that
   nothing here has run on a device since the port, so the screen doubles as the way to find out
   whether the OBD stack still works.
2. **Pantry.** Receipt capture, recent groceries with per-item macros, grocery spend. **Macros are
   LLM estimates and CLAUDE.md §4 rule five requires them to be surfaced as estimates, never as
   fact.** Decide exactly how that reads on screen; this is a guardrail, not a nicety.
3. **Shared vocabulary.** Both aspects plus ledger should feel like one app. Identify the shared
   components worth extracting now (list rows, empty states, section headers) rather than after
   three screens have diverged.
4. **What is honestly not built.** Neither aspect has a complete story. Decide what a screen shows
   for a feature that exists in the data layer but has no UI, rather than leaving dead space.

Compose previews, reusing the design-language decision.
