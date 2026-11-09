package com.kevin.legion.advisor

import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.memory.FakeMemberMemoryBackend
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.NOW
import com.kevin.legion.memory.FakeMemberMemoryBackend.Companion.memory
import com.kevin.legion.memory.InMemoryMemoryCache
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Goal-plan constraints on member memory (2026-11-09): reads, offline fallbacks, the one-time backfill. */
class GoalPlanConstraintsTest {

    private val prefix = GoalPlanAgent.CONSTRAINT_PREFIX
    private val offline = MemoryOutcome.Unreachable("No engine address saved on this device yet - nothing was sent.")

    private fun legacy(text: String, createdAt: Long = 1_000L, syncId: String = "sync-$text") = CompanionMemory(
        vehicleId = "car-a",
        text = prefix + text,
        category = CompanionMemory.Category.DRIVER,
        source = CompanionMemory.Source.STATED,
        createdAt = createdAt,
        syncId = syncId,
    )

    private class Flag(var set: Boolean = false)

    private fun constraints(
        engine: FakeMemberMemoryBackend,
        rows: List<CompanionMemory> = emptyList(),
        cache: InMemoryMemoryCache = InMemoryMemoryCache(),
        flag: Flag = Flag(),
    ) = GoalPlanConstraints(
        controller = MemoryController(engine, cache, now = { NOW }),
        legacyRows = { rows },
        isBackfilled = { flag.set },
        markBackfilled = { flag.set = true },
    )

    @Test
    fun `reads only this member's constraint memories, oldest first, prefix stripped`() = runBlocking {
        val engine = FakeMemberMemoryBackend(
            mutableListOf(
                memory("b", prefix + "no gym access", createdAtMs = NOW - 1_000),
                memory("a", prefix + "vegetarian", createdAtMs = NOW - 5_000),
                memory("c", "Wife is Mia"),
                memory("d", prefix + "Mia's own constraint", aboutYou = false),
            ),
        )
        assertEquals(listOf("vegetarian", "no gym access"), constraints(engine).read())
    }

    @Test
    fun `offline it reads the phone's cached copy`() = runBlocking {
        val engine = FakeMemberMemoryBackend(mutableListOf(memory("a", prefix + "no gym access")))
        val cache = InMemoryMemoryCache()
        MemoryController(engine, cache, now = { NOW }).refresh()
        engine.failWith = offline
        assertEquals(listOf("no gym access"), constraints(engine, cache = cache, flag = Flag(true)).read())
    }

    @Test
    fun `offline with no cache it falls back to the legacy rows, so a plan still has them`() = runBlocking {
        val engine = FakeMemberMemoryBackend().apply { failWith = offline }
        assertEquals(listOf("vegetarian"), constraints(engine, rows = listOf(legacy("vegetarian"))).read())
    }

    @Test
    fun `the backfill uploads legacy rows once and only marks done when the engine took them`() = runBlocking {
        val engine = FakeMemberMemoryBackend().apply { failWith = offline }
        val flag = Flag()
        val subject = constraints(engine, rows = listOf(legacy("vegetarian")), flag = flag)

        subject.read()
        assertFalse("a failed upload is retried next time", flag.set)

        engine.failWith = null
        subject.read()
        subject.read()
        assertTrue(flag.set)
        assertEquals(1, engine.imported.size)
        assertEquals("sync-vegetarian", engine.imported.single().originGuid)
        assertEquals(listOf("vegetarian"), subject.read())
    }

    @Test
    fun `add saves once, privately, and refuses in words offline`() = runBlocking {
        val engine = FakeMemberMemoryBackend()
        val subject = constraints(engine, flag = Flag(true))
        assertNull(subject.add("no gym access"))
        assertNull(subject.add("  NO GYM ACCESS "))
        assertEquals(1, engine.store.size)
        assertEquals(prefix + "no gym access", engine.store.single().text)
        assertTrue("create:phone:false" in engine.calls)

        engine.failWith = offline
        assertTrue(subject.add("vegetarian")!!.contains("nothing was saved"))
    }
}
