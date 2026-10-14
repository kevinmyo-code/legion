package com.kevin.midnightai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One track play observed via [com.kevin.midnightai.media.NowPlayingController] -
 * phone-over-BT (AVRCP) or any on-unit media app. `completed` is derived from
 * measured wall-clock dwell against the track's reported duration (there is no
 * track-end event in the MediaSession framework, and `position` is a stale
 * snapshot, not a live counter). This is the taste ledger: [completed] and
 * [timestamp] are what `musicTasteSummary` reasons over to answer "what have I
 * been listening to."
 */
@Entity(tableName = "music_plays")
data class MusicPlay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,       // 0 if the source never reported duration
    val listenedMs: Long,       // measured wall-clock dwell on this track
    val completed: Boolean,     // derived full/mostly-played vs skip
    val timestamp: Long,        // play start (System.currentTimeMillis)
    val lat: Double? = null,
    val lng: Double? = null,
)
