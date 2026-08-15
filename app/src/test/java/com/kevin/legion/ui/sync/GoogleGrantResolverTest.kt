package com.kevin.legion.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [GoogleGrantResolver] - the Google grant status/failure copy
 * shared by the Connect Google Drive screen and the GOOGLE row's read status. No Android
 * dependency, plain JVM test, same shape as `LedgerEmptyStateResolverTest`/
 * `AssistantStripResolverTest`. Ported 2026-08-13 from `DriveConnectResolverTest` (ticket
 * `.scratch/google-account-integration/issues/12-google-grant-plumbing.md`) - every case
 * below carries over unchanged, plus coverage for [GoogleGrantResolver.FailureCategory.NEEDS_CONSENT]
 * and for both [GoogleGrantResolver.Grant] values.
 */
class GoogleGrantResolverTest {

    @Test
    fun `no play services wins regardless of the stored sync flag`() {
        assertEquals(
            GoogleGrantResolver.Availability.UNAVAILABLE,
            GoogleGrantResolver.availability(playServicesAvailable = false, syncEnabled = false),
        )
        assertEquals(
            GoogleGrantResolver.Availability.UNAVAILABLE,
            GoogleGrantResolver.availability(playServicesAvailable = false, syncEnabled = true),
        )
    }

    @Test
    fun `play services present and not connected reads as disconnected`() {
        assertEquals(
            GoogleGrantResolver.Availability.DISCONNECTED,
            GoogleGrantResolver.availability(playServicesAvailable = true, syncEnabled = false),
        )
    }

    @Test
    fun `play services present and connected reads as connected`() {
        assertEquals(
            GoogleGrantResolver.Availability.CONNECTED,
            GoogleGrantResolver.availability(playServicesAvailable = true, syncEnabled = true),
        )
    }

    // --- diagnose(): the fix for the 2026-08-03 "no info on why" defect ------------

    @Test
    fun `status code 10 (DEVELOPER_ERROR) reads as a config problem, not a network one`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.DRIVE,
            statusCode = 10,
            isNetworkException = false,
            fallbackMessage = "10: ",
        )
        assertEquals(GoogleGrantResolver.FailureCategory.CONFIG, failure.category)
    }

    @Test
    fun `status code 10 wins over the network flag - a real config error is not offline`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.DRIVE,
            statusCode = 10,
            isNetworkException = true,
            fallbackMessage = null,
        )
        assertEquals(GoogleGrantResolver.FailureCategory.CONFIG, failure.category)
    }

    @Test
    fun `status code 10 for Gmail also reads as a config problem - one resolver, both grants`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.GMAIL,
            statusCode = 10,
            isNetworkException = false,
            fallbackMessage = "10: ",
        )
        assertEquals(GoogleGrantResolver.FailureCategory.CONFIG, failure.category)
        // Generalised wording (ticket 06 point 6): names Drive, Gmail, AND Calendar's
        // immunity, regardless of which grant actually triggered the failure.
        assert(failure.message.contains("Drive sync and Gmail will not work"))
        assert(failure.message.contains("Calendar is unaffected"))
    }

    @Test
    fun `status code 7 (NETWORK_ERROR) reads as a network problem`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.DRIVE,
            statusCode = 7,
            isNetworkException = false,
            fallbackMessage = "7: network error",
        )
        assertEquals(GoogleGrantResolver.FailureCategory.NETWORK, failure.category)
        assertEquals("Couldn't reach Google. Check your connection and try again.", failure.message)
    }

    @Test
    fun `a plain IOException with no Play Services status code also reads as network`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.DRIVE,
            statusCode = null,
            isNetworkException = true,
            fallbackMessage = "Unable to resolve host",
        )
        assertEquals(GoogleGrantResolver.FailureCategory.NETWORK, failure.category)
        assertEquals("Couldn't reach Google. Check your connection and try again.", failure.message)
    }

    @Test
    fun `an unmapped status code falls back to unknown but still shows the underlying message`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.DRIVE,
            statusCode = 8, // INTERNAL_ERROR - not one of the categories this screen specifically maps
            isNetworkException = false,
            fallbackMessage = "8: internal error",
        )
        assertEquals(GoogleGrantResolver.FailureCategory.UNKNOWN, failure.category)
        assertEquals("Couldn't connect: 8: internal error", failure.message)
    }

    @Test
    fun `no status code, no network signal, no message still shows a legible fallback`() {
        val failure = GoogleGrantResolver.diagnose(
            grant = GoogleGrantResolver.Grant.GMAIL,
            statusCode = null,
            isNetworkException = false,
            fallbackMessage = null,
        )
        assertEquals(GoogleGrantResolver.FailureCategory.UNKNOWN, failure.category)
        assertEquals("Couldn't connect.", failure.message)
    }

    @Test
    fun `fixed connected and cancelled copy stay stable`() {
        assertEquals("Connected to Google Drive.", GoogleGrantResolver.CONNECTED_MESSAGE)
        assertEquals("Drive wasn't connected. Nothing was turned on.", GoogleGrantResolver.CANCELLED_MESSAGE)
    }

    // --- unavailableMessage(): now names the grant instead of always saying "Sync" -------

    @Test
    fun `unavailable message names Drive when the grant is Drive`() {
        assertEquals(
            "Google Play Services isn't available on this device. Google Drive can't run here.",
            GoogleGrantResolver.unavailableMessage(GoogleGrantResolver.Grant.DRIVE),
        )
    }

    @Test
    fun `unavailable message names Gmail when the grant is Gmail`() {
        assertEquals(
            "Google Play Services isn't available on this device. Gmail can't run here.",
            GoogleGrantResolver.unavailableMessage(GoogleGrantResolver.Grant.GMAIL),
        )
    }

    // --- NEEDS_CONSENT: the case that never existed before ticket 12 ---------------------

    @Test
    fun `needs-reauthorising message names Drive`() {
        assertEquals(
            "Google Drive needs re-authorising. It's in Setup, under Google.",
            GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.DRIVE),
        )
    }

    @Test
    fun `needs-reauthorising message names Gmail`() {
        assertEquals(
            "Gmail needs re-authorising. It's in Setup, under Google.",
            GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.GMAIL),
        )
    }

    @Test
    fun `NEEDS_CONSENT is a distinct category from NETWORK and CONFIG`() {
        val categories = GoogleGrantResolver.FailureCategory.entries.toSet()
        assert(categories.contains(GoogleGrantResolver.FailureCategory.NEEDS_CONSENT))
        assertEquals(4, categories.size) // CONFIG, NETWORK, NEEDS_CONSENT, UNKNOWN
    }
}
