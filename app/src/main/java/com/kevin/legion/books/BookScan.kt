package com.kevin.legion.books

import com.kevin.legion.games.RomTree
import com.kevin.legion.games.TreeChild
import com.kevin.legion.games.titleOf

/** One file found under the granted folder, before it is decided whether it is a book. */
data class BookEntry(val documentId: String, val fileName: String)

/**
 * A book LEGION lists. [documentId] is the SAF tree document id of the file: the only key every
 * other part (position, cover, reader) hangs off, as with a ROM (`games/RomScan.kt`).
 */
data class Book(
    val documentId: String,
    val fileName: String,
    val format: BookFormat,
) {
    /** The title as far as the file NAME says; an EPUB's own metadata replaces it once read. */
    val fileTitle: String get() = titleOf(fileName).replace('_', ' ').trim().ifEmpty { fileName }
}

/** Natural ordering: "page2" before "page10", case-insensitive. Used for titles and comic pages. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        var order = 0
        while (order == 0 && i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val ei = a.digitRunEnd(i)
                val ej = b.digitRunEnd(j)
                val ra = a.substring(i, ei).trimStart('0')
                val rb = b.substring(j, ej).trimStart('0')
                // A longer run (no leading zeros) is the larger number.
                order = if (ra.length != rb.length) ra.length.compareTo(rb.length) else ra.compareTo(rb)
                i = ei
                j = ej
            } else {
                order = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                i++
                j++
            }
        }
        if (order == 0) order = (a.length - i).compareTo(b.length - j)
        return if (order != 0) order else a.compareTo(b)
    }

    private fun String.digitRunEnd(from: Int): Int {
        var k = from
        while (k < length && this[k].isDigit()) k++
        return k
    }
}

/** Pure: turns the files found under the folder into the books to list. Sorted by title. */
object BookScan {
    fun books(entries: List<BookEntry>): List<Book> =
        entries.mapNotNull { e -> BookFormat.fromFileName(e.fileName)?.let { Book(e.documentId, e.fileName, it) } }
            .sortedWith { a, b -> NaturalOrder.compare(a.fileTitle, b.fileTitle) }
}

/** What a scan found. [unreadable] means the root itself could not be listed, which is not "empty". */
data class BookScanResult(val books: List<Book>, val unreadable: Boolean = false)

/**
 * Walks the granted root down to [MAX_DEPTH] levels. A folder that cannot be listed is skipped, not
 * fatal: the books already found are still real. The storage seam is the games package's [RomTree],
 * SAF in production and a map in tests.
 */
class BookLibrary(private val tree: RomTree) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider can throw anything
    fun scan(rootDocumentId: String): BookScanResult {
        val rootChildren = try {
            tree.children(rootDocumentId)
        } catch (e: Exception) {
            return BookScanResult(emptyList(), unreadable = true)
        }
        val entries = rootChildren.flatMap { entriesOf(it, depth = 1) }
        return BookScanResult(BookScan.books(entries))
    }

    private fun entriesOf(child: TreeChild, depth: Int): List<BookEntry> = when {
        !child.isDirectory -> listOf(BookEntry(child.documentId, child.name))
        depth < MAX_DEPTH -> safeChildren(child.documentId).flatMap { entriesOf(it, depth + 1) }
        else -> emptyList()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // one bad folder must not hide the rest
    private fun safeChildren(id: String): List<TreeChild> = try {
        tree.children(id)
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val MAX_DEPTH = 4
    }
}
