---
map: cyberdeck-ui
ticket: 05
title: Navigation shell
type: prototype
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-deck-design-language]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Navigation shell

## Question

How does the deck present its modules? Today `LegionRoute` + MainActivity drive navigation with a
conventional structure. The deck fiction suggests modules ("uplinks"/"decks"/"systems") rather than
tabs. Prototype the shell: module switcher vs bottom nav vs drawer-as-deck-panel, where the
assistant strip lives, where global status (sync, OBD link, key state) surfaces in the chrome, and
how the utility screens are reached without bespoke redesign.

Constraint: nine data surfaces is too many for a flat bottom nav - some grouping is forced; the
grouping IS the information architecture of "my life in data", so it deserves a prototype, not a
default.

## Answer

**Option A: HARD-KEY ROW** (Kevin, 2026-08-07), from the two-option comparison at
`https://claude.ai/code/artifact/3548bf98-066d-4dcf-89b1-aad497a1e7db`. Module launcher (B)
declined - two taps per cross-module move, and its live tiles duplicated Today's panels.

The shell:
- Bottom bar reskinned as five physical hard-keys: `HOME / BIO / LOG / FLEET / CRED` (the
  existing five-module grouping, deck names). Stencil caps, active key inverts to amber,
  1px key separators, 2px edge rule on top. One tap anywhere-to-anywhere; existing
  `NavigationBar` wiring survives underneath.
- **Global status line at top of every screen**: `SYNC OK · OBD NO LINK · KEY ARMED` left,
  clock + blinking cursor right (the one ambient element, per motion ticket).
- **Alfred's strip stays pinned above the key row** (existing bottomBar-slot anchoring holds).
- Utility screens stay reachable through the existing settings route, no bespoke key.

Surfaced during resolution: **driving mode** - a driving-style UI Kevin can opt into when the
OBD dongle is connected. New ticket 11, not part of this shell decision.
