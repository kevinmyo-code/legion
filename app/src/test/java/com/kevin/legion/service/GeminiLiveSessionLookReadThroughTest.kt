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
 * ADR 0084: text the camera sees is third-party content. Once a frame went out on a socket, that
 * turn and every later turn on it are read-through (so `memory` remember, a goal constraint and
 * `ask_engine` refuse, and nothing reaches episodic memory), logged as `read_through source=look`.
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
    fun `a socket that saw a frame stays read-through for every later turn`() {
        session.markLookSeen()
        assertTrue(session.readThroughToolTouchedThisTurn())
        repeat(3) {
            session.audit.turnComplete()
            session.startNextTurnReadThrough()
            assertTrue(session.readThroughToolTouchedThisTurn())
        }
        assertEquals("read_through source=look", lines.first())
        assertEquals(4, lines.count { it == "read_through source=look" })
    }

    @Test
    fun `a socket that never looked starts every turn clean`() {
        session.audit.turnComplete()
        session.startNextTurnReadThrough()
        assertFalse(session.readThroughToolTouchedThisTurn())
    }
}
