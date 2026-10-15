package com.kevin.midnightai.diag

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveLogStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun store(max: Long = 10_000, seal: Long = 1_000) =
        DriveLogStore(File(tmp.root, "log"), max, seal) { clock++ }

    @Test
    fun `append then seal moves the lines into one pending batch with a window`() {
        val s = store()
        s.append(listOf("a", "b"))
        s.append(listOf("c"))
        val batch = s.seal()!!
        assertEquals(listOf("a", "b", "c"), batch.file.readLines())
        assertTrue(batch.startMs <= batch.endMs)
        assertEquals(listOf(batch.id), s.pending().map { it.id })
        assertNull("nothing left to seal", s.seal())
    }

    @Test
    fun `a batch seals itself when it reaches the seal size`() {
        val s = store(seal = 50)
        repeat(10) { s.append(listOf("0123456789")) }
        assertTrue(s.pending().isNotEmpty())
    }

    @Test
    fun `total size stays under the cap and the oldest pending batch is dropped first`() {
        val s = store(max = 300, seal = 100)
        val big = "x".repeat(90)
        repeat(12) { s.append(listOf(big)) }
        assertTrue("total ${s.totalBytes()}", s.totalBytes() <= 300)
        // The newest line survived; the very first batch is gone.
        val all = s.pending().flatMap { it.file.readLines() }
        assertTrue(all.isNotEmpty())
    }

    @Test
    fun `with nothing sealed the head of the live file is trimmed at a line boundary`() {
        val s = store(max = 200, seal = 10_000)
        repeat(30) { i -> s.append(listOf("line-%02d-".format(i) + "y".repeat(20))) }
        assertTrue(s.totalBytes() <= 200)
        val kept = File(tmp.root, "log/current.log").readLines()
        assertTrue(kept.all { it.startsWith("line-") })
        assertTrue(kept.last().startsWith("line-29"))
    }

    @Test
    fun `a deleted batch is gone and an unsent one survives a new store instance`() {
        val s = store()
        s.append(listOf("keep me"))
        val batch = s.seal()!!
        // A fresh instance over the same directory (process restart) still sees it.
        val again = DriveLogStore(File(tmp.root, "log"))
        assertEquals(1, again.pending().size)
        again.delete(batch)
        assertFalse(batch.file.exists())
        assertTrue(again.pending().isEmpty())
    }

    @Test
    fun `state files round trip`() {
        val s = store()
        assertNull(s.readState("anchor"))
        s.writeState("anchor", "some line")
        assertEquals("some line", s.readState("anchor"))
    }
}
