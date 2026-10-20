package com.kevin.legion.books

import androidx.compose.ui.graphics.Color
import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookDao
import com.kevin.legion.data.local.BookRow

/** The persistence the reader needs. Room in production, a map in tests. */
interface ReaderStore {
    suspend fun row(documentId: String): BookRow?

    @Suppress("LongParameterList") // one argument per saved fact
    suspend fun savePosition(
        documentId: String,
        progress: Double,
        chapterLabel: String?,
        pageIndex: Int,
        pageCount: Int,
        locatorJson: String?,
        atMs: Long,
    )

    suspend fun bookmarks(documentId: String): List<BookBookmark>

    suspend fun addBookmark(bookmark: BookBookmark)

    suspend fun deleteBookmark(id: Long)
}

class RoomReaderStore(private val dao: BookDao) : ReaderStore {
    override suspend fun row(documentId: String) = dao.get(documentId)

    override suspend fun savePosition(
        documentId: String,
        progress: Double,
        chapterLabel: String?,
        pageIndex: Int,
        pageCount: Int,
        locatorJson: String?,
        atMs: Long,
    ) = dao.savePosition(documentId, progress, chapterLabel, pageIndex, pageCount, locatorJson, atMs)

    override suspend fun bookmarks(documentId: String) = dao.bookmarks(documentId)

    override suspend fun addBookmark(bookmark: BookBookmark) {
        dao.addBookmark(bookmark)
    }

    override suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)
}

/** Where a book reopens: the exact place the last session saved. Pure, so "resume exactly" is testable. */
data class ResumePoint(val pageIndex: Int, val locatorJson: String?, val progress: Double) {
    companion object {
        fun from(row: BookRow?, pageCount: Int = Int.MAX_VALUE): ResumePoint = ResumePoint(
            // A saved page past the end (the file changed under us) clamps to the last page, never crashes.
            pageIndex = (row?.pageIndex ?: 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
            locatorJson = row?.locatorJson,
            progress = row?.progress ?: 0.0,
        )
    }
}

/** One line of the contents list. [index] is the entry's place in the flat list the host navigates by. */
data class TocEntry(val title: String, val depth: Int, val index: Int)

/** Which panel is over the page. */
enum class ReaderPanel { NONE, SETTINGS, CONTENTS, BOOKMARKS }

/** What the reader is showing. */
sealed interface ReaderContent {
    data object Loading : ReaderContent

    /** [sentence] says what went wrong and the fix; shown in place of the page. */
    data class Failed(val sentence: String) : ReaderContent

    /** The Readium navigator is the page. */
    data object Epub : ReaderContent

    /** A PDF or comic: [source] renders pages, [startPage] is where it reopens. */
    data class Paged(val source: PageSource, val startPage: Int) : ReaderContent
}

data class ReaderUiState(
    val title: String = "",
    val subtitle: String = "",
    val format: BookFormat? = null,
    val content: ReaderContent = ReaderContent.Loading,
    val settings: ReaderSettings = ReaderSettings(),
    val panel: ReaderPanel = ReaderPanel.NONE,
    /** "Page 212 of 560", or "Chapter 3" when the book has no page list. */
    val positionLabel: String = "",
    /** "38%". */
    val percentLabel: String = "",
    /** "14 min left in chapter" (EPUB only), else null. */
    val timeLeft: String? = null,
    val progress: Float = 0f,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val toc: List<TocEntry> = emptyList(),
    val bookmarks: List<BookBookmark> = emptyList(),
    val readAloud: ReadAloudUi = ReadAloudUi(),
    /** Listen refused or unavailable, in words ("Listen is for ebooks."); null when fine. */
    val listenNote: String? = null,
    /** True once the first page is up; the bottom bar stays hidden before there is a position. */
    val hasPosition: Boolean = false,
    /** How display pages map to source pages; changes identity only when the mapping changes. */
    val spread: SpreadMap = SpreadMap.of(emptyList(), split = false, pageCount = 0),
    /** The per-book Split / Whole choice; only offered when [SpreadMap.hasSpreads]. */
    val spreadSplit: Boolean = true,
    val landscapeScreen: Boolean = false,
)

/**
 * Colours of one reading theme. Night reuses the app's tokens; Day and Sepia are paper, which the app
 * has no token for.
 */
data class ReaderPalette(
    val page: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val chip: Color,
    val ttsMark: Color,
) {
    companion object {
        private val DAY = ReaderPalette(
            Color(0xFFF6F3EC), Color(0xFF1D1B17), Color(0xFF5C5850),
            Color(0xFFDDD6C8), Color(0xFFE8E2D5), Color(0xFFE2D8FF),
        )
        private val SEPIA = ReaderPalette(
            Color(0xFFECE0C8), Color(0xFF3B2F1E), Color(0xFF6B5A40),
            Color(0xFFD4C4A4), Color(0xFFE0D1B3), Color(0xFFD9C9F5),
        )
        private val NIGHT = ReaderPalette(
            com.kevin.legion.ui.theme.soft.SoftColors.ground,
            Color(0xFFD8D6CF),
            com.kevin.legion.ui.theme.soft.SoftColors.text2,
            Color(0xFF2C2E35),
            com.kevin.legion.ui.theme.soft.SoftColors.cardHigh,
            Color(0xFF3A3157),
        )

        fun of(theme: ReaderTheme): ReaderPalette = when (theme) {
            ReaderTheme.DAY -> DAY
            ReaderTheme.SEPIA -> SEPIA
            ReaderTheme.NIGHT -> NIGHT
        }
    }
}

/** What the reader chrome asks of its owner. */
data class ReaderActions(
    val onBack: () -> Unit = {},
    val onToggleSettings: () -> Unit = {},
    val onToggleBookmarks: () -> Unit = {},
    val onToggleContents: () -> Unit = {},
    val onClosePanel: () -> Unit = {},
    val onTheme: (ReaderTheme) -> Unit = {},
    val onBigger: () -> Unit = {},
    val onSmaller: () -> Unit = {},
    val onListen: () -> Unit = {},
    val onStopListening: () -> Unit = {},
    val onSpeed: () -> Unit = {},
    val onAddBookmark: () -> Unit = {},
    val onDeleteBookmark: (BookBookmark) -> Unit = {},
    val onGoBookmark: (BookBookmark) -> Unit = {},
    val onGoToc: (TocEntry) -> Unit = {},
    val onGoPage: (Int) -> Unit = {},
    val onSpreadSplit: (Boolean) -> Unit = {},
)
