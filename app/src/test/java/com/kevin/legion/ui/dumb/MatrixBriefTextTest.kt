package com.kevin.legion.ui.dumb

import com.kevin.legion.brief.BriefLine
import com.kevin.legion.brief.BriefLink
import com.kevin.legion.brief.BriefSection
import com.kevin.legion.brief.BriefView
import com.kevin.legion.brief.PhoneSection
import com.kevin.legion.brief.ServerBrief
import com.kevin.legion.ui.brief.BriefUiState
import com.kevin.legion.ui.brief.NewslettersUi
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matrix brief's words: sources in words, unverified said, failed apart from empty, read-through news. */
class MatrixBriefTextTest {
    private fun brief(vararg sections: BriefSection, notes: List<String> = emptyList()) = ServerBrief(
        id = "1", briefDate = LocalDate.of(2026, 10, 24), builtAtMs = 0L, trigger = "scheduled",
        ageSentence = "built just now", newsOk = true, notes = notes, sections = sections.toList(),
    )

    private fun showing(b: ServerBrief, savedCopy: String? = null) = BriefView.Showing(b, "Built 7:00", savedCopy)

    @Test
    fun `before the first read lands it says it is reading, not that there is no brief`() {
        val state = BriefUiState(loading = true)
        assertEquals("Reading the brief...", MatrixBriefText.header(state))
        assertEquals(
            listOf(MatrixBriefLine(MatrixBriefText.READING, MatrixTone.DIM)),
            MatrixBriefText.serverLines(state),
        )
    }

    @Test
    fun `none yet and unavailable are different sentences with different tones`() {
        val none = MatrixBriefText.serverLines(BriefUiState(view = BriefView.NoneYet("No brief for you yet.")))
        assertEquals(MatrixBriefLine("No brief for you yet.", MatrixTone.BODY), none.single())
        val down = MatrixBriefText.serverLines(
            BriefUiState(view = BriefView.Unavailable("The engine could not be reached.")),
        )
        assertEquals(MatrixTone.WARNING, down.single().tone)
        assertEquals("No brief to show", MatrixBriefText.header(BriefUiState(view = BriefView.Unavailable("x"))))
    }

    @Test
    fun `a saved copy is said in words above the sections`() {
        val view = showing(brief(), savedCopy = "This is the phone's saved copy from yesterday.")
        val lines = MatrixBriefText.serverLines(BriefUiState(view = view))
        assertEquals("This is the phone's saved copy from yesterday.", lines.first().text)
        assertEquals(MatrixTone.WARNING, lines.first().tone)
    }

    @Test
    fun `an unverified line gets its own Unverified line and a link is a tappable row`() {
        val section = BriefSection(
            "money", "Money", "ok",
            listOf(BriefLine("You spent 40.00 this week.", true, listOf(BriefLink("Story", "https://example.com/a")))),
            null,
        )
        val lines = MatrixBriefText.sectionLines(section)
        assertEquals(listOf("Money", "You spent 40.00 this week.", "Unverified", "link: Story"), lines.map { it.text })
        assertEquals(MatrixTone.UNVERIFIED, lines[2].tone)
        assertEquals("https://example.com/a", lines[3].url)
    }

    @Test
    fun `a failed section says failed beside its title and its note is alert, an empty one says empty`() {
        val failed = BriefSection("news", "News", BriefSection.STATUS_FAILED, emptyList(), "News could not be fetched.")
        val lines = MatrixBriefText.sectionLines(failed)
        assertEquals("News (failed)", lines.first().text)
        assertEquals(MatrixTone.ALERT, lines.last().tone)
        val empty = BriefSection("cal", "Calendar", BriefSection.STATUS_EMPTY, emptyList(), null)
        assertEquals("Calendar (empty)", MatrixBriefText.sectionLines(empty).single().text)
    }

    @Test
    fun `the phone half is headed live and reads while loading`() {
        val loading = MatrixBriefText.phoneLines(BriefUiState(phoneLoading = true))
        assertEquals(MatrixBriefText.PHONE_HEADING, loading.first().text)
        assertEquals(MatrixBriefText.PHONE_READING, loading[1].text)
        val loaded = MatrixBriefText.phoneLines(BriefUiState(phone = listOf(PhoneSection("Weather", "71F, clear"))))
        assertEquals(listOf(MatrixBriefText.PHONE_HEADING, "Weather", "71F, clear"), loaded.map { it.text })
    }

    @Test
    fun `newsletters show only when on, and a read summary says it is not saved`() {
        assertTrue(MatrixBriefText.newsletterLines(BriefUiState(newslettersOn = false)).isEmpty())
        assertTrue(MatrixBriefText.newsletterLines(BriefUiState(newslettersOn = true, phoneLoading = true)).isEmpty())
        val ready = MatrixBriefText.newsletterLines(
            BriefUiState(newslettersOn = true, newsletters = NewslettersUi.Ready("Two stories.")),
        )
        assertEquals(listOf("Newsletters", "Two stories.", MatrixBriefText.NEWSLETTERS_DROPPED), ready.map { it.text })
        val notChecked = MatrixBriefText.newsletterLines(BriefUiState(newslettersOn = true))
        assertEquals(MatrixBriefText.NEWSLETTERS_NOT_CHECKED, notChecked[1].text)
    }
}
