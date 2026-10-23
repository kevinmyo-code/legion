package com.kevin.legion.books

import com.kevin.legion.data.local.BookConversionRow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion state machine against fakes for every port (ticket 05): each status, the retry
 * cap, the missing-file reset, the size gate, smallest-first, and an unreachable engine that burns
 * no attempt. No WorkManager, no Android.
 */
class PdfConversionRunnerTest {
    private class Fixture(val pdfs: Map<String, ByteArray>, val declaredSizes: Map<String, Long> = emptyMap()) {
        val order = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val rows = mutableMapOf<String, BookConversionRow>()
        val written = mutableMapOf<String, ByteArray>()
        val hashed = mutableMapOf<String, String>()
        var note: String? = "stale"
        var replies: (String) -> ConvertReply = { ConvertReply.Converted(byteArrayOf(1, 2, 3), 12) }
        var current = ""
        var opened = 0
        var granted = true

        private val refs = pdfs.map { (id, bytes) ->
            PdfRef(Book(id, "$id.pdf", BookFormat.PDF), declaredSizes[id] ?: bytes.size.toLong(), 1L)
        }

        val runner = PdfConversionRunner(
            source = object : PdfSource {
                override fun list() = if (granted) refs else null

                override fun open(ref: PdfRef): InputStream {
                    opened++
                    order += ref.book.documentId
                    current = ref.book.documentId
                    return ByteArrayInputStream(pdfs.getValue(ref.book.documentId))
                }
            },
            backend = { bytes ->
                uploads += bytes
                replies(current)
            },
            store = object : ConversionStore {
                override suspend fun get(sha256: String) = rows[sha256]

                override suspend fun upsert(row: BookConversionRow) {
                    rows[row.sha256] = row
                }

                override suspend fun delete(sha256: String) {
                    rows.remove(sha256)
                }
            },
            files = object : ConvertedFiles {
                override fun file(sha256: String) = File("$sha256.epub")

                override fun exists(sha256: String) = sha256 in written

                override fun write(sha256: String, bytes: ByteArray) {
                    written[sha256] = bytes
                }

                override fun delete(sha256: String) {
                    written.remove(sha256)
                }
            },
            hashes = object : PdfHashCache {
                override fun get(ref: PdfRef) = hashed[ref.book.documentId]

                override fun lastKnown(documentId: String) = hashed[documentId]

                override fun put(ref: PdfRef, sha256: String, sizeBytes: Long) {
                    hashed[ref.book.documentId] = sha256
                }
            },
            notes = object : ConversionNotes {
                override var serverNote: String?
                    get() = note
                    set(value) {
                        note = value
                    }
            },
            now = { 99L },
        )

        fun run() = runBlocking { runner.run() }

        fun sha(id: String) = MessageDigest.getInstance("SHA-256").digest(pdfs.getValue(id))
            .joinToString("") { "%02x".format(it) }
    }

    private val small = "small pdf".toByteArray()
    private val medium = "a somewhat longer pdf body".toByteArray()

