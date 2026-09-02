package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LastAspectsSync.pull] - exercised against an in-memory [FakeLastAspectsBackend] and the real
 * (Robolectric) local tables, never a network. `goals` is the representative table for
 * [LastAspectsMerge.merge]'s five rules (the ONE shared implementation every sub-pull calls - see
 * that object's own class doc for why testing it four times by hand would be four independent
 * chances to get rule 6 wrong in exactly one of them); `list_items` gets its own tests for the
 * `listSyncId` foreign-key resolution [LastAspectsBackend]'s own class doc describes, since no
 * other table in this slice has that shape.
 */
@RunWith(RobolectricTestRunner::class)
class LastAspectsSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLastAspectsBackend : LastAspectsBackend {
        val goals = mutableMapOf<String, RemoteGoal>()
        val itemLists = mutableMapOf<String, RemoteItemList>()
        val listItems = mutableMapOf<String, RemoteListItem>()

        override suspend fun fetchChangedGoalsSince(sinceMs: Long) = Result.success(goals.values.toList())
        override suspend fun upsertGoal(originGuid: String, fields: GoalFields): Result<RemoteGoal> {
            val row = RemoteGoal("srv-$originGuid", fields.lineageId, fields.aspect, fields.statement, fields.targetValue, fields.unit, fields.metricKey, fields.deadlineEpoch, fields.status, fields.supersedesGuid, fields.closedAt, fields.createdAt, fields.createdAt, false, originGuid)
            goals[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteGoal(originGuid: String): Result<Boolean> {
            val existing = goals[originGuid] ?: return Result.success(false)
            goals[originGuid] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun fetchChangedGroceryStaplesSince(sinceMs: Long) = Result.success(emptyList<RemoteGroceryStaple>())
        override suspend fun upsertGroceryStaple(originGuid: String, fields: GroceryStapleFields) = Result.failure<RemoteGroceryStaple>(LastAspectsBackendException("not used"))
        override suspend fun softDeleteGroceryStaple(originGuid: String) = Result.failure<Boolean>(LastAspectsBackendException("not used"))

        override suspend fun fetchChangedItemListsSince(sinceMs: Long) = Result.success(itemLists.values.toList())
        override suspend fun upsertItemList(originGuid: String, fields: ItemListFields): Result<RemoteItemList> {
            val row = RemoteItemList("srv-$originGuid", fields.name, fields.tickable, fields.sortOrder, fields.lastUsedAt, fields.archived, fields.createdAt, fields.createdAt, false, originGuid)
            itemLists[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteItemList(originGuid: String) = Result.failure<Boolean>(LastAspectsBackendException("not used"))

        override suspend fun fetchChangedListItemsSince(sinceMs: Long) = Result.success(listItems.values.toList())
        override suspend fun upsertListItem(originGuid: String, fields: ListItemFields): Result<RemoteListItem> {
            val row = RemoteListItem("srv-$originGuid", fields.listSyncId, fields.text, fields.done, fields.doneAt, fields.sortOrder, fields.createdAt, fields.createdAt, false, fields.startsAt, fields.endsAt, fields.allDay, fields.triggerPlaceLabel, fields.repeatKind, fields.repeatEvery, fields.repeatDaysOfWeek, fields.repeatDay, fields.repeatMonth, fields.repeatEndKind, fields.repeatEndDate, fields.repeatEndCount, fields.exact, fields.exactDowngraded, fields.missedAt, fields.missedDismissedAt, fields.loggedAt, originGuid)
            listItems[originGuid] = row
            return Result.success(row)
        }
        override suspend fun softDeleteListItem(originGuid: String) = Result.failure<Boolean>(LastAspectsBackendException("not used"))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteGoal(guid: String, statement: String, updatedAtMs: Long, deleted: Boolean = false) =
        RemoteGoal("srv-$guid", 1L, "bio", statement, null, null, null, null, "active", null, null, updatedAtMs, updatedAtMs, deleted, guid)

    private suspend fun insertLocalGoal(guid: String, statement: String, updatedAtMs: Long, deleted: Boolean = false): Long {
        val db = CarDatabase.getDatabase(context)
        return db.goalDao().insert(Goal(lineageId = 1L, aspect = "bio", statement = statement, createdAt = updatedAtMs, updatedAt = updatedAtMs, syncId = guid, deleted = deleted))
    }

    // --- goals: the five merge rules --------------------------------------------------------------

    @Test
    fun `a server-only goal is inserted`() = runBlocking {
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-1"] = remoteGoal("guid-1", "save 30k", 1_000L)

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        val all = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted()
        assertEquals(1, all.size)
        assertEquals("save 30k", all.single().statement)
        assertEquals("guid-1", all.single().syncId)
    }

    @Test
    fun `a local-only goal survives untouched`() = runBlocking {
        val id = insertLocalGoal("local-guid", "ship the deck", 1_000L)
        val backend = FakeLastAspectsBackend()

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        val stored = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted().find { it.id == id }
        assertNotNull(stored)
        assertFalse(stored!!.deleted)
        assertEquals("ship the deck", stored.statement)
    }

    @Test
    fun `a newer local goal is not overwritten by an older server row`() = runBlocking {
        val id = insertLocalGoal("guid-2", "new statement", 5_000L)
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-2"] = remoteGoal("guid-2", "stale statement", 1_000L)

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted().find { it.id == id }
        assertEquals("new statement", stored!!.statement)
    }

    @Test
    fun `a newer server goal is applied over an older local row`() = runBlocking {
        val id = insertLocalGoal("guid-3", "stale statement", 1_000L)
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-3"] = remoteGoal("guid-3", "fresh statement", 5_000L)

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(1, report.updated)
        val stored = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted().find { it.id == id }
        assertEquals("fresh statement", stored!!.statement)
    }

    @Test
    fun `a server tombstone soft-deletes the matching local goal`() = runBlocking {
        val id = insertLocalGoal("guid-4", "abandoned goal", 1_000L)
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-4"] = remoteGoal("guid-4", "abandoned goal", 5_000L, deleted = true)

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted().find { it.id == id }
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
    }

    @Test
    fun `a tombstoned server goal with no local match is skipped, never inserted`() = runBlocking {
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-ghost"] = remoteGoal("guid-ghost", "never existed", 1_000L, deleted = true)

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertEquals(0, CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted().size)
    }

    @Test
    fun `a second consecutive goal pull of the same server state is a no-op`() = runBlocking {
        val backend = FakeLastAspectsBackend()
        backend.goals["guid-5"] = remoteGoal("guid-5", "utilities", 1_000L)

        val first = LastAspectsSync.pull(context, backend)
        assertEquals(1, first.inserted)

        val beforeSecond = CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted()
        val second = LastAspectsSync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(beforeSecond, CarDatabase.getDatabase(context).goalDao().getAllIncludingDeleted())
    }

    // --- list_items: the listSyncId foreign-key resolution ------------------------------------

    private suspend fun insertLocalList(syncId: String, name: String = "Car"): ItemList {
        val db = CarDatabase.getDatabase(context)
        val id = db.itemListDao().insert(ItemList(name = name, lastUsedAt = 1_000L, createdAt = 1_000L, syncId = syncId))
        return ItemList(id = id, name = name, lastUsedAt = 1_000L, createdAt = 1_000L, syncId = syncId)
    }

    @Test
    fun `a server-only list item resolves its parent list by syncId`() = runBlocking {
        val list = insertLocalList("list-sync-1")
        val backend = FakeLastAspectsBackend()
        backend.listItems["item-sync-1"] = RemoteListItem("srv-item-1", list.syncId, "oil change", false, null, 0, 1_000L, 1_000L, false, null, null, true, null, null, null, null, null, null, null, null, null, false, false, null, null, null, "item-sync-1")

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        val stored = CarDatabase.getDatabase(context).listItemDao().getAllIncludingDeleted().single()
        assertEquals(list.id, stored.listId)
        assertEquals("oil change", stored.text)
    }

    @Test
    fun `a list item whose parent list cannot be found locally is skipped, not guessed at`() = runBlocking {
        val backend = FakeLastAspectsBackend()
        backend.listItems["item-orphan"] = RemoteListItem("srv-item-orphan", "no-such-list-syncid", "mystery item", false, null, 0, 1_000L, 1_000L, false, null, null, true, null, null, null, null, null, null, null, null, null, false, false, null, null, null, "item-orphan")

        val report = LastAspectsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.skippedOrphanedListItem)
        assertTrue(CarDatabase.getDatabase(context).listItemDao().getAllIncludingDeleted().isEmpty())
    }
}
