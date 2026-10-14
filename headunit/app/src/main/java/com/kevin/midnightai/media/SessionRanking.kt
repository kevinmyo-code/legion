package com.kevin.midnightai.media

import android.media.session.PlaybackState

/** The facts [pickBestSession] ranks on, lifted off a MediaController so the rule is JVM-testable. */
data class SessionCandidate(
    /** [PlaybackState] state constant; STATE_NONE when the session has no playback state at all. */
    val state: Int,
    val hasTitle: Boolean,
    val packageName: String = "",
)

/**
 * Which active media session the Cruise HUD should mirror; returns an index into [candidates],
 * or null for an empty list. Ties go to the earliest entry (getActiveSessions is priority-ordered).
 *
 * Why not just "first PLAYING, else first": Bluetooth AVRCP often reports STATE_NONE while music
 * really is playing, and the head unit's own Spotify session can sit PAUSED with stale metadata.
 * After a BT reconnect neither is PLAYING, so "first" could land on the stale one. Rank instead:
 *  3 PLAYING; 2 has a title and is not explicitly paused/stopped/error (the lenient "active" rule
 *  in [NowPlayingController]); 1 has a title but is explicitly stopped; 0 nothing to show.
 * No package preference: our own mixtape session is just another session and only wins by playing.
 */
fun pickBestSession(
    candidates: List<SessionCandidate>,
    // Tie-break only: the session whose package matches the active MusicSource wins an equal
    // rank, so two tied titled sessions can't swap on callback chatter (which would log
    // spurious plays in MusicHistoryRecorder). Never overrides a higher rank.
    preferred: (String) -> Boolean = { false },
): Int? {
    if (candidates.isEmpty()) return null
    fun rank(c: SessionCandidate): Int = when {
        c.state == PlaybackState.STATE_PLAYING -> 3
        c.hasTitle && c.state != PlaybackState.STATE_PAUSED &&
            c.state != PlaybackState.STATE_STOPPED && c.state != PlaybackState.STATE_ERROR -> 2
        c.hasTitle -> 1
        else -> 0
    }
    var best = 0
    for (i in 1 until candidates.size) {
        val r = rank(candidates[i])
        val rb = rank(candidates[best])
        if (r > rb || (r == rb && !preferred(candidates[best].packageName) && preferred(candidates[i].packageName))) {
            best = i
        }
    }
    return best
}
