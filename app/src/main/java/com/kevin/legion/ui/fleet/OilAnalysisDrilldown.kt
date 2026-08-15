package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.OilAnalysis
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSmallMultiple
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate

/**
 * `OilAnalysis` small-multiples drilldown (quant-viz ticket 06). `OilAnalysis`
 * was, before this ticket, the richest multi-series table in the DB with ZERO
 * `ui/` references - reached from an OIL row [MaintenanceDrilldownScreen] now
 * carries alongside its RECAPS row, same "existing count line becomes a real
 * doorway" shape ticket 05 used for RECAPS.
 *
 * **No thresholds/condemnation limits are drawn** (ticket 06's explicit call):
 * the app has no authoritative per-engine wear-metal limit, and inventing an
 * amber/red band would present a guess as fact - the exact CLAUDE.md §4
 * posture ("anything the source does not state is an estimate, and an
 * invented number is not even an estimate of something real"). Trends only;
 * if real limits ever arrive, ticket 06's note is that they come in as DATA,
 * not as constants baked into this file.
 */

// ---------------------------------------------------------------- pure

/** Which axis [oilAnalysesOrdered] sorted by - stated in one faint sentence at the top of the drilldown, per ticket 06. */
internal enum class OilOrderAxis { MILEAGE, DATE }

/**
 * [OilAnalysis.mileage] is preferred over [OilAnalysis.date] as the trend
 * axis when EVERY analysis on file has one - mileage is the axis oil
 * condition actually tracks against (an analysis every 5,000 miles reads
 * very differently from one every calendar year). Falls back to date the
 * moment even one analysis lacks a mileage entry, since silently dropping
 * that analysis from an "every analysis" trend would be exactly the kind of
 * quiet omission CLAUDE.md §4 rule six exists to forbid, applied to a chart
 * axis rather than an ingestion gate.
 */
internal fun oilOrderAxis(analyses: List<OilAnalysis>): OilOrderAxis =
    if (analyses.isNotEmpty() && analyses.all { it.mileage != null }) OilOrderAxis.MILEAGE else OilOrderAxis.DATE

/** [analyses] (arrives newest-first off [com.kevin.legion.data.local.OilAnalysisDao.getAll]) re-sorted oldest-first on [oilOrderAxis]'s chosen axis, for the small-multiples' left-to-right trend. */
internal fun oilAnalysesOrdered(analyses: List<OilAnalysis>): List<OilAnalysis> =
    when (oilOrderAxis(analyses)) {
        OilOrderAxis.MILEAGE -> analyses.sortedBy { it.mileage }
        OilOrderAxis.DATE -> analyses.sortedBy { it.date }
    }

/**
 * One analyte's fixed identity: [label] (unit baked in, e.g. `"IRON (PPM)"`,
 * `"FUEL (%)"`), the field extractor, and [format] for the latest-value
 * readout ([DeckSmallMultiple.latestValue]) - ppm/count analytes default to
 * whole numbers, the four condition analytes below override to one decimal.
 */
internal data class OilAnalyte(
    val label: String,
    val format: (Float) -> String = { "%.0f".format(it) },
    val value: (OilAnalysis) -> Float?,
)

/**
 * Every numeric field [OilAnalysis] declares OTHER than the header facts
 * (date, mileage, oilBrand/oilGrade, drainIntervalMiles - all read straight
 * off the latest analysis by [OilHeaderPane], never trended here). Fixed
 * order per ticket 06 - wear metals first, then contaminants, then oil
 * condition, in the entity's own declared order - never resorted by value or
 * "interestingness", so this list reads the same every time a driver opens
 * the drilldown. All 16 fields are enumerated here so
 * [buildOilAnalyteSeries]'s "skip an all-null analyte" rule and its hidden
 * count can never silently drop a field this list forgot to name.
 */
internal val OIL_ANALYTES: List<OilAnalyte> = listOf(
    // Wear metals (ppm)
    OilAnalyte("IRON (PPM)") { it.iron?.toFloat() },
    OilAnalyte("COPPER (PPM)") { it.copper?.toFloat() },
    OilAnalyte("LEAD (PPM)") { it.lead?.toFloat() },
    OilAnalyte("TIN (PPM)") { it.tin?.toFloat() },
    OilAnalyte("ALUMINUM (PPM)") { it.aluminum?.toFloat() },
    OilAnalyte("CHROMIUM (PPM)") { it.chromium?.toFloat() },
    OilAnalyte("NICKEL (PPM)") { it.nickel?.toFloat() },
    // Contaminants (ppm)
    OilAnalyte("SODIUM (PPM)") { it.sodium?.toFloat() },
    OilAnalyte("POTASSIUM (PPM)") { it.potassium?.toFloat() },
    OilAnalyte("SILICON (PPM)") { it.silicon?.toFloat() },
    OilAnalyte("BORON (PPM)") { it.boron?.toFloat() },
    OilAnalyte("MAGNESIUM (PPM)") { it.magnesium?.toFloat() },
    // Oil condition
    OilAnalyte("FUEL (%)", format = { "%.1f".format(it) }) { it.fuelPercent?.toFloat() },
    OilAnalyte("WATER (%)", format = { "%.1f".format(it) }) { it.waterPercent?.toFloat() },
    OilAnalyte("TBN", format = { "%.1f".format(it) }) { it.tbn?.toFloat() },
    OilAnalyte("VISC 100C (CST)", format = { "%.1f".format(it) }) { it.viscosityCst?.toFloat() },
)

