package com.kevin.midnightai.media

/**
 * The three-way (really four-way) read of "why is the now-playing card blank",
 * split out as pure logic so it can be unit-tested without a real
 * [android.media.session.MediaSessionManager] or a device.
 *
 * CLAUDE.md sec 1's calendar-briefing rule applies here verbatim: "unreadable"
 * and "empty" are different sentences, and this app has the exact same failure
 * shape Kevin already hit once - the head unit's OWN stock display shows
 * nothing for his wife's Spotify-over-Bluetooth session, and nobody can tell
 * from a blank card whether that is because notification access was never
 * granted, because nothing is actually playing, or because AVRCP metadata
 * never reached this ROM at all. [NowPlayingController] collapses the last
 * two cases to the same `null` StateFlow value (see its `pickController`/
 * `updateState`), which is correct for its own job (drive the Cruise card)
 * but wrong for a diagnostic - so the home screen and the diagnostics screen
 * both need the finer-grained inputs below, gathered separately.
 */
enum class MediaEmptyState {
    /** [NowPlayingController.hasAccess] is false - getActiveSessions would throw. */
    NEEDS_NOTIFICATION_ACCESS,

    /** Access is granted, but there is no active MediaSession at all. */
    NOTHING_PLAYING,

    /** A session exists, but its MediaMetadata is null or has no readable fields. */
    METADATA_MISSING,

    /** Genuinely have something to show. */
    HAS_INFO,
}

/**
 * Priority order matters: a missing grant is checked before anything else,
 * because without it [hasActiveSession] and [hasMetadata] are both
 * unknowable (getActiveSessions throws) rather than truthfully false - so a
 * caller must pass `false`/`false` in that case and this function must still
 * report the access problem, not "nothing is playing".
 */
fun selectMediaEmptyState(
    hasNotificationAccess: Boolean,
    hasActiveSession: Boolean,
    hasMetadata: Boolean,
): MediaEmptyState = when {
    !hasNotificationAccess -> MediaEmptyState.NEEDS_NOTIFICATION_ACCESS
    !hasActiveSession -> MediaEmptyState.NOTHING_PLAYING
    !hasMetadata -> MediaEmptyState.METADATA_MISSING
    else -> MediaEmptyState.HAS_INFO
}

/** Whether a single session's [android.media.MediaMetadata] carries anything readable. */
enum class MetadataFillState { NULL, EMPTY, POPULATED }

/**
 * Classifies one session's metadata for the diagnostics screen. Takes the
 * three fields the app actually reads (title/artist/album) rather than the
 * MediaMetadata object itself, so this stays a plain-Kotlin function callable
 * from a unit test with no Robolectric shadow required.
 */
fun classifyMetadataFillState(
    title: String?,
    artist: String?,
    album: String?,
    metadataIsNull: Boolean,
): MetadataFillState = when {
    metadataIsNull -> MetadataFillState.NULL
    title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank() -> MetadataFillState.EMPTY
    else -> MetadataFillState.POPULATED
}
