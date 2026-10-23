package com.kevin.legion.books

import android.graphics.Bitmap
import com.kevin.legion.data.local.BookBookmark
import com.kevin.legion.data.local.BookRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's contract with a fake store and a fake page source: position is saved on every page
 * turn, a reopen resumes exactly there, a saved page past the end clamps, Listen is refused in words
 * for PDF and comics, and bookmarks round-trip. No Context, no Readium, no files.
 */
class ReaderViewModelTest {
    private class FakeStore(var row: BookRow?) : ReaderStore {
        val marks = mutableListOf<BookBookmark>()
        var saves = 0

        val ensured = mutableListOf<BookRow>()

        override suspend fun row(documentId: String) = ensured.firstOrNull { it.documentId == documentId } ?: row

        override suspend fun savePosition(
            documentId: String,
            progress: Double,
            chapterLabel: String?,
            pageIndex: Int,
            pageCount: Int,
            locatorJson: String?,
            atMs: Long,
        ) {
            saves++
            row = row?.copy(
                progress = progress, chapterLabel = chapterLabel, pageIndex = pageIndex, pageCount = pageCount,
                locatorJson = locatorJson, lastReadAtMs = atMs,
            )
        }

        override suspend fun ensureRow(row: BookRow) {
            if (ensured.none { it.documentId == row.documentId }) ensured += row
        }

        override suspend fun bookmarks(documentId: String) = marks.toList()

        override suspend fun addBookmark(bookmark: BookBookmark) {
            marks += bookmark.copy(id = marks.size + 1L)
        }

        override suspend fun deleteBookmark(id: Long) {
            marks.removeAll { it.id == id }
        }
    }

    private class FakePages(override val pageCount: Int) : PageSource {
        override suspend fun render(index: Int, widthPx: Int): Bitmap = error("not rendered in this test")

        override fun close() = Unit
    }

    private fun row(format: BookFormat, pageIndex: Int = 0, pageCount: Int = 0) = BookRow(
        documentId = "doc", fileName = "book.${format.extension}", format = format.name, title = "A Book",
        author = null, metadataRead = true, progress = 0.0, chapterLabel = null, pageIndex = pageIndex,
        pageCount = pageCount, locatorJson = null, lastReadAtMs = null, firstSeenAtMs = 0L,
    )

