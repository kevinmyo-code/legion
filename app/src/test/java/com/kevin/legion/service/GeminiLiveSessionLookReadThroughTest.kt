package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0084: text the camera sees is third-party content. A turn a frame went out in is read-through
 * (so `memory` remember, a goal constraint and `ask_engine` refuse, and nothing reaches episodic
 * memory), logged as `read_through source=look`, and so is the turn after it: a frame sent while
 * the reply plays belongs to the next turn. The round 3 carry, applied to frames.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiLiveSessionLookReadThroughTest {

    private val lines = mutableListOf<String>()
    private lateinit var session: GeminiLiveSession

    @Before
    fun setUp() {
        session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) {}
        session.auditSink = { lines += it }
        session.audit.enabled = true
    }

    @Test
    fun `no frame can go out with no conversation, and nothing is marked`() {
        assertFalse(session.sendVideoFrame(byteArrayOf(1, 2, 3)))
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a frame marks its turn and carries into the next, then the turn after starts clean`() {
        session.markLookSeen()
        assertTrue(session.readThroughToolTouchedThisTurn())
        session.audit.turnComplete()
        session.startNextTurnReadThrough()
        assertTrue(session.readThroughToolTouchedThisTurn())
        session.audit.turnComplete()
        session.startNextTurnReadThrough()
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertEquals(
            listOf("read_through source=look", "turn_complete", "read_through source=carry", "turn_complete"),
            lines,
        )
    }

    @Test
    fun `frames in every turn keep every turn of an open look read-through`() {
        repeat(3) {
            session.markLookSeen()
            assertTrue(session.readThroughToolTouchedThisTurn())
            session.audit.turnComplete()
            session.startNextTurnReadThrough()
        }
        assertTrue(session.readThroughToolTouchedThisTurn())
    }
}
