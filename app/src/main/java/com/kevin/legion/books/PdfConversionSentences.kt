package com.kevin.legion.books

import com.kevin.legion.data.local.BookConversionRow
import java.io.File

/**
 * Every conversion state said in words (ticket 05: "states said in words"). Pure, so each sentence
 * is a test. Nothing here claims a conversion happened unless a DONE row and its file say so.
 */
object PdfConversionSentences {
    const val CONVERTED_NOTICE = "Converted from PDF. Figures and tables may be missing."
    const val CONVERTING = "Converting to resizable text..."
    const val WAITING = "Waiting for Wi-Fi to convert"
    const val OPEN_ORIGINAL = "Open original"
    const val TRY_AGAIN = "Try again"
    const val TRY_AGAIN_DESCRIPTION = "Try converting this PDF again"
    const val SERVER_CANNOT_CONVERT =
        "Your LEGION server does not convert PDFs yet. Update the server, then reopen Books."

    /** True when [row] is a FAILED conversion that has used every try: the viewer offers "Try again". */
    fun isSpent(row: BookConversionRow?): Boolean =
        row != null && row.status == ConversionStatus.FAILED.name && row.attempts >= PdfConversionRules.MAX_ATTEMPTS

    /**
     * The PDF viewer's line for a PDF that has no usable EPUB copy, or null when there is nothing to
     * say. [row] is its conversion row (null when none), [running] whether the worker is working
     * right now, [serverNote] why the last run could not reach the engine.
     */
    fun viewerLine(row: BookConversionRow?, running: Boolean, serverNote: String?): String? {
        val status = row?.let { ConversionStatus.of(it.status) }
        return when {
            status == ConversionStatus.DONE -> null
            status == ConversionStatus.SCANNED -> row.message ?: PdfConversionRules.SCANNED_SENTENCE
            status == ConversionStatus.TOO_BIG || status == ConversionStatus.UNREADABLE ->
                row.message ?: "This PDF cannot be converted."
            status == ConversionStatus.FAILED && row.attempts >= PdfConversionRules.MAX_ATTEMPTS ->
                "Could not convert after ${PdfConversionRules.MAX_ATTEMPTS} tries: ${row.message ?: "no reason given"}"
            running -> CONVERTING
            serverNote != null -> serverNote
            else -> WAITING
        }
    }

    /** The Books sheet subtitle's tail, or null when no PDF is pending: "2 PDFs waiting for Wi-Fi". */
    fun pendingLine(pending: Int, serverNote: String?): String? = when {
        pending <= 0 -> null
        serverNote != null -> "${pdfCount(pending)} waiting: $serverNote"
        else -> "${pdfCount(pending)} waiting for Wi-Fi"
    }

    private fun pdfCount(n: Int) = if (n == 1) "1 PDF" else "$n PDFs"
}

/** An EPUB copy ready to read: the file, and the PDF it came from. */
data class ConvertedCopy(val file: File, val sha256: String)

/**
 * What the reader and the sheet ask about conversions. The EPUB copy of a PDF is read under its own
 * book row ([ConvertedBooks.derivedId]) so it and the PDF keep separate positions, bookmarks and
 * locators and never overwrite one another.
 */
interface PdfConversions {
    /** The EPUB copy of [book] when its conversion is DONE and the file is present, else null. */
    suspend fun copyFor(book: Book): ConvertedCopy?

    /** The viewer's sentence for a PDF with no copy ([PdfConversionSentences.viewerLine]), or null. */
    suspend fun noteFor(book: Book): String?

    /** True when [book]'s conversion failed every try, so the viewer offers "Try again". */
    suspend fun canRetry(book: Book): Boolean = false

    /** Forgets [book]'s spent conversion and queues the worker again. The hands path for a stuck book. */
    suspend fun retry(book: Book) = Unit

    companion object {
        /** No conversions at all (tests, and anywhere the feature is not wired). */
        val None: PdfConversions = object : PdfConversions {
            override suspend fun copyFor(book: Book): ConvertedCopy? = null

            override suspend fun noteFor(book: Book): String? = null
        }
    }
}

/** The book id the EPUB copy of a PDF is read, positioned and bookmarked under. */
object ConvertedBooks {
    private const val SUFFIX = "#resizable-epub"

    fun derivedId(pdfDocumentId: String) = pdfDocumentId + SUFFIX

    fun isDerived(documentId: String) = documentId.endsWith(SUFFIX)

    fun originalId(documentId: String) = documentId.removeSuffix(SUFFIX)
}
