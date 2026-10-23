package com.kevin.legion.books

import com.kevin.legion.data.local.BookConversionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each conversion state said in words, and the pending test the sheet's count rests on (ticket 05). */
class PdfConversionSentencesTest {
    private fun row(status: ConversionStatus, attempts: Int = 1, message: String? = null) =
        BookConversionRow("sha", status.name, attempts, message, 1L, null)

    private fun line(row: BookConversionRow?, running: Boolean = false, note: String? = null) =
        PdfConversionSentences.viewerLine(row, running, note)

    @Test fun `no row and the worker running says converting`() {
        assertEquals("Converting to resizable text...", line(null, running = true))
    }

    @Test fun `no row and nothing running says waiting for Wi-Fi`() {
        assertEquals("Waiting for Wi-Fi to convert", line(null))
    }

    @Test fun `an engine that could not be reached is said instead of waiting for Wi-Fi`() {
        assertEquals("engine down", line(null, note = "engine down"))
    }

    @Test fun `scanned, too big and unreadable show the server's own sentence`() {
        assertEquals("scanned words", line(row(ConversionStatus.SCANNED, message = "scanned words")))
        assertEquals("too big words", line(row(ConversionStatus.TOO_BIG, message = "too big words")))
        assertEquals("bad words", line(row(ConversionStatus.UNREADABLE, message = "bad words")))
    }

    @Test fun `a scanned row with no stored sentence still says the ruling's words`() {
        assertEquals(PdfConversionRules.SCANNED_SENTENCE, line(row(ConversionStatus.SCANNED)))
        assertEquals(
            "This PDF is scanned pages with no text, so its text cannot be refit.",
            PdfConversionRules.SCANNED_SENTENCE,
        )
    }

    @Test fun `a spent FAILED says three tries and why`() {
        assertEquals(
            "Could not convert after 3 tries: converter timed out",
            line(row(ConversionStatus.FAILED, 3, "converter timed out")),
        )
    }

    @Test fun `a FAILED with tries left is still pending, said as waiting`() {
        assertEquals("Waiting for Wi-Fi to convert", line(row(ConversionStatus.FAILED, 1, "x")))
    }

    @Test fun `a DONE row has nothing to say`() {
        assertNull(line(row(ConversionStatus.DONE)))
    }

    @Test fun `the sheet count is singular, plural, silent at zero, and says why when the engine is out`() {
        assertEquals("1 PDF waiting for Wi-Fi", PdfConversionSentences.pendingLine(1, null))
        assertEquals("2 PDFs waiting for Wi-Fi", PdfConversionSentences.pendingLine(2, null))
        assertNull(PdfConversionSentences.pendingLine(0, null))
        assertEquals("2 PDFs waiting: engine down", PdfConversionSentences.pendingLine(2, "engine down"))
    }

    @Test fun `pending means no row or a FAILED with tries left, nothing else`() {
        assertTrue(PdfConversionRules.isPending(null))
        assertTrue(PdfConversionRules.isPending(row(ConversionStatus.FAILED, 2)))
        assertFalse(PdfConversionRules.isPending(row(ConversionStatus.FAILED, 3)))
        val settled = listOf(
            ConversionStatus.DONE, ConversionStatus.SCANNED, ConversionStatus.TOO_BIG, ConversionStatus.UNREADABLE,
        )
        for (status in settled) assertFalse(status.name, PdfConversionRules.isPending(row(status)))
    }

    @Test fun `the EPUB copy is told apart from its PDF by a suffix that round-trips`() {
        val derived = ConvertedBooks.derivedId("primary:Books/x.pdf")
        assertTrue(ConvertedBooks.isDerived(derived))
        assertFalse(ConvertedBooks.isDerived("primary:Books/x.pdf"))
        assertEquals("primary:Books/x.pdf", ConvertedBooks.originalId(derived))
    }
}
