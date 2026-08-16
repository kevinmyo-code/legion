package com.kevin.legion.ui.fleet

import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.vehicle.FleetSpendController

/**
 * Ticket 11 §4's four fleet-spend figures, pre-formatted into display strings and chart data - a
 * pure view built once in `ui/FleetScreen.kt`'s state holder, matching every other `build*View`/
 * `build*Rows` function in this package ([buildDueRows], [buildScheduleRows], [buildFleetTile]):
 * the SCREEN composables below stay display-only (this file's own doc convention,
 * `ui/fleet/FleetDrilldowns.kt`'s "no controller or DAO reference"), never touching
 * [FleetSpendController] or `Long` cents directly.
 *
 * Every text field here is a COMPLETE sentence or figure, never a bare number a screen has to
 * caveat itself - CLAUDE.md §4 rule 6 (a total that silently omits what it doesn't cover is a lie
 * by omission) and ticket 11 §4's own wording ("must state how many records it covers", "must
 * refuse in words") are both satisfied HERE, in the builder, so no screen can accidentally render
 * the bare figure without the caveat that makes it honest.
 */
data class FleetSpendView(
    /** "$1,234.56" when at least one record has a cost, else "No costs logged yet" - CLAUDE.md §4
     * rule 6: never a bare "$0.00" standing in for "nothing has been logged". */
    val totalText: String,
    /** Always non-blank, always stated: "3 of 12 service records have a cost logged." */
    val coverageText: String,
    /** Either a "$X.XX / mi" reading or the refusal sentence itself - see [perMileIsRefusal]. */
    val perMileText: String,
    /** True when [perMileText] is a REFUSAL (no odometer, no mileage, no cost yet) rather than a
     * real figure - lets the screen render it in the same "advisory, not a value" tone
     * [DeckTag]'s `INVERTED_AMBER` family already uses elsewhere, without re-deriving the reason
     * from the string. */
    val perMileIsRefusal: Boolean,
    /** Bar-per-service-type chart data, spend descending - see [FleetSpendController.spendByServiceType]. */
    val byType: List<DeckBar>,
    /** The same data as [byType], as exact label/amount text rows for the "money stays mono" list
     * beneath the chart (ticket 11 §4: a chart is glanceable, not exact). */
    val byTypeRows: List<Pair<String, String>>,
    /** Bar-per-year chart data, oldest year first - see [FleetSpendController.spendByYear]. Empty
     * (never rendered) below [yearTrendAvailable]. */
    val byYear: List<DeckBar>,
    /** Same data as [byYear], as exact label/amount rows. */
    val byYearRows: List<Pair<String, String>>,
    /** True once at least two distinct years have logged cost - ticket 11 §4: "needs several years
     * to say anything; renders a worded empty state until then", same >= 2 threshold
     * [RecapDrilldownScreen]'s own trend-chart gate already uses for the identical reason. */
    val yearTrendAvailable: Boolean,
)

/**
 * Builds [FleetSpendView] from [FleetSpendController]'s four raw reads - a pure function (no
 * Android/Room dependency, unit-tested directly in `FleetSpendViewTest` without Robolectric, same
 * posture as [buildDueRows]/[buildScheduleRows]) so the wording and refusal logic ticket 11 §4
 * requires is exercised without standing up a database.
 */
fun buildFleetSpendView(
    total: FleetSpendController.SpendTotal,
    perMile: FleetSpendController.CostPerMile,
    byType: List<Pair<String, Long>>,
    byYear: List<Pair<Int, Long>>,
): FleetSpendView {
    val totalText = if (total.recordsWithCost == 0) "No costs logged yet" else "$${formatCents(total.totalCents)}"
    val coverageText = "${total.recordsWithCost} of ${total.totalRecords} service " +
        "${if (total.totalRecords == 1) "record has" else "records have"} a cost logged."

    val perMileText: String
    val perMileIsRefusal: Boolean
    when (perMile) {
        is FleetSpendController.CostPerMile.Value -> {
            // The caveat rides WITH the figure, never beside it as an optional extra. This ratio
            // divides by an odometer estimate that ticket 03 measured at 5-15% low, always in the
            // same direction, so the two-decimal figure is far more precise-looking than it is
            // accurate. Rendering it bare was the review finding: refusing at odometerBaseline == 0
            // handled the loud case and left the quiet one - a confirmed-but-stale baseline -
            // reading like a fact. `mileageCaveat` is null only when the odometer IS the driver's
            // own just-typed reading, which is exactly when no caveat is owed.
            val figure = "$${formatCents(Math.round(perMile.centsPerMile))} / mi"
            perMileText = perMile.mileageCaveat?.let { "$figure ($it)" } ?: figure
            perMileIsRefusal = false
        }
        is FleetSpendController.CostPerMile.Refused -> {
            perMileText = perMile.reason
            perMileIsRefusal = true
        }
    }

    val typeBars = byType.map { (label, cents) ->
        DeckBar(label = label, value = (cents / 100.0).toFloat(), valueLabel = "$${formatCents(cents)}")
    }
    val typeRows = byType.map { (label, cents) -> label to "$${formatCents(cents)}" }

    val yearBars = byYear.map { (year, cents) ->
        DeckBar(label = year.toString(), value = (cents / 100.0).toFloat(), valueLabel = "$${formatCents(cents)}")
    }
    val yearRows = byYear.map { (year, cents) -> year.toString() to "$${formatCents(cents)}" }

    return FleetSpendView(
        totalText = totalText,
        coverageText = coverageText,
        perMileText = perMileText,
        perMileIsRefusal = perMileIsRefusal,
        byType = typeBars,
        byTypeRows = typeRows,
        byYear = yearBars,
        byYearRows = yearRows,
        yearTrendAvailable = byYear.size >= 2,
    )
}
