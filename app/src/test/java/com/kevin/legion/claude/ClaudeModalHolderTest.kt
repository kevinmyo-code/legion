package com.kevin.legion.claude

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide holder the Claude task dialog follows (Kevin 2026-11-04): the newest task wins,
 * only Close takes it down, and a dialog nobody saw is dropped after 30 minutes.
 */
class ClaudeModalHolderTest {
    private var clock: Instant = Instant.parse("2026-11-04T18:00:00Z")
    private val memory = InMemoryClaudeModalMemory()
    private fun holder() = ClaudeModalHolder(memory) { clock }

    @Test
    fun `show sets the task, a newer one replaces it, and it is remembered`() {
        val h = holder()
        h.show(41)
        assertEquals(41, h.pending.value?.taskId)
        h.show(42)
        assertEquals(42, h.pending.value?.taskId)
        assertEquals(42, memory.stored?.taskId)
    }

    @Test
    fun `admitted while fresh it is seen and stays past 30 minutes until closed`() {
        val h = holder()
        h.show(41)
        h.admit(h.pending.value!!)
        assertTrue(h.pending.value!!.seen)
        clock = clock.plus(Duration.ofHours(2))
        assertTrue(h.mayShow(h.pending.value!!))
        h.close(99) // another task's close does nothing
        assertEquals(41, h.pending.value?.taskId)
        h.close(41)
        assertNull(h.pending.value)
        assertNull(memory.stored)
    }

    @Test
    fun `unseen past 30 minutes it is not shown and admit drops it`() {
        val h = holder()
        h.show(41)
        clock = clock.plus(ClaudeModalHolder.FRESH_FOR).plusSeconds(1)
        val p = h.pending.value!!
        assertFalse(h.mayShow(p))
        h.admit(p)
        assertNull(h.pending.value)
    }

    @Test
    fun `within 30 minutes an unseen one is shown on the next open`() {
        val h = holder()
        h.show(41)
        clock = clock.plus(Duration.ofMinutes(29))
        assertTrue(h.mayShow(h.pending.value!!))
    }

    @Test
    fun `a cold start restores only a fresh one`() {
        memory.stored = ClaudeModalPending(41, clock.minus(Duration.ofMinutes(10)), seen = false)
        assertEquals(41, holder().pending.value?.taskId)
        memory.stored = ClaudeModalPending(41, clock.minus(Duration.ofMinutes(31)), seen = true)
        assertNull(holder().pending.value)
    }

    @Test
    fun `opened by hand it is seen at once`() {
        val h = holder()
        h.open(7)
        assertTrue(h.pending.value!!.seen)
    }
}
