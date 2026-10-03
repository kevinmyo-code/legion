---
map: web-revamp
ticket: 03
title: The shell split by viewport
type: build
status: built
status-detail: "Two shells, NAV table, bigger-screen card, fake engine and shots are in; only Home and Lists exist as tabs or rail items today. Owed: a run on a real iPhone."
blockers: ["02"]
blocked-by: ["[[02-design-tokens-light-dark-manifest-icons]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The shell split by viewport

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D1.

## Build

- `src/lib/surface.ts`: `useSurface()` from `matchMedia('(min-width: 1024px)')`.
- `src/components/family-shell.tsx` (top bar, four bottom tabs, safe-area insets, 44 px targets) and
  `src/components/workbench-shell.tsx` (232 px rail). `app-shell.tsx` picks one by surface.
  **Different trees, never CSS-hidden.**
- One `NAV` table with `{to, label, icon, surfaces, built}`; a rail item renders only when `built`.
- `src/components/bigger-screen.tsx`: the "This page is made for a bigger screen." card, used by
  every workbench-only route at family width.
- `src/test/engine.ts`: the fake-engine helper every later ticket extends. `src/test/surface.ts`:
  stub `matchMedia` to either surface.
- Playwright: `@playwright/test` dev dependency and `e2e/shots.spec.ts` that runs the preview build
  against the fake engine and screenshots a route list at both viewports and both themes.
  `npm run shots` writes to `.scratch/web-revamp/research/shots/<label>/`.

## Verification

- [x] vitest: family width renders tabs and no rail; workbench renders rail and no tab bar (absence
      asserted with `queryBy*`); an unbuilt rail item never renders; a workbench-only route at family
      width shows the bigger-screen card.
- [x] `npm run shots` produces 4 images per route; kept in `research/shots/03/`.
