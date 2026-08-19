---
map: ledger-drive-ingestion
ticket: 02
title: "What does this app look like?"
type: prototype
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What does this app look like?

## Question

The city-pop design language died with the pivot and nothing replaced it. `ui/` is a deliberate
clean slate with no theme, no token set, and no reference. **Every UI ticket in this map is blocked
behind this**, and it is a taste call only Kevin can make.

Settle enough to build against, not a full design system:

1. **Register.** The assistant is Alfred/JARVIS, a tool with a personality. What does that look
   like? Restrained and instrument-like, or warm? Nothing in the code answers this.
2. **Baseline.** Plain Material 3 with a chosen color scheme and dynamic color, or a custom token
   set like Midnight AI's `AriaColors`/`AriaType`/`AriaDimens`? Material 3 is far cheaper and is
   already the dependency; a token set costs real time but is the only route to a distinct look.
3. **Density and navigation.** Three aspects plus settings. Bottom bar, nav rail, or a home surface
   that fans out? Phone-only now, so no head-unit landscape constraint.
4. **Data-heavy surfaces.** Ledger is tables of numbers. That is the hardest thing to make both
   legible and characterful, so decide it here rather than discovering it in the ledger UI ticket.

Produce a cheap concrete artifact to react to (Compose previews or a rough screenshot mock), not a
written description. Two or three distinct directions beat one polished one.

**Do not reinstate anything city-pop**, and note that the frame-clock motion ban is lifted
(CLAUDE.md §7): normal Compose animation is legal now.

## Answer

**Direction A ("Instrument") built on Direction C's machinery.** Kevin's call, 2026-08-01, from the
three-direction comparison at `https://claude.ai/code/artifact/b35ee01f-6a3b-453e-8147-055553cf4f78`.

1. **Register: the assistant as a readout.** Numbers are the hero, chrome gets out of the way,
   colour means state and nothing else. Matches the Alfred/JARVIS register in CLAUDE.md §1 without
   needing a mascot or illustration, neither of which this project has or wants.
2. **Baseline: Material 3, retuned - NOT a bespoke token system.** M3 is a component library plus a
   token layer, and Instrument is almost entirely a retuning of the token layer. Everything M3 gives
   free is kept: component behaviour, touch targets, accessibility semantics, dynamic type. This is
   the cheap route to the look, and it is explicitly a rejection of Midnight AI's approach, where
   `AriaColors`/`AriaType`/`AriaDimens` were built from scratch and everything downstream had to
   know about them.
   Three overrides carry the whole direction:
   - **Shape scale flattened to near-zero.** The single most load-bearing override. M3's 8-28dp
     radii and card-first habit cost vertical space and soften exactly the quality being sought.
     2dp survives on `large`/`extraLarge` because at 0dp a sheet has no visible edge against a
     near-black ground.
   - **Monospace for anything numeric.** Compose has no `font-variant-numeric: tabular-nums`, so
     choosing a mono family IS how digits are made to line up in a column. This is the mechanism,
     not the aesthetic.
   - **One accent.** `secondary` and `tertiary` are neutrals, not a second and third accent.
     Boldness is spent in one place.
3. **No dynamic colour.** `dynamicColorScheme` would hand the signal hue to the user's wallpaper,
   and the signal is the identity. Declined deliberately, documented in `Theme.kt`.
4. **Data-heavy surfaces: solved by hairlines, not cards.** A row plus a 1dp rule, with a softer
   rule for repeating separators so long lists do not stripe. Amounts are right-aligned mono; the
   description truncates, the number never does.
5. **Semantic roles live outside `ColorScheme`.** Money and provenance are orthogonal to M3's accent
   system, so `LegionSemantics` (via `LocalLegionSemantics`) carries `credit` / `debit` /
   `estimated` / `quarantined` / rules / faint / ghost. Squatting on `tertiary` would lie about the
   role's meaning and break when a component reads it for its own purposes.
   - **`debit` resolves to plain `onSurface`, deliberately.** Most statement rows are debits;
     colouring them all red turns signal into noise and makes the rare credit harder to find.
   - **`estimated` is a guardrail, not styling.** CLAUDE.md §4 rule five. Colour alone never
     satisfies it - it fails in greyscale and for colour-blind users - so an explicit label carries
     the meaning and the colour reinforces it.

### Built, not just decided

- `ui/theme/Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`, `ThemePreview.kt`.
- `res/values/themes.xml` retargeted from `Theme.Material.Light.NoActionBar` (which flashed white on
  every cold start against a near-black app) with `res/values/colors.xml` mirroring the ground for
  the pre-Compose launch window.
- `./gradlew compileDebugKotlin -Pnokey` green. **`tested` only in the sense that it compiles - no
  preview has been rendered and nothing has run on a device.**

### Left open on purpose

- **Dark-vs-light default.** `LegionTheme(darkTheme = true)` ignores the system setting, because
  Instrument is a dark-committed direction. A light scheme exists so the app is not broken in
  daylight, not because following the system was chosen. `LegionThemeFollowingSystem` is provided
  and unused. Changing this is one default parameter.
- **Icon set and motion.** These follow the direction rather than choosing it. Note the frame-clock
  motion ban is dead (CLAUDE.md §7), so normal Compose animation is available.
- `ThemePreview.kt` holds previews, not components. Shared components get extracted once the ledger
  UI ticket settles what they need to be.
