package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `.scratch/one-today/issues/06-a-generated-view-for-niche-questions.md`. How a
 * [GeneratedViewPayload]'s body renders. Deliberately three, not eight - "three shapes that cover
 * most one-off questions beat eight that are half-rendered" (the ticket's own words). Widening this
 * is a decision, not a refactor: every new member needs a real renderer in
 * [com.kevin.legion.ui.GeneratedViewHost] and a real producer in [GeneratedViewQueryRunner], or it
 * is a shape the model can ask for that nothing can ever satisfy.
 */
enum class GeneratedViewShape { BAR_SERIES, LINE_SERIES, TOTAL_WITH_ROWS }

/** Which controller answers the query. Closed on purpose - see this file's own doc comment on
 * [GeneratedViewQuerySpec] for why the model never gets a free-string source. */
enum class QuerySource { LEDGER, PANTRY }

/** How the matched rows collapse to a number. [SUM] is money; [COUNT] is "how many". */
enum class QueryAggregation { SUM, COUNT }

/** How far back the query reaches, in whole UTC months (CLAUDE.md §3's "UTC month boundaries
 * throughout" convention, matching [com.kevin.legion.ledger.LedgerController]). */
enum class QueryWindow { THIS_MONTH, LAST_3_MONTHS, LAST_6_MONTHS, LAST_12_MONTHS, THIS_YEAR }

/** How the matched rows bucket. [NONE] is a single figure; [BY_MONTH]/[BY_CATEGORY] each need a
 * real series or breakdown behind them - see [GeneratedViewQueryRunner] for which
 * source/grouping/window combinations are actually answerable today. */
enum class QueryGrouping { NONE, BY_MONTH, BY_CATEGORY }

/**
 * The validated query the model named - never a value. This is the whole ticket's rule made a
 * type: every field here is a closed enum an existing controller already knows how to answer, and
 * [title] is UI chrome (a short label to print above the result), never a number. There is no
 * field anywhere on this class a model could use to hand back a figure directly - the only way a
 * number reaches [com.kevin.legion.ui.GeneratedViewHost] is through [GeneratedViewQueryRunner]
 * actually running this spec against Room.
 */
data class GeneratedViewQuerySpec(
    val shape: GeneratedViewShape,
    val source: QuerySource,
    val aggregation: QueryAggregation,
    val window: QueryWindow,
    val grouping: QueryGrouping,
    val title: String,
)

/** The result of trying to parse a spec out of free-form tool-call arguments. */
sealed class GeneratedViewSpecParse {
    data class Valid(val spec: GeneratedViewQuerySpec) : GeneratedViewSpecParse()
    /** [reason] names the field and the value that was not understood - never a silent fallback. */
    data class Invalid(val reason: String) : GeneratedViewSpecParse()
}

/**
 * Parses a [GeneratedViewQuerySpec] out of raw strings (the shape a tool call's JSON arguments
 * arrive in). Pure and JSON-library-free on purpose, so it is testable with plain strings - the
 * caller ([service.LiveSessionController]'s `show_generated_view` branch) does the
 * `JSONObject.optString` extraction and hands this function plain values.
 *
 * **Every branch is `enumValueOfOrNull`, never `Enum.valueOf` unguarded** - an out-of-vocabulary
 * value (a model inventing "LAST_WEEK" or "BY_STORE") must come back as a named [GeneratedViewSpecParse.Invalid],
 * never a thrown exception the caller has to remember to catch, and never a best-effort default
 * that renders something the model asked for a shape of but not the actual thing it asked about.
 */
