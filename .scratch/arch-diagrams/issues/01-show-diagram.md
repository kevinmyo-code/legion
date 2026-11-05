---
map: arch-diagrams
ticket: "01"
title: "show_diagram: the diagram catalogue, the tool and the web card"
type: build
status: built
status-detail: "built 2026-11-05 on feat/arch-diagrams, server suite, vitest and tsc green. Owed: Kevin approves the Quanex text, engine deploy, the private load (ship_recruiter_diagrams.sh), one /try run"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# show_diagram: the diagram catalogue, the tool and the web card

## What was built

**Views.** One Mermaid file per view, its title and caption its own `accTitle:` and `accDescr:`,
its node ids stable so a part can be highlighted.

| Project | Views | Where |
|---|---|---|
| legion | context, containers, components, voice_turn, recruiter_visit, alfred_builds, ingestion_gate, data_model, claude_task_states | `docs/architecture/views/legion-*.mmd` |
| workflow | loop, roles, knowledge, ticket_to_phone | `docs/architecture/views/workflow-*.mmd` |
| midcon | context, containers, po_status_voice | Kevin's private folder, never the repo |
| quanex | data_flow, publish_gate | Kevin's private folder, never the repo |

**Rendering.** `python tools/render_diagrams.py` drives mermaid-cli (pinned in
`server/diagrams/render.py`) with the installed Chrome, one config
(`server/diagrams/mermaid.config.json`: one light neutral ground, labels as SVG text, no HTML).
After rendering it stamps `data-node="<source id>"` on every node, cluster, state, entity and
participant, reads the node list back from the stamped SVG, trims coordinates to two decimals, and
refuses an SVG carrying script, `foreignObject` or an event handler. Public views write the SVG
beside the source and `server/diagrams/catalogue.json` (what the image carries); `--private
<folder>` writes SVGs and a `manifest.json` per project in that folder. `--check` and
`tests/test_diagrams.py` fail when a source changed and was not re-rendered (a hash of the source,
the config and the renderer version), with no browser needed.

**Private load.** `manage.py load_recruiter_diagrams <folder> [--for-code linkedin] [--project ..]`
reads the manifests into `diagrams.RecruiterDiagram`, keyed like `RecruiterContent` (a code's label,
else `current`). It refuses a stale or unknown view or an unsafe SVG and loads nothing for that
project. It never loads Quanex for LinkedIn.

**Tool.** `show_diagram(project, view, highlight)` in the engine registry (so every member's web
assistant has it) and in `RECRUITER_TOOLS`. **Not on `/mcp`**: nothing behind it can draw the
card (phone, head unit, Claude Code), so `/mcp` neither lists it nor runs it, and a call by name
says in words that nothing was shown (`engine_mcp.server.SCREEN_ONLY_TOOLS`). Each caller is
declared only the projects and views it can be shown (`catalogue.declared_for`, from
`surface.tools_for`): a private project with nothing loaded, or withheld from the version, is not
named in the enum or the description, so a LinkedIn visitor's Live token never mentions Quanex. It returns the caption and the view's
part ids in words for the model, and a `diagram` card (title, scope, caption, inline SVG,
highlight, nodes) beside `response`, never in it. Unknown highlight ids are dropped and named. A
LinkedIn visitor is refused Quanex in words whatever rows exist (`catalogue.WITHHELD`), including
when no LinkedIn rows exist and it falls back to `current`. `legion_docs` now ends with the view
that draws the top passages (`show_diagram(project="legion", view="ingestion_gate")`), keyed on
the passage's file or ADR number. The recruiter frame says: when asked how something is built,
show the matching diagram and walk through it, highlighting the parts as you name them.

**Web card.** `cards/diagram.tsx`: the SVG on its own light panel in the dock, cleaned again in the
browser (scripts, `foreignObject`, handlers removed), highlighted parts outlined and named in words
under it, the caption as the figure's caption. Tap opens it full screen; a pinch, ctrl-wheel or
the buttons zoom. Showing the same view again replaces the earlier card instead of stacking it.

**Phone.** Nothing built. `assistant/cards/ServerCards.kt` `parse` answers null for a kind it does
not know (`else -> null`), and `EngineCards.fromAnswer` then falls back to `fromTool`, which is null
for every tool but `run_procedure`, so a diagram card from `ask_engine` draws nothing, silently.
`show_diagram` is not offered over `/mcp` at all, so no diagram card reaches the phone today.

## Verification

| Step | State |
|---|---|
| pytest: `tests/test_diagrams.py`, `test_recruiter_isolation.py`, full server suite | done |
| vitest: `cards.test.tsx` (renders, highlights, strips script, full screen, zoom) | done |
| `npx tsc -b`, `openapi.yaml` and `schema.d.ts` regenerated | done |
| Kevin reads and approves the Quanex diagram text before it loads on the live engine | owed: Kevin |
| Engine deploy (the `diagrams` migration), then `ship_recruiter_diagrams.sh prod` | owed: Kevin |
| One `/try` run: ask "how is LEGION deployed?" and "show me your workflow" by voice, the card shows, highlights follow, full screen zooms on the phone | owed: Kevin, on-device |
