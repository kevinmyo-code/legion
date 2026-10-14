package com.kevin.midnightai.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure cross-device merge planner (S1). No device needed. */
class SyncMergeTest {

    private fun row(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    @Test
    fun union_insertsOnlyUnseenRemoteRows() {
        val local = listOf(row("syncId" to "a", "text" to "keep"))
        val remote = listOf(
            row("syncId" to "a", "text" to "dup"),  // already local -> skip
            row("syncId" to "b", "text" to "new"),  // unseen -> insert
        )
        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, "updatedAt")
        assertEquals(1, actions.size)
        val a = actions.single()
        assertTrue(a is SyncMerge.Action.Insert)
        assertEquals("b", (a as SyncMerge.Action.Insert).row.getString("syncId"))
    }

    @Test
    fun union_neverUpdatesExisting_evenIfRemoteDiffers() {
        val local = listOf(row("syncId" to "a", "text" to "local"))
        val remote = listOf(row("syncId" to "a", "text" to "remoteChanged"))
        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, "updatedAt")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun lww_remoteNewerWins() {
        val local = listOf(row("syncId" to "a", "done" to 0, "updatedAt" to 100L))
        val remote = listOf(row("syncId" to "a", "done" to 1, "updatedAt" to 200L))
        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt")
        assertEquals(1, actions.size)
        val a = actions.single()
        assertTrue(a is SyncMerge.Action.Update)
        a as SyncMerge.Action.Update
        assertEquals(1, a.row.getInt("done"))
        assertEquals("a", a.identity["syncId"])
    }

    @Test
    fun lww_localNewerOrEqual_isIgnored() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 200L))
        val remoteOlder = listOf(row("syncId" to "a", "updatedAt" to 100L))
        val remoteEqual = listOf(row("syncId" to "a", "updatedAt" to 200L))
        assertTrue(SyncMerge.plan(local, remoteOlder, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt").isEmpty())
        assertTrue(SyncMerge.plan(local, remoteEqual, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt").isEmpty())
    }

    @Test
    fun lww_unstampedRows_bothZero_doNotClobber() {
        // Before write-stamping backfills, both sides may be 0; neither should win.
        val local = listOf(row("syncId" to "a", "done" to 0, "updatedAt" to 0L))
        val remote = listOf(row("syncId" to "a", "done" to 1, "updatedAt" to 0L))
        assertTrue(SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt").isEmpty())
    }

    @Test
    fun compositeIdentity_hasNoCrossColumnCollision() {
        // ["a b","c"] must NOT be treated as the same identity as ["a","b c"].
        val local = listOf(row("x" to "a b", "y" to "c", "updatedAt" to 100L))
        val remote = listOf(row("x" to "a", "y" to "b c", "updatedAt" to 100L))
        val actions = SyncMerge.plan(local, remote, listOf("x", "y"), SyncMerge.Mode.UNION, "updatedAt")
        assertEquals(1, actions.size)
        assertTrue(actions.single() is SyncMerge.Action.Insert)
    }

    @Test
    fun emptyRemote_producesNoActions() {
        val local = listOf(row("syncId" to "a"))
        assertTrue(SyncMerge.plan(local, emptyList(), listOf("syncId"), SyncMerge.Mode.UNION, "updatedAt").isEmpty())
    }

    // B19: soft-delete tombstones. `deleted` is just another column carried by
    // the row, so the existing LWW clock comparison is what makes a tombstone
    // propagate - these prove that holds both ways.

    @Test
    fun lww_remoteTombstoneNewer_winsOverLocalActiveRow() {
        val local = listOf(row("syncId" to "a", "text" to "change the oil", "deleted" to 0, "updatedAt" to 100L))
        val remote = listOf(row("syncId" to "a", "text" to "change the oil", "deleted" to 1, "updatedAt" to 200L))
        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt")
        assertEquals(1, actions.size)
        val a = actions.single()
        assertTrue(a is SyncMerge.Action.Update)
        assertEquals(1, (a as SyncMerge.Action.Update).row.getInt("deleted"))
    }

    @Test
    fun lww_olderRemoteTombstone_doesNotOverrideNewerLocalEdit() {
        // Local re-tagged/edited the row after the remote device deleted it -
        // the local edit's newer clock must win, undoing the remote tombstone.
        val local = listOf(row("syncId" to "a", "text" to "edited after delete", "deleted" to 0, "updatedAt" to 300L))
        val remote = listOf(row("syncId" to "a", "text" to "old", "deleted" to 1, "updatedAt" to 100L))
        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, "updatedAt")
        assertTrue(actions.isEmpty())
    }

    // Recap sync: monthly_recaps/yearly_wrapped/daily_drive_logs are autoincrement
    // id + a natural per-period key, no syncId/updatedAt - UNION by that key, same
    // shape as the other append-only tables above, just a composite identity.

    @Test
    fun recap_unionInsertsRemoteMonthMissingLocally() {
        val local = listOf(row("vehicleId" to "car1", "year" to 2026, "month" to 6, "narrative" to "june"))
        val remote = listOf(row("vehicleId" to "car1", "year" to 2026, "month" to 7, "narrative" to "july"))
        val actions = SyncMerge.plan(local, remote, listOf("vehicleId", "year", "month"), SyncMerge.Mode.UNION, "updatedAt")
        assertEquals(1, actions.size)
        val a = actions.single()
        assertTrue(a is SyncMerge.Action.Insert)
        assertEquals("july", (a as SyncMerge.Action.Insert).row.getString("narrative"))
    }

    @Test
    fun recap_unionLeavesExistingMonthUntouched() {
        val local = listOf(row("vehicleId" to "car1", "year" to 2026, "month" to 6, "narrative" to "june"))
        val remote = listOf(row("vehicleId" to "car1", "year" to 2026, "month" to 6, "narrative" to "june (regenerated)"))
        val actions = SyncMerge.plan(local, remote, listOf("vehicleId", "year", "month"), SyncMerge.Mode.UNION, "updatedAt")
        assertTrue(actions.isEmpty())
    }
}
