---
map: aspect-engine
ticket: "18"
title: "Build the widget pager and generated screens"
type: task
status: open
status-detail: ""
blockers: ["09", "16"]
blocked-by: ["[[09-grid-mechanics-prototype]]", "[[16-build-engine-core]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build the widget pager and generated screens

## Question

Build what tickets 08 and 10 locked, informed by the grid prototype (ticket 09):

1. The pager: home page one, one page per aspect, new aspect = new page. Stage 1 mechanics
   (reorderable half/full-width cards); stage 2 free grid per the prototype's pricing.
2. The eight engine widget types: stat tile, record list, next-due, quick-add, single-record
   card, agenda, chart, photo. Per-device `widget_instances` persistence. Error/empty states in
   words on every widget.
3. Generated list / detail / form screens per record type from field defs: provenance in words,
   required-field and quarantine messaging, plugin detail override with generated fallback,
   mission-control tokens throughout.
4. **Owed from ticket 08:** enumerate the mapping of existing mission-control screens to default
   widget arrangements; ship those as the defaults.
5. Verified on the A25, hash-checked install.
