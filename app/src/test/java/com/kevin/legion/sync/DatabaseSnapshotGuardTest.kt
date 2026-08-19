package com.kevin.legion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM - [DatabaseSnapshotGuard] is Android-free by design (see its doc comment). */
class DatabaseSnapshotGuardTest {

    @Test
    fun `first backup ever - nothing to compare against - never refused`() {
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 0, previousRowCount = null))
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 500, previousRowCount = null))
    }

    @Test
    fun `zero rows against a nonzero previous total is always refused - the 2026-08-12 incident`() {
        assertTrue(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 0, previousRowCount = 12_000))
        assertTrue(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 0, previousRowCount = 1))
    }

    @Test
    fun `zero rows against a previous total that was itself zero is not refused`() {
        // previousRowCount <= 0 is treated as "nothing to compare against" per the doc
        // comment - should not happen in practice (a real prior generation would never be
        // uploaded at 0 rows, since IT would have hit this same guard), but must not crash.
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 0, previousRowCount = 0))
    }

    @Test
    fun `under half the previous total is refused`() {
        assertTrue(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 4_999, previousRowCount = 10_000))
        assertTrue(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 1, previousRowCount = 10_000))
    }

    @Test
    fun `exactly half the previous total is NOT refused - only strictly under`() {
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 5_000, previousRowCount = 10_000))
    }

    @Test
    fun `an ordinary week of deletions - a modest drop - is not refused`() {
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 9_800, previousRowCount = 10_000))
    }

    @Test
    fun `growth is never refused`() {
        assertFalse(DatabaseSnapshotGuard.shouldRefuse(newRowCount = 10_500, previousRowCount = 10_000))
    }

    @Test
    fun `refusalReason names both counts and states the last backup is untouched`() {
        val reason = DatabaseSnapshotGuard.refusalReason(newRowCount = 0, previousRowCount = 12_000)
        assertTrue(reason.contains("0"))
        assertTrue(reason.contains("12000") || reason.contains("12,000"))
        assertEquals(true, reason.contains("untouched"))
    }
}
