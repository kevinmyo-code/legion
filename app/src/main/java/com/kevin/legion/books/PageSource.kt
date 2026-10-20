package com.kevin.legion.books

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The paged formats' common face: PDF and comics are both "page N as a bitmap of this width". */
interface PageSource : Closeable {
    val pageCount: Int

    /** Page [index] (0-based) rendered [widthPx] wide. Never returns a half-drawn page. */
    suspend fun render(index: Int, widthPx: Int): Bitmap

    /**
     * Width over height of every page, or empty when the source cannot say cheaply. Only the PDF
     * answers: comics are left unsplit because a spread there may read right to left (manga), and
     * "left half first" would be wrong.
     */
    suspend fun aspects(): List<Float> = emptyList()
}

/** Why a paged book could not be opened, in words the reader shows. */
class BookOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A PDF through the platform's `PdfRenderer` (ADR 0061: no PdfBox, no Pdfium adapter). The renderer
 * allows ONE open page at a time, so renders are serialised on [lock].
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PageSource {
    private val lock = Mutex()
    override val pageCount: Int get() = renderer.pageCount

    override suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                // A PDF page is transparent where nothing is drawn; paper white keeps it readable on any theme.
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override suspend fun aspects(): List<Float> = lock.withLock {
        withContext(Dispatchers.IO) {
            List(renderer.pageCount) { i ->
                renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        const val PASSWORD_SENTENCE = "This PDF is password protected, and LEGION cannot open protected PDFs."
        const val UNREADABLE_SENTENCE = "This PDF could not be read. It may be damaged."

        // each failure becomes a distinct sentence the reader shows, so the throws are not one idea
        @Suppress("SwallowedException", "ThrowsCount")
        fun open(resolver: ContentResolver, uri: Uri): PdfPages {
            val descriptor = resolver.openFileDescriptor(uri, "r")
                ?: throw BookOpenException("Android would not open this file. Pick the Books folder again.")
            return try {
                PdfPages(descriptor, PdfRenderer(descriptor))
            } catch (e: SecurityException) {
                descriptor.close()
                throw BookOpenException(PASSWORD_SENTENCE, e)
            } catch (e: IOException) {
                descriptor.close()
                throw BookOpenException(UNREADABLE_SENTENCE, e)
            }
        }
    }
}

/** Pure: which archive entries are comic pages, in reading order. */
object ComicPageNames {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun pages(entryNames: List<String>): List<String> =
        entryNames.filter { isPage(it) }.sortedWith(NaturalOrder)

    private fun isPage(name: String): Boolean {
        // macOS resource forks and hidden files are not pages ("._x" also starts with a dot).
        val hidden = name.split('/').any { it == "__MACOSX" || it.startsWith(".") }
        return !name.endsWith("/") && !hidden && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }
}

/**
 * A CBZ read as a zip of images, sorted naturally (ADR 0061). Opens the document's file descriptor
 * by its `/proc/self/fd` path so `ZipFile` can seek without copying the archive; where the provider
 * does not allow that, the archive is copied once to [cacheDir] and read from there.
 */
class ComicPages private constructor(
    private val zip: ZipFile,
    private val names: List<String>,
) : PageSource {
    private val lock = Mutex()
    override val pageCount: Int get() = names.size

    override suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) { decode(index, widthPx) }
    }

    private fun decode(index: Int, widthPx: Int): Bitmap {
        val entry = zip.getEntry(names[index])
            ?: throw BookOpenException("Page ${index + 1} is missing from this comic.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, widthPx) }
        return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw BookOpenException("Page ${index + 1} of this comic could not be decoded.")
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        const val UNREADABLE_SENTENCE = "This comic could not be read as a CBZ. It may be damaged or not a zip."
        const val NO_PAGES_SENTENCE = "This CBZ holds no images, so there is nothing to show."

        /** The power-of-two sample size that keeps a page at least [widthPx] wide. */
        fun sampleFor(sourceWidth: Int, widthPx: Int): Int {
            if (sourceWidth <= 0 || widthPx <= 0) return 1
            var sample = 1
            while (sourceWidth / (sample * 2) >= widthPx) sample *= 2
            return sample
        }

        @Suppress("SwallowedException") // each failure becomes a sentence the reader shows
        fun open(resolver: ContentResolver, uri: Uri, cacheDir: File): ComicPages {
            val zip = try {
                openZip(resolver, uri, cacheDir)
            } catch (e: IOException) { // ZipException is an IOException
                throw BookOpenException(UNREADABLE_SENTENCE, e)
            }
            val names = ComicPageNames.pages(zip.entries().asSequence().map { it.name }.toList())
            if (names.isEmpty()) {
                zip.close()
                throw BookOpenException(NO_PAGES_SENTENCE)
            }
            return ComicPages(zip, names)
        }

        private fun openZip(resolver: ContentResolver, uri: Uri, cacheDir: File): ZipFile {
            val direct = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { ZipFile("/proc/self/fd/${it.fd}") }
            }.getOrNull()
            if (direct != null) return direct
            cacheDir.mkdirs()
            val copy = File(cacheDir, "comic-${uri.toString().hashCode()}.cbz")
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw BookOpenException("Android would not open this file. Pick the Books folder again.")
            return ZipFile(copy)
        }
    }
}
