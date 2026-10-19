package com.kevin.legion.ui.home

import com.kevin.legion.books.Book
import com.kevin.legion.books.BookCatalog
import com.kevin.legion.books.BookFormat
import com.kevin.legion.books.BookItem
import com.kevin.legion.books.BookPrep
import com.kevin.legion.books.BooksController
import com.kevin.legion.books.CatalogState
import com.kevin.legion.books.PrepResult
import com.kevin.legion.books.ReaderLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Books sheet's four states (no folder, lost permission, empty, populated) and the chip's words,
 * with fakes for the catalog, the reader and the cover pass. Unconfined scope: each launch runs to
 * the end before the call returns, so assertions read the state straight after.
 */
class BooksViewModelTest {
    private fun item(
        title: String,
        format: BookFormat = BookFormat.EPUB,
        lastReadAtMs: Long? = null,
        progress: Double = 0.0,
    ) = BookItem(
        Book("id-$title", "$title.${format.extension}", format), title, null, progress,
        "Ch. 3".takeIf { format == BookFormat.EPUB }, 4, 40, null, lastReadAtMs,
    )

    private class Launcher : ReaderLauncher {
        val launched = mutableListOf<Pair<String, Boolean>>()
        override suspend fun launch(book: Book, listen: Boolean): Boolean {
            launched += book.documentId to listen
            return true
        }
    }

