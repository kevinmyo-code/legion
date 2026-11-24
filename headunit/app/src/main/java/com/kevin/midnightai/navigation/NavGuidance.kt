package com.kevin.midnightai.navigation

/**
 * What the NAV face says while guiding (review NAV-1): the turn, the time and the distance only
 * while the position is fresh by the CAR face's 3 s rule ([NavFix.FRESH_MS]). Past that, words say
 * the position is lost and nothing old is shown as current; the next fresh fix brings it back.
 */
object NavGuidance {
    data class Lines(val banner: String?, val bottom: String, val stale: Boolean)

    const val POSITION_LOST = "Position lost: the phone is not sending. Guidance paused."
    const val NUMBERS_HIDDEN = "Time and distance are hidden until the position is back."

    fun lines(state: NavState, fixAgeMs: Long?): Lines {
        if (state.phase != NavPhase.GUIDING) return Lines(null, state.message, false)
        val stale = fixAgeMs == null || fixAgeMs !in 0..NavFix.FRESH_MS
        if (stale) return Lines(POSITION_LOST, NUMBERS_HIDDEN, true)
        val p = state.progress
        val banner = NavFormat.turn(p?.turn) ?: if (state.rerouting) "Finding a new route..." else "Follow the route."
        val bottom = if (p == null) state.message
        else "${NavFormat.duration(p.durationLeftS)} left, ${NavFormat.distance(p.distanceLeftM)} to ${state.destination?.name}"
        return Lines(banner, bottom, false)
    }
}
