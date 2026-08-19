# Chart rendering: library vs hand-rolled Canvas

Research for ticket 02 (cyberdeck-ui). 2026-08-07.

Needed: line/area trends over time (bodyweight, sleep, calories, monthly spend), bar
comparisons (budget vs actual, sessions per week), inline sparklines in list rows.

## What already exists in the repo

- `app/src/main/java/com/kevin/legion/ui/fleet/TelemetryRows.kt` `TelemetryChart` (lines
  186-223): a working hand-rolled `Canvas` line chart, ~35 lines. Handles flat-series
  degenerate case, draws a baseline rule, uses `LocalLegionSemantics` colors. **verified**
- Downsampling already solved upstream (`ObdHistory.downsample`), so Canvas point-count is
  bounded by design. **verified**
- No chart library anywhere in `gradle/libs.versions.toml` or `app/src`. **verified**

## Repo versions (from `gradle/libs.versions.toml`)

- Kotlin 2.1.0, AGP 9.2.1, Compose BOM 2024.05.00 (Compose UI ~1.6.7), Material3 via BOM.
  **verified**

## Candidates

| Criterion | Hand-rolled Canvas | Vico 2.x | ComposeCharts (ehsannarmani) | KoalaPlot | MPAndroidChart |
|---|---|---|---|---|---|
| Maintained now | n/a | Yes: v2.5.2 Jun 2026, v3 in pre-release, very active (**verified**, GitHub releases + Maven Central) | Semi: v0.2.0, issue activity through late 2025, pre-1.0 (**verified** search; depth **reasoned**) | Yes: v0.11.2 ~Jul 2026, but built against Compose 1.10 / Kotlin 2.3 (**verified**) | Effectively unmaintained, View-based not Compose (**reasoned**, widely known; not re-checked) |
| Clone-and-run (Maven Central, no keys) | n/a | Yes (**verified**) | Yes (**verified**) | Yes (**verified**) | JitPack historically (**reasoned**) |
| Kotlin/Compose compat with this repo | Trivially yes | Kotlin >= 2.1.0 OK (**verified**, Vico guide). Compose floor not stated in docs; recent Vico likely built against Compose newer than BOM 2024.05.00, may force a BOM bump (**reasoned**) | Unstated; pre-1.0 risk (**reasoned**) | Requires Compose 1.10 / Kotlin 2.3 - ahead of this repo, would force upgrades (**verified** release notes, consequence **reasoned**) | N/A to Compose idioms |
| Deep restyling (glow, custom grid, mono axis labels, unusual colors) | Total control by construction | Extensible (custom components, shaders, formatters) but the cyberdeck look means overriding nearly every default; you fight its component model (**reasoned**) | Styling knobs exist but shallower; pre-1.0 API churn (**reasoned**) | Styling via composable slots, moderate (**reasoned**) | XML-era styling, poor fit (**reasoned**) |
| Long-cents display exactness | Format labels straight from `Long`; geometry in Float only affects pixel position, never a displayed digit (**reasoned**, sound) | Series y-values accept `Number` -> stored as floating point internally; labels need a custom `CartesianValueFormatter` keyed back to the source `Long` to guarantee exactness (**verified** that y accepts Number subtypes; internal Double **reasoned**). Long cents < 2^53 are exact in Double anyway (**reasoned**, arithmetic) | Same shape of problem (**reasoned**) | Same (**reasoned**) | Float-based (**reasoned**) |
| Animated draw-in / ambient motion | `Animatable` progress driving path measure / clip; motion ban is lifted, plain Compose animation (**reasoned**) | Built-in reveal/diff animations (**verified** release notes mention reveal animations in v3 pre-release; v2 has initial animation **reasoned**) | Advertised as "Animated" (**verified** README title) | Some (**reasoned**) | Yes but View-based |
| Dependency weight / APK | Zero | Multi-module, moderate; pulls its own Compose alignment (**reasoned**) | Small (**reasoned**) | KMP core, moderate (**reasoned**) | Large legacy |
| Interactivity for free (pan/zoom/scroll/markers) | Costs real effort if ever wanted | Strong (**verified** feature set on GitHub) | Basic (**reasoned**) | Interactive (**verified** README) | Yes |

## Recommendation

**Hand-rolled Canvas/DrawScope. No chart dependency.**

1. The restyling criterion, weighted heavily, decides it: every visual element of the
   cyberdeck look (glow strokes, scanline grids, monospace tick labels, non-M3 colors) is a
   default to override in a library and a first-class line of code in DrawScope. A library
   earns its keep on interactivity and axis-layout machinery LEGION does not need.
2. The repo already ships a working hand-rolled chart (`TelemetryChart`) with the degenerate
   cases handled; the new work extends a proven pattern rather than introducing a second
   rendering idiom next to it.
3. Version friction is real: KoalaPlot demands Compose 1.10/Kotlin 2.3, and current Vico is
   ahead of the repo's 2024.05.00 BOM (reasoned). Hand-rolled has zero compatibility
   surface, zero maintenance risk, zero APK cost.
4. Long-cents exactness is trivially airtight when axis/label strings are formatted directly
   from `Long` and Double/Float appears only in pixel geometry.

Fallback: if a future surface genuinely needs pan/zoom/scroll with markers over large
series, adopt **Vico** then (actively maintained, Maven Central, Kotlin 2.1-compatible) and
bump the Compose BOM as part of that ticket. Do not adopt it preemptively.

## What hand-rolling actually costs

Estimate, calibrated against the existing 35-line `TelemetryChart`:

| Composable | Scope | Est. size |
|---|---|---|
| `DeckLineChart` | time-series line + optional area fill (gradient to transparent), min/max normalization, flat-series case, grid rules, draw-in via `Animatable` progress clipping the path | ~120-180 lines |
| `DeckBarChart` | grouped/paired bars (budget vs actual), category labels, value labels from `Long` cents, per-bar draw-in stagger | ~100-150 lines |
| `DeckSparkline` | strip-down of the line chart: no axes, no labels, fixed height, list-row friendly | ~30-50 lines (already nearly exists) |
| `DeckAxis` helpers | tick selection (nice numbers for counts, exact cents for money), monospace label drawing via `drawText`/`TextMeasurer` | ~60-100 lines |
| Shared | normalization + `Long`-cents formatter + glow stroke helper (`drawPath` twice: wide low-alpha + narrow core) | ~40-60 lines |

Total: roughly **350-550 lines** of chart code, one-time, no dependency to track. What you
give up versus a library: pinch-zoom, scrollable axes, tooltips/markers, auto axis layout -
none currently required (**reasoned** against the ticket's listed chart types).

Glow note: `Paint.setShadowLayer` needs a native `Paint` via `drawIntoCanvas`; the cheaper
idiom is the double-stroke (blurred/wide translucent pass under a crisp core), which is pure
DrawScope and dark-background friendly (**reasoned**).

## Sources

- https://github.com/patrykandpatrick/vico/releases
- https://guide.vico.patrykandpatrick.com/
- https://mvnrepository.com/artifact/com.patrykandpatrick.vico
- https://github.com/ehsannarmani/ComposeCharts
- https://central.sonatype.com/artifact/io.github.ehsannarmani/compose-charts
- https://github.com/KoalaPlot/koalaplot-core/releases
