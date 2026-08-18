---
map: ledger-drive-ingestion
ticket: 09
title: "What do the fleet and pantry screens show?"
type: prototype
status: resolved
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-design-language]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What do the fleet and pantry screens show?

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

---

## Resolution (2026-08-02, prototype rendered on device)

**Prototype branch: `proto/fleet-pantry-ui`, commit `07abbdf`** (branched off `proto/ledger-ui`).
Not merged, never to be merged. Rendered on the **Oppo A17K at 360dp**.

Fake data only. No OBD adapter, no Gemini key, no receipt photos.

### 2. Pantry macros: TREATMENT B, SEGREGATED. This is the guardrail decision.

Two genuinely different treatments were built because CLAUDE.md §4 rule five makes this a
**guardrail, not styling**.

**CHOSEN: receipt numbers and guessed numbers are physically separated**, under their own headers,
with an explicit sentence between them:

```
ON THE RECEIPT
  ORGANIC WHOLE MILK 1 GAL              6.49
  CHICKEN BREAST BONELESS 2.1LB        12.87

ESTIMATED, NOT ON THE RECEIPT
  A receipt never prints nutrition. These are guesses from
  the product name and were not checked against anything.
  ORGANIC WHOLE MILK 1 GAL   610 kcal - 32P 48C 32F
```

**Why over the compact inline treatment.** Inline (`est.` prefix + `semantics.estimated` amber under
the product name) is denser and reads fine, but the estimate shares a row with a real price, so at a
glance the two can read as equally solid. Segregation makes that mistake structurally impossible
rather than merely discouraged. The sentence carries the meaning; the amber only reinforces it,
which is the §4 rule five requirement that colour alone is never sufficient.

**Accepted cost:** roughly double the vertical space, item names repeat, and reading one item's
price and macros means looking in two places.

### 1. Fleet: the minimum that is useful, and honest about the OBD stack

Four blocks, in order: **LIVE**, **DUE**, **FAULTS**, **NOT BUILT YET**.

- **LIVE carries a connection state and last-seen timestamps.** Rendered as
  `LIVE / DISCONNECTED` with "No OBD adapter connected. Values below are the last seen reading." and
  a per-row "3 days ago". This is deliberate: **nothing in the OBD stack has run since the port**, so
  the screen must not imply a live reading it does not have. It doubles as the way to find out
  whether the stack still works.
- **DUE** shows interval and last-done as the subtitle; `OVERDUE` uses `semantics.quarantined`.
- **FAULTS** shows code, description and first-seen.

**Defect the render exposed:** the fault *description* ("EVAP small leak") rendered in
`semantics.quarantined` red at reading size, so it visually dominated the code `P0442`. A
description is not an alarm state. **Fix: description in normal ink; red reserved for the code or an
explicit severity marker.**

### 3. Shared vocabulary - extract these four now, before three screens diverge

Built and validated across both aspects in this prototype, and matching what ticket 08 needed:

| Component | Job |
|---|---|
| `SectionHeader(left, right?)` | Stamp-cased label, optional trailing count, solid `rule` hairline under it |
| `Hairline()` | `ruleFaint` row separator, so long lists do not stripe |
| `ReadingRow(label, value, sub?, valueColor?)` | The workhorse: title + optional subtitle, trailing mono reading. Every aspect uses it |
| `NotBuiltRow(label, why)` | See sub-question 4 |

**Extract to `ui/common/`.** They are already used by fleet, pantry and (in shape) the ledger list.

### 4. Data with no UI: an explicit NOT BUILT block, never dead space

```
NOT BUILT YET
  Service history        12 records in the database, no screen    NOT BUILT
  Build sheet            4 entries, no screen                     NOT BUILT
  Monthly recaps         generated, never displayed               NOT BUILT
```

Ghosted (`semantics.ghost`), stating **what exists and why it is not shown**. Rendered well and
reads as honest rather than broken. This is the same posture as the rest of the project: say plainly
what is not built rather than hiding it or faking it.

### What this ticket does NOT settle

- Any fleet interaction. Everything above is read-only.
- Receipt capture flow. `CameraCapture` exists; the screen around it does not.
- Grocery spend aggregation and consumption-rate tracking, deferred at scoping time (CLAUDE.md §10).
