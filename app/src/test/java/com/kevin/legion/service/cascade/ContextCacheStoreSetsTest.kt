package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #453: the chat set and the full set each keep a cache. Switching sets must reuse, not replace, and a
 * set that cannot be cached must not delay the other's create. Invented content only.
 */
@RunWith(RobolectricTestRunner::class)
class ContextCacheStoreSetsTest {
    private val full = CascadePrefix(
        "model-x", "Invented instruction.",
        JSONArray().put(JSONObject().put("name", "end_conversation")).put(JSONObject().put("name", "remember")),
    )
    private val chat = ToolGate.chatPrefix(full)

    /** How many functions a create body declares: 1 is the chat set, 2 the full set. */
    private fun declared(body: JSONObject): Int {
        val tools = body.getJSONArray("tools")
        return tools.getJSONObject(tools.length() - 1).getJSONArray("functionDeclarations").length()
    }

    private fun store(
        creates: MutableList<Int>,
        memo: ContextCacheStore.Memo = ContextCacheStore.Memo.InMemory(),
        now: () -> Long = { 0L },
        chatFails: Boolean = false,
    ) = ContextCacheStore(
        { body ->
            creates.add(declared(body))
            if (chatFails && declared(body) == 1) {
                CacheCreateOutcome.Failed(400, "invented")
            } else {
                CacheCreateOutcome.Created("cachedContents/c${creates.size}", 1_000)
            }
        },
        CoroutineScope(Dispatchers.Unconfined),
        now = now,
        memo = memo,
    )

    @Test
    fun `each set keeps its own cache and switching sets reuses both`() {
        val creates = mutableListOf<Int>()
        val s = store(creates)
        s.warm(full)
        s.warm(chat)
        assertEquals(listOf(2, 1), creates)
        assertEquals("cachedContents/c1", s.peek(full))
        assertEquals("cachedContents/c2", s.peek(chat))
        assertEquals("no new create on a switch", 2, creates.size)
        assertEquals("live left_s=${CascadeRequests.CACHE_TTL_S}", s.state(chat))
    }

    @Test
    fun `both caches survive a restart through the memo`() {
        val memo = ContextCacheStore.Memo.InMemory()
        val first = store(mutableListOf(), memo)
        first.warm(full)
        first.warm(chat)
        val after = store(mutableListOf(), memo)
        assertEquals("cachedContents/c1", after.peek(full))
        assertEquals("cachedContents/c2", after.peek(chat))
    }

    @Test
    fun `a set that cannot be cached does not hold back the other`() {
        val creates = mutableListOf<Int>()
        val s = store(creates, chatFails = true)
        assertNull(s.peek(chat))
        // Inside the chat set's backoff: no second POST for it.
        assertNull(s.peek(chat))
        assertEquals(listOf(1), creates)
        // The full set is not in that backoff.
        assertNull(s.peek(full))
        assertEquals(listOf(1, 2), creates)
        assertEquals("cachedContents/c2", s.peek(full))
    }

    @Test
    fun `invalidating one name leaves the other set's cache`() {
        val s = store(mutableListOf())
        s.warm(full)
        s.warm(chat)
        s.invalidate("cachedContents/c1")
        assertNull(s.peek(full, create = false))
        assertEquals("cachedContents/c2", s.peek(chat))
    }
}