/** One analyte row, mapped and ready for [DeckSmallMultiple]. */
internal data class OilAnalyteSeries(val label: String, val latestValue: String, val points: List<Float?>)

/**
 * Maps every [OIL_ANALYTES] entry across [orderedAnalyses] (oldest-first, per
 * [oilAnalysesOrdered]) into an [OilAnalyteSeries], SKIPPING an analyte that
 * is `null` on every single analysis on file - "a column never reported is
 * not a trend; rendering 15 empty sparklines buries the six real ones"
 * (ticket 06). The returned [Int] is how many analytes were skipped, counted
 * in the SAME pass that decides the skip so the "N analytes... are hidden"
 * sentence [OilAnalysisDrilldownScreen] renders from it can never drift out
 * of sync with what was actually left out - the same hidden-but-said posture
 * CLAUDE.md §4 rule five requires for an estimate, applied here to an omitted
 * row rather than an unverified figure.
 */
internal fun buildOilAnalyteSeries(orderedAnalyses: List<OilAnalysis>): Pair<List<OilAnalyteSeries>, Int> {
    var hidden = 0
    val rows = OIL_ANALYTES.mapNotNull { analyte ->
        val points = orderedAnalyses.map { analyte.value(it) }
        val latest = points.lastOrNull { it != null }
        if (latest == null) {
            hidden++
            null
        } else {
            OilAnalyteSeries(analyte.label, analyte.format(latest), points)
        }
    }
    return rows to hidden
}

// ------------------------------------------------------------------- screen

/**
 * The OIL drilldown itself. Empty state (no analyses logged at all) is the
 * literal sentence ticket 06 specifies, not a blank list - matching every
 * other empty-state text in this file's sibling [FleetDrilldowns.kt].
 */
@Composable
fun OilAnalysisDrilldownScreen(analysesNewestFirst: List<OilAnalysis>, onBack: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "OIL ANALYSES",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
            if (analysesNewestFirst.isEmpty()) {
                Text(
                    "no oil analyses logged - log one by voice",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                val ordered = oilAnalysesOrdered(analysesNewestFirst)
                val axis = oilOrderAxis(analysesNewestFirst)
                val (series, hidden) = buildOilAnalyteSeries(ordered)
                // The DAO's own ORDER BY date DESC (see OilAnalysisDao.getAll)
                // means the first element of the NEWEST-FIRST input, not
                // `ordered`'s last, is the chronologically latest analysis -
                // the header pane's anchoring facts are always "what did the
                // most recent lab report say", independent of which axis the
                // trend charts below picked.
                val latest = analysesNewestFirst.first()
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { OilHeaderPane(latest) }
                    item(key = "axis-note") {
                        Text(
                            if (axis == OilOrderAxis.MILEAGE) "by mileage" else "by date",
                            style = LegionType.stamp,
                            color = sem.ghost,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    items(series, key = { it.label }) { row ->
                        DeckSmallMultiple(row.label, row.latestValue, row.points)
                    }
                    if (hidden > 0) {
                        item(key = "hidden-note") {
                            Text(
                                "$hidden analyte${if (hidden == 1) "" else "s"} never reported are hidden",
                                style = LegionType.stamp,
                                color = sem.ghost,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pinned anchoring facts (ticket 06: "these are the anchoring facts; the
 * sparklines are shape only") - date, mileage, oil brand+grade, drain
 * interval, all read off the single most recent [OilAnalysis]. [groupThousands]
 * is [FleetRows.kt]'s `internal` mileage formatter, reused rather than
 * reimplemented (same fleet package, no new number formatting).
 */
@Composable
private fun OilHeaderPane(latest: OilAnalysis) {
    DeckPane(header = "Latest Analysis") {
        DeckRow(label = "Date", value = shortDate(latest.date))
        DeckRow(label = "Mileage", value = latest.mileage?.let { "${groupThousands(it)} mi" } ?: "-")
        DeckRow(
            label = "Oil",
            value = listOf(latest.oilBrand, latest.oilGrade).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "-" },
        )
        DeckRow(label = "Drain Interval", value = latest.drainIntervalMiles?.let { "${groupThousands(it)} mi" } ?: "-")
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Oil analysis drilldown: mixed reporting", widthDp = 360, heightDp = 1400)
@Composable
private fun PreviewOilAnalysisDrilldown() = LegionTheme {
    OilAnalysisDrilldownScreen(
        analysesNewestFirst = listOf(
            OilAnalysis(
                vehicleId = "x", date = 1_754_000_000_000L, mileage = 140_200,
                oilBrand = "Mobil 1", oilGrade = "5W-30", drainIntervalMiles = 5000,
                iron = 22, copper = 4, lead = 2, aluminum = 3,
                sodium = 8, potassium = 2, fuelPercent = 1.2, tbn = 4.1,
            ),
            OilAnalysis(
                vehicleId = "x", date = 1_746_000_000_000L, mileage = 135_100,
                oilBrand = "Mobil 1", oilGrade = "5W-30", drainIntervalMiles = 5000,
                iron = 18, copper = 3, aluminum = 2,
                sodium = 6, fuelPercent = 0.9, tbn = 4.6,
            ),
        ),
        onBack = {},
    )
}

@Preview(name = "Oil analysis drilldown: empty", widthDp = 360, heightDp = 400)
@Composable
private fun PreviewOilAnalysisDrilldownEmpty() = LegionTheme {
    OilAnalysisDrilldownScreen(analysesNewestFirst = emptyList(), onBack = {})
}
