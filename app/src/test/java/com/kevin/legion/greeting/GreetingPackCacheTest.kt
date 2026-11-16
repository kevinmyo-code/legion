package com.kevin.legion.greeting

import com.kevin.legion.greeting.GreetingTestSupport.facts
import com.kevin.legion.greeting.GreetingTestSupport.item
import com.kevin.legion.greeting.GreetingTestSupport.pack
import com.kevin.legion.greeting.GreetingTestSupport.parsed
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack is fetched ahead of the tap and kept (2026-11-15: every open on the 11-15 phone logs fell
 * back with `pack_timeout`, the engine answering in 1.6 to 2.0 s against a 1.5 s budget).
 */
class GreetingPackCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packDay = LocalDate.of(2026, 11, 13)
    private val nine = OffsetDateTime.parse("2026-11-13T09:00:00-06:00").toInstant().toEpochMilli()
    private var now = nine
    private val good = parsed(pack(due = facts(item("due:1", "Essay is due Friday, 5:00 PM."))))

    private fun cache(backend: GreetingBackend, store: PackStore = PackStore.None, day: LocalDate = packDay) =
        GreetingPackCache(backend, scope, store, clock = { now }, today = { day })

    @Test fun `nothing is usable before a fetch, and a fetched pack is`() = runBlocking {
        val c = cache(FakeGreetingBackend { GreetingOutcome.Ok(good) })
        assertNull(c.usable())
        c.refresh("start").await()
        assertEquals(nine, c.usable()!!.fetchedAtMs)
    }

    @Test fun `a pack past the age limit or from another day is not used`() = runBlocking {
        val c = cache(FakeGreetingBackend { GreetingOutcome.Ok(good) })
        c.refresh("start").await()
        now = nine + GreetingPackCache.MAX_AGE_MS + 1
        assertNull(c.usable())
        val tomorrow = cache(FakeGreetingBackend { GreetingOutcome.Ok(good) }, day = packDay.plusDays(1))
        tomorrow.refresh("start").await()
        assertNull(tomorrow.usable())
    }

    @Test fun `a failed fetch keeps the last good pack`() = runBlocking {
        var fail = false
        val c = cache(
            FakeGreetingBackend { if (fail) GreetingOutcome.Unreachable("down") else GreetingOutcome.Ok(good) },
        )
        c.refresh("start").await()
        fail = true
        now += GreetingPackCache.MIN_GAP_MS + 1
        assertTrue(c.refresh("timer").await() is GreetingOutcome.Unreachable)
        assertNotNull(c.usable())
    }

    @Test fun `refreshes close together are one fetch`() = runBlocking {
        val calls = AtomicInteger()
        val c = cache(
            FakeGreetingBackend {
                calls.incrementAndGet()
                delay(100)
                GreetingOutcome.Ok(good)
            },
        )
        val a = c.refresh("onset")
        val b = c.refresh("tap")
        a.await()
        b.await()
        c.refresh("tap").await() // inside MIN_GAP_MS: the pack in hand
        c.prefetch("close") // younger than PREFETCH_FRESH_MS: nothing
        delay(50)
        assertEquals(1, calls.get())
    }

    @Test fun `an event that has started since the fetch is dropped from a kept pack`() = runBlocking {
        val ahead = """{"topic":"event:1","says":"Team sync at 10:00 AM.","raised":false,""" +
            """"starts_at":"2026-11-13T10:00:00-06:00"}"""
        val withEvent = parsed(pack(today = facts(ahead), due = facts(item("due:1", "Essay is due Friday."))))
        val c = cache(FakeGreetingBackend { GreetingOutcome.Ok(withEvent) })
        c.refresh("start").await()
        val today = { p: FactsPack -> p.sources.first { it.name == "today_events" } }
        assertEquals(1, today(c.usable()!!.pack).items.size)
        val later = withEvent.asOf(nine + 61 * 60_000L) // 10:01: the sync has started

        assertEquals(SourceState.EMPTY, today(later).state)
        assertEquals(FactsPack.PASSED_SAYS, today(later).says)
        assertEquals(1, later.sources.first { it.name == "due_soon" }.items.size)
    }

    @Test fun `a topic raised since the fetch is not offered again`() = runBlocking {
        val c = cache(FakeGreetingBackend { GreetingOutcome.Ok(good) })
        c.refresh("start").await()
        c.noteRaised("due:1")
        assertTrue("due:1" in c.usable()!!.pack.said)
        val plan = GreetingOpening(FakeGreetingBackend { GreetingOutcome.Ok(good) }, GreetingTestSupport.homeOnly,
            scope, cache = c, clock = { now }).prepare(LocalTime.of(9, 0))
        assertEquals("nothing_to_say", (plan as GreetingPlan.Scripted).reason)
    }

    @Test fun `the kept pack survives the process through its store`() = runBlocking {
        val file = File.createTempFile("pack", ".json").also { it.delete() }
        try {
            val first = cache(FakeGreetingBackend { GreetingOutcome.Ok(good) }, FilePackStore(file))
            first.refresh("start").await()
            val restarted = cache(FakeGreetingBackend { GreetingOutcome.Unreachable("down") }, FilePackStore(file))
            val kept = restarted.usable()
            assertEquals(nine, kept!!.fetchedAtMs)
            assertEquals("due:1", kept.pack.sources.first { it.name == "due_soon" }.items.single().topic)
        } finally {
            file.delete()
        }
    }

    @Test fun `a tap with a kept pack greets at once though the engine is slow, and logs the source`() = runBlocking {
        val lines = mutableListOf<String>()
        val slow = FakeGreetingBackend {
            delay(5_000)
            GreetingOutcome.Ok(good)
        }
        now += GreetingPackCache.MIN_GAP_MS + 1 // the kept pack is 30 s old
        // The kept pack comes from the store, as after an app restart; the tap's refresh is slow.
        val file = File.createTempFile("pack", ".json").also { it.delete() }
        FilePackStore(file).save(good.raw!!, nine)
        val seeded = GreetingPackCache(slow, scope, FilePackStore(file), clock = { now }, today = { packDay })
        val started = System.currentTimeMillis()
        val plan = GreetingOpening(slow, GreetingTestSupport.homeOnly, scope, packBudgetMs = 1_500L, cache = seeded,
            log = { lines += it }, clock = { now }).prepare(LocalTime.of(9, 0))
        file.delete()
        assertTrue(plan is GreetingPlan.Personal)
        assertTrue("took ${System.currentTimeMillis() - started}", System.currentTimeMillis() - started < 1_000)
        assertTrue(lines.toString(), lines.any { it.startsWith("greeting_pack source=cache age_s=30") })
    }

    @Test fun `a tap with nothing kept and a slow engine logs none and falls back at the budget`() = runBlocking {
        val lines = mutableListOf<String>()
        val slow = FakeGreetingBackend {
            delay(5_000)
            GreetingOutcome.Ok(good)
        }
        val plan = GreetingOpening(slow, GreetingTestSupport.homeOnly, scope, packBudgetMs = 150L,
            cache = cache(slow), log = { lines += it }, clock = { now }).prepare(LocalTime.of(9, 0))
        assertEquals("pack_timeout", (plan as GreetingPlan.Scripted).reason)
        assertEquals(listOf("greeting_pack source=none age_s=-1"), lines)
    }

    @Test fun `a tap with nothing kept and a quick engine logs fresh`() = runBlocking {
        val lines = mutableListOf<String>()
        val backend = FakeGreetingBackend { GreetingOutcome.Ok(good) }
        val plan = GreetingOpening(backend, GreetingTestSupport.homeOnly, scope, cache = cache(backend),
            log = { lines += it }, clock = { now }).prepare(LocalTime.of(9, 0))
        assertTrue(plan is GreetingPlan.Personal)
        assertEquals(listOf("greeting_pack source=fresh age_s=0"), lines)
    }
}