    private fun model(store: FakeStore, loaded: LoadedBook, listen: Boolean = false): ReaderViewModel =
        ReaderViewModel(
            documentId = "doc",
            listenRequested = listen,
            store = store,
            loader = { loaded },
            readAloudFor = { _, _, _ -> error("no read-aloud for a paged book") },
            readSettings = { ReaderSettings() },
            writeSettings = {},
            now = { 123L },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private class FakeConversions(
        private val copy: ConvertedCopy? = null,
        private val note: String? = null,
        private val spent: Boolean = false,
    ) : PdfConversions {
        var retried = 0

        override suspend fun copyFor(book: Book) = copy

        override suspend fun noteFor(book: Book) = if (retried > 0) PdfConversionSentences.WAITING else note

        override suspend fun canRetry(book: Book) = spent && retried == 0

        override suspend fun retry(book: Book) {
            retried++
        }
    }

    private fun converting(
        store: FakeStore,
        conversions: PdfConversions,
        openOriginal: Boolean = false,
        seen: MutableList<Book> = mutableListOf(),
    ) = ReaderViewModel(
        documentId = "doc",
        listenRequested = false,
        store = store,
        loader = { book ->
            seen += book
            LoadedBook.Failed("stub")
        },
        readAloudFor = { _, _, _ -> error("unused") },
        now = { 123L },
        scope = CoroutineScope(Dispatchers.Unconfined),
        conversions = conversions,
        openOriginal = openOriginal,
    )

    private val copy = ConvertedCopy(java.io.File("x.epub"), "sha")

    @Test fun `a PDF with a finished conversion opens as its EPUB copy under its own book id`() {
        val store = FakeStore(row(BookFormat.PDF, pageIndex = 7, pageCount = 40))
        val seen = mutableListOf<Book>()
        val vm = converting(store, FakeConversions(copy = copy), seen = seen)
        val opened = seen.single()
        assertEquals(ConvertedBooks.derivedId("doc"), opened.documentId)
        assertEquals(BookFormat.EPUB, opened.format)
        assertEquals(BookFormat.EPUB, vm.state.value.format)
        assertTrue(vm.state.value.convertedFromPdf)
        // The copy starts with no position of its own and the PDF's row is untouched.
        val own = store.ensured.single()
        assertEquals(ConvertedBooks.derivedId("doc"), own.documentId)
        assertEquals(0, own.pageIndex)
        assertNull(own.locatorJson)
        assertEquals(7, store.row!!.pageIndex)
    }

    @Test fun `bookmarks on the EPUB copy are kept under the copy's id, never the PDF's`() {
        val store = FakeStore(row(BookFormat.PDF))
        val vm = converting(store, FakeConversions(copy = copy))
        vm.addBookmark()
        assertEquals(ConvertedBooks.derivedId("doc"), store.marks.single().documentId)
    }

    @Test fun `Open original loads the PDF itself even when a copy exists`() {
        val seen = mutableListOf<Book>()
        val store = FakeStore(row(BookFormat.PDF))
        val vm = converting(store, FakeConversions(copy = copy), openOriginal = true, seen = seen)
        assertEquals("doc", seen.single().documentId)
        assertEquals(BookFormat.PDF, seen.single().format)
        assertFalse(vm.state.value.convertedFromPdf)
    }

    @Test fun `a PDF with no copy shows the conversion line in words`() {
        val vm = converting(FakeStore(row(BookFormat.PDF)), FakeConversions(note = PdfConversionSentences.WAITING))
        assertEquals("Waiting for Wi-Fi to convert", vm.state.value.conversionNote)
        assertFalse(vm.state.value.convertedFromPdf)
    }

    @Test fun `a spent conversion offers Try again, which resets it and says the PDF is waiting`() {
        val conversions = FakeConversions(note = "Could not convert after 3 tries: x", spent = true)
        val vm = converting(FakeStore(row(BookFormat.PDF)), conversions)
        assertTrue(vm.state.value.conversionCanRetry)
        vm.onRetryConversion()
        assertEquals(1, conversions.retried)
        assertFalse(vm.state.value.conversionCanRetry)
        assertEquals(PdfConversionSentences.WAITING, vm.state.value.conversionNote)
    }

    @Test fun `a conversion with tries left offers no Try again`() {
        val vm = converting(FakeStore(row(BookFormat.PDF)), FakeConversions(note = "Waiting"))
        assertFalse(vm.state.value.conversionCanRetry)
        vm.onRetryConversion()
        assertEquals("Waiting", vm.state.value.conversionNote)
    }

    @Test fun `an EPUB or a comic never asks about conversions`() {
        val vm = converting(FakeStore(row(BookFormat.CBZ)), FakeConversions(copy = copy, note = "no"))
        assertNull(vm.state.value.conversionNote)
        assertFalse(vm.state.value.convertedFromPdf)
    }

    @Test fun `a first open starts on page one`() {
        val vm = model(FakeStore(row(BookFormat.PDF)), LoadedBook.Paged(FakePages(40)))
        val content = vm.state.value.content as ReaderContent.Paged
        assertEquals(0, content.startPage)
        assertEquals("Page 1 of 40", vm.state.value.positionLabel)
        assertEquals("PDF · 40 pages", vm.state.value.subtitle)
    }

    @Test fun `every page turn is saved with its progress`() {
        val store = FakeStore(row(BookFormat.CBZ))
        val vm = model(store, LoadedBook.Paged(FakePages(11)))
        vm.onPage(5)
        assertEquals(5, store.row!!.pageIndex)
        assertEquals(11, store.row!!.pageCount)
        assertEquals(0.5, store.row!!.progress, 1e-9)
        assertEquals(123L, store.row!!.lastReadAtMs)
        assertEquals("50%", vm.state.value.percentLabel)
        vm.onPage(6)
        assertEquals(6, store.row!!.pageIndex)
    }

    @Test fun `reopening resumes exactly where the last session turned`() {
        val store = FakeStore(row(BookFormat.PDF))
        model(store, LoadedBook.Paged(FakePages(40))).onPage(17)
        // A second ViewModel over the same store is the reader opened again.
        val reopened = model(store, LoadedBook.Paged(FakePages(40)))
        assertEquals(17, (reopened.state.value.content as ReaderContent.Paged).startPage)
        assertEquals("Page 18 of 40", reopened.state.value.positionLabel)
    }

    @Test fun `a saved page past the end of a shorter file clamps to the last page`() {
        val vm = model(FakeStore(row(BookFormat.CBZ, pageIndex = 90, pageCount = 100)), LoadedBook.Paged(FakePages(30)))
        assertEquals(29, (vm.state.value.content as ReaderContent.Paged).startPage)
    }

    @Test fun `resume point ignores a missing row`() {
        assertEquals(ResumePoint(0, null, 0.0), ResumePoint.from(null))
        assertEquals(3, ResumePoint.from(row(BookFormat.PDF, pageIndex = 3), pageCount = 10).pageIndex)
    }

    @Test fun `a book missing from the library fails in words and offers the fix`() {
        val vm = model(FakeStore(null), LoadedBook.Paged(FakePages(1)))
        val failed = vm.state.value.content as ReaderContent.Failed
        assertTrue(failed.sentence, "rescan" in failed.sentence)
    }

    @Test fun `an unreadable book shows the loader's sentence in place of the page`() {
        val vm = model(FakeStore(row(BookFormat.PDF)), LoadedBook.Failed(PdfPages.PASSWORD_SENTENCE))
        assertEquals(ReaderContent.Failed(PdfPages.PASSWORD_SENTENCE), vm.state.value.content)
    }

    @Test fun `listen on a pdf is refused in words and starts nothing`() {
        val vm = model(FakeStore(row(BookFormat.PDF)), LoadedBook.Paged(FakePages(3)))
        vm.toggleListen()
        assertEquals(BookFormat.LISTEN_EBOOKS_ONLY, vm.state.value.listenNote)
        assertFalse(vm.state.value.readAloud.active)
    }

    @Test fun `listen on a comic is refused the same way`() {
        val vm = model(FakeStore(row(BookFormat.CBZ)), LoadedBook.Paged(FakePages(3)))
        vm.toggleListen()
        assertEquals(BookFormat.LISTEN_EBOOKS_ONLY, vm.state.value.listenNote)
    }

    @Test fun `settings persist and the theme changes the palette`() {
        var written: ReaderSettings? = null
        val vm = ReaderViewModel(
            "doc", false, FakeStore(row(BookFormat.PDF)), { LoadedBook.Paged(FakePages(2)) },
            { _, _, _ -> error("unused") }, { ReaderSettings() }, { written = it }, { 1L },
            CoroutineScope(Dispatchers.Unconfined),
        )
        vm.setTheme(ReaderTheme.SEPIA)
        vm.biggerText()
        assertEquals(ReaderTheme.SEPIA, vm.state.value.settings.theme)
        assertEquals(18, vm.state.value.settings.textSize)
        assertEquals(vm.state.value.settings, written)
    }

    @Test fun `the comfort settings change, persist and the chrome toggles`() {
        var written: ReaderSettings? = null
        val vm = ReaderViewModel(
            "doc", false, FakeStore(row(BookFormat.PDF)), { LoadedBook.Paged(FakePages(2)) },
            { _, _, _ -> error("unused") }, { ReaderSettings() }, { written = it }, { 1L },
            CoroutineScope(Dispatchers.Unconfined),
        )
        assertTrue(vm.state.value.chromeVisible)
        vm.toggleChrome()
        assertFalse(vm.state.value.chromeVisible)
        vm.toggleChrome()
        assertTrue(vm.state.value.chromeVisible)
        vm.setTheme(ReaderTheme.DAY)
        vm.nextTheme()
        assertEquals(ReaderTheme.SEPIA, vm.state.value.settings.theme)
        vm.toggleWarm()
        vm.setWarmStrength(300)
        vm.setWarmAuto(true)
        vm.setFullScreen(false)
        val s = vm.state.value.settings
        assertTrue(s.warmOn)
        assertEquals(WarmFilter.MAX_STRENGTH, s.warmStrength)
        assertTrue(s.warmAuto)
        assertFalse(s.fullScreen)
        assertEquals(s, written)
        assertTrue(ReaderSettings().fullScreen)
    }

    @Test fun `text size stays inside the prototype's range`() {
        var s = ReaderSettings()
        repeat(30) { s = s.biggerText() }
        assertEquals(ReaderSettings.MAX_TEXT_SIZE, s.textSize)
        repeat(30) { s = s.smallerText() }
        assertEquals(ReaderSettings.MIN_TEXT_SIZE, s.textSize)
    }

    @Test fun `a panel toggles open and shut`() {
        val vm = model(FakeStore(row(BookFormat.PDF)), LoadedBook.Paged(FakePages(2)))
        vm.showPanel(ReaderPanel.SETTINGS)
        assertEquals(ReaderPanel.SETTINGS, vm.state.value.panel)
        vm.showPanel(ReaderPanel.SETTINGS)
        assertEquals(ReaderPanel.NONE, vm.state.value.panel)
    }

    @Test fun `a bookmark is added with its place in words, listed, jumped to and deleted`() {
        val store = FakeStore(row(BookFormat.PDF))
        val vm = model(store, LoadedBook.Paged(FakePages(40)))
        vm.onPage(9)
        vm.addBookmark()
        val mark = vm.state.value.bookmarks.single()
        assertEquals("Page 10 of 40 · 23%", mark.label)
        assertEquals(9, mark.pageIndex)
        vm.goToBookmark(mark)
        assertEquals(9, vm.pageRequest.value)
        vm.onPageRequestHandled()
        assertNull(vm.pageRequest.value)
        vm.deleteBookmark(mark)
        assertTrue(vm.state.value.bookmarks.isEmpty())
    }

    @Test fun `jumping to a page closes the panel and asks the pager`() {
        val vm = model(FakeStore(row(BookFormat.PDF)), LoadedBook.Paged(FakePages(40)))
        vm.showPanel(ReaderPanel.CONTENTS)
        vm.goToPage(30)
        assertEquals(30, vm.pageRequest.value)
        assertEquals(ReaderPanel.NONE, vm.state.value.panel)
        assertNotNull(vm.state.value.content)
    }

    // ---- reading sync (ticket 09) -------------------------------------------------------------

    private class FakeSync(var picked: PickedUp? = null, val onPick: suspend (String) -> Unit = {}) : ReaderSync {
        val saved = mutableListOf<Triple<String, Double, String?>>()
        val added = mutableListOf<Pair<String, BookBookmark>>()
        val deleted = mutableListOf<BookBookmark>()
        var kicks = 0
        val picks = mutableListOf<String>()

        override suspend fun pickUp(readId: String): PickedUp? {
            picks += readId
            onPick(readId)
            return picked
        }

        override suspend fun positionSaved(
            readId: String,
            progress: Double,
            chapterLabel: String?,
            wireLocator: String?,
            atMs: Long,
        ) {
            saved += Triple(readId, progress, wireLocator)
        }

        override suspend fun bookmarkAdded(readId: String, bookmark: BookBookmark) {
            added += readId to bookmark
        }

        override suspend fun bookmarkDeleted(bookmark: BookBookmark) {
            deleted += bookmark
        }

        override fun kick() {
            kicks++
        }
    }

    private fun syncing(store: FakeStore, sync: ReaderSync, conversions: PdfConversions = PdfConversions.None) =
        ReaderViewModel(
            documentId = "doc",
            listenRequested = false,
            store = store,
            loader = { LoadedBook.Paged(FakePages(40)) },
            readAloudFor = { _, _, _ -> error("unused") },
            now = { 123L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            conversions = conversions,
            sync = sync,
        )

    @Test fun `a book picked up from another device opens there and says so in words`() {
        val store = FakeStore(row(BookFormat.PDF))
        val sync = FakeSync(PickedUp("web")) { store.row = store.row?.copy(pageIndex = 22, lastReadAtMs = 999) }
        val vm = syncing(store, sync)
        assertEquals("the page the pull wrote, not the stale row read before it", 22,
            (vm.state.value.content as ReaderContent.Paged).startPage)
        assertEquals("Picked up where you left off on the web.", vm.state.value.syncNote)
    }

    @Test fun `a book nothing moved says nothing`() {
        val vm = syncing(FakeStore(row(BookFormat.PDF, pageIndex = 4)), FakeSync(null))
        assertNull(vm.state.value.syncNote)
        assertEquals(4, (vm.state.value.content as ReaderContent.Paged).startPage)
    }

    @Test fun `every page turn is queued for sync with the page as its locator`() {
        val sync = FakeSync()
        val vm = syncing(FakeStore(row(BookFormat.PDF)), sync)
        vm.onPage(5)
        val (readId, progress, locator) = sync.saved.last()
        assertEquals("doc", readId)
        assertEquals(5 / 39.0, progress, 1e-9)
        assertEquals(PagedLocator.of(5), locator)
    }

    @Test fun `leaving the reader kicks a send`() {
        val sync = FakeSync()
        val vm = syncing(FakeStore(row(BookFormat.PDF)), sync)
        vm.flush()
        assertEquals(1, sync.kicks)
    }

    @Test fun `a bookmark gets its engine id at creation and its delete is queued`() {
        val sync = FakeSync()
        val vm = syncing(FakeStore(row(BookFormat.PDF)), sync)
        vm.addBookmark()
        val (readId, mark) = sync.added.single()
        assertEquals("doc", readId)
        assertTrue("an offline phone must be able to mint one", !mark.syncUuid.isNullOrEmpty())
        vm.deleteBookmark(mark)
        assertEquals(mark.syncUuid, sync.deleted.single().syncUuid)
        assertEquals(2, sync.kicks)
    }

    @Test fun `the EPUB copy of a PDF syncs under its own read id so it maps to the resizable variant`() {
        val sync = FakeSync()
        val vm = ReaderViewModel(
            documentId = "doc", listenRequested = false, store = FakeStore(row(BookFormat.PDF)),
            loader = { LoadedBook.Failed("stub") }, readAloudFor = { _, _, _ -> error("unused") },
            now = { 123L }, scope = CoroutineScope(Dispatchers.Unconfined),
            conversions = FakeConversions(copy = copy), sync = sync,
        )
        assertEquals(listOf(ConvertedBooks.derivedId("doc")), sync.picks)
        assertEquals(BookFormat.EPUB, vm.state.value.format)
    }
}
