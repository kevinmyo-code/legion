package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 15's own required test: "a test that asserts nothing mail-shaped reaches
 * [com.kevin.legion.data.local.EpisodicTurn]/[com.kevin.legion.data.local.CompanionMemory]" -
 * named explicitly because point 4's failure is invisible on the device (a subject line silently
 * lands in the episodic log, and from there in the 42-table Drive backup, with nobody deciding it
 * should).
 *
 * [GeminiLiveSession] itself needs a live [android.content.Context], a real
 * [okhttp3.OkHttpClient] websocket, [android.media.AudioTrack], and Room to construct at all, so
 * nothing about the class can be instantiated from a plain JVM test. What this asserts instead is
 * [GeminiLiveSession.isEpisodicExcludedTool] - the exact pure decision `handleToolCall` uses to
 * set `mailToolCalledThisTurn`, which is what `captureEpisodicTurn` checks before ever writing a
 * row. This is the real production function, not a parallel re-implementation a test could pass
 * while the live path silently drifted.
 */
class GeminiLiveSessionEpisodicExclusionTest {

    @Test
    fun `search_mail is excluded from episodic persistence`() {
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("search_mail"))
    }

    @Test
    fun `read_mail is excluded from episodic persistence`() {
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("read_mail"))
    }

    /**
     * 2026-08-17 (dispatcher split): the live session only ever sees "ask_mail" off the socket now
     * - search_mail/read_mail moved behind it and are no longer declared to the live model, so
     * without this the read-through exclusion would silently stop firing on every real mail turn.
     */
    @Test
    fun `ask_mail is excluded from episodic persistence`() {
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool("ask_mail"))
    }

    @Test
    fun `an ordinary tool is NOT excluded - the flag must not swallow unrelated turns`() {
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("get_vehicle_data"))
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("remember"))
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("manage_item"))
    }

    @Test
    fun `an unknown or empty tool name is NOT excluded by accident`() {
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool(""))
        assertFalse(GeminiLiveSession.isEpisodicExcludedTool("not_a_real_tool"))
    }

    @Test
    fun `the excluded set is exactly these four names - guards against silent drift`() {
        // Three, not two, since 2026-08-17: the original two names stay (dispatch still runs them
        // internally inside ask_mail's own investigate loop, and a future direct caller of either
        // should still be caught), and "ask_mail" joined them - see the doc comment above.
        //
        // FOUR since 2026-08-21, and the fourth is deliberately NOT mail-shaped. `get_sitrep`'s
        // news module fetches Gmail bodies and returns an LLM summary of them, so the summary is
        // mail content one derivation removed - and ticket 08 call 4 rejected "store the summary,
        // drop the bodies" in those words. This guard did its job: adding the name failed this
        // assertion and forced the decision to be made here rather than absorbed silently, which
        // is the whole reason the test is written as an exact-set comparison.
        assertEquals(
            setOf("search_mail", "read_mail", "ask_mail", "get_sitrep"),
            LiveToolbox.EPISODIC_EXCLUDED_TOOLS,
        )
    }
}
