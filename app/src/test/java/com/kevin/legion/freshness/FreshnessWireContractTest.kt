package com.kevin.legion.freshness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's parser against a body shaped as `server/ingest/freshness.py` emits it:
 * `FreshnessSerializer` is `{"sources": [FreshnessSourceSerializer, ...]}`, NOT a bare list.
 */
class FreshnessWireContractTest {
    private val body = """
        {"sources": [
          {"source": "canvas", "last_ok_at": "2026-10-04T20:00:00.123456Z", "last_outcome": "needs_login",
           "last_error": "Canvas refused the saved login at 2026-10-04T21:30:15.000000+00:00",
           "stale": true, "sentence": "Canvas needs you to log in again: run tools/connect_session.py canvas",
           "action_url": null},
          {"source": "webassign", "last_ok_at": "2026-10-22T08:00:00Z", "last_outcome": "ok",
           "last_error": null, "stale": false, "sentence": "WebAssign is up to date.", "action_url": null},
          {"source": "heartbeat", "last_ok_at": null, "last_outcome": null,
           "last_error": null, "stale": false, "sentence": "Heartbeat has not been set up.", "action_url": null}
        ]}
    """.trimIndent()

    @Test
    fun `reads the wrapped rows and the four fields it acts on`() {
        val feeds = parseFreshness(body)
        assertEquals(listOf("canvas", "webassign", "heartbeat"), feeds.map { it.source })
        val canvas = feeds.first()
        assertTrue(canvas.needsLogin)
        assertEquals("Canvas needs you to log in again: run tools/connect_session.py canvas", canvas.sentence)
        assertEquals("Canvas refused the saved login at 2026-10-04T21:30:15.000000+00:00", canvas.lastError)
        assertFalse(feeds[1].needsLogin)
        assertNull(feeds[2].lastOutcome)
    }

    @Test
    fun `a bare list is refused, not read as no feeds`() {
        assertThrows(IllegalArgumentException::class.java) { parseFreshness("[]") }
        assertThrows(IllegalArgumentException::class.java) { parseFreshness("""{"results": []}""") }
    }
}
