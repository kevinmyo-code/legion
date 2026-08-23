package com.kevin.legion.ui.world

import com.kevin.legion.service.LiveToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-function coverage for [failureLine] (command-center ticket 08's "distinct failure states"
 * requirement) - no Context, no Robolectric, no Compose harness needed (this repo has none, per
 * [com.kevin.legion.ui.body.BodyWriteSameFunctionTest]'s own doc comment; testing the mapping
 * function is the strongest assertion available for a Composable's branch logic without one).
 * Every one of [LiveToolbox.MailCardFailure]'s four values must produce its OWN sentence - the
 * whole point of tagging a kind instead of showing the raw (spoken) tool message.
 */
class PackageFlightCardFailureLineTest {

    @Test
    fun `every MailCardFailure kind produces a distinct line`() {
        val lines = LiveToolbox.MailCardFailure.entries.map { failureLine(it, "raw message") }
        assertEquals(
            "all four failure lines must be textually distinct",
            lines.size,
            lines.toSet().size,
        )
    }

    @Test
    fun `NO_PERMISSION reads as a Gmail connection problem`() {
        val line = failureLine(LiveToolbox.MailCardFailure.NO_PERMISSION, "raw")
        assertEquals("Gmail isn't connected - grant access in Setup to see this.", line)
    }

    @Test
    fun `NO_MATCH reads as nothing found, not an error`() {
        val line = failureLine(LiveToolbox.MailCardFailure.NO_MATCH, "raw")
        assertEquals("Nothing matching in your inbox recently.", line)
    }

    @Test
    fun `UNREACHABLE reads as a network problem`() {
        val line = failureLine(LiveToolbox.MailCardFailure.UNREACHABLE, "raw")
        assertEquals("Couldn't reach Gmail - check your connection.", line)
    }

    @Test
    fun `EXTRACTION_FAILED says the mail WAS found, unlike NO_MATCH`() {
        val line = failureLine(LiveToolbox.MailCardFailure.EXTRACTION_FAILED, "raw")
        assertNotEquals(failureLine(LiveToolbox.MailCardFailure.NO_MATCH, "raw"), line)
        assertEquals("Found the mail but couldn't read it through - try refreshing.", line)
    }

    @Test
    fun `a null kind falls back to the raw message rather than going blank`() {
        assertEquals("some tool message", failureLine(null, "some tool message"))
    }

    @Test
    fun `a null kind with a blank raw message still says something, never empty`() {
        val line = failureLine(null, "")
        assertEquals("Couldn't check right now.", line)
    }
}
