package com.kevin.legion.checklists

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Checklist pushes run off the caller's critical path (so an added item shows immediately) but
 * must still reach the engine in write order: create a list then add to it, and the item's push
 * must never overtake the list's. These run against the real worker, not a test scope, because
 * the ordering guarantee lives in the worker.
 */
class ChecklistPushOrderTest {

    @Before
    fun realWorker() {
        ChecklistController.pushScopeOverride = null
    }

    @Test
    fun `pushes run in the order they were enqueued, even when an earlier one is slower`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val done = CompletableDeferred<Unit>()
        for (i in 1..20) {
            ChecklistController.enqueuePush {
                // The first push is the slowest. A bare launch-per-push would let 2..20 finish
                // before it; the single worker must not.
                if (i == 1) delay(200)
                seen += i
                if (i == 20) done.complete(Unit)
            }
        }
        withTimeout(10_000) { done.await() }
        assertEquals((1..20).toList(), seen.toList())
    }

    @Test
    fun `a push that throws does not stop the ones queued after it`() = runBlocking {
        val done = CompletableDeferred<Unit>()
        ChecklistController.enqueuePush { error("engine said no") }
        ChecklistController.enqueuePush { done.complete(Unit) }
        withTimeout(10_000) { done.await() }
    }
}
