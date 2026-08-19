package com.kevin.legion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [NowPlayingController.choosePackage] - the exclusion that keeps LEGION's own media
 * session (published by `car.LegionMediaLibraryService` since the proxy card landed) from ever
 * being read back as "what's really playing". See that function's own doc, and
 * [LegionMediaLibraryService][com.kevin.legion.car.LegionMediaLibraryService]'s class doc, for why
 * this loop is closed HERE rather than in the UI: a UI-level filter would still let the loop run
 * underneath and still let `music_play_history` log phantom rows for LEGION quoting itself. Plain
 * JVM logic - [NowPlayingController.choosePackage] takes package-name/isPlaying pairs, not
 * framework [android.media.session.MediaController] instances - but merely REFERENCING the
 * [NowPlayingController] object loads its class, and that object's `mainHandler` property is
 * initialized eagerly with `Handler(Looper.getMainLooper())`, which throws on a plain JVM runner.
 * Robolectric shadows it, same shape as
 * [NowPlayingControllerHistoryTest][com.kevin.legion.media.NowPlayingControllerHistoryTest]'s own
 * doc comment.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingControllerSelfExclusionTest {

    private val ownPackage = "com.kevin.legion"

    @Test
    fun `LEGION's own package is never chosen, even when it is the only session`() {
        val result = NowPlayingController.choosePackage(
            listOf(ownPackage to true),
            ownPackage,
        )
        assertNull("LEGION's own session must never be read back as the source", result)
    }

    @Test
    fun `LEGION's own package is skipped even when it reports STATE_PLAYING and Spotify does not`() {
        // The exact loop shape this guards against: LEGION's proxy mirrors "playing" from an
        // earlier tick, and without the exclusion the STATE_PLAYING-preference rule below would
        // pick LEGION over a genuinely paused Spotify.
        val result = NowPlayingController.choosePackage(
            listOf(ownPackage to true, "com.spotify.music" to false),
            ownPackage,
        )
        assertEquals("com.spotify.music", result)
    }

    @Test
    fun `a real external session is preferred while playing, LEGION excluded from the candidate set`() {
        val result = NowPlayingController.choosePackage(
            listOf("com.spotify.music" to true, ownPackage to true),
            ownPackage,
        )
        assertEquals("com.spotify.music", result)
    }

    @Test
    fun `nothing playing but LEGION present falls back to null, never to LEGION`() {
        val result = NowPlayingController.choosePackage(
            listOf(ownPackage to false),
            ownPackage,
        )
        assertNull(result)
    }

    @Test
    fun `an unrelated non-playing session is still preferred over LEGION's own`() {
        val result = NowPlayingController.choosePackage(
            listOf(ownPackage to false, "com.spotify.music" to false),
            ownPackage,
        )
        assertEquals("com.spotify.music", result)
    }

    @Test
    fun `no candidates at all resolves to null`() {
        assertNull(NowPlayingController.choosePackage(emptyList(), ownPackage))
    }

    @Test
    fun `a null ownPackage (context not yet initialized) excludes nothing`() {
        // appCtx can be null very early in NowPlayingController.init - choosePackage must degrade
        // to "no exclusion" rather than throw, since a null package name can never match a real
        // controller's package anyway.
        val result = NowPlayingController.choosePackage(listOf(ownPackage to true), null)
        assertEquals(ownPackage, result)
    }
}
