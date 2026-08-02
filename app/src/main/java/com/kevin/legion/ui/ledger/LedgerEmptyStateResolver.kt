package com.kevin.legion.ui.ledger

import com.kevin.legion.service.FileResults

/**
 * Which of ticket 08's three empty-state copies ([LedgerEmptyCopy]) applies,
 * given the two signals only the ledger UI has: whether a folder is
 * connected, and what the most recent finished scan found. Pure and
 * Android-free on purpose - [com.kevin.legion.service.FileResults] is a
 * plain data class, so this stays a JVM unit test rather than needing
 * Robolectric.
 *
 * Ticket 08 Part 5 shipped all three copies but could only reach
 * [Kind.NO_FOLDER] - "nothing new" and "folder looks empty" both need a real
 * scan/[com.kevin.legion.service.ScanState] pass to tell apart, which is what
 * Part 6 (this class) supplies.
 */
object LedgerEmptyStateResolver {
    enum class Kind { NO_FOLDER, NOTHING_NEW, LOOKS_EMPTY }

    /**
     * [folderConnected] false always wins - nothing a scan found is relevant
     * if there's no folder to have scanned. Otherwise:
     * - [lastFinished] null (a folder is connected but nothing has finished a
     *   scan pass yet in this session) reads as [Kind.LOOKS_EMPTY] rather
     *   than a fourth, unbuilt copy - the wording ("Drive may still be
     *   syncing") is honest for "nothing has been listed yet" too, and both
     *   states offer the same next action (scan the folder).
     * - A finished scan whose every outcome bucket is zero - nothing listed
     *   at all, or a stale-empty listing (ticket 05 §9, the provider can
     *   return `COUNT=0` with no signal) - is also [Kind.LOOKS_EMPTY]. This
     *   must never read as an error (CLAUDE.md §4 rule 5's "anything not
     *   stated is an estimate" cousin: an absence the provider gives no
     *   signal for is not a fact of "the folder is empty").
     * - Any nonzero bucket with nothing newly ingested or quarantined means
     *   the scan genuinely ran against real files and every one of them was
     *   already accounted for (skipped/duplicate/unreadable/declined) -
     *   [Kind.NOTHING_NEW].
     */
    fun resolve(folderConnected: Boolean, lastFinished: FileResults?): Kind {
        if (!folderConnected) return Kind.NO_FOLDER
        if (lastFinished == null) return Kind.LOOKS_EMPTY

        val total = lastFinished.ingested + lastFinished.quarantined + lastFinished.unreadable +
            lastFinished.duplicate + lastFinished.skipped + lastFinished.needsLlmDeclined
        if (total == 0) return Kind.LOOKS_EMPTY

        return Kind.NOTHING_NEW
    }
}
