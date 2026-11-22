package com.kevin.legion.service

/** Stub of the emergency ring memory: behaviour lands in the next commit. */
internal object EmergencyRingRules {
    const val MAX_RINGS_PER_NIGHT = 3
    const val CAP_WINDOW_MS = 12L * 60 * 60 * 1000
    const val RING_AGAIN_WHEN_SEVERITY_RISES = true

    data class Record(val severity: Int, val atMs: Long, val series: String)

    fun parse(alertId: String, value: String): Record? = null

    fun format(r: Record): String = ""

    fun seriesOf(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): String = alarm.alertId

    fun isNew(alarm: ProactiveUrgency.Alarm, records: Map<String, Record>): Boolean = true

    fun underCap(ringTimes: List<Long>, now: Long): Boolean = true
}
