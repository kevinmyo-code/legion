package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * ADR 0090 write guard on Live (2026-11-17): a read-through tool that also acts (`email` reads mail
 * for a recipient, `browser` opens a page) marks the turn as it arrives, before it runs. Its tool
 * call says when that mark is its own, so `LiveSessionController` does not hand it the mark and the
 * write guard does not refuse it; a mark from an earlier read still counts. Invented content only.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOwnReadThroughMarkTest {

    private val events = mutableListOf<LiveEvent>()
    private val session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) { events += it }

    private fun toolCall(id: String, name: String, args: JSONObject = JSONObject()) {
        val call = JSONObject().put("id", id).put("name", name).put("args", args)
        GeminiLiveSession::class.java.getDeclaredMethod("handleToolCall", JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(session, JSONObject().put("functionCalls", JSONArray().put(call)))
        ShadowLooper.idleMainLooper()
    }

    private fun ownMark(id: String) = events.filterIsInstance<LiveEvent.ToolCall>().single { it.id == id }.ownMarkOnly

    @Test
    fun `the first email of a clean turn carries only its own mark`() {
        toolCall("e1", "email", JSONObject().put("action", "draft"))
        assertEquals(true, ownMark("e1"))
        assertEquals(true, session.readThroughToolTouchedThisTurn())
    }

    @Test
    fun `an email after a mail read is handed the earlier mark`() {
        toolCall("m1", "look_up", JSONObject().put("domain", "ask_mail").put("question", "newest"))
        toolCall("e1", "email", JSONObject().put("action", "draft"))
        assertEquals(false, ownMark("e1"))
    }

    @Test
    fun `a write that marks nothing never claims an own mark`() {
        toolCall("m1", "look_up", JSONObject().put("domain", "ask_mail").put("question", "newest"))
        toolCall("w1", "memory", JSONObject().put("action", "remember").put("text", "invented"))
        assertEquals(false, ownMark("w1"))
    }
}
