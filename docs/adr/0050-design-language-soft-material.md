---
status: accepted
decided: 2026-09-27
decided-by: Kevin
supersedes: [0023-design-language-mission-control]
source: "[[decisions#2026-09-27 - HOME becomes a launcher, and the phone's look goes soft]]"
tags: [adr]
---

# 50. Design language: soft Material

## Standing

ACCEPTED, being built (`.scratch/home-launcher/`). The phone's design language is a soft,
dark Material look. It supersedes [[0023-design-language-mission-control]] **surface by surface**:
HOME, the Lists screens and the shell chrome (status line, talk bar) move first, and every other
screen keeps mission control until it is next touched, then converts. The web client is not
covered; it keeps its own family-first register.

## Context

Kevin, 2026-09-27, asking for a non-scrolling home and lists that look like lists: *"the lists now
also doesnt look very appealing. it should look like an actual list."* Offered mission control
upgraded, soft modern Material, or light and warm, he picked soft Material, then approved a
clickable prototype in it.

## Decision

- Dark grey grounds (`#121317`) and rounded cards (`#1C1E24`), not black panes with ruled edges.
- One colour per area (Calendar, Lists, Money, Body, Fleet, Recordings, News, Reports), carried as
  a tonal icon chip; lists reuse the same eight pairs.
- Figtree, bundled, in place of the monospace face; Material Symbols Rounded, bundled, for icons.
- No global bezel. Sentence-case words, not uppercase stamps.
- Tokens live in `ui/theme/soft/`; `SoftTheme` wraps a converted surface and `LegionTheme` stays
  on the rest until they convert.

## Consequences

- Two looks coexist on the phone during the migration, with a visible seam at the chrome's edge on
  any screen not yet converted. Accepted by Kevin when he chose "Home, Lists, and shell chrome" over
  "Whole app now".
- **What does not change with the look:** trust disclosures stay in words on every surface
  (CLAUDE.md sec 4 rules 5 and 7), states are never colour alone, and a failed read never renders
  as an empty one.
- The L11 trap moves with it: `contentColorFor` resolves by value, so every container slot in the
  soft scheme is pairwise distinct, and a test says so.
