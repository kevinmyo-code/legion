package com.kevin.legion.prototype

/**
 * THROWAWAY fixtures for the stage-1 dashboard-grid prototype (aspect-engine ticket 09). Every
 * value here is hand-typed, not read from Room or an aspect definition - ticket 09 is answering
 * a MECHANICS question (does the reorder/resize feel right, what does true 2D drag cost), not
 * standing up a real widget-engine read path. That is ticket 10/18's job once ticket 08's widget
 * contract (resolved 2026-08-23) has a real engine table behind it.
 *
 * Deleted along with the rest of `prototype/` once this ticket resolves either way.
 */

/** The eight v1 widget types ticket 08 settled on. Not every type gets a mock here - enough of
 *  them to feel the column (stat tile, record list, next-due, quick-add, agenda), per the brief. */
enum class PrototypeWidgetKind { STAT_TILE, RECORD_LIST, NEXT_DUE, QUICK_ADD, AGENDA }

/** Half or full page width - the only two sizes stage 1 answers ticket 09 question 1 with. */
enum class PrototypeWidgetSize { HALF, FULL }

/**
 * One widget instance sitting in one page's column. `id` is stable across reorders (used as the
 * drag/compose key); `page` reorder and per-page widget order live entirely in in-memory state
 * ([PrototypeDashboardState]) since there is no Room table behind this prototype.
 */
data class PrototypeWidget(
    val id: String,
    val kind: PrototypeWidgetKind,
    val title: String,
    var size: PrototypeWidgetSize,
)

/** One pager page: a name (page-management chrome, ticket 09 question 4) and its widget column. */
data class PrototypePage(
    val id: String,
    val name: String,
    val widgets: MutableList<PrototypeWidget>,
)

/** Fake data behind the mock widgets - hardcoded, never a real query. */
object PrototypeFixtures {
    fun homePage() = PrototypePage(
        id = "home",
        name = "HOME",
        widgets = mutableListOf(
            PrototypeWidget("h1", PrototypeWidgetKind.STAT_TILE, "NET WORTH", PrototypeWidgetSize.HALF),
            PrototypeWidget("h2", PrototypeWidgetKind.STAT_TILE, "MILES THIS WEEK", PrototypeWidgetSize.HALF),
            PrototypeWidget("h3", PrototypeWidgetKind.AGENDA, "TODAY", PrototypeWidgetSize.FULL),
            PrototypeWidget("h4", PrototypeWidgetKind.NEXT_DUE, "NEXT DUE", PrototypeWidgetSize.FULL),
            PrototypeWidget("h5", PrototypeWidgetKind.RECORD_LIST, "RECENT LEDGER ROWS", PrototypeWidgetSize.FULL),
            PrototypeWidget("h6", PrototypeWidgetKind.QUICK_ADD, "LOG A DRIVE", PrototypeWidgetSize.HALF),
            PrototypeWidget("h7", PrototypeWidgetKind.QUICK_ADD, "ADD RECEIPT", PrototypeWidgetSize.HALF),
        ),
    )

    fun fleetPage() = PrototypePage(
        id = "fleet",
        name = "FLEET",
        widgets = mutableListOf(
            PrototypeWidget("f1", PrototypeWidgetKind.STAT_TILE, "ODOMETER", PrototypeWidgetSize.HALF),
            PrototypeWidget("f2", PrototypeWidgetKind.STAT_TILE, "FUEL LEVEL", PrototypeWidgetSize.HALF),
            PrototypeWidget("f3", PrototypeWidgetKind.NEXT_DUE, "OIL CHANGE", PrototypeWidgetSize.FULL),
            PrototypeWidget("f4", PrototypeWidgetKind.RECORD_LIST, "RECENT DRIVES", PrototypeWidgetSize.FULL),
            PrototypeWidget("f5", PrototypeWidgetKind.QUICK_ADD, "LOG FUEL-UP", PrototypeWidgetSize.HALF),
        ),
    )

    fun ledgerPage() = PrototypePage(
        id = "ledger",
        name = "LEDGER",
        widgets = mutableListOf(
            PrototypeWidget("l1", PrototypeWidgetKind.STAT_TILE, "THIS MONTH SPEND", PrototypeWidgetSize.HALF),
            PrototypeWidget("l2", PrototypeWidgetKind.STAT_TILE, "UNRECONCILED ROWS", PrototypeWidgetSize.HALF),
            PrototypeWidget("l3", PrototypeWidgetKind.RECORD_LIST, "LATEST TRANSACTIONS", PrototypeWidgetSize.FULL),
            PrototypeWidget("l4", PrototypeWidgetKind.QUICK_ADD, "IMPORT STATEMENT", PrototypeWidgetSize.FULL),
        ),
    )

    /** Fake per-widget body content, keyed by widget id - a stat tile's number, a list's rows, etc.
     *  Never sourced from a controller; see the file doc. */
    fun statValue(id: String): Pair<String, String> = when (id) {
        "h1" -> "$41,208" to "+2.1% vs last month"
        "h2" -> "212 mi" to "5 drives logged"
        "f1" -> "88,412 mi" to "since last service"
        "f2" -> "62%" to "~180 mi range"
        "l1" -> "$1,884.12" to "37 transactions"
        "l2" -> "3" to "awaiting a gated file"
        else -> "--" to ""
    }

    fun recordRows(id: String): List<Pair<String, String>> = when (id) {
        "h5" -> listOf("WHOLE FOODS" to "-$84.12", "SHELL #4471" to "-$52.00", "PAYCHECK" to "+$2,140.00")
        "f4" -> listOf("2026-08-21" to "38 mi, 41 min", "2026-08-19" to "12 mi, 22 min")
        "l3" -> listOf("AMAZON" to "-$29.99", "COSTCO" to "-$212.40", "RENT" to "-$1,800.00")
        else -> emptyList()
    }

    fun nextDue(id: String): Pair<String, String> = when (id) {
        "h4" -> "OIL CHANGE" to "in 412 mi"
        "f3" -> "OIL CHANGE" to "in 412 mi"
        else -> "--" to ""
    }

    fun agendaRows(id: String): List<Pair<String, String>> = when (id) {
        "h3" -> listOf("09:00" to "Standup", "12:30" to "Lunch w/ Dana", "18:00" to "Gym")
        else -> emptyList()
    }
}
