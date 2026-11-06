package com.kevin.midnightai.update

/**
 * Whether the car is moving, for the updater's prompt and install gate (alfred-ota ticket 08).
 *
 * Two feeds, whichever exists on the car: OBD PID 010D from [com.kevin.midnightai.vehicle.TelemetryRecorder]
 * (every 30 s while the engine runs), and the speed on a [com.kevin.midnightai.location.LocationController]
 * fix (the phone beacon or this unit's own GPS), collected by [UpdateGraph]. A reading above
 * [UpdateRules.MOVING_KMH] stamps [lastMovingAtMs]; [UpdateRules.isDriving] holds it for two
 * minutes. Engine-running is deliberately NOT a feed: this unit only has power with the ignition
 * on, so it would block every install.
 */
object DrivingState {
    @Volatile var lastMovingAtMs: Long = 0L
        private set

    fun note(speedKmh: Double?, atMs: Long = System.currentTimeMillis()) {
        if (speedKmh != null && speedKmh > UpdateRules.MOVING_KMH && atMs > lastMovingAtMs) lastMovingAtMs = atMs
    }

    fun isDriving(nowMs: Long = System.currentTimeMillis()): Boolean = UpdateRules.isDriving(nowMs, lastMovingAtMs)

    /** Tests only. */
    internal fun reset() {
        lastMovingAtMs = 0L
    }
}
