package com.kevin.legion.ui.dumb

import com.kevin.legion.brief.BriefSection
import com.kevin.legion.brief.BriefView
import com.kevin.legion.ui.brief.BriefUiState
import com.kevin.legion.ui.brief.NewslettersUi

/** One line of the Matrix brief. A non-null [url] makes it a tappable link row. */
internal data class MatrixBriefLine(val text: String, val tone: MatrixTone = MatrixTone.BODY, val url: String? = null)

/**
 * The Matrix brief (matrix-open ticket 04) as plain text sections, from the SAME [BriefUiState] the Soft
 * Brief modal draws, so the two cannot say different things. Sources are in words: the engine's
 * sections first, each with its own status, then the phone's live calendar and weather under a heading
 * that says they are live, then newsletters only when that module is on. Every line is drawn; an
 * unverified figure gets its own "Unverified" line (CLAUDE.md section 4 rule 7: words, not colour).
 *
 * Empty and unreadable are different sentences: a brief the engine could not be read for says why
 * ([BriefView.Unavailable]); a member with none yet says that ([BriefView.NoneYet]); a section that
 * failed says "failed" next to its note and is never shown as an empty one.
 */
internal object MatrixBriefText {
    const val READING = "Reading the brief from the engine..."
    const val PHONE_HEADING = "On this phone, live"
    const val PHONE_READING = "Reading the calendar and weather..."
    const val NEWSLETTERS_NOT_CHECKED =
        "Not checked. A check reads newsletter mail from your Gmail and summarizes it. Nothing is saved."
    const val NEWSLETTERS_CHECKING = "Checking your newsletters..."
    const val NEWSLETTERS_DROPPED = "Read from your mail just now. Not saved."

    fun header(state: BriefUiState): String =
        (state.view as? BriefView.Showing)?.header
            ?: if (state.loading || state.view == null) "Reading the brief..." else "No brief to show"

    fun serverLines(state: BriefUiState): List<MatrixBriefLine> = when (val view = state.view) {
        null -> listOf(MatrixBriefLine(READING, MatrixTone.DIM))
        is BriefView.NoneYet -> listOf(MatrixBriefLine(view.sentence))
        is BriefView.Unavailable -> listOf(MatrixBriefLine(view.sentence, MatrixTone.WARNING))
        is BriefView.Showing ->
            listOfNotNull(view.savedCopy?.let { MatrixBriefLine(it, MatrixTone.WARNING) }) +
                view.brief.notes.map { MatrixBriefLine(it, MatrixTone.DIM) } +
                view.brief.sections.flatMap(::sectionLines)
    }

    fun sectionLines(section: BriefSection): List<MatrixBriefLine> {
        // The status is a word, shown beside the title whenever it is not the ordinary "ok".
        val ordinary = section.status == BriefSection.STATUS_OK
        val title = if (ordinary) section.title else "${section.title} (${section.status})"
        val body = section.lines.flatMap { line ->
            listOf(MatrixBriefLine(line.text)) +
                (if (line.unverified) listOf(MatrixBriefLine("Unverified", MatrixTone.UNVERIFIED)) else emptyList()) +
                line.links.map { MatrixBriefLine("link: ${it.title}", MatrixTone.HEADING, it.url) }
        }
        val note = section.note?.let { MatrixBriefLine(it, if (section.failed) MatrixTone.ALERT else MatrixTone.DIM) }
        return listOf(MatrixBriefLine(title, MatrixTone.HEADING)) + body + listOfNotNull(note)
    }

    fun phoneLines(state: BriefUiState): List<MatrixBriefLine> {
        val reading = if (state.phoneLoading && state.phone.isEmpty()) {
            listOf(MatrixBriefLine(PHONE_READING, MatrixTone.DIM))
        } else {
            emptyList()
        }
        return listOf(MatrixBriefLine(PHONE_HEADING, MatrixTone.DIM)) + reading +
            state.phone.flatMap { listOf(MatrixBriefLine(it.title, MatrixTone.HEADING), MatrixBriefLine(it.text)) }
    }

    /** Newsletter lines, empty unless the module is on and the phone half has finished loading. */
    fun newsletterLines(state: BriefUiState): List<MatrixBriefLine> {
        if (state.phoneLoading || !state.newslettersOn) return emptyList()
        val heading = MatrixBriefLine("Newsletters", MatrixTone.HEADING)
        return listOf(heading) + when (val news = state.newsletters) {
            NewslettersUi.NotChecked -> listOf(MatrixBriefLine(NEWSLETTERS_NOT_CHECKED, MatrixTone.DIM))
            NewslettersUi.Checking -> listOf(MatrixBriefLine(NEWSLETTERS_CHECKING, MatrixTone.DIM))
            is NewslettersUi.Ready ->
                listOf(MatrixBriefLine(news.text), MatrixBriefLine(NEWSLETTERS_DROPPED, MatrixTone.DIM))
        }
    }
}
