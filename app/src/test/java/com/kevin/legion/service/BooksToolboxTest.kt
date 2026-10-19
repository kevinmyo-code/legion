package com.kevin.legion.service

import com.kevin.legion.books.Book
import com.kevin.legion.books.BookCatalog
import com.kevin.legion.books.BookFormat
import com.kevin.legion.books.BookItem
import com.kevin.legion.books.BooksController
import com.kevin.legion.books.CatalogState
import com.kevin.legion.books.ReaderLauncher
import com.kevin.legion.books.forContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `books` voice tool (books ticket 02): that it calls the same [BooksController] the sheet does
 * (ADR 0035) and that every failure says what did NOT happen (CLAUDE.md section 7's outcome-verb
 * rule): no folder, lost grant, book not found, CBR, listen on a non-ebook, no TTS engine, a reader
 * that never came up. Success is reported only after the (fake) reader was seen to open.
 */
@RunWith(RobolectricTestRunner::class)
class BooksToolboxTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun item(title: String, format: BookFormat = BookFormat.EPUB, lastReadAtMs: Long? = null) = BookItem(
        Book("id-$title", "$title.${format.extension}", format), title, null, 0.3, null, 0, 0, null, lastReadAtMs,
    )

    private class Launcher(val shows: Boolean = true) : ReaderLauncher {
        val launched = mutableListOf<Pair<String, Boolean>>()
        override suspend fun launch(book: Book, listen: Boolean): Boolean {
            launched += book.documentId to listen
            return shows
        }
    }

    private fun install(state: CatalogState, launcher: Launcher = Launcher(), tts: Boolean = true): Launcher {
        BooksToolbox.controllerFactory = { BooksController({ state }, launcher, { tts }) }
        return launcher
    }

    private fun call(args: JSONObject): JSONObject =
        runBlocking { BooksToolbox.dispatch(context, "books", args) }!!

    private fun args(action: String, title: String? = null, listen: Boolean? = null) = JSONObject().put("action", action)
        .apply { title?.let { put("title", it) } }
        .apply { listen?.let { put("listen", it) } }

    @After fun tearDown() {
        BooksToolbox.controllerFactory = { BooksController.forContext(it) }
    }

    @Test fun `it only answers its own name`() {
        assertEquals(null, runBlocking { BooksToolbox.dispatch(context, "bought_log", JSONObject()) })
    }

    @Test fun `the declaration is one books tool with an action`() {
        val decl = BooksToolbox.declarations().getJSONObject(0)
        assertEquals("books", decl.getString("name"))
        val props = decl.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("action") && props.has("title") && props.has("listen"))
    }

    @Test fun `opening a named book succeeds only after the reader showed`() {
        val launcher = install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby-Dick"))))
        val r = call(args("open", "moby dick"))
        assertTrue(r.getBoolean("success"))
        assertEquals("Opened \"Moby-Dick\".", r.getString("message"))
        assertEquals(listOf("id-Moby-Dick" to false), launcher.launched)
    }

    @Test fun `read aloud asks the reader to listen and says no more than that`() {
        val launcher = install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby-Dick"))))
        val r = call(args("open", "moby", listen = true))
        assertTrue(r.getBoolean("success"))
        assertTrue(r.getString("message"), "asked it to read aloud" in r.getString("message"))
        assertEquals(listOf("id-Moby-Dick" to true), launcher.launched)
    }

    @Test fun `continue reopens the book read last`() {
        val launcher = install(
            CatalogState.Ready("/sdcard/Books", listOf(item("Old", lastReadAtMs = 1), item("New", lastReadAtMs = 9))),
        )
        assertTrue(call(args("continue")).getBoolean("success"))
        assertEquals("id-New", launcher.launched.single().first)
    }

    @Test fun `no folder says nothing was opened and where to fix it`() {
        install(CatalogState.NoFolder)
        val r = call(args("continue"))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "nothing was opened" in r.getString("message"))
        assertTrue("Books sheet" in r.getString("message"))
    }

    @Test fun `a lost folder grant names the folder`() {
        install(CatalogState.Lost("/sdcard/Books"))
        val r = call(args("open", "moby"))
        assertFalse(r.getBoolean("success"))
        assertTrue("/sdcard/Books" in r.getString("message") && "nothing was opened" in r.getString("message"))
    }

    @Test fun `a book that is not there says nothing was opened`() {
        val launcher = install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby-Dick"))))
        val r = call(args("open", "dune"))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "could not find" in r.getString("message"))
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `an empty folder is not described as a missing book`() {
        install(CatalogState.Ready("/sdcard/Books", emptyList()))
        val r = call(args("open", "dune"))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "holds no" in r.getString("message"))
    }

    @Test fun `listen on a pdf fails in words and opens nothing`() {
        val launcher = install(CatalogState.Ready("/sdcard/Books", listOf(item("Manual", BookFormat.PDF))))
        val r = call(args("open", "manual", listen = true))
        assertFalse(r.getBoolean("success"))
        assertEquals(BookFormat.LISTEN_EBOOKS_ONLY, r.getString("message"))
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `a cbr fails with the convert-to-cbz sentence`() {
        install(CatalogState.Ready("/sdcard/Books", listOf(item("Kat", BookFormat.CBR))))
        val r = call(args("open", "kat"))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message").startsWith(BookFormat.CBR_UNSUPPORTED))
    }

    @Test fun `no text-to-speech engine fails the listen request before anything opens`() {
        val launcher = install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby"))), tts = false)
        val r = call(args("open", "moby", listen = true))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "no text-to-speech engine" in r.getString("message"))
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `a reader that never came up is a failure not a success`() {
        install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby"))), Launcher(shows = false))
        val r = call(args("open", "moby"))
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "was not opened" in r.getString("message"))
    }

    @Test fun `two books that fit are named and none is opened`() {
        val launcher = install(
            CatalogState.Ready("/sdcard/Books", listOf(item("Volume 1", BookFormat.CBZ), item("Volume 2", BookFormat.CBZ))),
        )
        val r = call(args("open", "volume"))
        assertFalse(r.getBoolean("success"))
        assertTrue("Volume 1" in r.getString("message") && "Volume 2" in r.getString("message"))
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `an unknown action opens nothing`() {
        install(CatalogState.Ready("/sdcard/Books", listOf(item("Moby"))))
        val r = call(args("delete"))
        assertFalse(r.getBoolean("success"))
        assertTrue("Nothing was opened" in r.getString("message"))
    }
}
