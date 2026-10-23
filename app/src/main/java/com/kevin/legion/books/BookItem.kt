package com.kevin.legion.books

import com.kevin.legion.data.local.BookRow
import com.kevin.legion.games.agoLabel
import kotlin.math.roundToInt

/** A listed [book] joined with what is known about it: its title, and where the reader left off. */
data class BookItem(
    val book: Book,
    /** The EPUB's own title once read, else the file name's. */
    val title: String,
    val author: String?,
    /** 0.0 to 1.0. */
    val progress: Double,
    val chapterLabel: String?,
    val pageIndex: Int,
    val pageCount: Int,
    val locatorJson: String?,
    val lastReadAtMs: Long?,
    /** A PDF whose EPUB copy is converted and present: the tile says "Resizable" (ticket 05). */
    val resizable: Boolean = false,
) {
    /** The tile's format tag in words: "PDF", or "PDF · Resizable" once the text can be refit. */
    val formatLabel: String get() = if (resizable) "${book.format.label} · Resizable" else book.format.label

    val percent: Int get() = (progress.coerceIn(0.0, 1.0) * PERCENT).roundToInt()

    /** The Continue card's second line, in words: "Ch. 41 · 38% · 2 h ago" or "Page 12 of 40 · 30% · 1 h ago". */
    fun continueLine(nowMs: Long): String = listOfNotNull(
        positionLabel(),
        "$percent%",
        lastReadAtMs?.let { agoLabel(nowMs, it) },
    ).joinToString(" · ")

    /** Where the reader is: a chapter for an EPUB, a page for the paged formats. */
    fun positionLabel(): String = when (book.format) {
        BookFormat.EPUB -> chapterLabel?.takeIf { it.isNotBlank() } ?: "Start"
        else -> if (pageCount > 0) "Page ${pageIndex + 1} of $pageCount" else "Page ${pageIndex + 1}"
    }

    companion object {
        private const val PERCENT = 100

        fun of(book: Book, row: BookRow?): BookItem = BookItem(
            book = book,
            title = row?.title?.takeIf { it.isNotBlank() } ?: book.fileTitle,
            author = row?.author,
            progress = row?.progress ?: 0.0,
            chapterLabel = row?.chapterLabel,
            pageIndex = row?.pageIndex ?: 0,
            pageCount = row?.pageCount ?: 0,
            locatorJson = row?.locatorJson,
            lastReadAtMs = row?.lastReadAtMs,
        )

        /** The row a first sighting inserts: the file name as the title, nothing read yet. */
        fun newRow(book: Book, nowMs: Long) = BookRow(
            documentId = book.documentId,
            fileName = book.fileName,
            format = book.format.name,
            title = book.fileTitle,
            author = null,
            metadataRead = false,
            progress = 0.0,
            chapterLabel = null,
            pageIndex = 0,
            pageCount = 0,
            locatorJson = null,
            lastReadAtMs = null,
            firstSeenAtMs = nowMs,
        )
    }
}

/** "7 books", "1 book". */
fun bookCountLabel(n: Int): String = if (n == 1) "1 book" else "$n books"

/** "14 min left in chapter", the bottom bar's right-hand line (EPUB only). */
fun timeLeftLabel(minutes: Int): String = when {
    minutes <= 0 -> "Less than a minute left in chapter"
    else -> "$minutes min left in chapter"
}

/**
 * Minutes left in a chapter, from the reading positions still ahead of the reader in it. Readium's
 * reflowable positions are ~1024 characters each and ~1000 characters a minute is an ordinary
 * reading speed, so one position is about a minute. This is an ESTIMATE: the reader says "left in
 * chapter" and never claims precision.
 */
fun minutesLeftInChapter(remainingPositions: Int): Int = remainingPositions.coerceAtLeast(0)
