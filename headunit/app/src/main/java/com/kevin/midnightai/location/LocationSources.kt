package com.kevin.midnightai.location

/**
 * Where a fix came from, best first (HU-1 T4). The unit's own GPS is dead in hardware on the
 * Cherokee (headunit/CLAUDE.md §14) but the order is written so a repaired antenna needs no code
 * change; the phone's GPS over the car link comes next; a Wi-Fi or cell network fix (often 100 m
 * off) is the last resort.
 */
enum class FixSource { UNIT_GPS, PHONE, NETWORK }

/** Pure rules for choosing between live sources and naming them. */
object LocationSources {

    /**
     * A source that reported within this long suppresses every lower one. Same 10 s the unit's GPS
     * always had over the beacon: long enough to bridge its own 1 s gaps, short enough that a lost
     * source hands over within one background cycle.
     */
    const val HOLD_MS = 10_000L

    /** `LocationManager.GPS_PROVIDER`, copied so this object needs no Android runtime. */
    const val GPS_PROVIDER = "gps"

    fun of(provider: String?, beaconProvider: String): FixSource = when (provider) {
        GPS_PROVIDER -> FixSource.UNIT_GPS
        beaconProvider -> FixSource.PHONE
        else -> FixSource.NETWORK
    }

    /** The words on screen (the CAR panel's readout). */
    fun label(source: FixSource): String = when (source) {
        FixSource.UNIT_GPS -> "GPS on this unit"
        FixSource.PHONE -> "Phone"
        FixSource.NETWORK -> "Network location"
    }

    /**
     * Whether a fix from [candidate] may become the published position at [nowMs], given when each
     * source last reported ([lastSeenMs], same monotonic clock): yes unless a better source reported
     * within [HOLD_MS]. A last-seen time in the future (clock trouble) does not suppress anything.
     */
    fun accepts(candidate: FixSource, lastSeenMs: Map<FixSource, Long>, nowMs: Long): Boolean =
        FixSource.entries
            .filter { it.ordinal < candidate.ordinal }
            .none { better -> lastSeenMs[better]?.let { nowMs - it in 0..HOLD_MS } == true }
}
