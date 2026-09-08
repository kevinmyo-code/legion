package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PlacesBackfill] - the phone-to-server half of `places`, which had no mechanism at all on the
 * Django transport. [PlacesReconcile] is the Supabase-era upload and stands down entirely on
 * Django, by design, and [PlacesSync.pull] correctly leaves a local-only row alone, so before this
 * backfill existed a place tagged before the flip could reach the engine by no route whatsoever.
 *
 * Exercised against an in-memory fake and the real (Robolectric) `places` table, never a network -
 * same posture as [PlacesSyncTest].
 */
@RunWith(RobolectricTestRunner::class)
class PlacesBackfillTest {
    private val context = RuntimeEnvironment.getApplication()

    /**
     * Both halves of the Django places transport, because [PlacesBackfill.run] needs both: the
     * write goes through [PlacesBackend.upsert] and the "does the engine already have this label"
     * read through [PlacesIncrementalPull.fetchChangedSince]. Only `DjangoPlacesBackend` implements
     * both in production, which is the structural reason this backfill cannot run on Supabase.
     */
    private class FakePlaces(
        var serverRows: MutableList<RemotePlace> = mutableListOf(),
    ) : PlacesBackend, PlacesIncrementalPull {
        val upserted = mutableListOf<String>()
        var fetchFailure: Throwable? = null
        var refuse: (String) -> Throwable? = { null }
        var clock = 1_000L

        override suspend fun fetchActive(): Result<List<RemotePlace>> =
            Result.success(serverRows.filterNot { it.deleted })

        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemotePlace>> {
            fetchFailure?.let { return Result.failure(it) }
            return Result.success(serverRows.filter { it.updatedAtMs >= sinceMs })
        }

        override suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace> {
            refuse(label)?.let { return Result.failure(it) }
            upserted += label
            clock += 1_000L
            val row = RemotePlace(label, latitude, longitude, updatedAtMs = clock, deleted = false)
            serverRows.removeAll { it.label == label }
            serverRows += row
            return Result.success(row)
        }

        override suspend fun softDelete(label: String): Result<Boolean> = Result.success(false)
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // SharedPreferences outlive a CarDatabase reset under Robolectric, so a cursor left behind
        // by one method would make the next method's backfill examine nothing at all - which would
        // not fail loudly, it would assert on a run that did no work.
        PlacesBackfillCursor.resetForTest(context)
        PlacesBackfill.setLastAutoRunAtForTest(0L)
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dao() = CarDatabase.getDatabase(context).placeDao()

    private suspend fun tag(label: String, at: Long = 1_000L, deleted: Boolean = false) {
        dao().upsert(TaggedPlace(label, 29.0, -95.0, timestamp = at, deleted = deleted))
    }

    private fun refusal(body: String) =
        EngineHttpException(EngineFailure.Refused(status = 400, body = body))

    @Test
    fun `every local place the engine has never seen crosses exactly once`() = runBlocking {
        tag("home")
        tag("work")
        tag("the gym")
        val backend = FakePlaces()

        val report = PlacesBackfill.run(context, backend)

        assertEquals(3, report.pushed)
        assertEquals(listOf("home", "the gym", "work"), backend.upserted.sorted())
        assertEquals("nothing was refused", 0, report.skipped.size)
        assertEquals("and nothing stopped the run", null, report.stopped)
    }

    @Test
    fun `a second run is a no-op and does not even reach the network`() = runBlocking {
        tag("home")
        val backend = FakePlaces()
        PlacesBackfill.run(context, backend)
        assertEquals(1, backend.upserted.size)

        // The fetch is armed to fail. A second run that touched the network at all would surface
        // that as a `stopped`; a genuine no-op never asks.
        backend.fetchFailure = IllegalStateException("the engine must not be called on a no-op run")
        val second = PlacesBackfill.run(context, backend)

        assertEquals("nothing pushed twice", 0, second.pushed)
        assertEquals("one label, already accounted for", 1, second.alreadyAccounted)
        assertEquals("and no server read was attempted", null, second.stopped)
        assertEquals(1, backend.upserted.size)
    }

    @Test
    fun `a refused place is skipped with its reason and every other place still crosses`() = runBlocking {
        // Ticket 09's lesson, on this aspect: one row the engine will not take must never stop the
        // rest. Before that correction on checklists, one legacy tick stopped every tick.
        tag("home")
        tag("a label far too long for the server to accept")
        tag("work")
        val backend = FakePlaces()
        backend.refuse = { label ->
            if (label.startsWith("a label far too long")) {
                refusal("""{"label":["That name is 44 characters; the limit is 30."]}""")
            } else {
                null
            }
        }

        val report = PlacesBackfill.run(context, backend)

        assertEquals("the other two crossed", 2, report.pushed)
        assertEquals(listOf("home", "work"), backend.upserted.sorted())
        assertEquals(1, report.skipped.size)
        assertEquals(
            "the engine's own sentence, unwrapped from DRF's envelope and not reworded",
            "That name is 44 characters; the limit is 30.",
            report.skipped.single().reason,
        )
        assertEquals("a refusal is never reported as a stop", null, report.stopped)
        assertTrue(
            "and the place stays on the phone, never deleted",
            dao().getAll().any { it.label.startsWith("a label far too long") },
        )

        // And it is remembered, so a later run can still say it is being held back rather than
        // reporting a clean "sent 0".
        val again = PlacesBackfill.run(context, backend)
        assertEquals(0, again.pushed)
        assertEquals(1, again.unsyncableTotal)
    }

    @Test
    fun `an unreachable engine stops the run and advances nothing`() = runBlocking {
        tag("home")
        val backend = FakePlaces()
        backend.fetchFailure = EngineHttpException(EngineFailure.Unreachable("laptop", "engine unreachable"))

        val stoppedRun = PlacesBackfill.run(context, backend)
        assertEquals(0, stoppedRun.pushed)
        assertTrue("a stop is said in words", stoppedRun.stopped?.contains("unreachable") == true)

        // Nothing was accounted for, so the very next run asks the same question again.
        backend.fetchFailure = null
        val retried = PlacesBackfill.run(context, backend)
        assertEquals(1, retried.pushed)
    }

    @Test
    fun `a place the engine already has is counted, never re-pushed over it`() = runBlocking {
        tag("home", at = 1_000L)
        val backend = FakePlaces(
            mutableListOf(RemotePlace("home", 40.0, -70.0, updatedAtMs = 9_000L, deleted = false)),
        )

        val report = PlacesBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyOnEngine)
        assertTrue("the phone's older copy must not overwrite the engine's", backend.upserted.isEmpty())
    }

