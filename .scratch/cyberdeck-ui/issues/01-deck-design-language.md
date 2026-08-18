# Deck design language

Type: prototype
Status: resolved

## Question

What does the diegetic cyberdeck actually look like? Build 2-3 genuinely different takes WITHIN
the locked dial (diegetic panels, dark-only, numbers-hero, rationed theatre) as throwaway HTML
mocks of REAL screens with REAL data shapes - Today, Body, Ledger at minimum, using representative
data (1450/2200 kcal, 3x8 leg press, budget vs actual). Kevin reacts, one wins.

Resolves: palette (ground, signal hues, how many accents), type stack (mono/display pairing),
panel language (brackets, borders, headers), density, and the overall temperature of the deck
(military-terse vs mediteck-clinical vs street-tech, or whatever the takes turn out to be).

The winning take becomes the token spec every downstream ticket reads.

## Answer

**MILSPEC** (Kevin, 2026-08-07), from the three-direction comparison at
`https://claude.ai/code/artifact/d8c62b71-4d72-486f-a38f-a4bfa628c0a5` (CLINICAL and STREET
declined; no hybrid requested).

The direction: avionics console. Phosphor amber on green-black, stencil caps, hard corner
brackets, dashed rules, dense. Status reads like a checklist. Coldest and most operational of
the three takes.

Token spec as prototyped (the starting point for `ui/theme`, refined in build, not re-litigated):

| Token | Value | Role |
|---|---|---|
| ground | `#0A0D08` | screen background (green-black) |
| panel | `#0D1109` | pane fill |
| ink | `#E8E6D8` | primary text (warm bone) |
| muted | `#8A8F78` | labels, secondary text |
| amber | `#FFB000` | THE accent: hero numbers, values, meters |
| green | `#7FBF3F` | armed/ok signal (semantic mapping is ticket 03's call) |
| red | `#FF5330` | reserved for quarantine-class states (ditto ticket 03) |
| line | `#2C3322` | hairlines, dashed row rules |
| edge | `#4A5238` | corner brackets, section rules |

Language of the panels:
- Mono everywhere. Headers: stencil-style caps, letterspacing ~0.2em, `MUTED // ACCENT` two-tone.
- Panes: 1px `line` border + 2px `edge` corner brackets (top-left and bottom-right only).
- Rows separated by DASHED hairlines; section tops by solid 2px `edge`.
- Tags are inverted blocks (dark text on `green`/`amber` fill), tiny, letterspaced.
- Meters chunky (~12px) with a pace/target tick in `green`.
- Status copy: checklist register - `NO LINK`, `NOT LOGGED`, `0 QUARANTINED`, `SET PLAN`.
- Provenance/estimate always worded (`REPORTED`, `ESTIMATED MACROS`), per CLAUDE.md §4.

Daylight-contrast note carried forward: ink-on-ground and amber-on-ground are high contrast by
construction; `muted` (#8A8F78) is the tier to watch - the fog's measurable contrast floors
apply to it first.
