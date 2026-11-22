package com.kevin.legion.service

/**
 * When an emergency alarm may ring again (phantom alarm fix, 2026-11-21). Pure, so it is tested
 * without a phone; [SafetyAlarms] keeps the [Record]s and the ring times.
 *
 * **One ring per alert SERIES, not per id.** NWS gives every update, extension or continuation of a
 * warning a new id and names the earlier ones in `references`; keyed on the id alone, one long flood
 * or storm warning rang again on every update, through the night. An alert joins the series of the
 * first earlier alert it references that is remembered; with none, it starts its own. A repeat is
 * remembered too ([SafetyAlarms.admit]), so a chain of updates stays one series. The segments of one
 * NWS message (one per zone group) are one series too ([seriesOf]).
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

    /**
     * Whether an Extreme alert may ring past [MAX_RINGS_PER_NIGHT]. False = the cap binds every
     * alert, as built. The owner's call (review R2, 2026-11-21): one line to flip.
     */
    const val EXTREME_BYPASSES_CAP = false

    /** The ring level of an NWS Extreme alert (`SevereAlertWatcher.alarmLevel`). */
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

    /**
     * The series [alarm] belongs to: its own record's, else the first remembered reference's, else
     * that of a remembered segment of the same NWS message ([messageOf]), else its own id.
     */
    fun seriesOf(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): String {
        val message = messageOf(alarm.alertId)
        return records[alarm.alertId]?.series
            ?: alarm.references.firstNotNullOfOrNull { records[it]?.series }
            ?: message?.let { m -> records.entries.firstOrNull { messageOf(it.key) == m }?.value?.series }
            ?: alarm.alertId
    }

    /**
     * The NWS message an alert id is a segment of: `urn:oid:<oid>.<hash>` without the trailing
     * `.<segment>.<version>`. One message carries a segment per zone group, so a Watch for one zone
     * and a Warning for the next (or the zone he moved into) share it: one weather event, one ring.
     * Production 11-13 to 11-14: one message prefix rang again hours apart. Null for any other id
     * shape, which never merges by prefix.
     */
    private fun messageOf(alertId: String): String? = NWS_SEGMENT.matchEntire(alertId)?.groupValues?.get(1)

    private val NWS_SEGMENT = Regex("""(urn:oid:[0-9.]+\.[0-9a-f]+)\.\d+\.\d+""")

    /** True when [alarm]'s series has not been delivered, or (per the policy) only at a lower severity. */
    fun isNew(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): Boolean {
        val series = seriesOf(alarm, records)
        val top = records.values.filter { it.series == series }.maxOfOrNull { it.severity } ?: return true
        return RING_AGAIN_WHEN_SEVERITY_RISES && alarm.severity > top
    }

    /**
     * True when fewer than [MAX_RINGS_PER_NIGHT] of [ringTimes] fall inside the window before [now],
     * or [severity] is Extreme and [EXTREME_BYPASSES_CAP] lets it through.
     */
    fun underCap(ringTimes: List<Long>, now: Long, severity: Int): Boolean =
        (EXTREME_BYPASSES_CAP && severity >= EXTREME_SEVERITY) ||
            ringTimes.count { now - it in 0 until CAP_WINDOW_MS } < MAX_RINGS_PER_NIGHT
}
