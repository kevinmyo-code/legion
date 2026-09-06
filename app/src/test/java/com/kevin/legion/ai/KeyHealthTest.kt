package com.kevin.legion.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [KeyHealth], which until 2026-09-06 had eight write sites and zero readers, and lost everything
 * it recorded on process death.
 *
 * Both halves mattered for the case it was fixed for: Kevin's key ran out of credits, so the Live
 * socket fails its HTTP upgrade on every launch, and the app knew exactly why on each one and said
 * nothing on any of them.
 */
@RunWith(RobolectricTestRunner::class)
class KeyHealthTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        KeyHealth.resetForTest(context)
        KeyHealth.init(context)
    }

    @After
    fun tearDown() {
        KeyHealth.resetForTest(null)
    }

    @Test
    fun `a rate limit survives a process restart`() {
        // The whole point of persisting: a quota failure repeats across restarts, so a diagnosis
        // that dies with the process is one the user never gets to see.
        KeyHealth.noteRateLimited("HTTP 429 opening the Gemini Live socket")

        KeyHealth.resetForTest(null) // wipe the in-memory copy, keep what is on disk
        KeyHealth.init(context) // a fresh process reads it back

        assertEquals(KeyHealth.PROBLEM_RATE_LIMITED, KeyHealth.lastProblem)
        assertEquals("HTTP 429 opening the Gemini Live socket", KeyHealth.detail)
        assertTrue(KeyHealth.lastProblemAt > 0)
    }

    @Test
    fun `a successful call clears the problem, so a recovered key stops being reported broken`() {
        KeyHealth.noteRateLimited("HTTP 429")
        KeyHealth.noteOk()

        assertNull(KeyHealth.lastProblem)
        assertEquals("", KeyHealth.detail)

        KeyHealth.resetForTest(null)
        KeyHealth.init(context)
        assertNull("and the clear must persist too, or the row comes back", KeyHealth.lastProblem)
    }

    @Test
    fun `the detail is kept, because it is what makes the Setup sentence checkable`() {
        // The instruction was to check the error, not to assume any failure is quota. The sentence
        // quotes this back, so the claim can be verified rather than merely believed.
        KeyHealth.noteInvalid("HTTP 403 opening the Gemini Live socket")
        assertEquals(KeyHealth.PROBLEM_INVALID, KeyHealth.lastProblem)
        assertTrue(KeyHealth.detail.contains("403"))
    }

    @Test
    fun `a sprawling server body is trimmed to something a settings screen can show`() {
        KeyHealth.noteRateLimited("x".repeat(1000))
        assertTrue("a wall of JSON is not a sentence", KeyHealth.detail.length <= 160)
    }

    @Test
    fun `newlines in a server body are flattened, not pasted into the row`() {
        KeyHealth.noteRateLimited("{\n  \"error\": {\n    \"code\": 429\n  }\n}")
        assertTrue(!KeyHealth.detail.contains("\n"))
        assertTrue(KeyHealth.detail.contains("429"))
    }

    @Test
    fun `noting before init does not crash, it just does not persist`() {
        // The note functions are called from a WebSocket callback and from inside an HTTP handler.
        // Observability must never break the thing it observes.
        KeyHealth.resetForTest(null)
        KeyHealth.noteRateLimited("HTTP 429")
        assertEquals("the in-memory copy still works", KeyHealth.PROBLEM_RATE_LIMITED, KeyHealth.lastProblem)
    }

    @Test
    fun `a later problem replaces an earlier one rather than accumulating`() {
        KeyHealth.noteRateLimited("HTTP 429")
        KeyHealth.noteInvalid("HTTP 403")
        assertEquals(KeyHealth.PROBLEM_INVALID, KeyHealth.lastProblem)
        assertEquals("HTTP 403", KeyHealth.detail)
    }
}
