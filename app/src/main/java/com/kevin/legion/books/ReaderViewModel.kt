package com.kevin.legion.books

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.CarDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.json.JSONObject

/** What opening a book came to. */
sealed interface LoadedBook {
    data class Epub(val session: EpubSession) : LoadedBook

    data class Paged(val source: PageSource) : LoadedBook

    data class Failed(val sentence: String) : LoadedBook
}

fun interface ReaderLoader {
    suspend fun load(book: Book): LoadedBook
}

/** What the reader asks of the EPUB navigator. Implemented by the Compose host around Readium's fragment. */
interface EpubControl {
    fun goToLink(link: org.readium.r2.shared.publication.Link)

    fun goToLocator(locatorJson: String)

    /** The first sentence on screen, as locator JSON; where Listen starts. */
    suspend fun firstVisibleLocatorJson(): String?

    /** Highlights the sentence being read aloud and follows it; null clears the highlight. */
    fun highlight(locatorJson: String?)

    fun applySettings(settings: ReaderSettings)
}

/**
 * The reader's ViewModel (ADR 0061): one [StateFlow] of [ReaderUiState]. Position is saved on every
 * page turn ([onPage], [onEpubLocator]) and again on [flush], and reopening resumes at
 * [ResumePoint.from] the saved row, so "reopen resumes exactly" is a property of this class, not of
 * the screen. PDF and comics answer Listen in words; only an EPUB has a [ReadAloudController].
 */
