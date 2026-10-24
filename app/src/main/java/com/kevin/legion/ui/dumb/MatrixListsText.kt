package com.kevin.legion.ui.dumb

import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.measurePromptLabel
import com.kevin.legion.checklists.measureTargetResult
import com.kevin.legion.checklists.measureTargetResultLabel
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.ui.checklists.ListCardUi
import com.kevin.legion.ui.checklists.ListDetailState
import com.kevin.legion.ui.checklists.ListItemUi
import com.kevin.legion.ui.checklists.ListsPageState

/** One line of the Matrix lists screens: [id] is the list or item it acts on, [text] is the whole row. */
internal data class MatrixListRow(
    val id: Long,
    val text: String,
    val tone: MatrixTone = MatrixTone.HEADING,
    val detail: String? = null,
)

/**
 * The words on the Matrix lists screens (matrix-open ticket 03), as pure functions of the SAME
 * [ListsPageState] and [ListDetailState] the Soft lists screen draws from, so the two faces cannot
 * disagree about what is on a list. Kept free of Compose so the states (loading, empty, unreadable,
 * write error) are unit-tested.
 *
 * Empty and unreadable are different sentences: a list whose items could not be read says so on its
 * row ([ListCardUi.progress] is "Couldn't load" then) and in its detail, never "Empty".
 */
internal object MatrixListsText {
    const val LOADING = "Reading your lists..."
    const val NO_LISTS = "No lists yet. Name one below to make it."
    const val DETAIL_LOADING = "Reading this list..."
    const val DETAIL_UNREADABLE = "Couldn't load this list's items. They are not shown as empty; nothing was changed."
    const val DETAIL_EMPTY = "Nothing on this list yet. Add the first item below."

    /** The sentence under the title for the page, or null when the rows speak for themselves. */
    fun pageNote(page: ListsPageState): String? = when {
        page.loading -> LOADING
        page.routines.isEmpty() && page.plainLists.isEmpty() && !page.groceriesPending -> NO_LISTS
        else -> null
    }

    fun cardRow(card: ListCardUi): MatrixListRow = MatrixListRow(
        id = card.checklist.id,
        text = card.checklist.name,
        // A null fraction is the failed read: alert-toned, and the label says "Couldn't load".
        tone = if (card.progress.fraction == null) MatrixTone.ALERT else MatrixTone.HEADING,
        detail = card.progress.label,
    )

    fun pageRows(page: ListsPageState): List<MatrixListRow> =
        (page.routines + page.plainLists).map(::cardRow)

    fun archivedRows(page: ListsPageState): List<MatrixListRow> =
        if (page.showArchived) page.archivedLists.map(::cardRow) else emptyList()

    fun archivedToggle(page: ListsPageState): String? = when {
        page.archivedLists.isEmpty() -> null
        page.showArchived -> "Hide archived lists (${page.archivedLists.size})"
        else -> "Show archived lists (${page.archivedLists.size})"
    }

    fun groceriesNote(page: ListsPageState): String? =
        if (page.groceriesPending) ChecklistController.GROCERIES_NOT_SYNCED else null

    /** The detail's status sentence: loading, unreadable, or empty; null when there are items to show. */
    fun detailNote(detail: ListDetailState): String? = when {
        detail.loading || detail.checklist == null -> DETAIL_LOADING
        detail.loadFailed -> DETAIL_UNREADABLE
        detail.unticked.isEmpty() && detail.ticked.isEmpty() -> DETAIL_EMPTY
        else -> null
    }

    fun scheduleNote(detail: ListDetailState): String? {
        val routine = detail.checklist?.scheduleKind != null
        return when {
            !routine -> null
            detail.appliesToday -> "${detail.scheduleLabel}. Ticks count for today."
            else -> "Not scheduled today."
        }
    }

    /** "[ ] text" for an open item; a measured one names what to enter. A tap ticks it. */
    fun openRow(row: ListItemUi): MatrixListRow = MatrixListRow(
        id = row.item.id,
        text = "[ ] ${row.item.text}",
        detail = measurePromptLabel(row.item),
    )

    /** "[x] text" for a ticked item, with the measured value and "Not synced yet" said in words. */
    fun tickedRow(row: ListItemUi): MatrixListRow {
        val value = row.value
        val shown = if (value != null) {
            val result = measureTargetResult(row.item, value)?.let { " - ${measureTargetResultLabel(it)}" }.orEmpty()
            measureValueDisplay(row.item, value) + result
        } else {
            row.item.text
        }
        return MatrixListRow(
            id = row.item.id,
            text = "[x] $shown",
            tone = MatrixTone.DIM,
            detail = if (row.queued) "Not synced yet" else null,
        )
    }
}
