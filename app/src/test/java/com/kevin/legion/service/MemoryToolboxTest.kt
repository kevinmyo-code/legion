package com.kevin.legion.service

import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.memory
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryWording
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 09: the `memory` tool's words for each action, success and failure.
 * Robolectric for `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryToolboxTest {

    private val context = RuntimeEnvironment.getApplication()
    private val offline = MemoryOutcome.Unreachable("No engine address saved on this device yet - nothing was sent.")

    private fun install(backend: FakeMemberMemoryBackend, cache: InMemoryMemoryCache = InMemoryMemoryCache()) {
        MemoryToolbox.controllerFactory = { MemoryController(backend, cache, now = { NOW }) }
    }

    private fun call(args: String, touched: Boolean = false): JSONObject = runBlocking {
        MemoryToolbox.dispatch(context, "memory", JSONObject(args), touched)!!
    }

    @After
    fun reset() {
        MemoryToolbox.controllerFactory = MemoryController::forContext
    }

    @Test
    fun `another tool's name falls through`() = runBlocking {
        assertEquals(null, MemoryToolbox.dispatch(context, "books", JSONObject(), false))
    }

    @Test
    fun `recall carries ids, the engine as source and the use note`() {
        install(FakeMemberMemoryBackend(mutableListOf(memory("m1", "Wife is Mia, she likes orchids"))))
        val out = call("""{"action":"recall","query":"wife gift"}""")
        assertTrue(out.getBoolean("success"))
        assertEquals("engine", out.getString("source"))
        assertEquals("m1", out.getJSONArray("memories").getJSONObject(0).getString("id"))
        assertTrue(out.getJSONArray("memories").getJSONObject(0).getString("said").contains("orchids"))
        assertEquals(LiveToolbox.RECALL_USE_NOTE, out.getString("how_to_use"))
    }

    @Test
    fun `recall offline says it is the phone's copy`() {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("m1", "Gym is on Westheimer")))
        val cache = InMemoryMemoryCache()
        install(backend, cache)
        runBlocking { MemoryController(backend, cache, now = { NOW }).refresh() }
        backend.failWith = offline
        val out = call("""{"action":"recall","query":"gym"}""")
        assertTrue(out.getBoolean("success"))
        assertEquals("phone_cache", out.getString("source"))
        assertTrue(out.getString("note").contains("phone's copy"))
    }

    @Test
    fun `an empty recall says so and asks, never guesses`() {
        install(FakeMemberMemoryBackend(mutableListOf(memory("m1", "Gym is on Westheimer"))))
        val out = call("""{"action":"recall","query":"orchids"}""")
        assertEquals(0, out.getInt("count"))
        assertEquals(MemoryToolbox.RECALL_EMPTY_NOTE, out.getString("message"))
    }

    @Test
    fun `remember after a read-through tool is refused before anything is sent`() {
        val backend = FakeMemberMemoryBackend()
        install(backend)
        val out = call("""{"action":"remember","text":"the flight is at 6"}""", touched = true)
        assertFalse(out.getBoolean("success"))
        assertEquals(LiveToolbox.REMEMBER_MAIL_REFUSAL, out.getString("message"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `remember saves with source phone and says private`() {
        val backend = FakeMemberMemoryBackend()
        install(backend)
        val out = call("""{"action":"remember","text":"XJ takes 5W-30"}""")
        assertTrue(out.getBoolean("success"))
        assertEquals("Saved. It is private to you.", out.getString("message"))
        assertTrue(out.getString("id").isNotBlank())
        assertEquals("create:phone:false", backend.calls.first())
    }

    @Test
    fun `remember offline is refused in words`() {
        install(FakeMemberMemoryBackend().apply { failWith = offline })
        val out = call("""{"action":"remember","text":"XJ takes 5W-30"}""")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("nothing was saved"))
    }

    @Test
    fun `an identity number is pointed to the vault`() {
        install(
            FakeMemberMemoryBackend().apply {
                failWritesWith = MemoryOutcome.Refused("Nothing was saved. They belong in the ID vault.", 400)
            },
        )
        val out = call("""{"action":"remember","text":"my passport is E1234567"}""")
        assertFalse(out.getBoolean("success"))
        assertEquals(MemoryWording.VAULT_REDIRECT, out.getString("message"))
    }

    @Test
    fun `forget says deleted for good only when the engine deleted it`() {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("m1", "x")))
        install(backend)
        val done = call("""{"action":"forget","id":"m1"}""")
        assertTrue(done.getBoolean("success"))
        assertEquals("Deleted for good.", done.getString("message"))

        val missing = call("""{"action":"forget","id":"m1"}""")
        assertFalse(missing.getBoolean("success"))
        assertTrue(missing.getString("message").contains("nothing was deleted"))

        val blank = call("""{"action":"forget"}""")
        assertFalse(blank.getBoolean("success"))
        assertTrue(blank.getString("message").startsWith("Nothing was deleted"))
    }

    @Test
    fun `list caps what it reads out and points at the screen`() {
        val many = (1..25).map { memory("m$it", "memory $it", createdAtMs = NOW - it * 1000L) }.toMutableList()
        install(FakeMemberMemoryBackend(many))
        val out = call("""{"action":"list"}""")
        assertEquals(25, out.getInt("count"))
        assertEquals(20, out.getJSONArray("memories").length())
        assertTrue(out.getString("message").contains("Memory screen"))
    }

    @Test
    fun `an unknown action does nothing and says so`() {
        install(FakeMemberMemoryBackend())
        val out = call("""{"action":"dance"}""")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Nothing was done"))
    }
}
