---
map: mission-control
ticket: 06
title: Chart kit recoloured under two hues
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "04"]
blocked-by: ["[[01-palette-tokens]]", "[[04-alarm-without-hue]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Chart kit recoloured under two hues

## Question

What do the charts look like when data is mint and chrome is red?

The shipped chart kit (`cyberdeck-ui` ticket 14, hand-rolled Canvas/DrawScope, 18 unit tests) was
drawn for a single-accent amber palette. Its whole colour logic changes here, and the refs offer
more chart vocabulary than the current kit has - `ref-d` alone shows a trajectory plot with
typed markers, a radial encounter radar, and a spiral helio map.

**Read first:** `.scratch/cyberdeck-ui/issues/14-build-chart-kit.md` and the kit's source. Consult
the `dataviz` skill before deciding anything about colour or mark specs.

**Resolves:**

1. **Series colour.** Mint for the primary series is the obvious read. Decide what a second and
   third series are, given amber is now highlights/markers and green may or may not have survived
   ticket 01.
2. **Chrome vs data inside the plot.** Axes, gridlines, tick rails and labels are chrome by the
   new contract - do they go red-orange, or does red-in-a-chart read as alarm regardless of the
   app-wide rule? This is the one place the charting decision may not survive contact, and it is
   fine for the answer to be a scoped exception. Say so explicitly if it is.
3. **Gaps.** "Null = gap, never zero" is a shipped invariant with tests behind it. Confirm the
   treatment still reads under the new palette.
4. **Markers.** `ref-b` and `ref-d` use typed markers - diamonds sized by magnitude, dots, arcs.
   Decide whether LEGION's charts gain a marker vocabulary or stay line-and-fill.
5. **Threshold and target lines.** Budget lines, target weight, redline RPM. Under the old palette
   these leaned on red; decide their treatment now.
6. **Alarm inside a chart.** A quarantined point or a fault window, consistent with ticket 04.
7. **Which new chart forms, if any, are worth building.** The radial and spiral forms in `ref-d`
   are gorgeous and may have no honest use in this app. Ruling them out is a valid answer; ruling
   them in requires naming the real LEGION data each one renders.

**Constraint.** Numbers stay the hero. A chart that is prettier and less readable than the shipped
one has failed.

## Answer

Grilled with Kevin, 2026-08-14, after running the `dataviz` skill's palette validator
(`scripts/validate_palette.js`) against the ticket 01 palette. **The colour part is computable, so
it was computed rather than eyeballed** - which is the only reason the finding below was caught.

### 0. The finding: green cannot exist in this palette

Five green candidates tested against mint `#57EFC6` and amber `#FFBA1F` at once. The floor is
normal-vision dE >= 15 and CVD dE >= 8.

| Green | vs mint (normal) | vs amber (CVD) |
|---|---|---|
| `#7BE86A` (ticket 01's revised value) | 10.4 FAIL | 5.5 deutan FAIL |
| `#9BE85A` | 11.9 FAIL | 3.8 deutan FAIL |
| `#5FD93F` | 14.5 FAIL | 3.1 protan FAIL |
| `#B4E832` | 14.7 FAIL | 2.9 deutan FAIL |
| `#3FCF7A` | 11.8 FAIL | 6.9 protan WARN |

**Green is geometrically squeezed between mint and amber.** Move it toward mint and normal-vision
separation collapses; move it toward amber and colour-blind separation collapses. Nothing clears
both.

**Ticket 01's revision of `good` was judged by eye and was not sufficient.** That ticket flagged the
risk correctly and then under-corrected it. This is a named revision to ticket 01, made the way that
ticket asked for - not absorbed quietly.

**A second, separate finding: this is already a bug in shipped code.** `DeckBarChart` (lines 326-327)
draws a `primary` amber fill with a `credit` green target line. That pair is dE 5.5 under
deuteranopia in the app as it stands today. Not introduced by this effort; fixed incidentally by it.

### 1. Green is dropped

The palette becomes genuinely two-hue: **mint is every value, amber is every highlight, red is
chrome.**

- A credit is **mint, with a leading `+` and the word `CREDIT`**. Ticket 01 pre-authorised exactly
  this: "the fallback is not a fourth hue: the word CREDIT is already on the row."
- "System ok" is **the word**, not a colour.
- `LegionSemantics.credit` keeps its field name (name stability is what keeps a retheme out of the
  screen files) and now resolves to `data`. It becomes equal to `debit`. That is correct and not
  redundant: the field still documents intent at the call site even when the two values match.

### 2. Multi-series: small multiples by default

**The lightness-band check fails on every pair in this palette, and that is structural.** The
daylight rule forces uniformly high lightness (0.68-0.86), and a categorical palette needs lightness
spread to work. **Hue can never carry series identity in LEGION.**

- **Default: small multiples.** Stacked separate charts sharing an x-axis, each single-series mint.
  The shipped kit is already single-series throughout, so this is mostly a rule about what not to
  add.
- **Exception: two-series overlay**, allowed only where the comparison IS the point (actual vs
  budget, actual vs target). **Capped at two.** Mint and amber, both direct-labelled at their
  endpoints. Never three overlaid.

### 3. Chrome does not enter the plot

**A scoped exception to the charting decision, stated deliberately** (the ticket anticipated this
one might not survive contact).

| Element | Colour |
|---|---|
| Gridlines | `ruleFaint` - unchanged from shipped |
| Axis labels | `faint` - unchanged from shipped |
| Series | `data` mint |
| Threshold / target | `amber`, dashed |
| The pane around it | `chromeDim`, and its pill `chrome` |

Red inside a plot means one thing only: **a genuine ALARM annotation** (a fault window, a
quarantined point), per ticket 04. The chart therefore sits inside the app's language without
speaking it. Putting a warm red grid behind cool mint data on every chart would both fight the
series and cry wolf.

### 4. Marker vocabulary, shape-typed

Ticket 01 ruled markers should differ by shape not hue. With hue down to one data colour, that is
now load-bearing rather than a nicety.

| Mark | Means |
|---|---|
| Filled dot | a logged reading |
| Hollow dot | the latest value, the endpoint |
| Diamond | an estimate (pantry macros, a projection) |
| Cross | provisional / `UNRECONCILED` |
| Amber dashed line | a threshold or target |
| **Nothing drawn** | a gap. Never a zero. |

The "null = gap, never zero" invariant and its 18 shipped tests are **untouched**.

### 5. No new chart forms

The radar and helio-map forms in `ref-d-mission-control.jpeg` are **ruled out**. The ticket required
that ruling them in name the real LEGION data each would render, and there is none - nothing in this
app is honestly cyclical enough to earn a radial axis, and a bar chart already answers every
comparison they would serve. They are the most beautiful thing in the refs and they do not belong
in this app.

### 6. Handed on

- **Ticket 01 is revised**: `good` `#7BE86A` is removed from the palette. Recorded on that ticket.
- **Ticket 10** should run the validator as part of its measurement pass rather than only computing
  WCAG contrast - it is what caught this.
- The shipped `DeckBarChart` target-line bug needs no separate ticket; the recolour fixes it.

### Assumptions ledger

| Claim | Tag |
|---|---|
| Every dE figure in section 0 | **`tested`** - `node scripts/validate_palette.js`, dataviz skill, run this session |
| Lightness-band failure is structural to a daylight-bright palette | `reasoned` - the validator reports the failure; the causal explanation is mine |
| `DeckBarChart` uses amber fill + green target line | `traced` - read `DeckCharts.kt` lines 326-327 |
| The shipped kit is single-series throughout | `traced` - read all five composables in `DeckCharts.kt` |
| Validator ran against surface `#1a1a19`, not LEGION's `#000000` | `tested` - the tool's dark default. Contrast passed anyway and would only improve against pure black |
| No LEGION data justifies a radial form | `reasoned` - judgement, not a survey of every dataset |
| Marker shapes are distinguishable at sparkline size | `reasoned` - **not rendered**, not seen on a device |
