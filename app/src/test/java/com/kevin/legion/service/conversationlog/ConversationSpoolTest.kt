package com.kevin.legion.service.conversationlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The bounded conversation spool (conversation-records ticket 03): order, bounds, sealing, window times. */
class ConversationSpoolTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun spool(
        max: Long = ConversationSpool.DEFAULT_MAX_TOTAL_BYTES,
        sealAt: Long = ConversationSpool.DEFAULT_SEAL_AT_BYTES,
    ) =
        ConversationSpool(tmp.root, maxTotalBytes = max, sealAtBytes = sealAt)

    @Test fun `threadtime is the logcat shape voice_audit parses`() {
        assertEquals(
            "10-28 16:20:00.000 1234 5678 I LIVE_AUDIT: user final=true text=\"hi there\"",
            ConversationSpool.threadtime(
                "10-28 16:20:00.000", 1234, 5678, "LIVE_AUDIT", "user final=true text=\"hi\nthere\"",
            ),
        )
    }

    @Test fun `seal keeps lines in order and stamps the first and last line's times`() {
        val s = spool()
        s.append("a", 1_000)
        s.append("b", 2_000)
        s.append("c", 3_500)
        val batch = s.seal()!!
        assertEquals(1_000L, batch.startMs)
        assertEquals(3_500L, batch.endMs)
        assertEquals("a\nb\nc\n", batch.file.readText())
        assertEquals(3, batch.lineCount())
        assertEquals(listOf(batch.id), s.pending().map { it.id })
    }

    @Test fun `nothing appended seals nothing`() {
        val s = spool()
        assertNull(s.seal())
        assertFalse(s.hasContent())
    }

    @Test fun `pending is oldest first and a new line after a seal starts a new window`() {
        val s = spool()
        s.append("one", 5_000)
        val first = s.seal()!!
        s.append("two", 9_000)
        val second = s.seal()!!
        assertEquals(listOf(first.id, second.id), s.pending().map { it.id })
        assertEquals(9_000L, second.startMs)
        assertEquals(9_000L, second.endMs)
    }

    @Test fun `a line with a newline stays one line`() {
        val s = spool()
        s.append("x\ny\rz", 1)
        assertEquals("x y z\n", s.seal()!!.file.readText())
    }

    @Test fun `seals itself at the size threshold`() {
        val s = spool(sealAt = 20)
        s.append("0123456789", 1)
        assertTrue(s.pending().isEmpty())
        s.append("0123456789", 2)
        assertEquals(1, s.pending().size)
    }

    @Test fun `over the cap the oldest pending batch is dropped first`() {
        val s = spool(max = 40, sealAt = 1_000)
        s.append("aaaaaaaaaaaaaaa", 1) // 16 bytes
        val oldest = s.seal()!!
        s.append("bbbbbbbbbbbbbbb", 2)
        val middle = s.seal()!!
        s.append("ccccccccccccccc", 3) // 48 bytes total: the oldest goes
        assertEquals(listOf(middle.id), s.pending().map { it.id })
        assertFalse(oldest.file.exists())
        assertTrue(s.totalBytes() <= 40)
    }

    @Test fun `with nothing sealed the live file loses its head at a line boundary`() {
        val s = spool(max = 40, sealAt = 1_000)
        (1..10).forEach { s.append("line-$it", it.toLong()) } // 7-8 bytes each
        assertTrue(s.totalBytes() <= 40)
        val text = s.seal()!!.file.readText()
        assertTrue(text, text.startsWith("line-"))
        assertTrue(text, text.endsWith("line-10\n"))
        assertTrue(text.lines().filter { it.isNotEmpty() }.all { it.matches(Regex("line-\\d+")) })
    }

    @Test fun `the default bounds are about 4 MB with batches far under the engine's 5 MB`() {
        assertEquals(4L * 1024 * 1024, ConversationSpool.DEFAULT_MAX_TOTAL_BYTES)
        assertTrue(ConversationSpool.DEFAULT_SEAL_AT_BYTES < 5L * 1024 * 1024 / 4)
    }
}
