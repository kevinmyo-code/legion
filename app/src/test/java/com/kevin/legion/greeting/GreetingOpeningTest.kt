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

    @Test fun `nothing offerable is the scripted line, not a model call about nothing`() = runBlocking {
        val spent = parsed(pack(due = facts(item("due:1", "x")), said = listOf("due:1")))
        val plan = opening(FakeGreetingBackend { GreetingOutcome.Ok(spent) }).prepare(morning)
        assertEquals("nothing_to_say", (plan as GreetingPlan.Scripted).reason)
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
