package com.kevin.legion.service.turn

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-call Speaker / Earpiece control's signal ([VoiceOutput.askRoute]).
 *
 * The case that matters is the second identical ask. The signal is a `StateFlow`, and a `StateFlow`
 * of a bare boolean conflates: Speaker on one call would leave `false` behind, and Speaker on the
 * NEXT call would set `false` again and emit nothing, so the second conversation would never hear
 * it. [RouteAsk]'s serial is what keeps every press a distinct value.
 */
class RouteAskTest {

    @Test
    fun `the same ask twice is two different values`() {
        VoiceOutput.askRoute(earpiece = false)
        val first = VoiceOutput.routeChanges.value
        VoiceOutput.askRoute(earpiece = false)
        val second = VoiceOutput.routeChanges.value
        assertNotEquals("a second press is not the same value", first, second)
        assertEquals(false, second?.earpiece)
        assertTrue("the serial moves forward", (second?.serial ?: 0) > (first?.serial ?: 0))
    }

    @Test
    fun `a collector hears every press, repeats included`() = runTest {
        VoiceOutput.askRoute(earpiece = true)
        val heard = mutableListOf<Boolean>()
        val job = launch {
            VoiceOutput.routeChanges.collect { ask -> ask?.let { heard.add(it.earpiece) } }
        }
        yield()
        val before = heard.size
        VoiceOutput.askRoute(earpiece = false)
        yield()
        VoiceOutput.askRoute(earpiece = false)
        yield()
        job.cancel()
        assertEquals(
            "both presses reach the open conversation",
            listOf(false, false),
            heard.drop(before),
        )
    }

    @Test
    fun `an ask says which way it goes`() {
        VoiceOutput.askRoute(earpiece = true)
        assertEquals(true, VoiceOutput.routeChanges.value?.earpiece)
        VoiceOutput.askRoute(earpiece = false)
        assertEquals(false, VoiceOutput.routeChanges.value?.earpiece)
    }
}
