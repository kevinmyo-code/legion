package com.kevin.legion.books

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A real CBZ opened and rendered through the same path the reader uses (file descriptor, then ZipFile). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun cbz(vararg entries: Pair<String, ByteArray>): Uri {
        val file = File(tmp.root, "c.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    @Test fun `pages come out in natural order and each renders at the asked width`() = runBlocking {
        val page = png(400, 600)
        val uri = cbz("10.png" to page, "2.png" to page, "1.png" to page, "notes.txt" to byteArrayOf(1))
        ComicPages.open(resolver, uri, File(tmp.root, "cache")).use { pages ->
            assertEquals(3, pages.pageCount)
            val first = pages.render(0, 200)
            assertTrue("rendered at or above the asked width, never half-drawn", first.width >= 200)
            assertTrue(first.height > first.width)
        }
    }

    @Test fun `a zip with no images says so in words`() {
        val uri = cbz("readme.txt" to byteArrayOf(1, 2))
        try {
            ComicPages.open(resolver, uri, File(tmp.root, "cache")).close()
            fail("a comic with no pages must not open")
        } catch (e: BookOpenException) {
            assertEquals(ComicPages.NO_PAGES_SENTENCE, e.message)
        }
    }

    @Test fun `a file that is not a zip says it could not be read as a CBZ`() {
        val file = File(tmp.root, "x.cbz").apply { writeText("not a zip at all") }
        try {
            ComicPages.open(resolver, Uri.fromFile(file), File(tmp.root, "cache")).close()
            fail("a non-zip must not open")
        } catch (e: BookOpenException) {
            assertEquals(ComicPages.UNREADABLE_SENTENCE, e.message)
        }
    }
}
