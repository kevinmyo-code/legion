package com.kevin.midnightai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonFatalRateLimiterTest {

    @Test
    fun `one per key per window, keys independent, open again after the window`() {
        var t = 1_000L
        val limiter = NonFatalRateLimiter(windowMs = 60_000) { t }
        assertTrue(limiter.allow("ask_engine:Unreachable"))
        assertFalse(limiter.allow("ask_engine:Unreachable"))
        assertTrue(limiter.allow("ask_engine:WriteRefused"))
        t += 59_999
        assertFalse(limiter.allow("ask_engine:Unreachable"))
        t += 1
        assertTrue(limiter.allow("ask_engine:Unreachable"))
    }

    @Test
    fun `the non-fatal message is the class and nothing else`() {
        assertEquals("AskEngineFailure(AgentTimeout)", AskEngineFailure("AgentTimeout").message)
    }
}
