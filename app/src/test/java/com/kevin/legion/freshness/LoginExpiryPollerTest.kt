package com.kevin.legion.freshness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginExpiryPollerTest {
    private class MemorySeen(var map: Map<String, String> = emptyMap()) : ExpirySeenStore {
        override fun load() = map
        override fun save(seen: Map<String, String>) {
            map = seen
        }
    }

    private class FakeBackend(var result: Result<List<FeedFreshness>>) : FreshnessBackend {
        override suspend fun feeds() = result
    }

    private val sentence = "Canvas needs you to log in again: run tools/connect_session.py canvas"
    private fun expired(error: String) = FeedFreshness("canvas", "needs_login", error, sentence)
    private val ok = FeedFreshness("canvas", "ok", null, "Canvas is up to date.")

    private val backend = FakeBackend(Result.success(listOf(expired("refused at 21:30"))))
    private val seen = MemorySeen()
    private val raised = mutableListOf<LoginExpiry>()
    private var silenced = false
    private var engine: FreshnessBackend? = backend
    private val poller = LoginExpiryPoller({ engine }, seen, { silenced }, { raised += it })

    @Test
    fun `fires once for needs_login with the server sentence verbatim`() = runBlocking {
        assertEquals(1, poller.pollOnce())
        assertEquals(listOf(LoginExpiry("canvas", "Canvas login expired", sentence)), raised)
    }

    @Test
    fun `no repeat on a second poll with the same last_error`() = runBlocking {
        poller.pollOnce()
        assertEquals(0, poller.pollOnce())
        assertEquals(1, raised.size)
    }

    @Test
    fun `fires again after an ok and then a new needs_login`() = runBlocking {
        poller.pollOnce()
        backend.result = Result.success(listOf(ok))
        poller.pollOnce()
        assertTrue(seen.map.isEmpty())
        // Identical error text: only the cleared key lets it fire again.
        backend.result = Result.success(listOf(expired("refused at 21:30")))
        poller.pollOnce()
        assertEquals(2, raised.size)
    }

    @Test
    fun `a new last_error fires again`() = runBlocking {
        poller.pollOnce()
        backend.result = Result.success(listOf(expired("refused at 23:00")))
        poller.pollOnce()
        assertEquals(2, raised.size)
    }

    @Test
    fun `any source fires, not only canvas`() = runBlocking {
        backend.result = Result.success(listOf(FeedFreshness("webassign", "needs_login", "e", "WebAssign needs you")))
        poller.pollOnce()
        assertEquals("WebAssign login expired", raised.single().title)
        assertEquals("WebAssign needs you", raised.single().body)
    }

    @Test
    fun `silenced posts nothing and leaves the seen set alone`() = runBlocking {
        silenced = true
        assertEquals(0, poller.pollOnce())
        assertTrue(raised.isEmpty())
        assertTrue(seen.map.isEmpty())
        silenced = false
        assertEquals(1, poller.pollOnce())
    }

    @Test
    fun `network failure and no engine post nothing and keep the seen set`() = runBlocking {
        poller.pollOnce()
        backend.result = Result.failure(RuntimeException("offline"))
        assertEquals(0, poller.pollOnce())
        engine = null
        assertEquals(0, poller.pollOnce())
        assertEquals(1, raised.size)
        assertEquals(mapOf("canvas" to "refused at 21:30"), seen.map)
    }
}
