package com.kevin.legion.media

import com.kevin.legion.media.SpotifyController.LibraryAction
import com.kevin.legion.media.SpotifyController.LibraryWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyController]'s library-write outcome mapping (ticket 05,
 * `.scratch/spotify-voice/issues/05-library-writes.md`) - the pure `(outcome, action) -> spoken
 * line` function, same shape and reasoning as [SpotifyControllerPlayOutcomeTest] and
 * [SpotifyControllerQueueOutcomeTest]. The claim under test that matters most: **"already liked"
 * and "liked it" must never read the same** (ticket 05 rule 3) - guessing would be a confident lie.
 */
class SpotifyControllerLibraryWriteTest {

    // ------------------------------------------------------- succeeded

    @Test
    fun `Applied and AlreadyInThatState both count as succeeded - the requested state now holds either way`() {
        assertTrue(SpotifyController.succeeded(LibraryWriteOutcome.Applied))
        assertTrue(SpotifyController.succeeded(LibraryWriteOutcome.AlreadyInThatState))
    }

    @Test
    fun `NothingPlaying, NotConnected and WriteRejected are not successes`() {
        assertFalse(SpotifyController.succeeded(LibraryWriteOutcome.NothingPlaying))
        assertFalse(SpotifyController.succeeded(LibraryWriteOutcome.NotConnected))
        assertFalse(SpotifyController.succeeded(LibraryWriteOutcome.WriteRejected))
    }

    // ------------------------------------------------------- message: the "already" honesty rule

    @Test
    fun `like says liked it when Applied, and already liked when AlreadyInThatState - never the same sentence`() {
        val applied = SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.LIKE)
        val already = SpotifyController.message(LibraryWriteOutcome.AlreadyInThatState, LibraryAction.LIKE)
        assertNotEquals("liking twice must not read as the identical sentence both times", applied, already)
        assertTrue(applied.contains("Liked"))
        assertTrue(already.contains("Already liked") || already.contains("already"))
    }

    @Test
    fun `follow_artist distinguishes Applied from AlreadyInThatState the same way like does`() {
        val applied = SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.FOLLOW_ARTIST)
        val already = SpotifyController.message(LibraryWriteOutcome.AlreadyInThatState, LibraryAction.FOLLOW_ARTIST)
        assertNotEquals(applied, already)
    }

    @Test
    fun `the four actions do not collapse onto one shared verb for Applied`() {
        val messages = setOf(
            SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.LIKE),
            SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.UNLIKE),
            SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.FOLLOW_ARTIST),
            SpotifyController.message(LibraryWriteOutcome.Applied, LibraryAction.UNFOLLOW_ARTIST),
        )
        assertEquals(4, messages.size)
    }

    @Test
    fun `NothingPlaying says nothing's playing, not a generic failure`() {
        val message = SpotifyController.message(LibraryWriteOutcome.NothingPlaying, LibraryAction.LIKE)
        assertTrue(message.contains("Nothing's playing"))
    }

    @Test
    fun `NotConnected points at Setup, same wording family as the rest of the Spotify tools`() {
        val message = SpotifyController.message(LibraryWriteOutcome.NotConnected, LibraryAction.FOLLOW_ARTIST)
        assertTrue(message.contains("Setup"))
    }

    @Test
    fun `WriteRejected is spoken as a failure, not a success`() {
        val message = SpotifyController.message(LibraryWriteOutcome.WriteRejected, LibraryAction.UNLIKE)
        assertTrue(message.isNotBlank())
        assertFalse(SpotifyController.succeeded(LibraryWriteOutcome.WriteRejected))
    }
}
