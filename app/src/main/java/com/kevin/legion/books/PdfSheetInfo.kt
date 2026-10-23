package com.kevin.legion.books

/**
 * What the Books sheet shows about PDF conversion (ticket 05): which PDFs are resizable, and the
 * subtitle tail ("2 PDFs waiting for Wi-Fi"), null when nothing is pending.
 */
data class PdfSheetInfo(val resizable: Set<String> = emptySet(), val pendingLine: String? = null) {
    companion object {
        val None = PdfSheetInfo()

        /** The production reader of it: cached hashes and Room rows only, so no PDF is read to draw a sheet. */
        suspend fun of(conversions: AndroidPdfConversions, items: List<BookItem>): PdfSheetInfo {
            val pdfs = items.map { it.book }.filter { it.format == BookFormat.PDF }
            if (pdfs.isEmpty()) return None
            val resizable = pdfs.filter { conversions.isResizable(it) }.map { it.documentId }.toSet()
            val line = PdfConversionSentences.pendingLine(conversions.pendingCount(pdfs), conversions.serverNote())
            return PdfSheetInfo(resizable, line)
        }
    }
}
