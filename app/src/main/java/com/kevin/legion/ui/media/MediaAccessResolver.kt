package com.kevin.legion.ui.media

/**
 * Pure UI-state derivation for the notification-listener-access banner
 * ([MediaTransportAccessBanner]). Kept Android-free on purpose, same shape as
 * [com.kevin.legion.ui.spotify.SpotifyConnectResolver] and
 * [com.kevin.legion.ui.sync.GoogleGrantResolver]: it takes only the boolean the
 * screen already has in hand
 * ([com.kevin.legion.media.NowPlayingController.hasAccess]), never a `Context`,
 * so this stays a plain JVM unit test target.
 *
 * **The defect this exists to close.** Notification-listener access is the
 * system gate for [android.media.session.MediaSessionManager.getActiveSessions]
 * - without it, pause/skip/previous cannot reach any media session at all.
 * [com.kevin.legion.media.NowPlayingController.hasAccess] checked that grant
 * but had zero callers anywhere in the app before commit d683d2c wired it into
 * `control_music`'s voice fallback. Every OTHER path still swallowed the
 * SecurityException silently: [com.kevin.legion.media.MusicController] into an
 * empty session list, [com.kevin.legion.media.NowPlayingController.init] into a
 * no-op retry. A driver on a phone that never had the grant (confirmed on
 * Kevin's Galaxy A25 2026-08-16 - the migration from the A17K dropped it) saw
 * pause/skip simply do nothing, with no surface anywhere saying why. This
 * resolver backs the UI half of that fix; d683d2c already closed the voice
 * half.
 */
object MediaAccessResolver {

    /**
     * Whether [MediaTransportAccessBanner] should draw anything at all. The
     * banner exists for exactly one state - grant missing - and must render
     * nothing, not an empty row, once [hasNotificationAccess] flips true.
     */
    fun shouldShowBanner(hasNotificationAccess: Boolean): Boolean = !hasNotificationAccess

    /**
     * The banner's one line. Careful not to overstate the outage: with the
     * App Remote fallback commit d683d2c added, playback started through
     * Spotify keeps working without this grant - only pause/skip/previous
     * (and starting playback on anything other than Spotify) are actually
     * blocked.
     */
    const val BANNER_MESSAGE: String =
        "Pause, skip, and previous need notification access, and Legion doesn't have it on this " +
            "device. Playback started through Spotify still works without it."
}
