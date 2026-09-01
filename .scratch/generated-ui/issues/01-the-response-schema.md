---
map: generated-ui
ticket: "01"
title: "The response schema: what a generated view is allowed to say"
type: grilling
status: open
status-detail: "Partly answered by the 2026-09-01 generated view (74db850): closed 3-shape vocabulary, flat composition, provenance first-class. NO schema_version exists (point 5 unaddressed). See map reconciliation."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The response schema: what a generated view is allowed to say

## Question

The root ticket. A strict JSON schema the model emits and the renderer consumes. Decide:

1. **The component vocabulary.** Which components exist, and how closed the set is. A closed set is
   safer, cheaper in tokens and easier to validate; an open one avoids a schema change every time a
   new answer shape is wanted. Recommend closed, versioned, and small to start: a stat, a list, a
   table, a chart, a record detail, a short text block.
2. **Composition.** Flat list of blocks, or a nested tree? Recommend flat: it renders predictably,
   it is trivially validated, and a nested tree invites the model to design layouts rather than
   answer questions.
3. **Provenance is a first-class field, not a caption.** Every component that shows a figure must be
   able to carry `unverified` and an `as of`. If the schema can express `$1,240` but not
   `$1,240, unverified`, the renderer launders an unreconciled row into fact. This is CLAUDE.md §4
   rule 7 applied to pixels, and it is the reason a generic "text" component is not sufficient.
4. **What the schema CANNOT express**, stated as a deliberate list: free HTML, arbitrary styling,
   colours by name, navigation to arbitrary routes, anything that writes.
5. **Versioning.** A `schema_version` on every payload, and what the renderer does with one it does
   not recognise (recommend: worded refusal, never a partial render).

## Why it matters

Everything else in this map is downstream. The renderer, the token cost, the injection surface and
the honesty properties are all decided by what the schema permits.