fun parseGeneratedViewSpec(
    shape: String,
    source: String,
    aggregation: String,
    window: String,
    grouping: String,
    title: String,
): GeneratedViewSpecParse {
    val parsedShape = enumValueOfOrNull<GeneratedViewShape>(shape)
        ?: return GeneratedViewSpecParse.Invalid("I don't understand the shape '$shape'.")
    val parsedSource = enumValueOfOrNull<QuerySource>(source)
        ?: return GeneratedViewSpecParse.Invalid("I don't understand the data source '$source'.")
    val parsedAggregation = enumValueOfOrNull<QueryAggregation>(aggregation)
        ?: return GeneratedViewSpecParse.Invalid("I don't understand the aggregation '$aggregation'.")
    val parsedWindow = enumValueOfOrNull<QueryWindow>(window)
        ?: return GeneratedViewSpecParse.Invalid("I don't understand the time window '$window'.")
    val parsedGrouping = enumValueOfOrNull<QueryGrouping>(grouping)
        ?: return GeneratedViewSpecParse.Invalid("I don't understand the grouping '$grouping'.")

    return GeneratedViewSpecParse.Valid(
        GeneratedViewQuerySpec(
            shape = parsedShape,
            source = parsedSource,
            aggregation = parsedAggregation,
            window = parsedWindow,
            grouping = parsedGrouping,
            title = title.ifBlank { "Your question" },
        ),
    )
}

private inline fun <reified T : Enum<T>> enumValueOfOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }

/** One plotted sample: [GeneratedViewShape.BAR_SERIES]/[GeneratedViewShape.LINE_SERIES] bodies. */
data class GeneratedViewPoint(val label: String, val valueCents: Long)

/** One row: [GeneratedViewShape.TOTAL_WITH_ROWS]'s breakdown list. */
data class GeneratedViewRow(val label: String, val value: String)

/**
 * The rendered answer to one generated-view query. [provenanceText] is never optional and never
 * hidden behind an expander (CLAUDE.md §4 rule 5/7, `legion-trust-disclosures-are-not-furniture`)
 * - it states what was counted and, when anything was excluded, what and why, in words. Carries
 * [shownAt] for the same reason [com.kevin.legion.service.VoiceModalPayload] does - a repeat call
 * for the identical spec must still re-trigger a collector keyed off this payload.
 *
 * **Empty is a real state, not a zero.** [points] and [rows] both empty means the query matched
 * nothing in the window, and [com.kevin.legion.ui.GeneratedViewHost] renders that as an explicit
 * empty state rather than drawing a zero-height bar or a "$0.00" that reads as a real fact about
 * spend nobody actually has data for.
 */
data class GeneratedViewPayload(
    val shape: GeneratedViewShape,
    val title: String,
    val points: List<GeneratedViewPoint> = emptyList(),
    val totalLabel: String? = null,
    val rows: List<GeneratedViewRow> = emptyList(),
    val provenanceText: String,
    val sourceTool: String = "show_generated_view",
    val shownAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = points.isEmpty() && rows.isEmpty() && totalLabel == null
}

/**
 * Ephemeral generated-view state - a SIBLING to [GlanceCardController] and [VoiceModalController],
 * not an extension of either, for the same reason [VoiceModalController]'s own doc comment gives:
 * a different dismiss policy. A glance card auto-dismisses on a countdown because it is a passive,
 * read-at-a-glance answer; a generated view is the answer to a question someone just asked and may
 * still be reading, scrolling, or comparing against something else on screen - a countdown here
 * would yank a chart out from under someone mid-read the same way [VoiceModalController]'s doc
 * comment says a countdown would yank an interactive sheet out from under a thumb. Dismissed
 * explicitly only, same posture as [VoiceModalController].
 *
 * Pure ephemeral state, no owned coroutine scope, not persisted - no Room table. Pinning a
 * generated view to a screen is explicitly out of scope for this ticket (it needs to store the
 * QUERY, never the numbers, which is a table/migration/sync-channel feature of its own).
 */
object GeneratedViewController {
    private val _current = MutableStateFlow<GeneratedViewPayload?>(null)
    val current: StateFlow<GeneratedViewPayload?> = _current.asStateFlow()

    /** Shows [payload]. A view already showing is replaced immediately (newest wins). */
    fun show(payload: GeneratedViewPayload) {
        _current.value = payload
    }

    /** Explicit dismiss only - there is no auto-dismiss timer; see this object's own KDoc. */
    fun dismiss() {
        _current.value = null
    }
}
