package com.kevin.legion.books

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner

/**
 * What the reader derives from a real (generated) EPUB through Readium: the file's own title and
 * author, the contents list, and the place words the bottom bar and Continue card use. The WebView
 * itself cannot be drawn on the JVM; this is the part around it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalReadiumApi::class)
class EpubSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val epub = ReadiumEpub(ApplicationProvider.getApplicationContext())

    private fun fixture(chapters: Int = 3): Uri =
        Uri.fromFile(EpubFixture.write(File(tmp.root, "b.epub"), "Moby Fixture", "H. Melville", chapters))

    @Test fun `inspect reads the title and author the file states`() = runBlocking {
        val info = epub.inspect(fixture())
        assertNotNull(info)
        assertEquals("Moby Fixture", info!!.title)
        assertEquals("H. Melville", info.author)
    }

    @Test fun `a file that is not an epub is a sentence, not a crash`() = runBlocking {
        val bad = File(tmp.root, "bad.epub").apply { writeText("this is not a zip") }
        val result = epub.open(Uri.fromFile(bad))
        assertTrue(result is EpubOpenResult.Failed)
        assertTrue((result as EpubOpenResult.Failed).sentence.startsWith("This EPUB could not be"))
        assertNull(epub.inspect(Uri.fromFile(bad)))
    }

    @Test fun `the contents list is the books own chapters in order`() = runBlocking {
        val opened = epub.open(fixture(3)) as EpubOpenResult.Opened
        val session = EpubSession.of(opened.publication)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), session.toc.map { it.title })
        assertEquals(listOf(0, 0, 0), session.toc.map { it.depth })
        assertNotNull(session.linkAt(2))
        assertNull(session.linkAt(3))
    }

    @Test fun `a place inside a chapter names the chapter and gives a page and a saved locator`() = runBlocking {
        val opened = epub.open(fixture(2)) as EpubOpenResult.Opened
        val session = EpubSession.of(opened.publication)
        val locator = opened.publication.locatorFromLink(session.linkAt(1)!!)!!
        val place = session.describe(locator)
        assertEquals("Chapter 2", place.chapterLabel)
        assertTrue(place.positionLabel, place.positionLabel.startsWith("Page ") || place.positionLabel == "Chapter 2")
        assertTrue(place.locatorJson.contains("href"))
        assertTrue(place.progress in 0.0..1.0)
    }

    @Test fun `a locator from this book opens exactly there, a foreign one opens near by progress`() = runBlocking {
        val opened = epub.open(fixture(3)) as EpubOpenResult.Opened
        val session = EpubSession.of(opened.publication)
        val own = session.describe(opened.publication.locatorFromLink(session.linkAt(1)!!)!!)
        val exact = session.startFor(own.locatorJson, own.progress)
        assertEquals(own.locatorJson, exact.locatorJson)
        assertEquals(false, exact.near)

        // Another toolkit's locator naming a chapter file this book does not have: fall back, and say near.
        val foreign = """{"href":"no-such-chapter.xhtml","type":"application/xhtml+xml"}"""
        val near = session.startFor(foreign, own.progress)
        assertEquals(true, near.near)
        assertNotNull(near.locatorJson)
        assertTrue(near.locatorJson!!.contains("href"))

        // No saved place at all is the start of the book, not a guess.
        assertEquals(StartPlace(null, near = false), session.startFor(null, 0.0))
    }

    @Test fun `time left is an estimate in words`() {
        assertEquals("14 min left in chapter", timeLeftLabel(14))
        assertEquals("Less than a minute left in chapter", timeLeftLabel(0))
        assertEquals(0, minutesLeftInChapter(-3))
    }
}
