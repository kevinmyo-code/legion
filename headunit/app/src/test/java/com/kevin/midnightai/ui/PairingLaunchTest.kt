package com.kevin.midnightai.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review P1 (recheck c53f1fc3): the driver backs out while the pairing server is being built. The
 * build cannot be interrupted, so whatever it made after the screen left must be closed, never
 * left listening.
 */
class PairingLaunchTest {
    private class Thing { val closed = AtomicInteger() }

    @Test fun p1_aServerBuiltAfterTheScreenLeftIsClosedNotLeaked() = runBlocking {
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val made = Thing()
        var handedOver = false
        val job = launch(Dispatchers.Default) {
            PairingLaunch.build(Dispatchers.IO, discard = { it.closed.incrementAndGet() }) {
                building.countDown()
                release.await(5, TimeUnit.SECONDS)
                made
            }
            handedOver = true
        }
        assertTrue("build never started", building.await(5, TimeUnit.SECONDS))
        job.cancel() // the driver pressed Back during "Starting..."
        release.countDown()
        job.join()
        Thread.sleep(200) // the IO block finishes on its own thread
        assertEquals("handed to a screen that is gone", false, handedOver)
        assertEquals("built server left open", 1, made.closed.get())
    }

    @Test fun aNormalBuildIsHandedOverAndNotClosed() = runBlocking {
        val made = Thing()
        val got = PairingLaunch.build(Dispatchers.IO, discard = { it.closed.incrementAndGet() }) { made }
        assertTrue(got === made)
        assertEquals(0, made.closed.get())
    }
}
