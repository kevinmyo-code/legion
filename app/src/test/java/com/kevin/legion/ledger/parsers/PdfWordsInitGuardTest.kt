package com.kevin.legion.ledger.parsers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [PdfWords.extractWords] must refuse to run before [PdfWords.init], with a sentence that names
 * the missing call.
 *
 * **The bug this pins was invisible to every other test in the suite, and that is the point.**
 * PdfBox loads its glyph list from Android assets inside a static initializer, so a caller that
 * forgets [PdfWords.init] does not get a bad parse - it gets `ExceptionInInitializerError` thrown
 * out of `LegacyPDFStreamEngine.<clinit>`, and the process dies. On 2026-08-28 that killed the
 * ledger re-ingest dry run three times in a row; the app vanished to the launcher with nothing
 * rendered, and it read as the user switching apps.
 *
 * Every parser test in this package calls [PdfWords.init] in its own `@Before`, because that is
 * the only way PdfBox works under Robolectric. So the suite supplied the precondition the
 * production path was missing, and a green suite sat happily alongside a screen that crashed on
 * its first real file. No amount of additional parser testing would have found it.
 *
 * Hence a runtime guard rather than a test of the callers: the next caller that forgets is told
 * what to do, at the call site, in words - instead of being handed a stack trace from a vendored
 * library it never names.
 *
 * **Deliberately NOT a Robolectric test.** It must run in a JVM where [PdfWords.init] has never
 * been called, which is precisely the state every Robolectric parser test destroys in its
 * `@Before`. A plain JUnit test is the only place this condition can still be observed.
 */
class PdfWordsInitGuardTest {

    @Test
    fun `extractWords refuses to run before init and says which call is missing`() {
        val error = runCatching { PdfWords.extractWords(ByteArrayInputStream(ByteArray(0))) }
            .exceptionOrNull()

        requireNotNull(error) { "extractWords should not succeed before PdfWords.init" }

        val message = error.message.orEmpty()
        assertTrue(
            "The failure must name PdfWords.init so the caller knows the fix. Got: $message",
            message.contains("PdfWords.init"),
        )
        assertTrue(
            "The failure must explain WHY, not just what. Got: $message",
            message.contains("assets"),
        )
    }
}
