package com.kevin.legion.ledger.parsers

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Spike test for the PdfBox-Android coordinate-extraction approach
 * (.claude/plans/wiggly-beaming-quasar.md, task 2) - run BEFORE porting the
 * rest of DBS's column-classification logic, since this is the one piece not
 * already proven elsewhere in this codebase.
 *
 * Runs under Robolectric, not a plain JUnit runner: PdfBox-Android's fonts/
 * glyphlists/cmaps ship as Android assets inside the AAR, reachable only via
 * a real (or shadowed) AssetManager - a plain JVM unit test has no Context at
 * all and fails with `GlyphList '...glyphlist.txt' not found` before this fix.
 */
@RunWith(RobolectricTestRunner::class)
class PdfWordsSpikeTest {
    @Test
    fun `extracts words with positions from the DBS fixture`() {
        PdfWords.init(RuntimeEnvironment.getApplication())

        val fixture = File("src/test/resources/ledger_fixtures/dbs_happy_path.pdf")
        assertTrue("fixture must exist at ${fixture.absolutePath}", fixture.exists())

        val pages = fixture.inputStream().use { PdfWords.extractWords(it) }
        val words = pages.flatten()

        println("Extracted ${words.size} words:")
        words.forEach { println("  '${it.text}' at x=${it.x0} y=${it.y}") }

        assertTrue("expected at least one word", words.isNotEmpty())
        assertTrue("expected the date token", words.any { it.text == "01/03/2026" })
        assertTrue("expected the description token", words.any { it.text.contains("GROCERY") })
    }
}
