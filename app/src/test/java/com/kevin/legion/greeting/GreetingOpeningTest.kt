package com.kevin.legion.greeting

import com.kevin.legion.greeting.GreetingTestSupport.facts
import com.kevin.legion.greeting.GreetingTestSupport.item
import com.kevin.legion.greeting.GreetingTestSupport.pack
import com.kevin.legion.greeting.GreetingTestSupport.parsed
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingOpeningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val morning = LocalTime.of(8, 0)
    private val atHome = GreetingTestSupport.signals(atHome = true)

    private fun opening(backend: FakeGreetingBackend, budget: Long = 500L, place: PlaceSignals = atHome) =
        GreetingOpening(backend, place, scope, budget)

    private val good = parsed(pack(due = facts(item("due:1", "Essay is due Friday, 5:00 PM."))))

    @Test fun `a pack with a topic makes a personal greeting leading with it`() = runBlocking {
        val plan = opening(FakeGreetingBackend { GreetingOutcome.Ok(good) }).prepare(morning)
        plan as GreetingPlan.Personal
        assertEquals("due:1", plan.topic)
        assertTrue(plan.prompt.contains("Essay is due Friday, 5:00 PM."))
    }

    @Test fun `unreachable, route missing, refused and undecodable all fall back to the scripted line`() = runBlocking {
        val outcomes = listOf(
            GreetingOutcome.Unreachable("no route to host"),
            GreetingOutcome.Refused("no route", 404),
            GreetingOutcome.Refused("recruiter", 403),
            GreetingOutcome.Unconfirmed("did not decode"),
        )
        for (o in outcomes) {
            val plan = opening(FakeGreetingBackend { o }).prepare(morning)
            assertTrue("$o -> $plan", plan is GreetingPlan.Scripted)
        }
    }

    @Test fun `a slow engine falls back at the budget, not after it`() = runBlocking {
        val started = System.currentTimeMillis()
        val slow = FakeGreetingBackend {
            delay(5_000)
            GreetingOutcome.Ok(good)
        }
        val plan = opening(slow, budget = 150L).prepare(morning)
        val took = System.currentTimeMillis() - started
        assertEquals("pack_timeout", (plan as GreetingPlan.Scripted).reason)
        assertTrue("took $took", took < 2_000)
    }

    @Test fun `a throwing engine call is a fallback, not a crash`() = runBlocking {
        val plan = opening(FakeGreetingBackend { error("boom") }).prepare(morning)
        assertTrue(plan is GreetingPlan.Scripted)
    }

    @Test fun `nothing offerable is a hello about nothing, with no facts handed over`() = runBlocking {
        val spent = parsed(pack(due = facts(item("due:1", "Essay is due Friday.")), said = listOf("due:1")))
        val plan = opening(FakeGreetingBackend { GreetingOutcome.Ok(spent) }).prepare(morning)
        plan as GreetingPlan.Hello
        assertEquals(GreetingPrompt.hello(), plan.prompt)
        assertFalse(plan.prompt.contains("Essay"))
    }

    @Test fun `a spoken hello records no topic on the engine, a personal greeting records its own`() = runBlocking {
        val backend = FakeGreetingBackend { GreetingOutcome.Ok(good) }
        val o = opening(backend)
        o.noteOpened(GreetingPlan.Hello(GreetingPrompt.hello()))
        o.noteOpened(GreetingPlan.Personal("p", "due:1"))
        repeat(50) { if (backend.recorded.isEmpty()) delay(20) }
        delay(50)
        assertEquals(listOf("due:1" to "raised"), backend.recorded)
    }

    @Test fun `the next hello is asked not to reuse the last one's words`() = runBlocking {
        val spent = parsed(pack(due = facts(item("due:1", "Essay is due Friday.")), said = listOf("due:1")))
        val o = opening(FakeGreetingBackend { GreetingOutcome.Ok(spent) })
        val first = o.prepare(morning) as GreetingPlan.Hello
        o.noteOpened(first, "An invented hello line.")
        val second = o.prepare(morning) as GreetingPlan.Hello
        assertTrue(second.prompt, second.prompt.contains("\"An invented hello line.\""))
    }

    @Test fun `a money lead today leaves the next open without money`() = runBlocking {
        // Invented: two budgets over their caps and nothing else.
        val money = parsed(
            pack(
                money = facts(
                    item("money:2026-11:Fuel", "Fuel: \$300.00 of \$200.00 spent. Over the cap."),
                    item("money:2026-11:Hobbies", "Hobbies: \$90.00 of \$60.00 spent. Over the cap."),
                ),
            ),
        )
        val o = opening(FakeGreetingBackend { GreetingOutcome.Ok(money) })
        val first = o.prepare(morning) as GreetingPlan.Personal
        assertEquals("money:2026-11:Fuel", first.topic)
        o.noteOpened(first)
        assertTrue(o.prepare(morning) is GreetingPlan.Hello)
    }

    @Test fun `a throwing place read does not stop the greeting and is said as unreadable`() = runBlocking {
        val broken = object : PlaceSignals {
            override fun fix(): Pair<Double, Double>? = error("no provider")
            override suspend fun area(latitude: Double, longitude: Double) = AreaLookup.NotFound
            override suspend fun atHome(): Boolean? = null
        }
        val plan = opening(FakeGreetingBackend { GreetingOutcome.Ok(good) }, place = broken).prepare(morning)
        plan as GreetingPlan.Personal
        assertTrue(plan.prompt.contains("The place could not be read"))
    }

    @Test fun `recording a topic is fire and forget`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeGreetingBackend { GreetingOutcome.Ok(good) }.also { it.recordGate = gate }
        val started = System.currentTimeMillis()
        opening(backend).noteRaised("due:1") // returns while the POST is still blocked
        assertTrue(System.currentTimeMillis() - started < 500)
        assertTrue(backend.recorded.isEmpty())
        gate.complete(Unit)
        repeat(50) { if (backend.recorded.isEmpty()) delay(20) }
        assertEquals(listOf("due:1" to "raised"), backend.recorded)
    }

    @Test fun `a failing record call is swallowed`() = runBlocking {
        val backend = object : GreetingBackend {
            override suspend fun facts() = GreetingOutcome.Ok(good)
            override suspend fun recordTopic(topicKey: String, state: String): Boolean = error("network down")
        }
        opening(FakeGreetingBackend { GreetingOutcome.Ok(good) }).noteRaised("x")
        GreetingOpening(backend, atHome, scope).noteRaised("due:1")
        delay(100) // reaching here without the scope's handler tearing the test down is the assertion
    }
}
