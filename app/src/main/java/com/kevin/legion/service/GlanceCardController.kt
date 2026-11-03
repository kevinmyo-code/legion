package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a [GlanceCardPayload]'s body renders - see `.scratch/glance-cards/`. */
enum class GlanceShape { NUMBER, LIST, STATUS_GRID, HEADLINE_LIST }

/** One row: `LIST` and `HEADLINE_LIST` bodies. */
data class GlanceRow(val label: String, val value: String)

/** One cell: `STATUS_GRID` bodies (a monitor/check, ready or not). */
data class GlanceCell(val label: String, val ready: Boolean)

/**
 * The data behind a voice tool call, large enough to read on screen at a
 * glance. Content model + shape taxonomy locked in
 * `.scratch/glance-cards/issues/01-content-model-and-tool-mapping.md`.
 */
data class GlanceCardPayload(
    val shape: GlanceShape,
    val title: String,
    val headline: String? = null,
    val rows: List<GlanceRow> = emptyList(),
    val cells: List<GlanceCell> = emptyList(),
    val sourceTool: String,
    val shownAt: Long = System.currentTimeMillis(),
)

/**
 * Ephemeral glance-card state, driven by [LiveToolbox]'s data-tool dispatch
 * (`get_codes`, `check_readiness`, `get_mpg`, `get_health` - one inline
 * `GlanceCardController.show()` call per opted-in tool, no registry). Pure
 * state, no owned timer or coroutine scope. The 5s auto-dismiss (and
 * therefore "newest wins" on a fresh card replacing one still showing) lives
 * entirely in the collecting composable's `LaunchedEffect(payload)` - Compose
 * cancels the previous effect the instant the key (the payload) changes,
 * which is what makes a new card pre-empt an old one's countdown for free, no
 * manual Job bookkeeping needed.
 *
 * Deliberately not persisted - no Room table. This is display-only,
 * driver-requested state that belongs to the current moment, not history.
 */
object GlanceCardController {
    private val _current = MutableStateFlow<GlanceCardPayload?>(null)
    val current: StateFlow<GlanceCardPayload?> = _current.asStateFlow()

    /** Shows [payload]. A card already showing is replaced immediately (newest wins). */
    fun show(payload: GlanceCardPayload) {
        _current.value = payload
    }

    /**
     * Replaces the card with [transform] of itself, only if the card showing is still the one
     * identified by [shownAt] and [sourceTool]; a newer card or a dismissed one is left alone.
     */
    fun replace(shownAt: Long, sourceTool: String, transform: (GlanceCardPayload) -> GlanceCardPayload) {
        _current.update { cur ->
            if (cur != null && cur.shownAt == shownAt && cur.sourceTool == sourceTool) transform(cur) else cur
        }
    }

    /** Explicit dismiss - the 5s auto-dismiss timeout, or a driver's tap. */
    fun dismiss() {
        _current.value = null
    }
}

/**
 * The get_codes glance rows: each code with its meaning on one line, an AI-written meaning
 * labelled as such, an unknown code "Looking this up..." until its lookup fails.
 */
internal fun codeGlanceRows(
    codes: List<String>,
    seed: Map<String, Pair<String, String>>,
    learned: Map<String, Pair<String, String>>,
    lookup: com.kevin.legion.vehicle.DtcLookupState,
): List<GlanceRow> = codes.map { code ->
    val meaning = com.kevin.legion.vehicle.DtcMeanings.resolve(code, seed, learned + lookup.learned, lookup.failed)
    GlanceRow(code, com.kevin.legion.vehicle.DtcMeanings.glanceValue(meaning))
}
