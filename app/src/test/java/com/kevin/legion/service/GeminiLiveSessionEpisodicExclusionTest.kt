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
    fun `the excluded set is exactly the two mail tools - guards against silent drift`() {
        assertEquals(setOf("search_mail", "read_mail"), LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }
}
