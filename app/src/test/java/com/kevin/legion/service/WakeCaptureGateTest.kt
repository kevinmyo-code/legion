package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash records 2026-11-10: a wake capture's teardown ran while another thread was opening one or
 * still inside native work, freeing the recognizer under it (SIGSEGV in "wake-two-stage") or the
 * AudioRecord between its state check and startRecording (an uncaught IllegalStateException). The
 * gate's rules, without a microphone. What only a phone run can show: that the engine's real
 * threads go through the gate everywhere they touch native code.
 */
class WakeCaptureGateTest {

    @Test
    fun `a chunk runs for the open capture and never after it is closed`() {
        val gate = WakeCaptureGate()
        val ticket = gate.open()
        var ran = 0
        assertTrue(gate.whileOpen(ticket) { ran++ })
        gate.close()
        assertFalse(gate.whileOpen(ticket) { ran++ })
        assertEquals(1, ran)
    }

    @Test
    fun `a new open closes every older capture`() {
        val gate = WakeCaptureGate()
        val old = gate.open()
        val fresh = gate.open()
        assertFalse(gate.whileOpen(old) { error("an old capture must not run") })
        assertTrue(gate.whileOpen(fresh) {})
    }

    @Test
    fun `close waits for a chunk already doing native work, and none runs after`() {
        val gate = WakeCaptureGate()
        val ticket = gate.open()
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val freedWhileInside = AtomicBoolean(false)
        val chunkDone = AtomicBoolean(false)
        val capture = Thread {
            gate.whileOpen(ticket) {
                inside.countDown()
                finish.await(5, TimeUnit.SECONDS)
                chunkDone.set(true)
            }
        }
        capture.start()
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val closed = CountDownLatch(1)
        val teardown = Thread {
            gate.close()
            // The teardown frees native objects only after close() returns.
            if (!chunkDone.get()) freedWhileInside.set(true)
            closed.countDown()
        }
        teardown.start()
        assertFalse("close must wait for the chunk", closed.await(200, TimeUnit.MILLISECONDS))
        finish.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        capture.join(5_000)
        teardown.join(5_000)
        assertFalse("nothing was freed under a running chunk", freedWhileInside.get())
        assertFalse(gate.whileOpen(ticket) { error("a closed capture must not run") })
    }

    @Test
    fun `an open waits for a teardown in progress, so neither sees the other half done`() {
        val gate = WakeCaptureGate()
        val order = mutableListOf<String>()
        val inTeardown = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val teardown = Thread {
            gate.locked {
                inTeardown.countDown()
                letGo.await(5, TimeUnit.SECONDS)
                synchronized(order) { order += "teardown" }
            }
        }
        teardown.start()
        assertTrue(inTeardown.await(5, TimeUnit.SECONDS))
        val opener = Thread { gate.locked { synchronized(order) { order += "open" } } }
        opener.start()
        Thread.sleep(100)
        letGo.countDown()
        teardown.join(5_000)
        opener.join(5_000)
        assertEquals(listOf("teardown", "open"), order)
    }

    @Test
    fun `a chunk that opens the conversation may tear its own capture down`() {
        val gate = WakeCaptureGate()
        val ticket = gate.open()
        // Reentrant: the trigger path closes from inside the chunk without deadlocking.
        assertTrue(gate.whileOpen(ticket) { gate.locked { gate.close() } })
        assertFalse(gate.whileOpen(ticket) {})
    }

    @Test
    fun `a capture that will not open backs off, doubling to a cap`() {
        assertEquals(0, WakeOpenBackoff.ticksToSkip(0))
        assertEquals(listOf(1, 2, 4, 8, 16, 16, 16), (1..7).map { WakeOpenBackoff.ticksToSkip(it) })
    }
}
