package com.kevin.midnightai.navigation

/**
 * What the NAV face accepts while the car moves (review round edd3a320, item 5): single large taps
 * stay (GO, END, RECENTER, a saved place: normal head-unit practice), but no panning or zooming the
 * map and no scrolling a list. Re-checked every second on the face, like the pairing pane.
 */
object NavInput {
    fun mapPanAllowed(driving: Boolean): Boolean = !driving
    fun listScrollAllowed(driving: Boolean): Boolean = !driving
    fun tapsAllowed(@Suppress("UNUSED_PARAMETER") driving: Boolean): Boolean = true
}