@OptIn(ExperimentalReadiumApi::class)
@Suppress("TooManyFunctions") // one method per reader action
class ReaderViewModel(
    private val documentId: String,
    private val listenRequested: Boolean,
    private val store: ReaderStore,
    private val loader: ReaderLoader,
    private val readAloudFor: (EpubSession, suspend () -> String?, CoroutineScope) -> ReadAloudController,
    private val readSettings: () -> ReaderSettings = { ReaderSettings() },
    private val writeSettings: (ReaderSettings) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(ReaderUiState(settings = readSettings()))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** A page the contents/bookmark list asked for; the pager consumes it with [onPageRequestHandled]. */
    private val _pageRequest = MutableStateFlow<Int?>(null)
    val pageRequest: StateFlow<Int?> = _pageRequest.asStateFlow()

    private var session: EpubSession? = null

    /** The opened EPUB while [ReaderContent.Epub] is showing; the host builds the navigator from it. */
    val epubSession: EpubSession? get() = session
    private var control: EpubControl? = null
    private var readAloud: ReadAloudController? = null
    private var lastEpubJson: String? = null
    private var lastEpubPlace: EpubPlace? = null
    private var autoListenDone = false

    init {
        this.scope.launch { load() }
    }

    private suspend fun load() {
        val row = store.row(documentId)
        val format = row?.let { runCatching { BookFormat.valueOf(it.format) }.getOrNull() }
        if (row == null || format == null) {
            fail("This book is no longer in the library. Open the Books sheet on HOME to rescan the folder.")
            return
        }
        val book = Book(row.documentId, row.fileName, format)
        _state.update { it.copy(title = row.title, format = format, bookmarks = emptyList()) }
        when (val loaded = loader.load(book)) {
            is LoadedBook.Failed -> fail(loaded.sentence)
            is LoadedBook.Paged -> {
                val resume = ResumePoint.from(row, loaded.source.pageCount)
                _state.update {
                    it.copy(
                        subtitle = "${format.label} · ${pagesLabel(loaded.source.pageCount)}",
                        content = ReaderContent.Paged(loaded.source, resume.pageIndex),
                        pageIndex = resume.pageIndex,
                        pageCount = loaded.source.pageCount,
                    )
                }
                updatePaged(resume.pageIndex, loaded.source.pageCount, save = false)
            }
            is LoadedBook.Epub -> {
                session = loaded.session
                epubStart = row.locatorJson
                readAloud = readAloudFor(loaded.session, { control?.firstVisibleLocatorJson() }, scope)
                lastEpubJson = row.locatorJson
                _state.update {
                    it.copy(
                        subtitle = row.author ?: "EPUB",
                        content = ReaderContent.Epub,
                        toc = loaded.session.toc,
                        progress = row.progress.toFloat(),
                        percentLabel = "${(row.progress * PERCENT).toInt()}%",
                    )
                }
                scope.launch { readAloud?.state?.collect { ra -> onReadAloud(ra) } }
            }
        }
        _state.update { it.copy(bookmarks = store.bookmarks(documentId)) }
    }

    private fun fail(sentence: String) = _state.update { it.copy(content = ReaderContent.Failed(sentence)) }

    private fun pagesLabel(n: Int) = if (n == 1) "1 page" else "$n pages"

    // ---- the starting place -------------------------------------------------------------------

    /**
     * The locator JSON an EPUB reopens at, or null to start at the beginning. Fixed at load, BEFORE the
     * state says the EPUB is ready, so the navigator is never built ahead of the place it must resume at.
     */
    var epubStart: String? = null
        private set

    // ---- position -----------------------------------------------------------------------------

    /** A PDF or comic page turn. */
    fun onPage(index: Int) {
        val count = (state.value.content as? ReaderContent.Paged)?.source?.pageCount ?: return
        updatePaged(index, count, save = true)
    }

    private fun updatePaged(index: Int, count: Int, save: Boolean) {
        val progress = if (count <= 1) 0.0 else index.toDouble() / (count - 1)
        _state.update {
            it.copy(
                pageIndex = index,
                pageCount = count,
                positionLabel = "Page ${index + 1} of $count",
                percentLabel = "${(progress * PERCENT).toInt()}%",
                progress = progress.toFloat(),
                hasPosition = true,
            )
        }
        if (save) {
            scope.launch { store.savePosition(documentId, progress, null, index, count, null, now()) }
        }
    }

    /** The EPUB navigator moved (a page turn, a jump, or the read-aloud following a sentence). */
    fun onEpubLocator(locator: Locator) {
        val place = session?.describe(locator) ?: return
        applyPlace(place)
    }

    private fun applyPlace(place: EpubPlace) {
        lastEpubPlace = place
        lastEpubJson = place.locatorJson
        _state.update {
            it.copy(
                positionLabel = place.positionLabel,
                percentLabel = "${(place.progress * PERCENT).toInt()}%",
                timeLeft = place.timeLeft,
                progress = place.progress.toFloat(),
                hasPosition = true,
            )
        }
        scope.launch {
            store.savePosition(documentId, place.progress, place.chapterLabel, 0, 0, place.locatorJson, now())
        }
    }

    /** Saves once more on leaving, in case the last change has not been written. */
    fun flush() {
        val place = lastEpubPlace
        val current = _state.value
        scope.launch {
            if (place != null) {
                store.savePosition(documentId, place.progress, place.chapterLabel, 0, 0, place.locatorJson, now())
            } else if (current.content is ReaderContent.Paged) {
                store.savePosition(
                    documentId, current.progress.toDouble(), null, current.pageIndex, current.pageCount, null, now(),
                )
            }
        }
    }

    // ---- navigator ----------------------------------------------------------------------------

    /** The host attached (or detached with null) the EPUB navigator. */
    fun attach(epubControl: EpubControl?) {
        control = epubControl
        epubControl?.applySettings(_state.value.settings)
        if (epubControl != null && listenRequested && !autoListenDone) {
            autoListenDone = true
            toggleListen()
        }
    }

    // ---- panels, settings ---------------------------------------------------------------------

    fun showPanel(panel: ReaderPanel) =
        _state.update { it.copy(panel = if (it.panel == panel) ReaderPanel.NONE else panel) }

    fun closePanel() = _state.update { it.copy(panel = ReaderPanel.NONE) }

    fun setTheme(theme: ReaderTheme) = changeSettings { it.copy(theme = theme) }

    fun biggerText() = changeSettings { it.biggerText() }

    fun smallerText() = changeSettings { it.smallerText() }

    private fun changeSettings(change: (ReaderSettings) -> ReaderSettings) {
        val next = change(_state.value.settings)
        writeSettings(next)
        _state.update { it.copy(settings = next) }
        control?.applySettings(next)
    }

    // ---- contents, bookmarks ------------------------------------------------------------------

    fun goToToc(entry: TocEntry) {
        session?.linkAt(entry.index)?.let { control?.goToLink(it) }
        closePanel()
    }

    fun goToPage(index: Int) {
        _pageRequest.value = index
        closePanel()
    }

    fun onPageRequestHandled() {
        _pageRequest.value = null
    }

    fun addBookmark() {
        val s = _state.value
        val place = lastEpubPlace
        val label = place?.chapterLabel?.let { "$it · ${s.percentLabel}" } ?: "${s.positionLabel} · ${s.percentLabel}"
        val bookmark = BookBookmark(
            documentId = documentId,
            label = label,
            locatorJson = place?.locatorJson,
            pageIndex = s.pageIndex,
            progress = s.progress.toDouble(),
            createdAtMs = now(),
        )
        scope.launch {
            store.addBookmark(bookmark)
            refreshBookmarks()
        }
    }

    fun deleteBookmark(bookmark: BookBookmark) {
        scope.launch {
            store.deleteBookmark(bookmark.id)
            refreshBookmarks()
        }
    }

    fun goToBookmark(bookmark: BookBookmark) {
        bookmark.locatorJson?.let { control?.goToLocator(it) } ?: goToPage(bookmark.pageIndex)
        closePanel()
    }

    private suspend fun refreshBookmarks() {
        val rows = store.bookmarks(documentId)
        _state.update { it.copy(bookmarks = rows) }
    }

    // ---- read aloud ---------------------------------------------------------------------------

    /** The Listen button. */
    fun toggleListen() {
        val format = _state.value.format
        if (format == null || !format.listenable) {
            _state.update { it.copy(listenNote = BookFormat.LISTEN_EBOOKS_ONLY) }
            return
        }
        _state.update { it.copy(listenNote = null) }
        readAloud?.toggle(null)
    }

    fun cycleSpeed() {
        readAloud?.cycleSpeed()
    }

    fun stopListening() {
        readAloud?.stop()
    }

    private fun onReadAloud(ra: ReadAloudUi) {
        _state.update { it.copy(readAloud = ra) }
        control?.highlight(ra.utteranceJson)
        // Read-aloud moves on with the screen off; the saved place follows the voice.
        ra.utteranceJson?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let(::onEpubLocator)
        }
    }

    override fun onCleared() {
        readAloud?.stop()
        session?.publication?.close()
        (_state.value.content as? ReaderContent.Paged)?.source?.close()
        super.onCleared()
    }

    private companion object {
        const val PERCENT = 100
    }

    /** Builds the production ViewModel for one book. */
    class Factory(
        context: Context,
        private val documentId: String,
        private val listen: Boolean,
    ) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val folders = BooksFolderStore(app)
            val settings = ReaderSettingsStore(app)
            val readium = ReadiumEpub(app)
            val loader = ReaderLoader { book ->
                val tree = folders.treeUri()
                val uri: Uri? = tree?.let { com.kevin.legion.games.SafRomTree.documentUri(it, book.documentId) }
                when {
                    uri == null -> LoadedBook.Failed("No Books folder is set up. Pick one in the Books sheet on HOME.")
                    book.format == BookFormat.CBR -> LoadedBook.Failed(BookFormat.CBR_UNSUPPORTED)
                    else -> withContext(Dispatchers.IO) { open(book, uri, readium) }
                }
            }
            return ReaderViewModel(
                documentId = documentId,
                listenRequested = listen,
                store = RoomReaderStore(CarDatabase.getDatabase(app).bookDao()),
                loader = loader,
                readAloudFor = { session, firstVisible, scope ->
                    ReadAloudController(scope, AndroidAudioFocus(app)) { start ->
                        ReadiumReadAloudEngine.create(
                            app, session.publication, session.publication.metadata.title.orEmpty(),
                            start ?: firstVisible(), scope,
                        )
                    }
                },
                readSettings = settings::read,
                writeSettings = settings::write,
            ) as T
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // every failure becomes a sentence
        private suspend fun open(book: Book, uri: Uri, readium: ReadiumEpub): LoadedBook = try {
            when (book.format) {
                BookFormat.EPUB -> when (val opened = readium.open(uri)) {
                    is EpubOpenResult.Opened -> LoadedBook.Epub(EpubSession.of(opened.publication))
                    is EpubOpenResult.Failed -> LoadedBook.Failed(opened.sentence)
                }
                BookFormat.PDF -> LoadedBook.Paged(PdfPages.open(app.contentResolver, uri))
                BookFormat.CBZ ->
                    LoadedBook.Paged(ComicPages.open(app.contentResolver, uri, File(app.cacheDir, "comics")))
                BookFormat.CBR -> LoadedBook.Failed(BookFormat.CBR_UNSUPPORTED)
            }
        } catch (e: BookOpenException) {
            LoadedBook.Failed(e.message ?: "This book could not be opened.")
        } catch (e: Exception) {
            LoadedBook.Failed("This book could not be opened: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
