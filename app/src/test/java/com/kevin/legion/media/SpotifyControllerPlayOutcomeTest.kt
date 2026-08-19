package com.kevin.legion.media

import com.kevin.legion.media.SpotifyController.PlayOutcome
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyController]'s pure decision logic (ticket 02,
 * `.scratch/spotify-voice/issues/02-app-remote-spine.md`) - the connect-failure -> [PlayOutcome]
 * mapping and the outcome -> spoken-line mapping, same shape as
 * `com.kevin.legion.location.NavigationControllerTest`. No Context, no App Remote SDK connection,
 * no coroutines: [SpotifyController.outcomeForConnectFailure] and [SpotifyController.message] are
 * both plain functions over plain data, and the four exception classes under test
 * ([CouldNotFindSpotifyApp], [NotLoggedInException], [UserNotAuthorizedException],
 * [OfflineModeException]) are ordinary `java.lang.Exception` subclasses bundled in the App Remote
 * aar with no Android framework dependency of their own (confirmed by javap against the bundled
 * classes.jar) - constructing them here needs nothing Robolectric or Android would have to shadow.
 *
 * The claim under test that matters is scope item 2 of the ticket: **four distinct spoken
 * failures, never one generic string** - each of [CouldNotFindSpotifyApp], [NotLoggedInException],
 * [UserNotAuthorizedException] and [OfflineModeException] must map to its own [PlayOutcome] and
 * therefore its own, differently-worded line.
 */
class SpotifyControllerPlayOutcomeTest {

    // ------------------------------------------------------- outcomeForConnectFailure

    @Test
    fun `CouldNotFindSpotifyApp maps to NotInstalled`() {
        assertEquals(PlayOutcome.NotInstalled, SpotifyController.outcomeForConnectFailure(CouldNotFindSpotifyApp()))
    }

    @Test
    fun `NotLoggedInException maps to NotLoggedIn`() {
        val error = NotLoggedInException("not logged in", null)
        assertEquals(PlayOutcome.NotLoggedIn, SpotifyController.outcomeForConnectFailure(error))
    }

    @Test
    fun `UserNotAuthorizedException maps to NotAuthorized`() {
        val error = UserNotAuthorizedException("not authorized", null)
        assertEquals(PlayOutcome.NotAuthorized, SpotifyController.outcomeForConnectFailure(error))
    }

    @Test
    fun `OfflineModeException maps to Offline`() {
        val error = OfflineModeException("offline", null)
        assertEquals(PlayOutcome.Offline, SpotifyController.outcomeForConnectFailure(error))
    }

    @Test
    fun `an unrecognized throwable maps to ConnectFailed carrying its class name`() {
        val outcome = SpotifyController.outcomeForConnectFailure(IllegalStateException("wedged"))
        assertEquals(PlayOutcome.ConnectFailed("IllegalStateException"), outcome)
    }

    @Test
    fun `null (no exception recorded) still maps to a ConnectFailed, not a crash`() {
        val outcome = SpotifyController.outcomeForConnectFailure(null)
        assertEquals(PlayOutcome.ConnectFailed(null), outcome)
    }

    // ------------------------------------------------------- succeeded

    @Test
    fun `only Started counts as succeeded`() {
        assertTrue(SpotifyController.succeeded(PlayOutcome.Started()))
        assertFalse(SpotifyController.succeeded(PlayOutcome.NotInstalled))
        assertFalse(SpotifyController.succeeded(PlayOutcome.NotLoggedIn))
        assertFalse(SpotifyController.succeeded(PlayOutcome.NotAuthorized))
        assertFalse(SpotifyController.succeeded(PlayOutcome.Offline))
        assertFalse(SpotifyController.succeeded(PlayOutcome.ConnectFailed(null)))
        assertFalse(SpotifyController.succeeded(PlayOutcome.PlayRejected))
    }

    // ------------------------------------------------------- message (the honesty mapping)

    @Test
    fun `the four connect failures each get their own distinct spoken line`() {
        val messages = setOf(
            SpotifyController.message(PlayOutcome.NotInstalled, "Bad Bunny"),
            SpotifyController.message(PlayOutcome.NotLoggedIn, "Bad Bunny"),
            SpotifyController.message(PlayOutcome.NotAuthorized, "Bad Bunny"),
            SpotifyController.message(PlayOutcome.Offline, "Bad Bunny"),
        )
        // A set collapses duplicates - four inputs producing fewer than four distinct strings
        // would mean two "different" failures read identically to the driver, which is exactly
        // the one-generic-string failure mode the ticket exists to close.
        assertEquals(4, messages.size)
    }

    @Test
    fun `NotInstalled names Spotify not being installed, not a generic failure`() {
        val message = SpotifyController.message(PlayOutcome.NotInstalled, "Bad Bunny")
        assertTrue(message.contains("isn't installed"))
        assertTrue(message.contains("Bad Bunny"))
    }

    @Test
    fun `NotLoggedIn tells the driver to log in`() {
        assertTrue(SpotifyController.message(PlayOutcome.NotLoggedIn, "Bad Bunny").contains("log"))
    }

    @Test
    fun `NotAuthorized points at the developer dashboard allowlist`() {
        assertTrue(SpotifyController.message(PlayOutcome.NotAuthorized, "Bad Bunny").contains("allowlist"))
    }

    @Test
    fun `Offline names being offline`() {
        assertTrue(SpotifyController.message(PlayOutcome.Offline, "Bad Bunny").contains("offline mode"))
    }

    @Test
    fun `Started with no premium warning is just the plain confirmation`() {
        assertEquals("Playing \"Bad Bunny\" on Spotify.", SpotifyController.message(PlayOutcome.Started(), "Bad Bunny"))
    }

    @Test
    fun `Started with a premium warning appends it after the confirmation`() {
        val message = SpotifyController.message(PlayOutcome.Started(premiumWarning = "Heads up - no Premium."), "Bad Bunny")
        assertEquals("Playing \"Bad Bunny\" on Spotify. Heads up - no Premium.", message)
    }

    @Test
    fun `PlayRejected names the track and that it may not be playable here`() {
        val message = SpotifyController.message(PlayOutcome.PlayRejected, "Bad Bunny")
        assertTrue(message.contains("Bad Bunny"))
        assertTrue(message.contains("may not be playable"))
    }

    @Test
    fun `a ConnectFailed with a detail carries it through rather than paraphrasing it away`() {
        val message = SpotifyController.message(PlayOutcome.ConnectFailed("SecurityException"), "Bad Bunny")
        assertTrue(message.contains("SecurityException"))
    }
}
