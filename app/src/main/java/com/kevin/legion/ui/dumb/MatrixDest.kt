package com.kevin.legion.ui.dumb

/**
 * Where the Matrix screens (matrix-open tickets 03-06) can go. They are not nav-graph routes: Matrix
 * HOME keeps a stack of these in its own state, the same way it holds All apps, so Back steps out one
 * level and nothing is reachable while Matrix mode is off (the Soft screens are untouched).
 */
internal enum class MatrixDest(val label: String) {
    INDEX("LEGION"),
    LISTS("Lists"),
    CALENDAR("Calendar"),
    BRIEF("Brief"),
    NOTES("Notes"),
    VOICE_NOTES("Voice notes"),
    ;

    companion object {
        /** What the LEGION index lists, in order. [INDEX] is the index itself and is not listed. */
        val indexed: List<MatrixDest> = listOf(LISTS, CALENDAR, BRIEF, NOTES, VOICE_NOTES)
    }
}
