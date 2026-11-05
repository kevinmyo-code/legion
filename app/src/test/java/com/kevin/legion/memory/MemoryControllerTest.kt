package com.kevin.legion.memory

import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.HOUR
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.memory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory-and-vault ticket 09: the phone reads and writes memory on the engine, keeps a whole-list
 * cache, answers offline from that cache and says so, and refuses writes offline in words.
 */
class MemoryControllerTest {

    private val offline = MemoryOutcome.Unreachable("No engine address saved on this device yet - nothing was sent.")

    private fun controller(backend: FakeMemberMemoryBackend, cache: MemoryCache = InMemoryMemoryCache()) =
        MemoryController(backend, cache, now = { NOW })

    @Test
    fun `recall online is the engine's answer, not the cache`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "Jeep needs an LS swap eventually")))
        val outcome = controller(backend).recall("jeep swap")
        val read = (outcome as MemoryOutcome.Ok).value
        assertFalse(read.fromCache)
        assertEquals(listOf("a"), read.memories.map { it.id })
        assertEquals(listOf("recall:jeep swap"), backend.calls)
    }

    @Test
    fun `recall offline answers from the cache and says it is the phone's copy`() = runBlocking {
        val backend = FakeMemberMemoryBackend(
            mutableListOf(memory("a", "Jeep needs an LS swap"), memory("b", "Likes flat whites")),
        )
        val cache = InMemoryMemoryCache()
        val c = controller(backend, cache)
        c.refresh()
        backend.failWith = offline
        val read = (c.recall("coffee flat whites") as MemoryOutcome.Ok).value
        assertTrue(read.fromCache)
        assertEquals(listOf("b"), read.memories.map { it.id })
        assertEquals(NOW, read.cachedAtMs)
        assertTrue(MemoryWording.cachedNote(read).contains("phone's copy"))
    }

    @Test
    fun `recall offline with no cache is unreachable, never an empty answer`() = runBlocking {
        val backend = FakeMemberMemoryBackend().apply { failWith = offline }
        val outcome = controller(backend).recall("anything")
        assertTrue(outcome is MemoryOutcome.Unreachable)
        assertTrue((outcome as MemoryOutcome.Unreachable).sentence.contains("no copy"))
    }

    @Test
    fun `a refusal is not answered from the cache`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "x")))
        val c = controller(backend)
        c.refresh()
        backend.failWith = MemoryOutcome.Refused("Nothing was read or written. This is a recruiter's demo visit.", 403)
        assertTrue(c.recall("x") is MemoryOutcome.Refused)
    }

    @Test
    fun `the cache is replaced whole, so a hard delete elsewhere disappears from it`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "one"), memory("b", "two")))
        val cache = InMemoryMemoryCache()
        val c = controller(backend, cache)
        c.refresh()
        assertEquals(setOf("a", "b"), cache.read()!!.memories.map { it.id }.toSet())
        backend.store.removeAll { it.id == "a" } // forgotten on the web, no tombstone
        c.refresh()
        assertEquals(listOf("b"), cache.read()!!.memories.map { it.id })
    }

    @Test
    fun `remember offline is refused, nothing is queued`() = runBlocking {
        val backend = FakeMemberMemoryBackend().apply { failWith = offline }
        val c = controller(backend)
        val outcome = c.remember("Tire pressure is 35 psi")
        assertTrue(outcome is MemoryOutcome.Unreachable)
        assertTrue(MemoryWording.writeFailed(outcome, "saved").contains("nothing was saved"))
        backend.failWith = null
        c.refresh()
        assertTrue(backend.store.isEmpty())
    }

    @Test
    fun `blank and over-long text are refused before anything is sent`() = runBlocking {
        val backend = FakeMemberMemoryBackend()
        val c = controller(backend)
        assertTrue(c.remember("   ") is MemoryOutcome.Refused)
        assertTrue(c.remember("x".repeat(MemoryController.TEXT_MAX + 1)) is MemoryOutcome.Refused)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `remember sends the source and refreshes the cache`() = runBlocking {
        val backend = FakeMemberMemoryBackend()
        val cache = InMemoryMemoryCache()
        val saved = controller(backend, cache).remember("Gym is on Westheimer", shared = true, source = "manual")
        assertTrue(saved is MemoryOutcome.Ok)
        assertEquals("create:manual:true", backend.calls.first())
        assertEquals(listOf("Gym is on Westheimer"), cache.read()!!.memories.map { it.text })
    }

    @Test
    fun `forget is said done only from the engine and the cache loses the row`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "one"), memory("b", "two")))
        val cache = InMemoryMemoryCache()
        val c = controller(backend, cache)
        c.refresh()
        assertTrue(c.forget("a") is MemoryOutcome.Ok)
        assertEquals(listOf("b"), cache.read()!!.memories.map { it.id })

        val missing = c.forget("zzz")
        assertTrue(missing is MemoryOutcome.Refused)
        assertTrue(MemoryWording.writeFailed(missing, "deleted").contains("nothing was deleted"))
    }

    @Test
    fun `forget offline deletes nothing, even from the cache`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "one")))
        val cache = InMemoryMemoryCache()
        val c = controller(backend, cache)
        c.refresh()
        backend.failWith = offline
        assertTrue(c.forget("a") is MemoryOutcome.Unreachable)
        assertEquals(listOf("a"), cache.read()!!.memories.map { it.id })
    }

    @Test
    fun `only a memory about you can be shared or made private`() = runBlocking {
        val theirs = memory("a", "Mia's birthday is in May", aboutYou = false, shared = true)
        val backend = FakeMemberMemoryBackend(mutableListOf(theirs))
        val c = controller(backend)
        assertTrue(c.setShared(backend.store[0], shared = false) is MemoryOutcome.Refused)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `the vault filter's refusal is said as belonging in the ID vault`() {
        val refused = MemoryOutcome.Refused(
            "Nothing was saved. That looks like an identity or bank number (a passport, NRIC, SSN, A-number or " +
                "account number), and those are never kept in memory: they belong in the ID vault on the phone, " +
                "behind the fingerprint.",
            400,
        )
        assertEquals(MemoryWording.VAULT_REDIRECT, MemoryWording.writeFailed(refused, "saved"))
        assertTrue(MemoryWording.VAULT_REDIRECT.contains("belongs in the ID vault"))
    }

    @Test
    fun `continuity ranks the engine's list on the phone and never recalls, so nothing is rehearsed`() = runBlocking {
        val backend = FakeMemberMemoryBackend(
            mutableListOf(
                memory("old", "Old trivia", importance = 1, decayClass = "episodic", createdAtMs = NOW - 500 * HOUR),
                memory("big", "Wife is Mia", importance = 9),
            ),
        )
        val picked = controller(backend).continuity(limit = 1)
        assertEquals(listOf("big"), picked.map { it.id })
        assertTrue(backend.calls.none { it.startsWith("recall") })
    }

    @Test
    fun `continuity with the engine down uses the cache it has`() = runBlocking {
        val backend = FakeMemberMemoryBackend(mutableListOf(memory("a", "one")))
        val cache = InMemoryMemoryCache()
        controller(backend, cache).refresh()
        backend.failWith = offline
        val later = MemoryController(backend, cache, now = { NOW + 3_600_000L })
        assertEquals(listOf("a"), later.continuity().map { it.id })
    }

    @Test
    fun `a distilled memory says it came from a conversation`() {
        val m = memory("a", "x", source = "consolidation", fromConversation = "log-1")
        assertTrue(MemoryWording.origin(m).startsWith("from a conversation on "))
        assertNull(memory("b", "y").fromConversation)
    }
}