    @Test
    fun `a forgotten place the engine never had is skipped, never resurrected`() = runBlocking {
        // Sharper here than on any other aspect: PlaceViewSet.put_revives_tombstone = True, so a
        // push would not merely create a row to tombstone again - it would bring a deliberately
        // forgotten place back to life on both limbs.
        tag("old flat", deleted = true)
        val backend = FakePlaces()

        val report = PlacesBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedLocalOnlyDeleted)
        assertTrue("nothing was sent", backend.upserted.isEmpty())
    }

    @Test
    fun `a place forgotten here but alive on the engine is held back and said in words`() = runBlocking {
        // The divergence this backfill deliberately does NOT close: deleting a server row is a
        // destructive act and there is no queued intent to act on. It is counted and named rather
        // than quietly resolved the wrong way in either direction.
        tag("the gym", deleted = true)
        val backend = FakePlaces(
            mutableListOf(RemotePlace("the gym", 29.7, -95.4, updatedAtMs = 9_000L, deleted = false)),
        )

        val report = PlacesBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertTrue("never pushed - that would revive it here", backend.upserted.isEmpty())
        assertEquals(1, report.skipped.size)
        assertTrue(
            "the reason has to say what did not happen",
            report.skipped.single().reason.contains("will not delete a server row"),
        )
    }

    @Test
    fun `the engine's own updated_at is written back onto the replica`() = runBlocking {
        // Not a claim about the next pull's counters - BodyMerge lets the server win an exact tie,
        // so an equal-clock row is still counted as "updated". The claim is only that Room agrees
        // with the engine the moment the push lands, instead of holding a row that claims to
        // predate the server's copy of its own data until a throttled pull reconciles it.
        tag("home", at = 1_000L)
        val backend = FakePlaces()

        PlacesBackfill.run(context, backend)

        val local = dao().getAll().single()
        val remote = backend.serverRows.single()
        assertEquals(remote.updatedAtMs, local.timestamp)
    }

    @Test
    fun `maybeAutoRun does nothing at all on the Supabase transport`() = runBlocking {
        assertEquals(
            Transport.SUPABASE,
            EngineTransport(context).transportFor(EngineBackends.ASPECT_PLACES),
        )
        tag("home")

        PlacesBackfill.maybeAutoRun(context)

        assertTrue("no cursor may even be created on this transport", PlacesBackfillCursor.accounted(context).isEmpty())
        assertEquals("and the local row is untouched", 1, dao().getAll().size)
    }

    @Test
    fun `maybeAutoRun on Django with no engine configured is a silent no-op`() = runBlocking {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_PLACES, Transport.DJANGO)
        tag("home")

        PlacesBackfill.maybeAutoRun(context)

        assertTrue(PlacesBackfillCursor.accounted(context).isEmpty())
        assertEquals(1, dao().getAll().size)
    }
}
