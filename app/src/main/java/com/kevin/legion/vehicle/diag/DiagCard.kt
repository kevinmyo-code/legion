package com.kevin.legion.vehicle.diag

import com.kevin.legion.assistant.cards.CardDestination
import com.kevin.legion.assistant.cards.CardProvenance
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.RowStatus
import com.kevin.legion.assistant.cards.StatusRow

/**
 * A module scan as a `status_list` card (stage 4's card kinds, 2026-11-16): one row for the
 * engine's generic codes, then one per module that answered. Phone only, no server change.
 *
 * A row is OK only when its codes were READ and none is a fault. A module that did not answer is
 * "Not reached"; refused, cut off or not understood is "Unknown" with the reason. Rows that need a
 * look come first, so the card's row limit never hides one behind a clean module. A scan that
 * stopped is one Unknown row saying why, never an empty (clean-looking) card.
 */
object DiagCard {

    fun scan(carLabel: String, r: ScanReport): ConversationCard.StatusList {
        val rows = r.stopped?.let { listOf(StatusRow("Scan", RowStatus.UNKNOWN, it)) }
            ?: (listOf(engineRow(r.engine)) + r.modules.map(::moduleRow)).sortedBy { rank(it.status) }
        val shown = rows.take(ConversationCard.MAX_ROWS)
        val partial = if (r.stopped == null && !r.complete) {
            "Partial scan: ${r.coverage.probed} of ${r.coverage.planned} addresses probed. "
        } else {
            ""
        }
        return ConversationCard.StatusList(
            title = "Module scan: $carLabel",
            items = shown,
            more = rows.size - shown.size,
            note = (partial + NOT_LISTED).trim(),
            provenance = CardProvenance("OBD port", readAtMs = r.finishedAtMs),
            open = CardDestination.CAR,
        )
    }

    private fun engineRow(e: EngineReport?): StatusRow {
        if (e == null) return StatusRow("Engine (generic)", RowStatus.NOT_REACHED, "Generic codes were not read.")
        val reads = listOf(e.stored, e.pending)
        val codes = (reads + e.permanent).flatMap { r -> r.dtcs.map { it.code } }
            .distinct()
        val unread = reads.filter { it.outcome != Outcome.READ }
        return when {
            codes.isNotEmpty() -> StatusRow(
                "Engine (generic)", RowStatus.ATTENTION,
                codes.joinToString() + if (unread.isEmpty()) "" else "; some reads failed",
            )
            unread.size == reads.size -> StatusRow("Engine (generic)", RowStatus.NOT_REACHED, words(e.stored))
            unread.isNotEmpty() ->
                StatusRow("Engine (generic)", RowStatus.UNKNOWN, "Not all codes read: ${words(unread.first())}")
            else -> StatusRow("Engine (generic)", RowStatus.OK, "No stored or pending codes.")
        }
    }

    private fun moduleRow(m: ModuleReport): StatusRow {
        val name = m.name?.let { "$it (${m.address})" } ?: "Module ${m.address}"
        val faults = m.codes.dtcs.filter { it.fault }.map { it.code }
        return when {
            m.codes.outcome == Outcome.NO_ANSWER -> StatusRow(name, RowStatus.NOT_REACHED, words(m.codes))
            m.codes.outcome != Outcome.READ ->
                StatusRow(name, RowStatus.UNKNOWN, "Codes ${words(m.codes)}. Not known to be clear.")
            faults.isNotEmpty() -> StatusRow(name, RowStatus.ATTENTION, faults.joinToString())
            else -> StatusRow(name, RowStatus.OK, "No fault codes.")
        }
    }

    private fun words(c: CodeRead) = c.detail ?: c.outcome.words

    /** Needs-a-look first, clean last. */
    private val ORDER = listOf(
        RowStatus.ATTENTION, RowStatus.FAILED, RowStatus.UNKNOWN, RowStatus.NOT_REACHED, RowStatus.OK,
    )

    private fun rank(s: RowStatus) = ORDER.indexOf(s)

    /** Said on every scan card: an address that never answered is not on it, and is not "clear". */
    const val NOT_LISTED = "Modules that did not answer the scan, or sit on another network, are not listed " +
        "and are not known to be clear."
}
