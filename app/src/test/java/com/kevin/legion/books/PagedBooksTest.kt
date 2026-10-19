package com.kevin.legion.books

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CBZ page selection and ordering, the cover cache's miss rule, and the zoom sampling arithmetic. */
class PagedBooksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `comic pages are only images, in natural order`() {
        val pages = ComicPageNames.pages(
            listOf("ComicInfo.xml", "p10.jpg", "p2.JPG", "p1.png", "notes.txt", "sub/", "p3.webp"),
        )
        assertEquals(listOf("p1.png", "p2.JPG", "p3.webp", "p10.jpg"), pages)
    }

    @Test fun `mac resource forks and hidden files are not pages`() {
        val names = listOf("__MACOSX/._01.jpg", ".hidden.jpg", "01.jpg", "dir/._02.jpg", "dir/02.jpg")
        val pages = ComicPageNames.pages(names)
        assertEquals(listOf("01.jpg", "dir/02.jpg"), pages)
    }

    @Test fun `pages in folders sort by full path so chapters stay in order`() {
        val pages = ComicPageNames.pages(listOf("ch10/01.jpg", "ch2/01.jpg", "ch2/02.jpg", "ch1/01.jpg"))
        assertEquals(listOf("ch1/01.jpg", "ch2/01.jpg", "ch2/02.jpg", "ch10/01.jpg"), pages)
    }

    @Test fun `a real zip of images lists its pages the way the viewer will`() {
        val file = File(tmp.root, "x.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("10.jpg", "9.jpg", "1.jpg", "readme.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        val names = ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertEquals(listOf("1.jpg", "9.jpg", "10.jpg"), ComicPageNames.pages(names))
    }

    @Test fun `sample size keeps the page at least as wide as the screen`() {
        assertEquals(1, ComicPages.sampleFor(1000, 1080))
        assertEquals(2, ComicPages.sampleFor(2400, 1080))
        assertEquals(4, ComicPages.sampleFor(4400, 1080))
        assertEquals(1, ComicPages.sampleFor(0, 1080))
    }

    @Test fun `a recorded cover miss is not retried until a day has passed`() {
        var now = 1_000L
        val cache = BookCoverCache(tmp.newFolder("covers"), { now })
        assertFalse(cache.missIsFresh("doc"))
        cache.recordMiss("doc")
        assertTrue(cache.missIsFresh("doc"))
        now += BookCoverCache.RETRY_MS - 1
        assertTrue(cache.missIsFresh("doc"))
        now += 2
        assertFalse(cache.missIsFresh("doc"))
    }

    @Test fun `a book with no cached cover reports none`() {
        assertEquals(null, BookCoverCache(tmp.newFolder("c2")).cached("doc"))
    }
}
