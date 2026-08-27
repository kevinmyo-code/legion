package com.kevin.legion.engine

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [DefaultArrangementSeeder] coverage - the seed-once, idempotent-forever contract ticket 18 build
 * item 4 requires ("a default-arrangement seeder... on first run with an empty widget_instances
 * table, seed the home arrangement"). Robolectric, same shape as [WidgetInstanceStoreTest]. */
@RunWith(RobolectricTestRunner::class)
class DefaultArrangementSeederTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val store get() = WidgetInstanceStore(db.widgetInstanceDao())
    private val seeder get() = DefaultArrangementSeeder(db, store, db.aspectDao())

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
    fun `seeds five widgets on an empty device`() = runBlocking {
        seeder.seedHomeIfEmpty("device-a")
        val home = store.layoutForPage("device-a", aspectId = null)
        assertEquals(5, home.size)
    }

    @Test
    fun `is idempotent - a second call on an already-seeded device changes nothing`() = runBlocking {
        seeder.seedHomeIfEmpty("device-a")
        val firstPass = store.layoutForPage("device-a", aspectId = null)
        seeder.seedHomeIfEmpty("device-a")
        val secondPass = store.layoutForPage("device-a", aspectId = null)
        assertEquals(firstPass.map { it.id }, secondPass.map { it.id })
        assertEquals(5, secondPass.size)
    }

    @Test
    fun `never re-seeds home once the user has hand-edited the layout away from the seed`() = runBlocking {
        seeder.seedHomeIfEmpty("device-a")
        store.removeWidget(store.layoutForPage("device-a", null).first().id)
        val afterEdit = store.layoutForPage("device-a", null).size
        seeder.seedHomeIfEmpty("device-a") // must be a no-op - the device is not EMPTY, just edited
        assertEquals(afterEdit, store.layoutForPage("device-a", null).size)
    }

    @Test
    fun `does not seed a different device`() = runBlocking {
        seeder.seedHomeIfEmpty("device-a")
        assertTrue(store.isDeviceEmpty("device-b"))
    }

    @Test
    fun `two racing calls on the same device still seed exactly five widgets, not ten`() = runBlocking {
        // Senior review, 2026-08-23: check-then-act without a transaction let two concurrent callers
        // both observe isDeviceEmpty() == true before either had inserted anything, doubling the
        // seed. db.withTransaction serializes them - the second call's own emptiness check does not
        // begin until the first call's five inserts have fully committed.
        val a = async { seeder.seedHomeIfEmpty("device-race") }
        val b = async { seeder.seedHomeIfEmpty("device-race") }
        awaitAll(a, b)
        assertEquals(5, store.layoutForPage("device-race", aspectId = null).size)
    }

    @Test
    fun `seeded geometry is non-overlapping`() = runBlocking {
        seeder.seedHomeIfEmpty("device-a")
        val items = com.kevin.legion.engine.WidgetInstanceStore.toGridItems(store.layoutForPage("device-a", null))
        for (a in items) {
            for (b in items) {
                if (a.id == b.id) continue
                assertTrue("expected ${a.id} and ${b.id} not to overlap", !com.kevin.legion.ui.grid.GridEngine.collides(a, b))
            }
        }
    }
}
