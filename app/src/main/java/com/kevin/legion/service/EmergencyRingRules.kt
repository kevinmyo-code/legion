package com.kevin.legion.service

/**
 * When an emergency alarm may ring again (phantom alarm fix, 2026-11-21). Pure, so it is tested
 * without a phone; [SafetyAlarms] keeps the [Record]s and the ring times.
 *
 * **One ring per alert SERIES, not per id.** NWS gives every update, extension or continuation of a
 * warning a new id and names the earlier ones in `references`; keyed on the id alone, one long flood
 * or storm warning rang again on every update, through the night. An alert joins the series of the
 * first earlier alert it references that is remembered; with none, it starts its own. A repeat is
 * remembered too ([SafetyAlarms.admit]), so a chain of updates stays one series.
 *
 * No event + area matching: two warnings of the same kind for the same county in one night can be
 * two storms, and merging them would keep the second one quiet. Until the engine sends `references`,
 * an update reads as a new alert and only [MAX_RINGS_PER_NIGHT] bounds it.
 */
internal object EmergencyRingRules {
    /**
     * The most emergency rings in [CAP_WINDOW_MS]; past it an alert is posted, never rung and never
     * dropped. Not decided by the owner yet (ADR 0082 removed the daily cap on spoken raises): one
     * line to change.
     */
    const val MAX_RINGS_PER_NIGHT = 3

    /** The cap's window: a rolling 12 hours, so it covers one night whatever hour it starts. */
    const val CAP_WINDOW_MS = 12L * 60 * 60 * 1000

    /**
     * Today's rule (ADR 0082), kept: a series that rang at Severe rings once more if it turns
     * Extreme, so a series rings at most twice. False = one ring per series, ever. The owner's call.
     */
    const val RING_AGAIN_WHEN_SEVERITY_RISES = true

    const val EXTREME_BYPASSES_CAP = false

    const val EXTREME_SEVERITY = 2

    /** What is kept per alert id: the highest severity delivered, when, and the series it is in. */
    data class Record(val severity: Int, val atMs: Long, val series: String)

    /** `severity|atMs|series`; a record from before series (`severity|atMs`) is its own series. */
    fun parse(alertId: String, value: String): Record? {
        val parts = value.split('|')
        val severity = parts[0].toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return Record(severity, at, parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: alertId)
    }

    fun format(r: Record): String = "${r.severity}|${r.atMs}|${r.series}"

    /** The series [alarm] belongs to: its own record's, else the first remembered reference's, else its id. */
    fun seriesOf(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): String =
        records[alarm.alertId]?.series
            ?: alarm.references.firstNotNullOfOrNull { records[it]?.series }
            ?: alarm.alertId

    /** True when [alarm]'s series has not been delivered, or (per the policy) only at a lower severity. */
    fun isNew(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): Boolean {
        val series = seriesOf(alarm, records)
        val top = records.values.filter { it.series == series }.maxOfOrNull { it.severity } ?: return true
        return RING_AGAIN_WHEN_SEVERITY_RISES && alarm.severity > top
    }

    /** True when fewer than [MAX_RINGS_PER_NIGHT] of [ringTimes] fall inside the window before [now]. */
    fun underCap(ringTimes: List<Long>, now: Long, severity: Int = 0): Boolean =
        ringTimes.count { now - it in 0 until CAP_WINDOW_MS } < MAX_RINGS_PER_NIGHT
}
