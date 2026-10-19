package com.kevin.legion.ui.home

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.books.Book
import com.kevin.legion.books.BookCatalog
import com.kevin.legion.books.BookCoverCache
import com.kevin.legion.books.BookCovers
import com.kevin.legion.books.BookItem
import com.kevin.legion.books.BookOutcome
import com.kevin.legion.books.BooksController
import com.kevin.legion.books.BookPrep
import com.kevin.legion.books.BooksFolderStore
import com.kevin.legion.books.CatalogState
import com.kevin.legion.books.SafBookCatalog
import com.kevin.legion.books.SafBookPrep
import com.kevin.legion.books.forContext
import com.kevin.legion.books.ReadiumEpub
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOTICE_MS = 2_400L

/**
 * The Books chip and sheet's ViewModel (ADR 0061): one [StateFlow] of [BooksUiState]. The scan, the
 * reader launch and the cover pass are all seams, so what is tested is the sheet's states (no
 * folder, lost permission, empty, populated), not the file system. No Hilt yet, matching
 * [GamesViewModel] (CLAUDE.md sec 8's migration order); [Factory] is the injection point.
 */
@Suppress("TooManyFunctions") // one function per sheet action, as GamesViewModel
class BooksViewModel(
    private val catalog: BookCatalog,
    private val controller: BooksController,
    private val prep: BookPrep,
    private val connectFolder: (Uri) -> Unit,
    private val cachedCount: () -> Int?,
    private val coverOf: suspend (Book) -> ImageBitmap?,
    private val now: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(BooksUiState(cachedCount = cachedCount()))
    val state: StateFlow<BooksUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var prepJob: Job? = null
    private var noticeJob: Job? = null

    /** Re-reads the grant and rescans. Called on resume, so books added from a PC show up. */
    fun refresh() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true) }
        scanJob = scope.launch {
            val loaded = withContext(io) { catalog.load() }
            apply(loaded)
            if (loaded is CatalogState.Ready) prepare(loaded.items)
        }
    }

    private fun apply(loaded: CatalogState) {
        _state.update { current ->
            when (loaded) {
                CatalogState.NoFolder -> current.copy(
                    folder = BooksFolderState.None, scanning = false, scanned = false, unreadable = false,
                    items = emptyList(), resume = null,
                )
                is CatalogState.Lost -> current.copy(
                    folder = BooksFolderState.Lost(loaded.label), scanning = false, scanned = false,
                    unreadable = false, items = emptyList(), resume = null,
                )
                is CatalogState.Unreadable -> current.copy(
                    folder = BooksFolderState.Granted(loaded.label), scanning = false, scanned = true,
                    unreadable = true, items = emptyList(), resume = null,
                )
                is CatalogState.Ready -> current.copy(
                    folder = BooksFolderState.Granted(loaded.label), scanning = false, scanned = true,
                    unreadable = false, items = loaded.items, resume = resumeFor(loaded.items),
                    cachedCount = loaded.items.size,
                )
            }
        }
    }

    private fun resumeFor(items: List<BookItem>): ContinueUi? =
        items.filter { it.lastReadAtMs != null }.maxByOrNull { it.lastReadAtMs ?: 0L }
            ?.let { ContinueUi(it, it.continueLine(now())) }

    /** Covers and EPUB titles, one book at a time, off the main thread; tiles re-ask as each lands. */
    private fun prepare(items: List<BookItem>) {
        prepJob?.cancel()
        prepJob = scope.launch {
            for (item in items) {
                val result = withContext(io) { prep.prepare(item) }
                if (!result.changed) continue
                _state.update { current ->
                    val updated = current.items.map {
                        if (it.book.documentId == item.book.documentId) {
                            it.copy(title = result.title ?: it.title, author = result.author ?: it.author)
                        } else {
                            it
                        }
                    }
                    current.copy(items = updated, resume = resumeFor(updated), coverVersion = current.coverVersion + 1)
                }
            }
        }
    }

    /** The raw `OPEN_DOCUMENT_TREE` result, straight from the picker. */
    fun onFolderPicked(uri: Uri) {
        connectFolder(uri)
        refresh()
    }

    /** A tile, or the Read button. */
    fun read(item: BookItem) = open(item, listen = false)

    /** The Listen button. */
    fun listen(item: BookItem) = open(item, listen = true)

    private fun open(item: BookItem, listen: Boolean) {
        scope.launch { show(controller.open(item, listen)) }
    }

    /** The Continue card's Read and Listen buttons. */
    fun continueReading(listen: Boolean) {
        scope.launch { show(controller.continueReading(listen)) }
    }

    private fun show(outcome: BookOutcome) {
        val notice = when (outcome) {
            is BookOutcome.Opened -> BookNotice(outcome.sentence, failed = false)
            is BookOutcome.Failed -> BookNotice(outcome.sentence, failed = true)
        }
        noticeJob?.cancel()
        _state.update { it.copy(notice = notice) }
        if (!notice.failed) {
            noticeJob = scope.launch {
                delay(NOTICE_MS)
                _state.update { it.copy(notice = null) }
            }
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _state.update { it.copy(notice = null) }
    }

    /** The decoded cover, or null: none cached yet, a recorded miss, or unreadable. */
    suspend fun cover(book: Book): ImageBitmap? = coverOf(book)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val folders = BooksFolderStore(app)
            val safCatalog = SafBookCatalog(app, folders)
            val cache = BookCoverCache(File(app.filesDir, "book_covers"))
            val covers = BookCovers(cache, ReadiumEpub(app), app.contentResolver, File(app.cacheDir, "comics"))
            return BooksViewModel(
                catalog = safCatalog,
                controller = BooksController.forContext(app),
                prep = SafBookPrep(app, safCatalog, covers, cache),
                connectFolder = folders::connect,
                cachedCount = folders::cachedCount,
                coverOf = { book ->
                    withContext(Dispatchers.IO) {
                        cache.cached(book.documentId)?.let(BookCovers::decode)?.asImageBitmap()
                    }
                },
            ) as T
        }
    }
}

/** The system folder picker (`OPEN_DOCUMENT_TREE`) wired to [BooksViewModel]; call the result to open it. */
@androidx.compose.runtime.Composable
fun rememberBooksFolderPicker(viewModel: BooksViewModel): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onFolderPicked) }
    return { launcher.launch(null) }
}
