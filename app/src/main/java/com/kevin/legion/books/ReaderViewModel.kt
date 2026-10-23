package com.kevin.legion.books

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookRow
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val readSpread: (String) -> Boolean? = { null },
    private val writeSpread: (String, Boolean) -> Unit = { _, _ -> },
    private val conversions: PdfConversions = PdfConversions.None,
    /** Open the PDF itself even when it has an EPUB copy ("Open original"). */
    private val openOriginal: Boolean = false,
    /** Reading sync (ticket 09): pulls a newer place before opening, queues every save and bookmark. */
    private val sync: ReaderSync = ReaderSync.None,
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

    // A paged book's real place is a source page and a half (see [Spot]); display indexes derive from it.
    private var aspects: List<Float> = emptyList()
    private var spot = Spot(0)
    private var splitSetting = true
    private var landscape = false
    private var pagedSource: PageSource? = null

    /**
     * The book id positions and bookmarks are saved under: [documentId] itself, or the EPUB copy's own
     * id ([ConvertedBooks.derivedId]) once a PDF opens as its converted EPUB, so the two never share
     * a locator or a page number.
     */
    private var readId: String = documentId

    init {
        this.scope.launch { load() }
    }

    @Suppress("LongMethod") // one branch per kind of book, each a few lines; splitting would hide the order
    private suspend fun load() {
        val row = store.row(documentId)
        val format = row?.let { runCatching { BookFormat.valueOf(it.format) }.getOrNull() }
        if (row == null || format == null) {
            fail("This book is no longer in the library. Open the Books sheet on HOME to rescan the folder.")
            return
        }
        val book = Book(row.documentId, row.fileName, format)
        val reading = resolveReading(row, book)
        val readBook = reading.book
        val readFormat = readBook.format
        val copy = reading.copied
        readId = readBook.documentId
        // A newer place from another device is pulled BEFORE the starting place is fixed, but never
        // for longer than PICK_UP_MS: an unreachable engine must not hold the page hostage.
        val pickedUp = withTimeoutOrNull(PICK_UP_MS) { sync.pickUp(readId) }
        val readRow = if (pickedUp != null) store.row(readId) ?: reading.row else reading.row
        _state.update {
            it.copy(
                title = row.title, format = readFormat, bookmarks = emptyList(),
                convertedFromPdf = copy, conversionNote = reading.note,
            )
        }
        when (val loaded = loader.load(readBook)) {
            is LoadedBook.Failed -> fail(loaded.sentence)
            is LoadedBook.Paged -> {
                val resume = ResumePoint.from(readRow, loaded.source.pageCount)
                pagedSource = loaded.source
                aspects = runCatching { loaded.source.aspects() }.getOrDefault(emptyList())
                splitSetting = readSpread(documentId) ?: true
                // An old whole-page position has no locator and resumes at that page's left half.
                spot = Spot.saved(resume.pageIndex, resume.locatorJson)
                val map = currentMap(loaded.source.pageCount)
                _state.update {
                    it.copy(
                        subtitle = "${readFormat.label} · ${pagesLabel(loaded.source.pageCount)}",
                        content = ReaderContent.Paged(loaded.source, map.indexOf(spot)),
                        spread = map,
                        spreadSplit = splitSetting,
                        landscapeScreen = landscape,
                    )
                }
                updatePaged(map.indexOf(spot), save = false)
                pickedUp?.let { picked ->
                    _state.update { it.copy(syncNote = ReadingSyncSentences.pickedUp(picked.device, near = false)) }
                }
            }
            is LoadedBook.Epub -> {
                session = loaded.session
                val start = loaded.session.startFor(readRow.locatorJson, readRow.progress)
                epubStart = start.locatorJson
                readAloud = readAloudFor(loaded.session, { control?.firstVisibleLocatorJson() }, scope)
                lastEpubJson = start.locatorJson
                _state.update {
                    it.copy(
                        syncNote = pickedUp?.let { picked -> ReadingSyncSentences.pickedUp(picked.device, start.near) },
                        subtitle = readRow.author ?: if (copy) "Resizable PDF copy" else "EPUB",
                        content = ReaderContent.Epub,
                        toc = loaded.session.toc,
                        progress = readRow.progress.toFloat(),
                        percentLabel = "${(readRow.progress * PERCENT).toInt()}%",
                    )
                }
                scope.launch { readAloud?.state?.collect { ra -> onReadAloud(ra) } }
            }
        }
        _state.update { it.copy(bookmarks = store.bookmarks(readId)) }
    }

    /** What the reader actually opens for a book: the book itself, or its EPUB copy with the copy's own row. */
    private class Reading(val book: Book, val row: BookRow, val copied: Boolean, val note: String?)

    /** A PDF with a finished conversion opens as its EPUB copy unless the user asked for the original. */
    private suspend fun resolveReading(row: BookRow, book: Book): Reading {
        val isPdf = book.format == BookFormat.PDF
        val copy = if (isPdf && !openOriginal) conversions.copyFor(book) else null
        if (copy == null) {
            return Reading(book, row, copied = false, note = if (isPdf) conversions.noteFor(book) else null)
        }
        val epub = Book(ConvertedBooks.derivedId(book.documentId), row.fileName, BookFormat.EPUB)
        return Reading(epub, copiedRow(row, epub), copied = true, note = null)
    }

    /** The EPUB copy's own book row, made on first open: same title, no position yet. */
    private suspend fun copiedRow(pdf: BookRow, copy: Book): BookRow {
        store.ensureRow(
            pdf.copy(
                documentId = copy.documentId, format = BookFormat.EPUB.name, progress = 0.0, chapterLabel = null,
                pageIndex = 0, pageCount = 0, locatorJson = null, lastReadAtMs = null,
            ),
        )
        return store.row(copy.documentId) ?: pdf
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

    private fun currentMap(pageCount: Int) = SpreadMap.of(aspects, splitSetting && !landscape, pageCount)

    /** A PDF or comic page turn; [index] is a display page. */
    fun onPage(index: Int) {
        if (state.value.content !is ReaderContent.Paged) return
        spot = _state.value.spread.spotOf(index)
        updatePaged(index, save = true)
    }

    /** The screen turned. Same spot, new mapping: portrait splits spreads, landscape shows them whole. */
    fun setLandscape(isLandscape: Boolean) {
        if (landscape == isLandscape) return
        landscape = isLandscape
        remap()
    }

    /** The Aa choice between splitting spreads and showing them whole, remembered for this book. */
    fun setSpreadSplit(split: Boolean) {
        if (splitSetting == split) return
        splitSetting = split
        writeSpread(documentId, split)
        remap()
    }

    private fun remap() {
        val source = pagedSource ?: return
        val map = currentMap(source.pageCount)
        // One update carries the map and the page index in it, so the pager is never rebuilt on half of it.
        _state.update {
            it.copy(
                spread = map, spreadSplit = splitSetting, landscapeScreen = landscape, pageIndex = map.indexOf(spot),
            )
        }
        updatePaged(map.indexOf(spot), save = false)
    }

    private fun savePaged(progress: Double) {
        val pages = pagedSource?.pageCount ?: return
        val at = now()
        scope.launch {
            store.savePosition(readId, progress, null, spot.page, pages, spot.locator, at)
            sync.positionSaved(readId, progress, null, PagedLocator.of(spot.page), at)
        }
    }

    private fun updatePaged(index: Int, save: Boolean) {
        val count = _state.value.spread.count
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
        if (save) savePaged(progress)
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
        val at = now()
        scope.launch {
            store.savePosition(readId, place.progress, place.chapterLabel, 0, 0, place.locatorJson, at)
            sync.positionSaved(readId, place.progress, place.chapterLabel, place.locatorJson, at)
        }
    }

    /** Saves once more on leaving, in case the last change has not been written. */
    fun flush() {
        val place = lastEpubPlace
        val current = _state.value
        scope.launch {
            if (place != null) {
                val at = now()
                store.savePosition(readId, place.progress, place.chapterLabel, 0, 0, place.locatorJson, at)
                sync.positionSaved(readId, place.progress, place.chapterLabel, place.locatorJson, at)
            } else if (current.content is ReaderContent.Paged) {
                savePaged(current.progress.toDouble())
            }
            sync.kick()
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

    fun nextTheme() = changeSettings { it.nextTheme() }

    fun toggleWarm() = changeSettings { it.copy(warmOn = !it.warmOn) }

    fun setWarmStrength(strength: Int) =
        changeSettings { it.copy(warmStrength = strength.coerceIn(0, WarmFilter.MAX_STRENGTH)) }

    fun setWarmAuto(auto: Boolean) = changeSettings { it.copy(warmAuto = auto) }

    fun setFullScreen(on: Boolean) = changeSettings { it.copy(fullScreen = on) }

    /** A tap in the middle third: show or hide the reader's own top and bottom bars. */
    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

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
            documentId = readId,
            label = label,
            locatorJson = place?.locatorJson,
            pageIndex = if (s.content is ReaderContent.Paged) spot.page else s.pageIndex,
            progress = s.progress.toDouble(),
            createdAtMs = now(),
            // Chosen here so a phone with no signal can still create a bookmark the engine will accept.
            syncUuid = java.util.UUID.randomUUID().toString(),
        )
        scope.launch {
            store.addBookmark(bookmark)
            sync.bookmarkAdded(readId, bookmark)
            sync.kick()
            refreshBookmarks()
        }
    }

    fun deleteBookmark(bookmark: BookBookmark) {
        scope.launch {
            store.deleteBookmark(bookmark.id)
            sync.bookmarkDeleted(bookmark)
            sync.kick()
            refreshBookmarks()
        }
    }

    fun goToBookmark(bookmark: BookBookmark) {
        // A bookmark's pageIndex is a source page, so it lands right whatever the spread mode is now.
        bookmark.locatorJson?.let { control?.goToLocator(it) }
            ?: goToPage(_state.value.spread.indexOf(Spot(bookmark.pageIndex)))
        closePanel()
    }

    private suspend fun refreshBookmarks() {
        val rows = store.bookmarks(readId)
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
        const val PICK_UP_MS = 4_000L
    }

    /** Builds the production ViewModel for one book. */
    class Factory(
        context: Context,
        private val documentId: String,
        private val listen: Boolean,
        private val openOriginal: Boolean = false,
    ) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val folders = BooksFolderStore(app)
            val settings = ReaderSettingsStore(app)
            val readium = ReadiumEpub(app)
            val conversions = AndroidPdfConversions(app)
            val sync = AndroidReaderSync(app)
            val loader = ReaderLoader { book ->
                // The EPUB copy of a PDF lives in app-private storage, not under the granted folder.
                if (ConvertedBooks.isDerived(book.documentId)) {
                    return@ReaderLoader openCopy(book, conversions, readium)
                }
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
                readSpread = settings::spreadSplit,
                writeSpread = settings::writeSpreadSplit,
                conversions = conversions,
                openOriginal = openOriginal,
                sync = sync,
            ) as T
        }

        private suspend fun openCopy(book: Book, conversions: AndroidPdfConversions, readium: ReadiumEpub): LoadedBook {
            val pdf = Book(ConvertedBooks.originalId(book.documentId), book.fileName, BookFormat.PDF)
            val copy = conversions.copyFor(pdf)
                ?: return LoadedBook.Failed("The resizable copy of this PDF is gone. Open the original instead.")
            return withContext(Dispatchers.IO) { open(book, Uri.fromFile(copy.file), readium) }
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
