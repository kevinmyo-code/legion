package com.kevin.legion.ui.dumb

import android.content.Context
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.notes.formatDateOnly
import com.kevin.legion.ui.notes.formatDateTime

/**
 * [MatrixNotesSource] over [NotesController], the one funnel the Soft calendar's reminders and the
 * voice `manage_item` tool write through. [NotesController.addItem] throws on a failed write, which is
 * caught here and reported as false so the ViewModel can say it did not happen.
 */
internal class AndroidMatrixNotesSource(private val context: Context) : MatrixNotesSource {
    override suspend fun items(): List<MatrixNoteItem> =
        NotesController.allItems(context)
            .sortedWith(compareBy<ListItem> { it.done }.thenBy { it.sortOrder }.thenBy { it.id })
            .map { it.toNote() }

    private fun ListItem.toNote() = MatrixNoteItem(
        id = id,
        text = text,
        done = done,
        whenLabel = startsAt?.let { if (allDay) formatDateOnly(it) else formatDateTime(it) },
        repeats = repeatKind != null,
        placeLabel = triggerPlaceLabel,
    )

    @Suppress("TooGenericExceptionCaught") // a write that threw is "not saved", said in words by the caller
    override suspend fun add(text: String): Boolean =
        try {
            NotesController.addItem(context, NotesController.theList(context).id, text)
            true
        } catch (e: Exception) {
            android.util.Log.w("MatrixNotes", "add failed: ${e.message}")
            false
        }

    override suspend fun rename(id: Long, text: String): Boolean =
        NotesController.itemById(context, id)?.let { NotesController.renameItem(context, it, text) } ?: false

    override suspend fun setDone(id: Long, done: Boolean): Boolean =
        NotesController.itemById(context, id)?.let {
            if (done) NotesController.tick(context, it) else NotesController.untick(context, it)
        } ?: false

    override suspend fun remove(id: Long): Boolean =
        NotesController.itemById(context, id)?.let { NotesController.removeItem(context, it) } ?: false
}
