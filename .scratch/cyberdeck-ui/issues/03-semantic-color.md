---
map: cyberdeck-ui
ticket: 03
title: Semantic color under the deck
type: grilling
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-deck-design-language]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Semantic color under the deck

## Question

How do LEGION's semantic states map onto the winning deck palette? The current system
(`LegionSemantics`: credit/debit/provenance, quarantine red) predates the new skin. Under neon
signal hues: which hue means money-in vs money-out, what does quarantine look like, how do
DETERMINISTIC / LLM_RECONCILED / UNRECONCILED provenance tiers and estimate-vs-fact read, and how
does TrustTier (PROVEN/REPORTED) surface on Body panels?

Hard constraint carried from CLAUDE.md §4: colour NEVER carries a state alone - "said in words" on
every surface. The deck aesthetic actually helps here (status text is diegetic), but the mapping
must be decided once, not per-screen.

## Answer

Grilled with Kevin, 2026-08-07. Three hue families, teachable in one sentence:
**amber = data, green = good, red = needs you.**

1. **Red `#FF5330` = intervention required, EXCLUSIVELY.** Quarantined documents, failed ingest,
   crisis-tier states. A debit is NEVER red - spending is normal life, rendered amber with a
   minus sign. Over-budget is worded (`71% AT 23% OF MONTH`) plus an amber advisory tag, not red.
   Red stays rare so that when it appears, Kevin actually looks.
2. **Green `#7FBF3F` = the good/armed family.** System-ok states, pace-ahead ticks, AND money-in
   (credits are rare enough that green stays meaningful - the exact reason red-for-debits was
   rejected). Amber `#FFB000` = every other value.
3. **Silence is the strong state (exception tagging).** A fully verified row carries no tag; the
   section states the default once, checklist-style (`ALL ROWS RECONCILED`, `0 QUARANTINED`).
   Tag weight ladder, fixed:
   - `EST` / `REPORTED` - muted outline tag, informational.
   - `UNRECONCILED` - amber inverted tag on the row, PLUS wording on any aggregate containing
     one (§4 rule 7, per-surface, non-negotiable).
   - Quarantine - red inverted, the only red in the app.
   - Refinement: where a state is universal on a surface (Body = all REPORTED), the panel
     header carries it once (`UPLINK: SELF-REPORT`), not per-row.

Folded-in calls (follow from the above, stated not grilled):
- `LLM_RECONCILED` renders identically to `DETERMINISTIC` at list level - both passed the same
  gate; the method is visible in row detail.
- Amber INVERTED tags = advisories (`SET PLAN`, `PACING HOT`) - amber is data and advice about
  data; red remains untouched by advisories.

Build consequence: `LegionSemantics` is re-cut to this mapping when the theme ticket builds.
