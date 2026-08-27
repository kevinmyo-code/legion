package com.kevin.legion.engine

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.grid.GridItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** DB-backed coverage for [WidgetInstanceStore] - Robolectric through the real [CarDatabase.getDatabase]
 * path, same shape as [RecordStoreTest] (see that file's own doc comment for why a hand-rolled
 * in-memory DB would not exercise the same wiring). */
@RunWith(RobolectricTestRunner::class)
class WidgetInstanceStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val store get() = WidgetInstanceStore(db.widgetInstanceDao())

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `a fresh device is empty`() = runBlocking {
        assertTrue(store.isDeviceEmpty("device-a"))
    }

    @Test
    fun `addWidget makes the device non-empty and readable back on its page`() = runBlocking {
        val id = store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "x", row = 0, col = 0, rowSpan = 1, colSpan = 2),
        )
        assertFalse(store.isDeviceEmpty("device-a"))
        val page = store.layoutForPage("device-a", aspectId = null)
        assertEquals(1, page.size)
        assertEquals(id, page.first().id)
        assertEquals(2, page.first().colSpan)
    }

    @Test
    fun `widgets on different pages are scoped by aspectId`() = runBlocking {
        store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "home-1", row = 0, col = 0),
        )
        store.addWidget(
            deviceId = "device-a", aspectId = 99L, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "aspect-1", row = 0, col = 0),
        )
        assertEquals(1, store.layoutForPage("device-a", null).size)
        assertEquals(1, store.layoutForPage("device-a", 99L).size)
        assertEquals(0, store.layoutForPage("device-a", 42L).size)
    }

    @Test
    fun `widgets are scoped by deviceId too`() = runBlocking {
        store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "x", row = 0, col = 0),
        )
        assertTrue(store.isDeviceEmpty("device-b"))
        assertEquals(0, store.layoutForPage("device-b", null).size)
    }

    @Test
    fun `saveLayout writes back new geometry`() = runBlocking {
        val id = store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "x", row = 0, col = 0, rowSpan = 1, colSpan = 1),
        )
        val moved = GridItem(id = id.toString(), row = 3, col = 2, rowSpan = 2, colSpan = 4)
        store.saveLayout(listOf(moved))
        val page = store.layoutForPage("device-a", null)
        assertEquals(3, page.first().gridRow)
        assertEquals(2, page.first().gridCol)
        assertEquals(2, page.first().rowSpan)
        assertEquals(4, page.first().colSpan)
    }

    @Test
    fun `saveLayout ignores an id that is not a real widget row`() = runBlocking {
        // Should not throw - a stale/garbage id in the committed list is simply skipped.
        store.saveLayout(listOf(GridItem(id = "not-a-real-id", row = 0, col = 0)))
    }

    @Test
    fun `removeWidget deletes the row and is idempotent on a second call`() = runBlocking {
        val id = store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "x", row = 0, col = 0),
        )
        store.removeWidget(id)
        assertTrue(store.isDeviceEmpty("device-a"))
        store.removeWidget(id) // no-op, must not throw
        assertTrue(store.isDeviceEmpty("device-a"))
    }

    @Test
    fun `toGridItems converts stored geometry back to a plain GridItem list`() = runBlocking {
        store.addWidget(
            deviceId = "device-a", aspectId = null, recordTypeId = null, kind = WidgetKind.STAT_TILE,
            position = 0, item = GridItem(id = "x", row = 1, col = 2, rowSpan = 2, colSpan = 2),
        )
        val rows = store.layoutForPage("device-a", null)
        val items = WidgetInstanceStore.toGridItems(rows)
        assertEquals(1, items.size)
        assertEquals(GridItem(id = rows.first().id.toString(), row = 1, col = 2, rowSpan = 2, colSpan = 2), items.first())
    }
}
