package com.kevin.legion.meditations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The bundled text parses completely, and the licence material Project Gutenberg requires ships beside it. */
class MeditationsTextTest {

    /**
     * Long's own numbering, which every standard edition of his translation shares: 487 sections.
     * A count is pinned per book because a parser that dropped one section of Book IV would still
     * produce a plausible-looking total.
     */
    private val expectedSections = listOf(17, 17, 16, 51, 36, 59, 75, 61, 42, 38, 39, 36)

    @Test
    fun `all twelve books load with Long's section counts`() {
        val byBook = MeditationsFixture.passages.groupBy { it.book }
        assertEquals((1..12).toList(), byBook.keys.sorted())
        assertEquals(expectedSections, (1..12).map { byBook.getValue(it).size })
        assertEquals(487, MeditationsFixture.passages.size)
    }

    @Test
    fun `sections are numbered one to n with no gap in every book`() {
        for ((book, ps) in MeditationsFixture.passages.groupBy { it.book }) {
            assertEquals("Book $book", (1..ps.size).toList(), ps.map { it.section })
        }
    }

    @Test
    fun `citations read Book Roman comma section`() {
        val p = MeditationsFixture.passages.first { it.book == 4 && it.section == 3 }
        assertEquals("Book IV, 3", p.cite)
        assertTrue(p.text.startsWith("Men seek retreats for themselves"))
        assertEquals("Book XII, 36", MeditationsFixture.passages.last().cite)
    }

    @Test
    fun `apparatus that is not Marcus is stripped`() {
        val raw = MeditationsFixture.raw
        val apparatus = listOf("[Illustration", "[Greek:", "Project Gutenberg", "INDEX OF TERMS", "BIOGRAPHICAL SKETCH")
        for (marker in apparatus) {
            assertFalse("meditations.txt still contains \"$marker\"", raw.contains(marker))
        }
        assertFalse("a footnote marker like [A] survived", Regex("""\[[A-Z]\]""").containsMatchIn(raw))
        assertFalse("a doubtful-reading + mark survived", raw.contains("+"))
        // Section 1 of Book II is the one the persona leans on; it must be whole.
        val beginTheMorning = MeditationsFixture.passages.first { it.book == 2 && it.section == 1 }
        assertTrue(beginTheMorning.text.contains("we are made for co-operation"))
    }

    @Test
    fun `a malformed file fails loudly instead of parsing short`() {
        try {
            MeditationsText.parse("## I\n### 1\nOnly one book here.\n")
            fail("a one-book file must not parse")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `book tokens read as Roman or Arabic and nothing else`() {
        assertEquals(4, MeditationsText.bookNumber("iv"))
        assertEquals(4, MeditationsText.bookNumber("4"))
        assertEquals(12, MeditationsText.bookNumber("XII"))
        assertEquals(null, MeditationsText.bookNumber("13"))
        assertEquals(null, MeditationsText.bookNumber("banana"))
    }

    @Test
    fun `the Gutenberg notice and full licence ship beside the text`() {
        val notice = File(MeditationsFixture.dir(), "NOTICE.txt").readText()
        // Paragraph 1.E.1's sentence, kept verbatim.
        assertTrue(notice.contains("This eBook is for the use of anyone anywhere in the United States and most"))
        assertTrue(notice.contains("George Long"))
        assertTrue(notice.contains("#15877"))
        val license = File(MeditationsFixture.dir(), "GUTENBERG-LICENSE.txt").readText()
        assertTrue(license.contains("1.E.1."))
        assertTrue(license.contains("Section 5. General Information About Project Gutenberg"))
    }
}
