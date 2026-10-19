package com.kevin.legion.books

/**
 * The file kinds the Books launcher lists (ADR 0061). CBR is listed but NOT readable: Readium does
 * not open RAR and Kevin ruled out junrar (2026-10-19), so opening one says so in words
 * ([CBR_UNSUPPORTED]) and a `.cbr` still sits on the Comics shelf so it is never silently missing.
 */
enum class BookFormat(
    val extension: String,
    val shelf: BookShelf,
    /** The short tag drawn on a tile and in the sheet, in words: "EPUB". */
    val label: String,
    val readable: Boolean,
) {
    EPUB("epub", BookShelf.EBOOKS, "EPUB", readable = true),
    PDF("pdf", BookShelf.DOCUMENTS, "PDF", readable = true),
    CBZ("cbz", BookShelf.COMICS, "CBZ", readable = true),
    CBR("cbr", BookShelf.COMICS, "CBR", readable = false),
    ;

    /** Read-aloud is for ebooks only: PdfRenderer has no text layer and a comic has no text. */
    val listenable: Boolean get() = this == EPUB

    companion object {
        const val CBR_UNSUPPORTED = "CBR is not supported. Convert it to CBZ to read it here."
        const val LISTEN_EBOOKS_ONLY = "Listen is for ebooks. This is not an EPUB, so nothing was read aloud."

        fun fromFileName(fileName: String): BookFormat? {
            if ('.' !in fileName) return null
            val ext = fileName.substringAfterLast('.').lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}

/** A row of the sheet. Declaration order is display order. */
enum class BookShelf(val title: String) {
    EBOOKS("Ebooks"),
    DOCUMENTS("Documents"),
    COMICS("Comics"),
}