    private fun model(
        state: CatalogState,
        launcher: Launcher = Launcher(),
        prep: BookPrep = BookPrep { PrepResult(changed = false) },
        cached: Int? = null,
    ): BooksViewModel {
        val catalog = BookCatalog { state }
        return BooksViewModel(
            catalog = catalog,
            controller = BooksController(catalog, launcher) { true },
            prep = prep,
            connectFolder = {},
            cachedCount = { cached },
            coverOf = { null },
            now = { 10_000_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
    }

    @Test fun `before any scan the chip says set up`() {
        val vm = model(CatalogState.NoFolder)
        assertEquals("set up", vm.state.value.chipSub)
        assertEquals(BooksFolderState.None, vm.state.value.folder)
    }

    @Test fun `no folder is said in words with the fix and no shelves`() {
        val vm = model(CatalogState.NoFolder)
        vm.refresh()
        val s = vm.state.value
        assertEquals(BooksFolderState.None, s.folder)
        assertEquals("set up", s.chipSub)
        assertTrue(s.shelves.isEmpty())
        assertFalse(s.scanning)
    }

    @Test fun `a lost permission is its own state, not an empty shelf`() {
        val vm = model(CatalogState.Lost("/sdcard/Books"))
        vm.refresh()
        val s = vm.state.value
        assertEquals(BooksFolderState.Lost("/sdcard/Books"), s.folder)
        assertTrue(s.items.isEmpty())
        assertEquals("set up", s.chipSub)
    }

    @Test fun `an unreadable folder is flagged and is not an empty folder`() {
        val vm = model(CatalogState.Unreadable("/sdcard/Books"))
        vm.refresh()
        assertTrue(vm.state.value.unreadable)
        assertEquals(BooksFolderState.Granted("/sdcard/Books"), vm.state.value.folder)
    }

    @Test fun `an empty folder is granted, scanned, empty and counts zero books`() {
        val vm = model(CatalogState.Ready("/sdcard/Books", emptyList()))
        vm.refresh()
        val s = vm.state.value
        assertTrue(s.scanned)
        assertFalse(s.unreadable)
        assertTrue(s.shelves.isEmpty())
        assertNull(s.resume)
        assertEquals("0 books", s.chipSub)
    }

    @Test fun `a populated folder builds the shelves in order and hides an empty shelf`() {
        val vm = model(
            CatalogState.Ready(
                "/sdcard/Books",
                listOf(
                    item("Nemo", BookFormat.CBZ),
                    item("Moby"),
                    item("Manual", BookFormat.PDF),
                    item("Kat", BookFormat.CBR),
                ),
            ),
        )
        vm.refresh()
        val shelves = vm.state.value.shelves
        assertEquals(listOf("Ebooks", "Documents", "Comics"), shelves.map { it.shelf.title })
        assertEquals("CBZ, CBR · 2", shelves.last().countLabel)
        assertEquals("EPUB · 1", shelves.first().countLabel)
        assertEquals("4 books", vm.state.value.chipSub)
    }

    @Test fun `a folder with only ebooks shows only the Ebooks shelf`() {
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Moby"))))
        vm.refresh()
        assertEquals(listOf("Ebooks"), vm.state.value.shelves.map { it.shelf.title })
        assertEquals("1 book", vm.state.value.chipSub)
    }

    @Test fun `the chip shows the cached count while the first scan runs`() {
        val vm = model(CatalogState.Ready("/sdcard/Books", emptyList()), cached = 7)
        assertEquals(7, vm.state.value.cachedCount)
    }

    @Test fun `the continue card is the most recently read book and says where`() {
        val vm = model(
            CatalogState.Ready(
                "/sdcard/Books",
                listOf(
                    item("Old", lastReadAtMs = 1_000_000L, progress = 0.1),
                    item("New", lastReadAtMs = 9_000_000L, progress = 0.38),
                    item("Never"),
                ),
            ),
        )
        vm.refresh()
        val resume = vm.state.value.resume!!
        assertEquals("New", resume.item.title)
        assertEquals("Ch. 3 · 38% · 16 min ago", resume.line)
    }

    @Test fun `a pdf continue card names its page`() {
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Manual", BookFormat.PDF, 9_999_999L, 0.1))))
        vm.refresh()
        assertEquals("Page 5 of 40 · 10% · just now", vm.state.value.resume!!.line)
    }

    @Test fun `titles and covers read from the files update the list and bump the cover version`() {
        val prep = BookPrep { PrepResult(changed = true, title = "Moby-Dick; or, The Whale", author = "Melville") }
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("moby_dick"))), prep = prep)
        vm.refresh()
        assertEquals("Moby-Dick; or, The Whale", vm.state.value.items.single().title)
        assertEquals("Melville", vm.state.value.items.single().author)
        assertEquals(1, vm.state.value.coverVersion)
    }

    @Test fun `read opens the book and the sheet says what happened`() {
        val launcher = Launcher()
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Moby"))), launcher)
        vm.refresh()
        vm.read(vm.state.value.items.single())
        assertEquals(listOf("id-Moby" to false), launcher.launched)
        val notice = vm.state.value.notice!!
        assertFalse(notice.failed)
        assertEquals("Opened \"Moby\".", notice.text)
    }

    @Test fun `listen on a comic is refused in words and nothing opens`() {
        val launcher = Launcher()
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Nemo", BookFormat.CBZ))), launcher)
        vm.refresh()
        vm.listen(vm.state.value.items.single())
        assertTrue(launcher.launched.isEmpty())
        val notice = vm.state.value.notice!!
        assertTrue(notice.failed)
        assertEquals(BookFormat.LISTEN_EBOOKS_ONLY, notice.text)
    }

    @Test fun `opening a cbr says it is unsupported and keeps the notice until dismissed`() {
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Kat", BookFormat.CBR))))
        vm.refresh()
        vm.read(vm.state.value.items.single())
        assertTrue(vm.state.value.notice!!.text.startsWith(BookFormat.CBR_UNSUPPORTED))
        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    @Test fun `continue buttons route through the controller`() {
        val launcher = Launcher()
        val vm = model(CatalogState.Ready("/sdcard/Books", listOf(item("Moby", lastReadAtMs = 5L))), launcher)
        vm.refresh()
        vm.continueReading(listen = true)
        assertEquals(listOf("id-Moby" to true), launcher.launched)
        assertNotNull(vm.state.value.notice)
    }
}
