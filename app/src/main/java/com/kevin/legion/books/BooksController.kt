package com.kevin.legion.books

/** What the Books folder grant and scan came to. The seam the controller and the sheet both read. */
sealed interface CatalogState {
    data object NoFolder : CatalogState

    /** A folder was picked once, but the system no longer holds the grant. */
    data class Lost(val label: String) : CatalogState

    /** The folder is still granted but Android would not list it. */
    data class Unreadable(val label: String) : CatalogState

    /** [items] empty means the folder holds no EPUB, PDF, CBZ or CBR. */
    data class Ready(val label: String, val items: List<BookItem>) : CatalogState
}

fun interface BookCatalog {
    suspend fun load(): CatalogState
}

/** Opens the reader on a book. Returns whether the reader was observed on screen, never "no exception". */
fun interface ReaderLauncher {
    suspend fun launch(book: Book, listen: Boolean): Boolean
}

/** Whether the phone has a text-to-speech engine at all; Listen cannot work without one. */
fun interface TtsAvailability {
    fun available(): Boolean
}

/** What a request to open a book came to, always in words. */
sealed interface BookOutcome {
    /** [sentence] says what DID happen; it is only built after the reader was seen opening. */
    data class Opened(val title: String, val listening: Boolean, val sentence: String) : BookOutcome

    /** [sentence] says what did NOT happen and the fix. */
    data class Failed(val sentence: String) : BookOutcome
}

/**
 * The one controller behind the Books sheet buttons AND the voice tool (ADR 0035): both call
 * [open], [continueReading] or [openByName], so the two paths cannot drift into disagreeing.
 *
 * Every failure says what did NOT happen ("nothing was opened") and the fix, because the outcome
 * verbs of CLAUDE.md section 7 may only follow a launch that was observed ([ReaderLauncher]).
 * Listen is a request the reader acts on once it is up; this controller never claims the voice is
 * already speaking, only that the reader was opened and asked to read aloud.
 */
class BooksController(
    private val catalog: BookCatalog,
    private val launcher: ReaderLauncher,
    private val tts: TtsAvailability,
) {
    /** Opens the most recently read book, optionally reading aloud. */
    suspend fun continueReading(listen: Boolean): BookOutcome {
        val items = when (val state = catalog.load()) {
            is CatalogState.Ready -> state.items
            else -> return state.failure()
        }
        if (items.isEmpty()) return Failed(EMPTY)
        val last = items.filter { it.lastReadAtMs != null }.maxByOrNull { it.lastReadAtMs ?: 0L }
            ?: return Failed("No book has been opened yet, so there is nothing to continue. Nothing was opened.")
        return open(last, listen)
    }

    /** Opens the book [query] names, optionally reading aloud. */
    suspend fun openByName(query: String, listen: Boolean): BookOutcome {
        if (query.isBlank()) return Failed("No book name was given, so nothing was opened.")
        val items = when (val state = catalog.load()) {
            is CatalogState.Ready -> state.items
            else -> return state.failure()
        }
        if (items.isEmpty()) return Failed(EMPTY)
        return when (val match = BookMatcher.find(items, query)) {
            is BookMatch.One -> open(match.item, listen)
            is BookMatch.Several -> Failed(
                "More than one book fits \"${query.trim()}\": ${match.items.joinToString("; ") { it.title }}. " +
                    "Nothing was opened. Say which one.",
            )
            BookMatch.None ->
                Failed("I could not find a book matching \"${query.trim()}\" in the Books folder, so nothing was opened.")
        }
    }

    /** The sheet's tile, Read and Listen buttons land here, and so does everything above. */
    suspend fun open(item: BookItem, listen: Boolean): BookOutcome {
        val format = item.book.format
        val refusal = when {
            !format.readable -> "${BookFormat.CBR_UNSUPPORTED} Nothing was opened."
            listen && !format.listenable -> BookFormat.LISTEN_EBOOKS_ONLY
            listen && !tts.available() ->
                "This phone has no text-to-speech engine installed, so nothing was read aloud and nothing was opened. " +
                    "Install one in Android settings, under Text-to-speech."
            else -> null
        }
        if (refusal != null) return Failed(refusal)
        if (!launcher.launch(item.book, listen)) {
            return Failed("The reader did not come up, so \"${item.title}\" was not opened. Try the Books sheet on HOME.")
        }
        return Opened(
            item.title,
            listen,
            if (listen) "Opened \"${item.title}\" and asked it to read aloud." else "Opened \"${item.title}\".",
        )
    }

    /** The sentence for a catalog that cannot supply books (Ready is handled by the callers). */
    private fun CatalogState.failure(): Failed = when (this) {
        CatalogState.NoFolder -> Failed(
            "No Books folder is set up, so nothing was opened. Pick one in the Books sheet on HOME.",
        )
        is CatalogState.Lost -> Failed(
            "LEGION can no longer read $label, so nothing was opened. Pick the folder again in the Books sheet.",
        )
        is CatalogState.Unreadable -> Failed(
            "Android would not list $label, so nothing was opened. Pick the folder again in the Books sheet.",
        )
        is CatalogState.Ready -> Failed(EMPTY)
    }

    companion object {
        private const val EMPTY = "The Books folder holds no EPUB, PDF or CBZ files, so nothing was opened."
    }
}

private typealias Failed = BookOutcome.Failed
private typealias Opened = BookOutcome.Opened
