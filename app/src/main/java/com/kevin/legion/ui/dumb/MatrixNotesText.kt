package com.kevin.legion.ui.dumb

/**
 * The words on the Matrix notes screen (matrix-open ticket 05), pure so loading, empty and unreadable
 * are tested as three different sentences. A note that could not be read is never an empty list.
 */
internal object MatrixNotesText {
    const val LOADING = "Reading your notes..."
    const val UNREADABLE = "Couldn't read your notes. This is not an empty list: nothing could be read."
    const val EMPTY = "No notes yet. Write one below."
    const val STALE = "Couldn't refresh your notes. These may be out of date."

    /** The sentence above the list, or null when the rows speak for themselves. */
    fun listNote(state: MatrixNotesUiState): String? = when {
        state.loading -> LOADING
        state.readFailed && state.items.isEmpty() -> UNREADABLE
        state.items.isEmpty() -> EMPTY
        else -> null
    }

    /** After a failed re-read the old rows stay and this is said above them. */
    fun staleNote(state: MatrixNotesUiState): String? =
        if (state.readFailed && state.items.isNotEmpty()) STALE else null

    fun rowText(item: MatrixNoteItem): String = (if (item.done) "[x] " else "[ ] ") + item.text

    /** Date, repeat and place trigger said in words under the row; null when the note states none. */
    fun rowDetail(item: MatrixNoteItem): String? {
        val parts = listOfNotNull(
            item.whenLabel,
            if (item.repeats) "Repeats" else null,
            item.placeLabel?.let { "At $it" },
        )
        return parts.joinToString(" - ").ifEmpty { null }
    }

    fun rowTone(item: MatrixNoteItem): MatrixTone = if (item.done) MatrixTone.DIM else MatrixTone.HEADING
}
