package com.kevin.legion.ledger.parsers

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.InputStream

/**
 * One word (whitespace-delimited run of characters) with its bounding box, in
 * PDF points. [y] increases downward from the page top (matches Python
 * pdfplumber's "top", NOT PDF user-space's bottom-up y) - PdfBox-Android's
 * `yDirAdj`/`xDirAdj` are already display/reading-order adjusted, confirmed
 * empirically in [PdfWordsSpikeTest] (row y-values increase top-to-bottom).
 */
data class PdfWord(val text: String, val x0: Float, val x1: Float, val y: Float)

/**
 * Per-word coordinate extraction - the Android equivalent of Python's
 * `pdfplumber.Page.extract_words()`, which Andromeda's DBS parser depends on
 * for column classification (date/description/withdrawal/deposit/balance are
 * distinguished by x-position, not by delimiters). Android has no built-in
 * PDF text-position API; PdfBox-Android's `PDFTextStripper` is subclassed here
 * to capture per-character [TextPosition]s and group them into words.
 *
 * [PDFBoxResourceLoader.init] must run once before use - it loads AFM font
 * metrics from Android assets. Safe to call repeatedly (idempotent per
 * PdfBox-Android's own contract).
 */
object PdfWords {
    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** All words on every page, in reading order, one list per page. */
    fun extractWords(input: InputStream): List<List<PdfWord>> {
        PDDocument.load(input).use { doc ->
            val pages = mutableListOf<MutableList<PdfWord>>()
            val stripper = object : PDFTextStripper() {
                private var current: MutableList<PdfWord>? = null

                override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                    current = mutableListOf<PdfWord>().also { pages.add(it) }
                    super.startPage(page)
                }

                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val page = current ?: return
                    var wordStart = 0
                    var i = 0
                    while (i <= textPositions.size) {
                        val atEnd = i == textPositions.size
                        val isSpace = !atEnd && text.getOrNull(i)?.isWhitespace() == true
                        if (atEnd || isSpace) {
                            if (i > wordStart) {
                                val word = text.substring(wordStart, i)
                                val first = textPositions[wordStart]
                                val last = textPositions[i - 1]
                                val x1 = last.xDirAdj + last.widthDirAdj
                                page.add(PdfWord(word, first.xDirAdj, x1, first.yDirAdj))
                            }
                            wordStart = i + 1
                        }
                        i++
                    }
                }
            }
            stripper.sortByPosition = true
            stripper.getText(doc)
            return pages
        }
    }
}
