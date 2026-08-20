package com.kevin.legion.location

import com.kevin.legion.location.NavigationController.Mode
import com.kevin.legion.location.NavigationController.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage of [NavigationController] - URI shape, encoding, and the honesty mapping from an
 * [Outcome] to what the driver is told. No Context, no Android framework, which is why the
 * encoder is hand-rolled rather than `Uri.encode`.
 *
 * The claim under test that matters is ticket 03's: **only an outcome where something actually
 * appeared on screen may report success**, and every other one must say in words that nothing
 * opened. A launcher that reports success unconditionally is the original bug behind a tool call.
 */
class NavigationControllerTest {

    @Test
    fun `navigate mode uses google navigation, show mode uses geo`() {
        assertEquals("google.navigation:q=Kirby", NavigationController.uriFor("Kirby", Mode.NAVIGATE))
        assertEquals("geo:0,0?q=Kirby", NavigationController.uriFor("Kirby", Mode.SHOW))
    }

    @Test
    fun `spaces are percent-encoded, never plus-encoded`() {
        val uri = NavigationController.uriFor("2200 Kirby Drive", Mode.NAVIGATE)
        assertEquals("google.navigation:q=2200%20Kirby%20Drive", uri)
        assertFalse("a + would be carried through a geo query literally", uri.contains("+"))
    }

    @Test
    fun `punctuation, ampersands and non-ascii survive as UTF-8 percent escapes`() {
        assertEquals(
            "geo:0,0?q=Ben%20%26%20Jerry%27s%2C%20Houston",
            NavigationController.uriFor("Ben & Jerry's, Houston", Mode.SHOW),
        )
        // Cafe with an acute e: two UTF-8 bytes, uppercase hex.
        assertEquals("geo:0,0?q=Caf%C3%A9", NavigationController.uriFor("Café", Mode.SHOW))
    }

    @Test
    fun `surrounding whitespace is trimmed before encoding`() {
        assertEquals("google.navigation:q=Shell", NavigationController.uriFor("  Shell  ", Mode.NAVIGATE))
    }

    @Test
    fun `only a real launch reports success`() {
        assertTrue(NavigationController.succeeded(Outcome.Launched))
        assertTrue(NavigationController.succeeded(Outcome.LaunchedAsMapPin))
        assertFalse(NavigationController.succeeded(Outcome.NoMapApp))
        assertFalse(NavigationController.succeeded(Outcome.BlankDestination))
        assertFalse(NavigationController.succeeded(Outcome.Failed("SecurityException")))
    }

    @Test
    fun `every non-launch outcome says in words that nothing opened`() {
        for (outcome in listOf(Outcome.NoMapApp, Outcome.Failed("SecurityException"))) {
            val message = NavigationController.message(outcome, "the museum")
            assertNotNull("$outcome must be spoken, never swallowed", message)
            assertTrue(
                "$outcome must state that nothing opened: $message",
                message!!.contains("nothing opened", ignoreCase = true) ||
                    message.contains("Nothing's navigating"),
            )
        }
    }

    @Test
    fun `the map-pin fallback admits it is not turn-by-turn`() {
        val message = NavigationController.message(Outcome.LaunchedAsMapPin, "the museum")
        assertNotNull(message)
        assertTrue(message!!.contains("turn-by-turn"))
        assertTrue("the driver has to know the destination that did land", message.contains("the museum"))
    }

    @Test
    fun `a plain successful launch says nothing - the map is visibly up`() {
        assertNull(NavigationController.message(Outcome.Launched, "the museum"))
    }

    @Test
    fun `a blank destination is reported, not launched as an empty query`() {
        val message = NavigationController.message(Outcome.BlankDestination, "")
        assertNotNull(message)
        assertTrue(message!!.contains("haven't opened anything"))
    }
}
