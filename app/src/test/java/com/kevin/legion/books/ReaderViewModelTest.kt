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

        override suspend fun row(documentId: String) = row

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
}
