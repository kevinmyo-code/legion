package com.kevin.legion.books

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Covers kept on the phone, one JPEG per book ([dir], keyed by the document id's SHA-1). A book whose
 * cover could not be read gets a `.miss` marker holding the epoch millis after which to try again, so
 * opening the sheet never re-parses a file known to have none (and a damaged file costs one attempt a
 * day). Nothing is fetched: ADR 0061, covers come from the files themselves.
 */
class BookCoverCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    fun cached(documentId: String): File? = File(dir, "${key(documentId)}.jpg").takeIf { it.exists() }

    /** True while a recorded miss is still fresh, i.e. do not try this book again yet. */
    fun missIsFresh(documentId: String): Boolean {
        val miss = File(dir, "${key(documentId)}.miss").takeIf { it.exists() } ?: return false
        val retryAt = miss.readText().trim().toLongOrNull() ?: return false
        return now() < retryAt
    }

    fun put(documentId: String, bitmap: Bitmap) {
        dir.mkdirs()
        File(dir, "${key(documentId)}.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        File(dir, "${key(documentId)}.miss").delete()
    }

    fun recordMiss(documentId: String) {
        dir.mkdirs()
        File(dir, "${key(documentId)}.miss").writeText("${now() + RETRY_MS}")
    }

    private fun key(documentId: String): String =
        MessageDigest.getInstance("SHA-1").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val RETRY_MS = 24L * 60 * 60 * 1000
        private const val JPEG_QUALITY = 85
    }
}

/**
 * Produces a book's cover and, for an EPUB, the title and author its own metadata states. The
 * format decides how: EPUB from Readium, PDF from its first page, CBZ from its first image in
 * natural order. CBR has no cover (it cannot be read), so it records a miss and the tile says the
 * title and "CBR" in words. Returns the EPUB's [EpubInfo] so the caller can store the title.
 */
class BookCovers(
    private val cache: BookCoverCache,
    private val epub: ReadiumEpub,
    private val resolver: ContentResolver,
    private val comicCacheDir: File,
) {
    @Suppress("TooGenericExceptionCaught") // one unreadable file must not stop the rest of the shelf
    suspend fun prepare(book: Book, uri: Uri): EpubInfo? {
        var info: EpubInfo? = null
        val cover: Bitmap? = try {
            when (book.format) {
                BookFormat.EPUB -> {
                    info = epub.inspect(uri)
                    info?.cover
                }
                BookFormat.PDF -> PdfPages.open(resolver, uri).use { it.render(0, COVER_WIDTH_PX) }
                BookFormat.CBZ -> ComicPages.open(resolver, uri, comicCacheDir).use { it.render(0, COVER_WIDTH_PX) }
                BookFormat.CBR -> null
            }
        } catch (e: Exception) {
            null
        }
        withContext(Dispatchers.IO) {
            val scaled = cover?.let(::scaled)
            if (scaled != null) cache.put(book.documentId, scaled) else cache.recordMiss(book.documentId)
        }
        return info
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= COVER_WIDTH_PX) return bitmap
        val height = (bitmap.height.toFloat() * COVER_WIDTH_PX / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, COVER_WIDTH_PX, height, true)
    }

    companion object {
        const val COVER_WIDTH_PX = 360

        /** Decodes a cached cover file, or null if it cannot be decoded. */
        fun decode(file: File): android.graphics.Bitmap? = BitmapFactory.decodeFile(file.absolutePath)
    }
}