    @Test fun `a converted PDF stores its EPUB and a DONE row with the page count`() {
        val f = Fixture(mapOf("a" to small))
        assertEquals(RunOutcome.COMPLETE, f.run())
        val row = f.rows.getValue(f.sha("a"))
        assertEquals("DONE", row.status)
        assertEquals(12, row.pages)
        assertNull(row.message)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.written.getValue(f.sha("a")))
        assertNull("a run that reached the engine clears the stale note", f.note)
    }

    @Test fun `PDFs are converted smallest first`() {
        val f = Fixture(mapOf("big" to medium, "tiny" to small))
        f.run()
        assertEquals(listOf("tiny", "big"), f.order.distinct())
    }

    @Test fun `scanned, unreadable and too big are recorded with the server's sentence and never retried`() {
        val f = Fixture(mapOf("a" to small))
        f.replies = { ConvertReply.Refused(ConversionStatus.SCANNED, PdfConversionRules.SCANNED_SENTENCE) }
        assertEquals(RunOutcome.COMPLETE, f.run())
        assertEquals(PdfConversionRules.SCANNED_SENTENCE, f.rows.getValue(f.sha("a")).message)
        val uploads = f.uploads.size
        f.run()
        assertEquals("a SCANNED row is not uploaded again", uploads, f.uploads.size)

        for (status in listOf(ConversionStatus.UNREADABLE, ConversionStatus.TOO_BIG)) {
            val g = Fixture(mapOf("a" to small))
            g.replies = { ConvertReply.Refused(status, "said in words") }
            g.run()
            g.run()
            assertEquals(status.name, g.rows.getValue(g.sha("a")).status)
            assertEquals(1, g.uploads.size)
        }
    }

    @Test fun `FAILED is retried up to three attempts, then stops and stays said`() {
        val f = Fixture(mapOf("a" to small))
        f.replies = { ConvertReply.Refused(ConversionStatus.FAILED, "the converter timed out") }
        assertEquals(RunOutcome.RETRY, f.run())
        assertEquals(RunOutcome.RETRY, f.run())
        assertEquals("the third failure is the last", RunOutcome.COMPLETE, f.run())
        val row = f.rows.getValue(f.sha("a"))
        assertEquals(3, row.attempts)
        assertEquals("the converter timed out", row.message)
        f.run()
        assertEquals("a spent FAILED is never uploaded again", 3, f.uploads.size)
    }

    @Test fun `a PDF the provider reports over 30 MB is marked too big on the phone and never uploaded`() {
        val f = Fixture(mapOf("a" to small), declaredSizes = mapOf("a" to 35_600_000L))
        f.hashed["a"] = f.sha("a") // already hashed once, so the size comes from the provider
        f.run()
        val row = f.rows.getValue(f.sha("a"))
        assertEquals("TOO_BIG", row.status)
        assertEquals(PdfConversionRules.tooBigSentence(35_600_000L), row.message)
        assertTrue(f.uploads.isEmpty())
    }

    @Test fun `a PDF whose bytes turn out over 30 MB when first read is marked too big without an upload`() {
        val huge = ByteArray((PdfConversionRules.MAX_BYTES + 1).toInt())
        val f = Fixture(mapOf("a" to huge))
        f.run()
        assertEquals("TOO_BIG", f.rows.getValue(f.sha("a")).status)
        assertTrue(f.uploads.isEmpty())
    }

    @Test fun `the too big sentence rounds half to even like the server's Python`() {
        // 31.25 MiB is an exact tie: server prints 31.2, Java's %.1f would print 31.3.
        assertEquals(
            "This PDF is 31.2 MB; the most the server takes is 30 MB.",
            PdfConversionRules.tooBigSentence(31L * 1024 * 1024 + 256 * 1024),
        )
        assertEquals(30L * 1024 * 1024, PdfConversionRules.MAX_BYTES)
    }

    @Test fun `the too big sentence names the size to one decimal and the 30 MB cap`() {
        assertEquals(
            "This PDF is 34.0 MB; the most the server takes is 30 MB.",
            PdfConversionRules.tooBigSentence(35_600_000L),
        )
    }

    @Test fun `a DONE row whose EPUB is gone resets and converts again`() {
        val f = Fixture(mapOf("a" to small))
        f.run()
        f.written.clear()
        f.run()
        assertEquals(2, f.uploads.size)
        assertEquals("DONE", f.rows.getValue(f.sha("a")).status)
        assertTrue(f.written.containsKey(f.sha("a")))
    }

    @Test fun `a PDF with its EPUB present is neither read nor uploaded again`() {
        val f = Fixture(mapOf("a" to small))
        f.run()
        val opens = f.opened
        f.run()
        assertEquals("the hash cache answers, so the file is not even opened", opens, f.opened)
        assertEquals(1, f.uploads.size)
    }

    @Test fun `a renamed or moved PDF reuses the same EPUB because identity is its bytes`() {
        val f = Fixture(mapOf("old-name" to small))
        f.run()
        val moved = Fixture(mapOf("new-name" to small))
        moved.rows.putAll(f.rows)
        moved.written.putAll(f.written)
        moved.run()
        assertTrue("no upload for the same bytes under a new name", moved.uploads.isEmpty())
        assertEquals(f.sha("old-name"), moved.sha("new-name"))
    }

    @Test fun `an edited PDF converts anew`() {
        val f = Fixture(mapOf("a" to small))
        f.run()
        val edited = Fixture(mapOf("a" to medium))
        edited.rows.putAll(f.rows)
        edited.written.putAll(f.written)
        edited.run()
        assertEquals(1, edited.uploads.size)
        assertEquals(2, edited.rows.size)
    }

    @Test fun `an unreachable engine stops the run, burns no attempt, and says why`() {
        val f = Fixture(mapOf("a" to small, "b" to medium))
        f.replies = { ConvertReply.Unreachable("The engine could not be reached, so nothing was converted: offline") }
        assertEquals(RunOutcome.RETRY, f.run())
        assertEquals("the second PDF would fail the same way, so it is not tried", 1, f.uploads.size)
        assertTrue(f.rows.isEmpty())
        assertTrue(f.note!!.contains("could not be reached"))
    }

    @Test fun `no engine or a signed-out token stops the run without a retry and without a row`() {
        val f = Fixture(mapOf("a" to small))
        f.replies = { ConvertReply.Unavailable("No engine is set up on this device, so PDFs were not converted.") }
        assertEquals(RunOutcome.COMPLETE, f.run())
        assertTrue(f.rows.isEmpty())
        assertFalse(f.note.isNullOrBlank())
    }

    @Test fun `no readable folder grant is a complete run with nothing done`() {
        val f = Fixture(mapOf("a" to small))
        f.granted = false
        assertEquals(RunOutcome.COMPLETE, f.run())
        assertTrue(f.uploads.isEmpty() && f.rows.isEmpty())
    }

    @Test fun `SHA-256 identity hashes the bytes and keeps them only under the cap`() {
        val read = PdfIdentity.read(ByteArrayInputStream(small))
        val expected = MessageDigest.getInstance("SHA-256").digest(small).joinToString("") { "%02x".format(it) }
        assertEquals(expected, read.sha256)
        assertEquals(small.size.toLong(), read.sizeBytes)
        assertArrayEquals(small, read.bytes)
        val over = PdfIdentity.read(ByteArrayInputStream(medium), cap = 5)
        assertEquals(medium.size.toLong(), over.sizeBytes)
        assertNull("an oversize file is hashed without being held", over.bytes)
        assertEquals(PdfIdentity.read(ByteArrayInputStream(medium)).sha256, over.sha256)
    }

    @Test fun `the same bytes under different names share one identity and different bytes do not`() {
        assertEquals(
            PdfIdentity.read(ByteArrayInputStream(small)).sha256,
            PdfIdentity.read(ByteArrayInputStream(small.copyOf())).sha256,
        )
        val other = PdfIdentity.read(ByteArrayInputStream(medium)).sha256
        assertFalse(PdfIdentity.read(ByteArrayInputStream(small)).sha256 == other)
    }
}
