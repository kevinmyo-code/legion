package com.kevin.midnightai.navigation

/**
 * What the NAV face accepts while the car moves (review round edd3a320, item 5): single large taps
 * stay (GO, END, RECENTER, a saved place: normal head-unit practice), but no panning or zooming the
 * map and no scrolling a list. Re-checked every second on the face, like the pairing pane.
 */
object NavInput {
    fun mapPanAllowed(driving: Boolean): Boolean = !driving
    fun listScrollAllowed(driving: Boolean): Boolean = !driving
    /** 5 km/h, the updater's moving threshold (UpdateRules.MOVING_KMH). */
    const val MOVING_MPS = 5.0f / 3.6f

    /**
     * The pan/scroll lock (review confirm 0cac90f7): the driving signal, OR a feed that went quiet
     * while its last fix was moving. Only a fresh fix (or the driving signal) can say stopped; a
     * timed hold running out does not unlock.
     */
    fun locked(drivingNow: Boolean, lastSpeedMps: Float?, fixFresh: Boolean): Boolean =
        drivingNow || (!fixFresh && (lastSpeedMps ?: 0f) > MOVING_MPS)

    fun tapsAllowed(@Suppress("UNUSED_PARAMETER") driving: Boolean): Boolean = true
}
