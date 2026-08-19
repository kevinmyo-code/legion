package com.kevin.legion.media

import com.kevin.legion.media.SpotifyController.QueueOutcome
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyController]'s queue outcome mapping (ticket 04,
 * `.scratch/spotify-voice/issues/04-queue.md`) - same shape and same reasoning as
 * [SpotifyControllerPlayOutcomeTest], kept as a SEPARATE test class because [QueueOutcome] is
 * its own sealed type, not [SpotifyController.PlayOutcome] reused.
 */
class SpotifyControllerQueueOutcomeTest {

    // ------------------------------------------------------- queueOutcomeForConnectFailure

    @Test
    fun `the four connect exceptions map to their own QueueOutcome, same as PlayOutcome`() {
        assertEquals(QueueOutcome.NotInstalled, SpotifyController.queueOutcomeForConnectFailure(CouldNotFindSpotifyApp()))
        assertEquals(QueueOutcome.NotLoggedIn, SpotifyController.queueOutcomeForConnectFailure(NotLoggedInException("x", null)))
        assertEquals(QueueOutcome.NotAuthorized, SpotifyController.queueOutcomeForConnectFailure(UserNotAuthorizedException("x", null)))
        assertEquals(QueueOutcome.Offline, SpotifyController.queueOutcomeForConnectFailure(OfflineModeException("x", null)))
    }

    @Test
    fun `an unrecognized throwable maps to ConnectFailed carrying its class name`() {
        val outcome = SpotifyController.queueOutcomeForConnectFailure(IllegalStateException("wedged"))
        assertEquals(QueueOutcome.ConnectFailed("IllegalStateException"), outcome)
    }

    @Test
    fun `null still maps to a ConnectFailed, not a crash`() {
        assertEquals(QueueOutcome.ConnectFailed(null), SpotifyController.queueOutcomeForConnectFailure(null))
    }

    // ------------------------------------------------------- succeeded

    @Test
    fun `only Queued counts as succeeded`() {
        assertTrue(SpotifyController.succeeded(QueueOutcome.Queued))
        assertFalse(SpotifyController.succeeded(QueueOutcome.NotInstalled))
        assertFalse(SpotifyController.succeeded(QueueOutcome.NotLoggedIn))
        assertFalse(SpotifyController.succeeded(QueueOutcome.NotAuthorized))
        assertFalse(SpotifyController.succeeded(QueueOutcome.Offline))
        assertFalse(SpotifyController.succeeded(QueueOutcome.ConnectFailed(null)))
        assertFalse(SpotifyController.succeeded(QueueOutcome.QueueRejected))
    }

    // ------------------------------------------------------- message (the honesty mapping)

    @Test
    fun `Queued says next-up, never a specific position, since Spotify offers no insert-at-position`() {
        val message = SpotifyController.message(QueueOutcome.Queued, "Bad Bunny")
        assertTrue(message.contains("Bad Bunny"))
        assertTrue(
            "the line must not imply an ordering the API does not offer",
            message.contains("next") || message.contains("Next"),
        )
    }

    @Test
    fun `the four connect failures each get their own distinct spoken line, same discipline as play`() {
        val messages = setOf(
            SpotifyController.message(QueueOutcome.NotInstalled, "Bad Bunny"),
            SpotifyController.message(QueueOutcome.NotLoggedIn, "Bad Bunny"),
            SpotifyController.message(QueueOutcome.NotAuthorized, "Bad Bunny"),
            SpotifyController.message(QueueOutcome.Offline, "Bad Bunny"),
        )
        assertEquals(4, messages.size)
    }

    @Test
    fun `QueueRejected names the track and that it may not be playable here`() {
        val message = SpotifyController.message(QueueOutcome.QueueRejected, "Bad Bunny")
        assertTrue(message.contains("Bad Bunny"))
        assertTrue(message.contains("may not be playable"))
    }

    @Test
    fun `a ConnectFailed with a detail carries it through rather than paraphrasing it away`() {
        val message = SpotifyController.message(QueueOutcome.ConnectFailed("SecurityException"), "Bad Bunny")
        assertTrue(message.contains("SecurityException"))
    }
}
