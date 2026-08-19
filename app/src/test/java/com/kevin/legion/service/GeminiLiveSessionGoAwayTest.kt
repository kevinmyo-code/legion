package com.kevin.legion.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ticket 02 (drive-test-2026-08-18, "the context dies with the socket"): covers
 * [GeminiLiveSession.parseGoAwayDurationMs], the pure parse behind [GeminiLiveSession]'s
 * `handleGoAway`. [GeminiLiveSession] itself needs a live Context/OkHttpClient websocket/
 * AudioTrack/Room to construct - same shape as [GeminiLiveSessionEpisodicExclusionTest] -
 * so this is the seam a plain JVM test can actually exercise, and it's the real function
 * `handleGoAway` calls, not a parallel re-implementation.
 *
 * The proto3 well-known JSON mapping for `Duration` (the type of `GoAway.timeLeft`) is a
 * string like `"9.5s"`; the `{seconds, nanos}` struct form is only defensively supported
 * and has never been observed from a real response - see the production function's own
 * doc comment for why (unmeasured as of 2026-08-19, no live session has run this code).
 */
class GeminiLiveSessionGoAwayTest {

    @Test
    fun `parses a whole-second duration string`() {
        assertEquals(9_000L, GeminiLiveSession.parseGoAwayDurationMs("9s"))
    }

    @Test
    fun `parses a fractional-second duration string`() {
        assertEquals(9_500L, GeminiLiveSession.parseGoAwayDurationMs("9.5s"))
    }

    @Test
    fun `parses zero`() {
        assertEquals(0L, GeminiLiveSession.parseGoAwayDurationMs("0s"))
    }

    @Test
    fun `parses the defensive seconds-nanos object form`() {
        val obj = JSONObject().put("seconds", 9L).put("nanos", 500_000_000L)
        assertEquals(9_500L, GeminiLiveSession.parseGoAwayDurationMs(obj))
    }

    @Test
    fun `an object with only seconds omits nanos cleanly`() {
        val obj = JSONObject().put("seconds", 12L)
        assertEquals(12_000L, GeminiLiveSession.parseGoAwayDurationMs(obj))
    }

    @Test
    fun `an unparseable string yields null rather than a wrong guess`() {
        assertNull(GeminiLiveSession.parseGoAwayDurationMs("not-a-duration"))
    }

    @Test
    fun `null input yields null`() {
        assertNull(GeminiLiveSession.parseGoAwayDurationMs(null))
    }
}
