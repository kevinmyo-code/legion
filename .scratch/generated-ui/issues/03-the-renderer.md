---
map: generated-ui
ticket: "03"
title: "The Compose renderer: validation, components, and worded failure"
type: build
status: open
status-detail: ""
blockers: ["01", "02"]
blocked-by: ["[[01-the-response-schema]]", "[[02-the-tool-binding-contract]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The Compose renderer: validation, components, and worded failure

## Question

Build the renderer once 01 and 02 are settled:

- Parse and **schema-validate before anything reaches Compose**. Reject on unknown component,
  unknown version, missing required field, or a binding to a non-allowlisted tool. A hostile or
  malformed payload fails to a worded error; never a crash, never a blank, never a partial render
  that looks complete.
- Map components to **mission-control** components ([[0023-design-language-mission-control]]).
  Reuse `ui/theme/` and the existing deck controls rather than inventing a parallel kit.
- Every component renders its provenance affordances (`unverified`, `as of`) when the bound result
  carries them.
- Screenshot tests via Roborazzi for each component, plus the refusal states. The refusal states
  matter more than the happy path: they are what the user sees when the model is wrong.

## Verification

- [ ] A payload with an unknown component renders a worded refusal and nothing else.
- [ ] A payload binding to a non-allowlisted tool is rejected before any tool call happens.
- [ ] A bound result carrying `UNRECONCILED` renders the word, not a colour alone (§4 rule 7).
- [ ] A binding that fails at fetch time renders what could not be read, distinctly from empty.
- [ ] Roborazzi snapshots for every component and every refusal state.
- [ ] `testDebugUnitTest` green.
