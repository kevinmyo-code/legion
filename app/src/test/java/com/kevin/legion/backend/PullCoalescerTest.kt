package com.kevin.legion.backend

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PullCoalescer] - the mechanism that keeps [EventsRealtime] from turning a burst of
 * `postgres_changes` events into a storm of concurrent pulls. Pure coroutine logic, no
 * Robolectric, no Realtime types, no network - see [PullCoalescer]'s own class doc for why it is
 * built this way.
 *
 * **Constructed with `this` (the [kotlinx.coroutines.test.TestScope] itself), never
 * `backgroundScope` - traced, not a stylistic choice.** [kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle]'s
 * own impl stops the instant no FOREGROUND-tagged event remains queued
 * (`events.none(TestDispatchEvent::isForeground)`); a coroutine launched on `backgroundScope` is
 * tagged background specifically so ordinary test bodies are never forced to wait for
 * long-lived infrastructure, which means `advanceUntilIdle()` can return with that coroutine
 * still sitting unrun in the queue - confirmed empirically in this session (a bare
 * `backgroundScope.launch { counter++ }; advanceUntilIdle()` left the counter at 0). `this` is a
 * foreground scope, so a coroutine launched on it is exactly what `advanceUntilIdle()` promises
 * to run to completion.
 */
class PullCoalescerTest {

    @Test
    fun `a single trigger runs exactly one pull`() = runTest {
        val pullCalls = AtomicInteger(0)
        val coalescer = PullCoalescer(this) { pullCalls.incrementAndGet() }

        coalescer.trigger()
        advanceUntilIdle()

        assertEquals(1, pullCalls.get())
    }

    @Test
    fun `a burst of triggers while a pull is already running never starts a concurrent second pull`() = runTest {
        val pullCalls = AtomicInteger(0)
        val firstPullStarted = CompletableDeferred<Unit>()
        val releaseFirstPull = CompletableDeferred<Unit>()
        val maxConcurrentPulls = AtomicInteger(0)
        val currentlyRunning = AtomicInteger(0)

        val coalescer = PullCoalescer(this) {
            val nowRunning = currentlyRunning.incrementAndGet()
            maxConcurrentPulls.updateAndGet { current -> maxOf(current, nowRunning) }
            val callNumber = pullCalls.incrementAndGet()
            if (callNumber == 1) {
                firstPullStarted.complete(Unit)
                releaseFirstPull.await()
            }
            currentlyRunning.decrementAndGet()
        }

        // One trigger starts the first, deliberately held-open pull.
        coalescer.trigger()
        firstPullStarted.await()

        // A burst of MANY more triggers arrives while that first pull is still running - a storm,
        // by construction. Every one of them must be coalesced, never each starting its own pull.
        repeat(20) { coalescer.trigger() }
        releaseFirstPull.complete(Unit)
        advanceUntilIdle()

        // Never more than one pull in flight at any instant - the "not a storm" guarantee stated
        // literally: however many triggers arrived, at most one pull ever ran concurrently.
        assertEquals(1, maxConcurrentPulls.get())
        // Coalesced into exactly one extra pull to pick up whatever the burst represented - not
        // 20 more, and not zero (a burst arriving mid-pull must not be silently dropped).
        assertEquals(2, pullCalls.get())
    }

    @Test
    fun `triggers after a pull has fully finished each start their own pull`() = runTest {
        val pullCalls = AtomicInteger(0)
        val coalescer = PullCoalescer(this) { pullCalls.incrementAndGet() }

        coalescer.trigger()
        advanceUntilIdle()
        coalescer.trigger()
        advanceUntilIdle()
        coalescer.trigger()
        advanceUntilIdle()

        assertEquals(3, pullCalls.get())
    }
}
