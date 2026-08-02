package com.kevin.legion.ui.ledger

import com.kevin.legion.service.FileResults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [LedgerEmptyStateResolver] - ticket 08 Part 6's
 * wiring of the two empty states ("nothing new" / "folder looks empty") that
 * Part 5 built but could not reach. No Android dependency, plain JVM test.
 */
class LedgerEmptyStateResolverTest {

    @Test
    fun `no folder connected wins regardless of any scan result`() {
        assertEquals(
            LedgerEmptyStateResolver.Kind.NO_FOLDER,
            LedgerEmptyStateResolver.resolve(folderConnected = false, lastFinished = null),
        )
        assertEquals(
            LedgerEmptyStateResolver.Kind.NO_FOLDER,
            LedgerEmptyStateResolver.resolve(
                folderConnected = false,
                lastFinished = FileResults(ingested = 5),
            ),
        )
    }

    @Test
    fun `folder connected but no scan has finished yet reads as looks-empty`() {
        assertEquals(
            LedgerEmptyStateResolver.Kind.LOOKS_EMPTY,
            LedgerEmptyStateResolver.resolve(folderConnected = true, lastFinished = null),
        )
    }

    @Test
    fun `a scan that found literally nothing reads as looks-empty, never an error`() {
        assertEquals(
            LedgerEmptyStateResolver.Kind.LOOKS_EMPTY,
            LedgerEmptyStateResolver.resolve(folderConnected = true, lastFinished = FileResults()),
        )
    }

    @Test
    fun `a scan that only skipped already-known files reads as nothing-new`() {
        assertEquals(
            LedgerEmptyStateResolver.Kind.NOTHING_NEW,
            LedgerEmptyStateResolver.resolve(
                folderConnected = true,
                lastFinished = FileResults(skipped = 12),
            ),
        )
    }

    @Test
    fun `a scan whose only activity was duplicates, unreadables, or declines is still nothing-new`() {
        assertEquals(
            LedgerEmptyStateResolver.Kind.NOTHING_NEW,
            LedgerEmptyStateResolver.resolve(
                folderConnected = true,
                lastFinished = FileResults(duplicate = 1, unreadable = 1, needsLlmDeclined = 1),
            ),
        )
    }
}
