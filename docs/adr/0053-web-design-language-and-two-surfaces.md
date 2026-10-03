---
status: accepted
decided: 2026-10-03
decided-by: Kevin
source: "[[decisions#2026-10-03 - web-revamp: one client, two surfaces, private rows, soft Material light]]"
tags: [adr]
---

# 53. The web's design language, and two surfaces rendered by viewport

## Standing

ACCEPTED, not built (`.scratch/web-revamp/`). The household web client uses a **soft Material light**
look with a dark mode that follows the system, and renders two different surfaces by viewport: a
family view below 1024 px and a workbench from 1024 px up. Refines [[0040-pc-is-the-primary-surface-phone-is-voice-first]]'s
"one codebase, responsive": one codebase, two rendered trees, not one page reflowed. The web was
previously drawn "family-first warm" (2026-09-12, never built); that drawing is retired.

## Context

The web shipped in stock shadcn grey with a PWA manifest in the phone's mission-control colours.
Kevin reopened the look on 2026-10-03 and picked from three clickable prototypes (family warm, calm
Apple, soft Material light): **C**. It is the light sibling of the phone's soft Material dark
([[0051-design-language-soft-material]]), so the two clients now read as one product. The surfaces
split because the users differ: Kevin reviews and edits records at the desk, Mia glances and ticks on
her iPhone, and ADR 0045 allows no roles to separate them any other way.

## Decision

- **Tokens** (OKLCH, light / dark), in `server/frontend/src/index.css`, never raw in components.
  Hue 285 neutrals: ground `0.98 0.004` / `0.17 0.006`; surface steps `0.958 0.009`, `0.938 0.012`,
  `0.915 0.014` / `0.215`, `0.25`, `0.29`; ink `0.22 0.012` / `0.93 0.006`; muted ink `0.48 0.016` /
  `0.75 0.012`; outline `0.72 0.014` / `0.52 0.012`, soft outline `0.88 0.01` / `0.34 0.01`.
  Primary hue 275: `0.5 0.15` / `0.78 0.11`, container `0.915 0.05` / `0.34 0.09`.
  **Shared** hue 355: `0.6 0.19` / `0.74 0.15`, container `0.925 0.055` / `0.34 0.09`, on-container
  `0.33 0.13` / `0.92 0.05`. **Unverified** amber: container `0.93 0.07 75` / `0.36 0.07 70`, on
  `0.38 0.09 60` / `0.9 0.08 80`. **Done** hue 160: `0.5 0.12` / `0.78 0.12`, container `0.92 0.05`
  / `0.33 0.06`. Source of truth for every value: `.scratch/web-revamp/research/prototypes/c-soft-material.html`.
- **Shape:** tonal surface containers, no heavy borders; 28 px sheets and large cards, 20 px cards,
  16 px controls, pill chips and buttons; filled tonal buttons for secondary actions, filled primary
  for the one main action.
- **Type:** Roboto Flex, bundled through `@fontsource-variable/roboto-flex`, never fetched at runtime.
  Tabular figures for money and times.
- **Colour carries no meaning alone.** Shared is the pink container plus the word "Shared" or a
  two-person glyph; private is a lock plus "Only you"; unverified is the amber chip with the word.
- **Theme:** System by default, Light / Dark override stored per device.
- **Surfaces:** `useSurface()` at `min-width: 1024px`. Family: bottom bar (Home, Lists, Calendar,
  Settings). Workbench: navigation rail. A workbench-only affordance is absent from the DOM at
  family width, never hidden by CSS.

## Consequences

- Mia on a laptop gets the workbench and Kevin on a narrow window gets the family view. Accepted:
  there is no permission difference to protect.
- The PWA manifest and icons stop deriving from the phone's `Color.kt`.
- `docs/design/canvas/` (the 2026-09-12 family-warm drawing) and `docs/design/today.md`'s "no
  multi-column dashboard" are history, not guidance.
