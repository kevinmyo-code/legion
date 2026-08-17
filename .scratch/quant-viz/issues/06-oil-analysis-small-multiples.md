# 06 - Oil analysis small multiples

Status: resolved (2026-08-16, verified built in the all-effort sweep)

## What

`OilAnalysis` (`data/local/OilAnalysis.kt`) is the richest multi-series table in the DB and has
ZERO ui/ references. It becomes a small-multiples drilldown: one sparkline row per analyte,
trending across analyses. This is the textbook small-multiples dataset - shared x, many small
series, shape over numbers, latest number printed once per row.

## Spec

- New file `ui/fleet/OilAnalysisDrilldown.kt`. Entry: an OIL row on FleetScreen's maintenance/
  service area (follow where SERVICE HISTORY count lives), value = analysis count from
  `OilAnalysisDao.getAll(vehicleId)` size; tapping opens the drilldown (existing FleetDrilldowns
  pattern). Count 0 -> row still renders, value `0`, drilldown shows the empty sentence
  `"no oil analyses logged - log one by voice"`.
- Ordering: by `mileage` ascending when every row has mileage, else by `date` ascending. State
  which axis was used in one faint sentence at the top ("by mileage" / "by date").
- Analyte rows, fixed order, each a `DeckSmallMultiple(label, latestValue, points)`:
  - Wear metals (ppm): IRON, COPPER, LEAD, TIN, ALUMINUM, CHROMIUM, NICKEL
  - Contaminants: SODIUM, POTASSIUM, plus whatever further fields `OilAnalysis.kt` declares
    (silicon, fuel %, water %, TBN, viscosity - read the entity, include every numeric field,
    label with its unit: `"FUEL (%)"`, `"VISC 100C (CST)"` etc.)
  - `points` = the field across analyses, `null` where that analysis left it null (gap, per the
    kit). `latestValue` = most recent non-null, formatted with unit; `"-"` when the field was
    never reported.
  - SKIP rows where the field is null across ALL analyses (a column never reported is not a
    trend; rendering 15 empty sparklines buries the six real ones). This skip is stated once at
    the bottom: `"N analytes never reported are hidden"` - hidden-but-said, §4 rule 5 posture.
- Header pane: latest analysis as text `DeckRow`s - DATE, MILEAGE, OIL (brand + grade), DRAIN
  INTERVAL. These are the anchoring facts; the sparklines are shape only.
- No thresholds/condemnation limits. The app has no authoritative per-engine limits and inventing
  amber/red bands would present a guess as fact. Trends only. (If limits ever arrive they come as
  data, not constants.)

## Verification

- [ ] Any pure mapping (entity list -> per-analyte List<Float?>) unit-tested: null field -> gap,
      all-null analyte skipped + counted, mileage-vs-date ordering choice.
- [ ] `compileDebugKotlin -Pnokey` + `testDebugUnitTest` green.
- [ ] Every numeric field of `OilAnalysis` accounted for (rendered or in the hidden count) -
      enumerate them in the commit message.
- [ ] On-device (map-level): drilldown opens; with zero analyses the empty sentence shows.

## VERIFIED BUILT 2026-08-16 - closed

Swept against HEAD during the all-effort verification. **Every one of this effort's 16 tickets was
built, wired to a production path, and unit-tested where it had a pure layer.** Each has a landing
commit. `MEMORY.md` was right that the effort shipped; **these `Status:` lines were simply never
flipped**, so the tracker counted 16 phantom open tickets and any frontier query was wrong.

Full per-ticket evidence is in the sweep record on `../map.md`.
