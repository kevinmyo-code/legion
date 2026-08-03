package com.kevin.legion.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [SyncMerge.plan] directly - the pure planner `sync/` has never run
 * against a device (ticket 10's resolution is all `traced`, none `tested`).
 * These tests convert some of that into fact without a device: what they
 * verify is the PLANNER's decisions, not Drive I/O, conflict retry, or the
 * upload path, none of which this file touches.
 *
 * Plain JUnit, no Robolectric - [SyncMerge] is declared Android-free in its
 * own doc comment, and `org.json:json` is wired as a `testImplementation`
 * (see `LedgerDedupTest`/`app/build.gradle.kts`) so `JSONObject` works on a
 * plain JVM.
 */
class SyncMergeTest {

    /** One row, built from varargs so each test only spells out what it cares about. */
    private fun row(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
        return o
    }

    // ---------------------------------------------------------------- UNION

    @Test
    fun `union inserts a remote row whose identity is unseen locally`() {
        val local = listOf(row("syncId" to "a"))
        val remote = listOf(row("syncId" to "b"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, clock = "updatedAt")

        assertEquals(1, actions.size)
        val insert = actions.single() as SyncMerge.Action.Insert
        assertEquals("b", insert.row.getString("syncId"))
    }

    @Test
    fun `union takes no action on a remote row whose identity already exists locally`() {
        val local = listOf(row("syncId" to "a", "description" to "original"))
        // Same identity, different payload - UNION must still leave the local row alone.
        val remote = listOf(row("syncId" to "a", "description" to "changed"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, clock = "updatedAt")

        assertTrue("existing rows must never be touched under UNION", actions.isEmpty())
    }

    /**
     * Ticket 04's twin-transaction bug, at the sync layer instead of the
     * dedup layer. Two rows identical in every field except their identity
     * column must both survive - the planner must never collapse them into
     * one insert just because their content matches.
     */
    @Test
    fun `union inserts both twin rows that differ only by syncId`() {
        val local = emptyList<JSONObject>()
        val remote = listOf(
            row("syncId" to "twin-1", "accountId" to "acc-1", "amountCents" to -500L, "description" to "COFFEE SHOP"),
            row("syncId" to "twin-2", "accountId" to "acc-1", "amountCents" to -500L, "description" to "COFFEE SHOP"),
        )

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, clock = "updatedAt")

        assertEquals(2, actions.size)
        val insertedIds = actions.map { (it as SyncMerge.Action.Insert).row.getString("syncId") }.toSet()
        assertEquals(setOf("twin-1", "twin-2"), insertedIds)
    }

    /**
     * UNION's `Action` sealed interface has only Insert and Update - there is
     * no Delete. A row local-only (absent from the remote snapshot) is
     * therefore never touched by [SyncMerge.plan] itself; `plan` only ever
     * looks at what's IN `remote`, so a locally-only row cannot even appear
     * in its output. Pinning that shape rather than re-deriving it, since a
     * future change adding a delete action would be exactly the kind of
     * silent-loss risk CLAUDE.md sec 4/9 cares about for ledger.
     */
    @Test
    fun `union has no delete action - a local-only row cannot appear in the plan at all`() {
        val local = listOf(row("syncId" to "local-only"))
        val remote = emptyList<JSONObject>()

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, clock = "updatedAt")

        assertTrue(actions.isEmpty())
    }

    // ------------------------------------------------------------------ LWW

    @Test
    fun `lww updates the local row when the remote clock is strictly newer`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 100L, "description" to "old"))
        val remote = listOf(row("syncId" to "a", "updatedAt" to 200L, "description" to "new"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertEquals(1, actions.size)
        val update = actions.single() as SyncMerge.Action.Update
        assertEquals("new", update.row.getString("description"))
        assertEquals(mapOf("syncId" to "a"), update.identity)
    }

    @Test
    fun `lww leaves the local row alone when the remote clock is older`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 200L, "description" to "current"))
        val remote = listOf(row("syncId" to "a", "updatedAt" to 100L, "description" to "stale"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertTrue("an older remote clock must not overwrite a newer local row", actions.isEmpty())
    }

    /**
     * The comparison in [SyncMerge.plan] is strict `>`, so a tie produces no
     * action and the local row survives untouched. This test PINS that
     * current behaviour; it is not asserting a spec requirement that ties
     * must resolve this way (equal clocks are inherently ambiguous - a
     * remote row written at the same clock value with different content
     * would be silently dropped by this rule, same as an older one).
     */
    @Test
    fun `lww with an equal clock takes no action - pinning current tie-breaking behaviour, not a requirement`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 100L, "description" to "local"))
        val remote = listOf(row("syncId" to "a", "updatedAt" to 100L, "description" to "remote"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertTrue(actions.isEmpty())
    }

    /**
     * [SyncMerge.plan] reads the clock with `optLong(clock, 0L)` on both
     * sides. A remote row with the clock column entirely ABSENT (not merely
     * null) is read as 0, so it loses to any local row with a clock > 0 and
     * WINS (produces an Update) against a local row whose clock is also
     * absent/0 or negative - because 0 > 0 is false, a genuinely missing
     * clock on both sides still produces no action. This is a real risk
     * surface for `ingested_files.lastAttemptAt`, which can be unset before
     * a file's first sync attempt. Pinning current behaviour.
     */
    @Test
    fun `lww with a missing clock column on the remote row is read as zero and never wins over a positive local clock`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 1L, "description" to "current"))
        // No "updatedAt" key at all on the remote row.
        val remote = listOf(row("syncId" to "a", "description" to "no clock at all"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertTrue(actions.isEmpty())
    }

    /**
     * Symmetric case: BOTH sides are missing the clock column. `optLong`
     * defaults both to 0, so `0 > 0` is false and nothing happens - a brand
     * new `ingested_files` row synced before its first `lastAttemptAt` write
     * would be silently ignored by a remote copy in the same state, rather
     * than either side winning. Pinning current behaviour, not asserting
     * it's correct.
     */
    @Test
    fun `lww with the clock column missing on both sides never produces an action`() {
        val local = listOf(row("syncId" to "a", "description" to "local, no clock"))
        val remote = listOf(row("syncId" to "a", "description" to "remote, no clock"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertTrue(actions.isEmpty())
    }

    /**
     * A JSON `null` clock value (present as a key, explicitly null - distinct
     * from the column being absent, tested above) also reads as 0 via
     * `optLong`'s null-safe default, per `JSONObject.optLong`'s documented
     * behaviour of falling back to the default whenever the value can't be
     * coerced to a long. Pinning it explicitly since `JSONObject.isNull` is
     * how the rest of this file distinguishes "column present but null" from
     * "column absent", and it's worth confirming the clock path doesn't.
     */
    @Test
    fun `lww with an explicit null clock value on the remote row is also read as zero`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 1L, "description" to "current"))
        val remote = listOf(row("syncId" to "a", "updatedAt" to null, "description" to "null clock"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertTrue(actions.isEmpty())
    }

    /**
     * `car_tasks`/`places` tombstone soft-deletes (B19, per SyncEngine's
     * class doc): the snapshot is never filtered on `deleted`, so a
     * `deleted = 1` row ships and must win an ordinary LWW comparison like
     * any other edit, propagating the delete to the other device.
     */
    @Test
    fun `lww propagates a remote tombstone - a newer deleted row wins like any other edit`() {
        val local = listOf(row("syncId" to "a", "updatedAt" to 100L, "deleted" to 0L, "label" to "Home"))
        val remote = listOf(row("syncId" to "a", "updatedAt" to 200L, "deleted" to 1L, "label" to "Home"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertEquals(1, actions.size)
        val update = actions.single() as SyncMerge.Action.Update
        assertEquals(1L, update.row.getLong("deleted"))
    }

    // ------------------------------------------------------------- Identity

    @Test
    fun `a composite identity matches only when every column matches, like obd_samples' vehicleId, pid, timestamp`() {
        val identity = listOf("vehicleId", "pid", "timestamp")
        val local = listOf(row("vehicleId" to "v1", "pid" to "0C", "timestamp" to 1000L, "value" to 42L))
        val remote = listOf(
            // Same vehicle and pid, different timestamp - a genuinely different sample, must insert.
            row("vehicleId" to "v1", "pid" to "0C", "timestamp" to 2000L, "value" to 43L),
            // Every composite column matches the local row - must not insert or update (UNION).
            row("vehicleId" to "v1", "pid" to "0C", "timestamp" to 1000L, "value" to 999L),
        )

        val actions = SyncMerge.plan(local, remote, identity, SyncMerge.Mode.UNION, clock = "updatedAt")

        assertEquals(1, actions.size)
        val insert = actions.single() as SyncMerge.Action.Insert
        assertEquals(2000L, insert.row.getLong("timestamp"))
    }

    @Test
    fun `a natural-key identity, like places' label, matches on that column directly`() {
        val local = listOf(row("label" to "Home", "timestamp" to 1L, "lat" to 1.0))
        val remote = listOf(row("label" to "Home", "timestamp" to 2L, "lat" to 2.0))

        val actions = SyncMerge.plan(local, remote, listOf("label"), SyncMerge.Mode.LWW, clock = "timestamp")

        assertEquals(1, actions.size)
        assertTrue(actions.single() is SyncMerge.Action.Update)
    }

    @Test
    fun `a portable syncId identity matches on that column directly, same as a natural key`() {
        val local = listOf(row("syncId" to "s1", "updatedAt" to 1L, "note" to "old"))
        val remote = listOf(row("syncId" to "s1", "updatedAt" to 2L, "note" to "new"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.LWW, clock = "updatedAt")

        assertEquals(1, actions.size)
        assertEquals("new", (actions.single() as SyncMerge.Action.Update).row.getString("note"))
    }

    /**
     * A row that has no value at all for one of its declared identity
     * columns. `key()` builds the identity string with `row.isNull(col)`,
     * which returns true for BOTH an explicit JSON null AND a key that is
     * plain absent (`JSONObject.isNull`'s documented contract), so a missing
     * identity column is treated as an identity of JSON `null` for that
     * column - it still matches, it does not throw and does not silently
     * drop the row. Pinning this because a row genuinely missing its
     * identity column (e.g. a pre-migration row with a blank `syncId`
     * before `backfillSyncIds` runs) is a real shape, not a hypothetical.
     */
    @Test
    fun `a row missing its identity column entirely is treated as identity null, not skipped or thrown`() {
        val local = listOf(row("description" to "no syncId at all"))
        val remote = listOf(row("description" to "also no syncId"))

        val actions = SyncMerge.plan(local, remote, listOf("syncId"), SyncMerge.Mode.UNION, clock = "updatedAt")

        // Both rows resolve to the SAME missing-identity key ([null]), so the remote
        // one is treated as already-present locally and produces no action - two
        // unrelated rows that both happen to be missing syncId would collide under
        // this identity, which is exactly why backfillSyncIds runs before every sync.
        assertTrue(actions.isEmpty())
    }
}
