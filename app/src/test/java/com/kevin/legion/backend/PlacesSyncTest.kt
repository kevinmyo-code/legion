package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.testutil.RoomTestReset
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

/**
 * [PlacesSync.pull] - the server-to-phone path `places` did not have until 2026-09-07, when a place
 * inserted directly on the engine was proven on the A25 not to arrive across SYNC NOW, a re-entry
 * and a cold start.
 *
 * Exercised against an in-memory [FakePlacesPull] and the real (Robolectric) `places` table, never
 * a network - the same posture [PantryReceiptsSyncTest] and [LedgerTransactionsSyncTest] take.
 */
@RunWith(RobolectricTestRunner::class)
class PlacesSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Only [PlacesIncrementalPull], not the whole [PlacesBackend] - which is the point of that
     * interface being narrow: a fake for a pull test needs three lines, not three functions it
     * never calls. */
    private class FakePlacesPull(var rows: List<RemotePlace> = emptyList()) : PlacesIncrementalPull {
        var lastSinceMs: Long? = null
        var failure: Throwable? = null

        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemotePlace>> {
            lastSinceMs = sinceMs
            failure?.let { return Result.failure(it) }
            return Result.success(rows.filter { it.updatedAtMs >= sinceMs })
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // The cursor lives in SharedPreferences, which Robolectric resets per method - asserted
        // rather than assumed by the first test below.
        PlacesSync.setLastAutoPullAtForTest(0L)
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dao() = CarDatabase.getDatabase(context).placeDao()

    private fun remote(label: String, lat: Double, lon: Double, at: Long, deleted: Boolean = false) =
        RemotePlace(label = label, latitude = lat, longitude = lon, updatedAtMs = at, deleted = deleted)

    @Test
    fun `a place created server-side arrives on the phone`() = runBlocking {
        // The A25 defect, in one assertion.
        val backend = FakePlacesPull(listOf(remote("fancy walmart", 29.91, -95.72, at = 5_000L)))

        val report = PlacesSync.pull(context, backend)

        assertEquals(1, report.inserted)
        val stored = dao().getAll()
        assertEquals(1, stored.size)
        assertEquals("fancy walmart", stored.single().label)
        assertEquals(29.91, stored.single().latitude, 0.0001)
        assertEquals("the server's own updated_at becomes the local LWW clock", 5_000L, stored.single().timestamp)
    }

    @Test
    fun `a first pull asks for everything, never for nothing`() = runBlocking {
        val backend = FakePlacesPull()
        PlacesSync.pull(context, backend)
        assertEquals("a missing watermark must mean 1970, not now", 0L, backend.lastSinceMs)
    }

    @Test
    fun `a server tombstone soft-deletes the local row`() = runBlocking {
        dao().upsert(TaggedPlace("the gym", 29.7, -95.4, timestamp = 1_000L, deleted = false))
        val backend = FakePlacesPull(listOf(remote("the gym", 29.7, -95.4, at = 9_000L, deleted = true)))

        val report = PlacesSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        assertTrue("the active read must no longer see it", dao().getAll().isEmpty())
        val row = dao().getAllIncludingTombstones().single { it.label == "the gym" }
        assertTrue("and it is a tombstone, not a hard delete - so it can still propagate", row.deleted)
        assertEquals(9_000L, row.timestamp)
    }

    @Test
    fun `a server tombstone for a place this phone never had is skipped, never inserted`() = runBlocking {
        val backend = FakePlacesPull(listOf(remote("nowhere", 0.0, 0.0, at = 9_000L, deleted = true)))

        val report = PlacesSync.pull(context, backend)

        assertEquals(1, report.skippedTombstoneNoLocalMatch)
        assertEquals(0, report.inserted)
        assertTrue("dead weight must never be inserted", dao().getAllIncludingTombstones().isEmpty())
    }

    @Test
    fun `a local place the server has never seen is left completely alone`() = runBlocking {
        // BodyMerge rule 6, which is the rule that stops a pull from deleting a place tagged while
        // this phone was offline. The pull only ever looks up a match for a row the SERVER sent.
        dao().upsert(TaggedPlace("home", 29.0, -95.0, timestamp = 1_000L, deleted = false))
        val backend = FakePlacesPull(listOf(remote("work", 30.0, -96.0, at = 5_000L)))

        PlacesSync.pull(context, backend)

        val labels = dao().getAll().map { it.label }.sorted()
        assertEquals(listOf("home", "work"), labels)
    }

    @Test
    fun `a locally-newer row wins and the server does not clobber it`() = runBlocking {
        dao().upsert(TaggedPlace("home", 29.0, -95.0, timestamp = 9_000L, deleted = false))
        val backend = FakePlacesPull(listOf(remote("home", 1.0, 1.0, at = 5_000L)))

        val report = PlacesSync.pull(context, backend)

        assertEquals(1, report.skippedLocalNewer)
        assertEquals(29.0, dao().getAll().single().latitude, 0.0001)
    }

    @Test
    fun `the watermark advances only past rows actually merged`() = runBlocking {
        val backend = FakePlacesPull(listOf(remote("a", 1.0, 1.0, at = 4_000L), remote("b", 2.0, 2.0, at = 7_000L)))

        PlacesSync.pull(context, backend)
        // A second pull asks from the newest row it saw, not from zero.
        PlacesSync.pull(context, backend)

        assertEquals(7_000L, backend.lastSinceMs)
    }

    @Test
    fun `a failed fetch throws and leaves the watermark untouched`() = runBlocking {
        val backend = FakePlacesPull(listOf(remote("a", 1.0, 1.0, at = 4_000L)))
        PlacesSync.pull(context, backend)
        assertEquals(4_000L, PlacesPullCursor.lastPulledAtMs(context))

        backend.failure = PlacesBackendException("engine unreachable")
        val thrown = runCatching { PlacesSync.pull(context, backend) }.exceptionOrNull()

        assertTrue("a failed pull must not be reported as a partial pass", thrown is PlacesBackendException)
        assertEquals(
            "and must not advance past a window it never merged",
            4_000L,
            PlacesPullCursor.lastPulledAtMs(context),
        )
    }

    @Test
    fun `maybeAutoPull does nothing at all on the Supabase transport`() = runBlocking {
        // "On SUPABASE every one of these behaves exactly as it does today" - and today `places`
        // has no pull. The guard is structural as well as explicit: SupabasePlacesBackend does not
        // implement PlacesIncrementalPull, so there is no route for this to take even if the
        // transport check were removed.
        assertEquals(
            Transport.SUPABASE,
            EngineTransport(context).transportFor(EngineBackends.ASPECT_PLACES),
        )
        dao().upsert(TaggedPlace("home", 29.0, -95.0, timestamp = 1_000L, deleted = false))

        PlacesSync.maybeAutoPull(context)

        assertEquals("no watermark may even be created on this transport", 0L, PlacesPullCursor.lastPulledAtMs(context))
        assertEquals("and the local row is untouched", 1, dao().getAll().size)
    }

    @Test
    fun `maybeAutoPull on Django with no engine configured is a silent no-op`() = runBlocking {
        // Transport flipped, but this device holds no engine address or token, so
        // EngineBackends.placesBackend() answers null. Never a crash, never a dialog.
        EngineTransport(context).setTransport(EngineBackends.ASPECT_PLACES, Transport.DJANGO)

        PlacesSync.maybeAutoPull(context)

        assertEquals(0L, PlacesPullCursor.lastPulledAtMs(context))
        assertFalse("nothing was written", dao().getAllIncludingTombstones().isNotEmpty())
    }
}
