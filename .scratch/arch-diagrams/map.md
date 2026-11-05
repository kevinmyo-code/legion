---
map: arch-diagrams
title: "Architecture diagrams that pop up when an interviewer asks"
charted: 2026-11-05
charted-by: "Kevin + Opus"
effort: "`.scratch/arch-diagrams/`"
tickets: 1
open: 1
status: open
tags: [map]
---

# Architecture diagrams that pop up

**Kevin, 2026-11-05:** *"can we add the project architecture diagrams like uml layers, views etc.
so a technical interviewer could ask and the diagrams pop up?"*, then *"same for all projects. so
legion, midcon, quanex even?"* and *"same with my agentic coding workflows, something that
visualizes"*.

When a visitor on `/try` (or a member on the web) asks how something is built, Alfred calls
`show_diagram(project, view, highlight)` and the picture appears as a card in the chat dock while
he explains it, outlining the parts he names.

## Rulings carried in the brief (Kevin, 2026-11-05)

1. **The model picks, it never draws.** `project` and `view` are fixed enums; every picture is a
   Mermaid source someone wrote from the code, rendered ahead of time.
2. **Pre-rendered SVG, checked for staleness** like the docs corpus and `openapi.yaml`, rather than
   shipping the Mermaid library to the browser.
3. **LEGION and the workflow are public** (`docs/architecture/views/`). **Midcon and Quanex are other
   companies' systems:** their sources live in Kevin's private folder and load into the engine's
   database, never the repo.
4. **The LinkedIn version gets no Quanex diagram**, the same way it gets no Quanex work section.
5. **Quanex facts come only from Kevin's approved recruiter text** (`work` and the Experience
   section `about_kevin` answers from): no figures, no real names, system types over product names.
6. **No phone work in this map.** The phone draws no diagram card and ignores the kind.

## Tickets

| # | Ticket | Status |
|---|---|---|
| 01 | [[01-show-diagram]] | built |
