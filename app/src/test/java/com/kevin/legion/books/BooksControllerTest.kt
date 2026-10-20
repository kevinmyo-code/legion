package com.kevin.legion.books

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksControllerTest {
    private class FakeLauncher(var shows: Boolean = true) : ReaderLauncher {
        val launched = mutableListOf<Pair<Book, Boolean>>()
        override suspend fun launch(book: Book, listen: Boolean): Boolean {
            launched += book to listen
            return shows
        }
    }

    private fun item(title: String, format: BookFormat = BookFormat.EPUB, lastReadAtMs: Long? = null) = BookItem(
        Book("id-$title", "$title.${format.extension}", format), title, null, 0.2, "Ch. 3", 0, 0, null, lastReadAtMs,
    )

    private fun controller(
        state: CatalogState,
        launcher: ReaderLauncher = FakeLauncher(),
        tts: Boolean = true,
    ) = BooksController({ state }, launcher, { tts })

    private fun ready(vararg items: BookItem) = CatalogState.Ready("/sdcard/Books", items.toList())

    private fun failed(o: BookOutcome) = (o as BookOutcome.Failed).sentence

    @Test fun `no folder says nothing was opened and where to fix it`() = runBlocking {
        val said = failed(controller(CatalogState.NoFolder).continueReading(listen = false))
        assertTrue(said, "nothing was opened" in said && "Books sheet" in said)
    }

    @Test fun `a lost grant names the folder and says nothing was opened`() = runBlocking {
        val said = failed(controller(CatalogState.Lost("/sdcard/Books")).openByName("moby", listen = false))
        assertTrue(said, "/sdcard/Books" in said && "nothing was opened" in said)
    }

    @Test fun `an unreadable folder is not described as empty`() = runBlocking {
        val said = failed(controller(CatalogState.Unreadable("/sdcard/Books")).continueReading(false))
        assertTrue(said, "would not list" in said && "holds no" !in said)
    }

    @Test fun `an empty folder says so`() = runBlocking {
        val said = failed(controller(ready()).continueReading(false))
        assertTrue(said, "holds no EPUB" in said && "nothing was opened" in said)
    }

    @Test fun `a name that matches nothing says nothing was opened`() = runBlocking {
        val launcher = FakeLauncher()
        val said = failed(controller(ready(item("Moby-Dick")), launcher).openByName("dune", listen = false))
        assertTrue(said, "could not find" in said && "nothing was opened" in said)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `an ambiguous name lists the candidates and opens none`() = runBlocking {
        val launcher = FakeLauncher()
        val said = failed(
            controller(ready(item("Volume 1", BookFormat.CBZ), item("Volume 2", BookFormat.CBZ)), launcher)
                .openByName("volume", listen = false),
        )
        assertTrue(said, "Volume 1" in said && "Volume 2" in said && "Nothing was opened" in said)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `continue with no history says there is nothing to continue`() = runBlocking {
        val said = failed(controller(ready(item("Moby-Dick"))).continueReading(false))
        assertTrue(said, "nothing to continue" in said)
    }

    @Test fun `continue opens the most recently read book`() = runBlocking {
        val launcher = FakeLauncher()
        val outcome = controller(
            ready(item("Old", lastReadAtMs = 10), item("New", lastReadAtMs = 99), item("Never")),
            launcher,
        ).continueReading(listen = true)
        assertEquals(listOf("New"), launcher.launched.map { it.first.fileTitle })
        assertTrue(launcher.launched.single().second)
        assertTrue((outcome as BookOutcome.Opened).listening)
    }

    @Test fun `a cbr never reaches the reader and says why`() = runBlocking {
        val launcher = FakeLauncher()
        val said = failed(controller(ready(item("Krazy Kat", BookFormat.CBR)), launcher).openByName("krazy", false))
        assertTrue(said, BookFormat.CBR_UNSUPPORTED in said && "Nothing was opened" in said)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `listen is refused for a pdf and a comic in words`() = runBlocking {
        for (format in listOf(BookFormat.PDF, BookFormat.CBZ)) {
            val launcher = FakeLauncher()
            val said = failed(controller(ready(item("X", format)), launcher).openByName("x", listen = true))
            assertEquals(BookFormat.LISTEN_EBOOKS_ONLY, said)
            assertTrue(launcher.launched.isEmpty())
        }
    }

    @Test fun `a pdf still opens to read`() = runBlocking {
        val launcher = FakeLauncher()
        val outcome = controller(ready(item("Manual", BookFormat.PDF)), launcher).openByName("manual", false)
        assertTrue(outcome is BookOutcome.Opened)
        assertEquals(1, launcher.launched.size)
    }

    @Test fun `no text-to-speech engine refuses listen before opening anything`() = runBlocking {
        val launcher = FakeLauncher()
        val said = failed(controller(ready(item("Moby-Dick")), launcher, tts = false).openByName("moby", listen = true))
        assertTrue(said, "no text-to-speech engine" in said && "nothing was opened" in said)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test fun `a reader that never showed is a failure, not an opened book`() = runBlocking {
        val outcome = controller(ready(item("Moby-Dick")), FakeLauncher(shows = false)).openByName("moby", false)
        assertTrue(failed(outcome), "was not opened" in failed(outcome))
    }

    @Test fun `success names the book and says only what happened`() = runBlocking {
        val outcome = controller(ready(item("Moby-Dick"))).openByName("moby dick", listen = false) as BookOutcome.Opened
        assertEquals("Opened \"Moby-Dick\".", outcome.sentence)
        val listening = controller(ready(item("Moby-Dick")))
            .openByName("moby dick", listen = true) as BookOutcome.Opened
        assertTrue(listening.sentence, "asked it to read aloud" in listening.sentence)
    }

    @Test fun `a blank name opens nothing`() = runBlocking {
        val said = failed(controller(ready(item("Moby-Dick"))).openByName("  ", false))
        assertTrue(said, "nothing was opened" in said)
    }
}
