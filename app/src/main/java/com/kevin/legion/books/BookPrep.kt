package com.kevin.legion.books

import android.content.Context
import com.kevin.legion.data.local.CarDatabase

/** What preparing one book (its cover and, for an EPUB, its own title) came to. */
data class PrepResult(val changed: Boolean, val title: String? = null, val author: String? = null)

/** Reads a book's cover and metadata from the file and keeps them. A seam so the ViewModel tests need no files. */
fun interface BookPrep {
    suspend fun prepare(item: BookItem): PrepResult
}

/**
 * The production [BookPrep]: nothing to do when the cover is cached (or a miss is still fresh) and
 * an EPUB's metadata was already read, so opening the sheet never re-parses the library. The title
 * and author are stored in Room, so they survive the next scan.
 */
class SafBookPrep(
    context: Context,
    private val catalog: SafBookCatalog,
    private val covers: BookCovers,
    private val cache: BookCoverCache,
) : BookPrep {
    private val dao = CarDatabase.getDatabase(context.applicationContext).bookDao()

    override suspend fun prepare(item: BookItem): PrepResult {
        val book = item.book
        val needsCover = cache.cached(book.documentId) == null && !cache.missIsFresh(book.documentId)
        val needsMeta = book.format == BookFormat.EPUB && dao.get(book.documentId)?.metadataRead != true
        if (!needsCover && !needsMeta) return PrepResult(changed = false)
        val uri = catalog.uriOf(book) ?: return PrepResult(changed = false)
        val info = covers.prepare(book, uri)
        if (book.format == BookFormat.EPUB) {
            if (info != null) {
                dao.setMetadata(book.documentId, info.title ?: item.title, info.author)
            } else {
                dao.markMetadataRead(book.documentId)
            }
        }
        return PrepResult(changed = true, title = info?.title, author = info?.author)
    }
}
