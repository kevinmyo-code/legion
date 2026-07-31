package com.kevin.legion.ledger.parsers

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Plain (non-coordinate) text extraction - the Android equivalent of Python's
 * `pdfplumber.Page.extract_text()`, which Andromeda's BofA parser uses (its
 * layout is line/section-based, not column-position-based like DBS's). Also
 * the extraction path the LLM fallback ([LedgerStatementAgent]) hands to
 * Gemini for unrecognized layouts. [PdfWords.init] must have run first.
 */
object PdfText {
    fun extractText(input: InputStream): String {
        PDDocument.load(input).use { doc ->
            return PDFTextStripper().getText(doc)
        }
    }
}
