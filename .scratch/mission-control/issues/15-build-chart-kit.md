# Build: chart kit under one data hue

Type: task
Status: resolved
Blocked by: 13

## Question

Recolour and extend `ui/common/DeckCharts.kt` to ticket 06's answer.

Graduated from fog 2026-08-14.

## Scope

1. **Series colour** moves to `data` mint. **Threshold and target lines move to `amber`, dashed.**
   This incidentally fixes a **live bug in shipped code**: `DeckBarChart` currently draws an amber
   `primary` fill against a green `credit` target line, which is dE 5.5 under deuteranopia (ticket
   06, measured).
2. **Chrome stays OUT of the plot**, a deliberate scoped exception: gridlines keep `ruleFaint`, axis
   labels keep `faint`, both unchanged from shipped. Red inside a plot means an ALARM annotation and
   nothing else.
3. **Shape-typed markers**, since hue can no longer carry meaning: filled dot = a logged reading,
   hollow dot = the latest value / endpoint, diamond = an estimate, cross = provisional or
   `UNRECONCILED`.
4. **Small multiples** as the default multi-series form. **Overlay is capped at two series**, mint
   and amber, both direct-labelled at their endpoints. Never three.
5. **`DeckMeter`** fill to mint, pace tick to amber, height 12dp to 6dp (ticket 03).

## Do not break

- **"Null = gap, never zero" and its 18 existing tests are untouched.** Nothing drawn means a gap.
- Charts in a root panel stay sparkline-sized; drilldown charts get >= 120dp (ticket 05).
- HOME's three existing sparklines (INTAKE, SLEEP, LEDGER cumulative) already ship - recolour them,
  do not remove or re-invent them.

## Verification

- `compileDebugKotlin` and `testDebugUnitTest` green, including the 18 gap tests.
- Render the chart previews in `DeckCharts.kt`.
- **Confirm marker shapes are actually distinguishable at sparkline size on the device.** Ticket 06
  flagged this as `reasoned`, not seen. If they are not, that is a finding, and the fallback is
  fewer marker types rather than a second hue.

## Answer

Built 2026-08-14. The chart kit now speaks one data hue.

### What landed

- **Series colour moved to `data` mint** across `DeckSparkline`, `DeckSmallMultiple`,
  `DeckLineChart` and `DeckBarChart`.
- **`DeckBarChart`'s target line moved from green to amber, dashed.** This **fixes a live shipped
  bug**: ticket 06 measured the old amber-fill-against-green-target pair at dE 5.5 under
  deuteranopia. Green no longer exists in the palette, so it is a correctness fix as well as a
  recolour.
- **Chrome stayed out of the plot**, the scoped exception ticket 06 recorded. Gridlines keep
  `ruleFaint`, axis labels keep `faint`, both untouched.
- **A shape-typed marker vocabulary**: `DeckMarkerType` with `LOGGED` (filled dot), `ENDPOINT`
  (hollow dot), `ESTIMATE` (diamond), `PROVISIONAL` (cross). All four draw in `marker`; **shape
  carries the meaning, never a second hue.** Optional and defaulted, so no existing call site
  changed.
- **The two-series overlay cap is structural, not a convention.** `DeckLineChart` takes one typed
  `DeckLineOverlay?` rather than a list, so a third overlaid series is unrepresentable rather than
  merely discouraged.

### The check ticket 06 could not make

Ticket 06 flagged marker distinguishability at sparkline size as `reasoned`, explicitly not seen,
with the stated fallback of fewer marker types rather than a second hue.

**It was checked properly and the answer is that they are distinguishable.** The build agent stood
up a throwaway rig rendering the real composables at shipped sparkline scale, screenshotted it,
sampled pixels, and reverted the rig completely (confirmed: `git diff` on `MainActivity.kt` is
empty and no temp file survives). Filled dot, hollow ring, diamond and cross read as different
silhouettes at real device density.

**Ticket 06's `reasoned` claim is upgraded to `on-device` verified true. No fallback needed.**

### Verified on the device

The LEDGER sparkline on HOME was amber before this ticket and is the easiest proof the recolour
reached a real screen rather than only a preview. **Sampled by the orchestrator independently: 861
pixels of exactly `#57EFC6` in the sparkline band, plus antialiasing variants, and no amber.**

Pixel sampling rather than eyeballing, deliberately - ticket 14 established that judging a rendered
detail by eye is not a check.

### Two findings, neither blocking

Both were reported rather than fixed, and neither has a live caller:

1. **`DeckBar`'s `valueLabel` and `mark` anchor to the same point** above the bar and can collide
   (seen as `53<diamond>00` in the rig). No shipped call site sets `DeckBar.mark` - this ticket only
   built the capacity - so nothing live is affected. Needs an offset whenever a real caller uses
   both.
2. **`DeckLineOverlay`'s endpoint labels can crowd the right edge** or the x-axis labels when a
   series ends near the top or bottom. Again, no shipped caller uses overlay yet.

### A correction to this ticket's own text

This ticket said "the 18 gap tests". **There are 24**, confirmed from the test-results XML rather
than assumed. The count grew before this session. The full suite is 1056 tests, 0 failures.

### Verification accounting (CLAUDE.md §8, L11)

| Step | Status |
|---|---|
| `compileDebugKotlin -Pnokey` | **DONE**, green, run directly by the orchestrator |
| `testDebugUnitTest` including every gap test | **DONE**, green. 24 chart-data tests, 1056 total, 0 failures |
| Update previews in `DeckCharts.kt` and `ThemePreview.kt` | **DONE** - marker vocabulary, two-series overlay, bar chart with dashed target |
| Install and confirm the LEDGER sparkline is mint | **DONE**, APK hash-matched both sides, sampled `#57EFC6` on-device |
| Check marker distinguishability at sparkline size | **DONE**, and it resolves ticket 06's open `reasoned` flag |

Nothing owed on this ticket.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Compile and tests green, 24 chart tests, 1056 total | **`tested`** - run directly, count read from the results XML |
| Working tree holds only the intended files, rig fully reverted | **`tested`** - `git status` and an empty `git diff` on `MainActivity.kt` |
| APK installed is byte-identical to the built one | **`on-device`** - SHA-256 both sides |
| The LEDGER sparkline renders mint on HOME | **`on-device`** - 861 pixels of `#57EFC6` sampled by the orchestrator |
| Marker shapes are distinguishable at sparkline scale | **`on-device`** - rendered at shipped scale and sampled, upgrading ticket 06 |
| The bar-chart target is now dashed amber and distinct from a mint fill | **`on-device`** |
| The label collisions are non-blocking | `reasoned` - true only while no shipped caller sets `DeckBar.mark` or uses overlay |
| The two-series cap cannot be circumvented | `traced` - it is a typed single parameter, not a list |
