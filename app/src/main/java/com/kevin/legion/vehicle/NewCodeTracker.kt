package com.kevin.legion.vehicle

/**
 * Decides whether an OBD Mode 03 read contains a trouble code this car has never shown before -
 * the gate in front of the `new_trouble_code` proactive raise.
 *
 * **Why this exists (voice audit finding 5, 2026-10-09).** 47 `new_trouble_code` raises (9 spoken)
 * announced P1282, P0700 and P0740 as "new" for codes stored since July. The health monitor kept
 * its baseline in a local `var` and overwrote it every scan from `getDtcCodes()`, which collapses
 * a failed link ("NO DATA", blank, "UNABLE TO CONNECT") into an empty list. One failed or early
 * read became "the car has no codes", and the next good read was `codes - {}` = everything "new".
 * The baseline also died with the service, so every restart re-armed it. Same shape as CLAUDE.md
 * section 4 rule 6: an empty read passing as a baseline.
 *
 * Three rules close it:
 *  1. A read that is not a real Mode 03 reply (see [ObdResponseParser.isValidDtcReply]) is
 *     [Verdict.Skip] and changes nothing.
 *  2. The baseline is CUMULATIVE: every code ever seen on the car. A clean read does not shrink
 *     it, so a flaky empty read cannot re-arm anything. A code that was cleared and returns is not
 *     new either; that is what `clear_codes` already reports.
 *  3. It is seeded from the persisted `code_events` history ([seed]), so it survives restarts.
 *     With no history, the first valid read becomes the baseline silently.
 */
class NewCodeTracker {

    sealed interface Verdict {
        /** The read was not a usable reply. Nothing learned, nothing raised. */
        data object Skip : Verdict

        /** First valid read for a car with no history: [codes] is the baseline, never raised. */
        data class Baselined(val codes: Set<String>) : Verdict

        /** A valid read with nothing unseen. */
        data object Unchanged : Verdict

        /** Valid read; [fresh] are codes this car has never shown before. [all] is the full set now. */
        data class Fresh(val fresh: Set<String>, val all: Set<String>) : Verdict
    }

    private val seen = HashMap<String, MutableSet<String>>()
    private val baselined = HashSet<String>()
    private val seeded = HashSet<String>()

    fun isSeeded(vehicleKey: String): Boolean = vehicleKey in seeded

    /** Loads the persisted history for [vehicleKey] once; later calls are ignored. */
    fun seed(vehicleKey: String, history: Set<String>) {
        if (!seeded.add(vehicleKey)) return
        seen.getOrPut(vehicleKey) { mutableSetOf() }.addAll(history)
        // Any history at all means a real read was taken in the past: not a first install.
        if (history.isNotEmpty()) baselined.add(vehicleKey)
    }

    fun observe(vehicleKey: String, rawReply: String): Verdict {
        if (!ObdResponseParser.isValidDtcReply(rawReply)) return Verdict.Skip
        val codes = ObdResponseParser.dtcCodes(rawReply).toSet()
        val known = seen.getOrPut(vehicleKey) { mutableSetOf() }
        val firstValidRead = baselined.add(vehicleKey)
        val fresh = codes - known
        known.addAll(codes)
        return when {
            firstValidRead -> Verdict.Baselined(codes)
            fresh.isEmpty() -> Verdict.Unchanged
            else -> Verdict.Fresh(fresh, codes)
        }
    }

    companion object {
        private val CODE = Regex("\"([PCBU][0-9A-F]{4})\"")

        /** Codes named in stored `code_events.codesJson` arrays (for example `["P0420","P0128"]`). */
        fun historyFrom(codesJsons: List<String>): Set<String> =
            codesJsons.flatMap { json -> CODE.findAll(json).map { it.groupValues[1] }.toList() }.toSet()
    }
}
