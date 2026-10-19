package com.kevin.legion.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import com.kevin.legion.books.Book
import com.kevin.legion.books.BookFormat
import com.kevin.legion.books.BookItem
import com.kevin.legion.books.BookShelf
import com.kevin.legion.books.bookCountLabel

/** Where the Books folder grant stands. [label] is the folder as the user would say it ("/sdcard/Books"). */
sealed interface BooksFolderState {
    data object None : BooksFolderState

    /** A folder was picked once, but the system no longer holds the grant. */
    data class Lost(val label: String) : BooksFolderState

    data class Granted(val label: String) : BooksFolderState
}

/** A line the sheet says in words. A refusal (CBR, no TTS, ...) lands here and stays until the next action. */
data class BookNotice(val text: String, val failed: Boolean)

/** The Continue reading card: the most recently opened book, with its place in words. */
data class ContinueUi(val item: BookItem, val line: String)

/** One shelf of the sheet. A shelf with no files is never built. */
data class BookShelfUi(val shelf: BookShelf, val items: List<BookItem>) {
    /** "EPUB · 3", as the prototype's shelf header, naming every format present ("CBZ, CBR · 3"). */
    val countLabel: String
        get() {
            val formats = BookFormat.entries.filter { f -> items.any { it.book.format == f } }
            return "${formats.joinToString(", ") { it.label }} · ${items.size}"
        }
}

data class BooksUiState(
    val folder: BooksFolderState = BooksFolderState.None,
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    /** The folder is granted but Android would not list it, which is not the same as holding no books. */
    val unreadable: Boolean = false,
    val items: List<BookItem> = emptyList(),
    /** The last scan's count, shown on the chip until this scan finishes. */
    val cachedCount: Int? = null,
    val resume: ContinueUi? = null,
    val notice: BookNotice? = null,
    /** Bumped whenever a cover or title landed, so tiles re-ask for their cover. */
    val coverVersion: Int = 0,
) {
    /** One shelf per shelf kind that has files, in [BookShelf] order. */
    val shelves: List<BookShelfUi>
        get() = BookShelf.entries.mapNotNull { shelf ->
            items.filter { it.book.format.shelf == shelf }.takeIf { it.isNotEmpty() }?.let { BookShelfUi(shelf, it) }
        }

    /** The chip's second line, in words. */
    val chipSub: String
        get() = when {
            folder is BooksFolderState.Granted && scanned -> bookCountLabel(items.size)
            folder is BooksFolderState.Granted && cachedCount != null -> bookCountLabel(cachedCount)
            folder is BooksFolderState.Granted -> "looking"
            else -> "set up"
        }
}

/** What the Books chip and sheet ask their owner to do. */
data class BooksCallbacks(
    val onOpenSheet: () -> Unit = {},
    val onRead: (BookItem) -> Unit = {},
    val onListen: (BookItem) -> Unit = {},
    val onContinue: (listen: Boolean) -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** Everything HOME needs to draw Books: state, callbacks, and how a tile gets its cover (null = none). */
class BooksHome(
    val state: BooksUiState = BooksUiState(),
    val callbacks: BooksCallbacks = BooksCallbacks(),
    val cover: suspend (Book) -> ImageBitmap? = { null },
)
