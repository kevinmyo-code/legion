package com.kevin.legion.location

import android.location.Location
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.PlacesBackendException
import com.kevin.legion.backend.RemotePlace
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.MutableStateFlow
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
 * PlaceController's CONFIGURED path (backend-erp Phase 4, places -
 * .scratch/backend-erp/issues/05-migration-path.md). Exercised entirely through
 * PlaceController.backendOverride and an in-memory FakePlacesBackend - never a real
 * SupabaseClient - so ticket 01 ruling 9 ("Room is written on server ACK, never ahead of it") and
 * the CLAUDE.md section 7 outcome-verb rule can both be asserted without a network.
 * PlaceControllerTest (this package) covers the UNCONFIGURED (engine) path and is untouched by
 * this ticket.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceControllerBackendTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakePlacesBackend(
        seed: List<RemotePlace> = emptyList(),
        var upsertFails: Boolean = false,
        var deleteFails: Boolean = false,
        /** Stands in for the Django transport answering a non-2xx - the shape `EngineHttp.classify`
         * produces, so the relay in PlaceController.engineRefusal is exercised as it would be by a
         * real refusal from `server/api/places.py`. Null means "no engine failure to simulate". */
        var upsertRefusal: EngineFailure? = null,
    ) : PlacesBackend {
        val rows = seed.associateBy { it.label }.toMutableMap()
        var upsertCalls = 0

        /** Every upsert the controller ATTEMPTED, including the ones this fake then refuses -
         * where [upsertCalls] counts only the ones that stored a row. The label-cap tests below
         * need the first number, because the whole question there is whether the request was made
         * at all. */
        var upsertAttempts = 0
        var clock = 1_000L

        override suspend fun fetchActive(): Result<List<RemotePlace>> =
            Result.success(rows.values.filterNot { it.deleted })

        override suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace> {
            upsertAttempts++
            val refusal = upsertRefusal
            val failure: Throwable? = when {
                refusal != null -> EngineHttpException(refusal)
                upsertFails -> PlacesBackendException("simulated network failure")
                else -> null
            }
            if (failure != null) return Result.failure(failure)
            upsertCalls++
            val row = RemotePlace(label, latitude, longitude, updatedAtMs = ++clock, deleted = false)
            rows[label] = row
            return Result.success(row)
        }

        override suspend fun softDelete(label: String): Result<Boolean> {
            if (deleteFails) return Result.failure(PlacesBackendException("simulated network failure"))
            val existing = rows[label]
            if (existing == null || existing.deleted) return Result.success(false)
            rows[label] = existing.copy(deleted = true, updatedAtMs = ++clock)
            return Result.success(true)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        setFix(29.7604, -95.3698)
    }

    @After
    fun clearOverride() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

        PlaceController.backendOverride = null
        setFix(null, null)
    }

    private fun setFix(lat: Double?, lon: Double?) {
        val field = LocationController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(LocationController) as MutableStateFlow<Location?>
        flow.value = if (lat == null || lon == null) {
            null
        } else {
            Location("test").apply { latitude = lat; longitude = lon }
        }
    }

    @Test
    fun `a successful tagPlace writes the replica exactly once and returns the ack`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        val ack = PlaceController.tagPlace(context, "work").message

        assertTrue(ack.isNotBlank())
        assertEquals(1, backend.upsertCalls)
        val replica = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, replica.size)
        assertEquals("work", replica.single().label)
    }

    @Test
    fun `a FAILED remote write leaves the replica completely untouched and returns no ack`() = runBlocking {
        val backend = FakePlacesBackend(upsertFails = true)
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, "work").message

        assertTrue(
            "a failed write must say in words that it did not save, never a bare success",
            result.contains("didn't save") || result.contains("went wrong"),
        )
        assertTrue(
            "Room must never be written ahead of a server ACK (ticket 01 ruling 9)",
            CarDatabase.getDatabase(context).placeDao().getAll().isEmpty(),
        )
    }

    @Test
    fun `re-tagging an existing label updates the replica rather than duplicating it`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        PlaceController.tagPlace(context, "work")
        setFix(30.0, -96.0)
        PlaceController.tagPlace(context, "work")

        val replica = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, replica.size)
        assertEquals(30.0, replica.single().latitude, 0.0001)
    }

    @Test
    fun `forget on a label the server does not have returns false without claiming a delete`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        val message = PlaceController.forgetPlace(context, "nowhere").message
        assertTrue(message.contains("don't have"))

        val boolResult = PlaceController.forget(context, "nowhere")
        assertFalse(boolResult)
    }

    @Test
    fun `forgetPlace on a real label deletes server-side and clears the replica`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")

        val ack = PlaceController.forgetPlace(context, "home").message

        assertTrue(ack.isNotBlank())
        assertTrue(CarDatabase.getDatabase(context).placeDao().getAll().isEmpty())
    }

    @Test
    fun `a FAILED re-tag leaves the PREVIOUS row intact rather than blanking it`() = runBlocking {
        // The sibling failure test asserts an EMPTY replica stays empty, which a no-op would also
        // satisfy. This is the version that a no-op cannot pass: a good row is already stored, the
        // refresh of it fails, and the old value must still be there afterwards - unchanged, not
        // blanked and not half-written to the new coordinates.
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")
        val before = CarDatabase.getDatabase(context).placeDao().getAll().single()

        backend.upsertFails = true
        val result = PlaceController.tagPlace(context, "home").message

        assertTrue(
            "a failed write must say it did not save",
            result.contains("didn't save") || result.contains("went wrong"),
        )
        val after = CarDatabase.getDatabase(context).placeDao().getAll().single()
        assertEquals("the stored label must survive a failed refresh", before.label, after.label)
        assertEquals("and its coordinates must not be half-written", before.latitude, after.latitude, 0.0)
        assertEquals(before.longitude, after.longitude, 0.0)
    }

    @Test
    fun `a FAILED remote delete leaves the replica untouched and says so in words`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")
        backend.deleteFails = true

        val message = PlaceController.forgetPlace(context, "home").message

        assertTrue(message.contains("nothing was deleted"))
        assertEquals(1, CarDatabase.getDatabase(context).placeDao().getAll().size)
    }

    @Test
    fun `all reads the replica when configured and never touches the backend`() = runBlocking {
        val backend = FakePlacesBackend(
            seed = listOf(RemotePlace("work", 29.7604, -95.3698, updatedAtMs = 500L, deleted = false)),
        )
        PlaceController.backendOverride = backend
        // Seed the replica directly - all() must read Room, not call fetchActive itself.
        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(label = "work", latitude = 29.7604, longitude = -95.3698, timestamp = 500L),
        )

        val places = PlaceController.all(context)

        assertEquals(1, places.size)
        assertEquals("work", places.single().label)
    }

    @Test
    fun `currentLabel performs no network IO - configured but the backend is never called`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(label = "work", latitude = 29.7604, longitude = -95.3698, timestamp = 500L),
        )

        val label = PlaceController.currentLabel(context)

        assertEquals("work", label)
        assertEquals(0, backend.upsertCalls)
    }

    // -- django-engine ticket 14: the rules that used to live only on the phone ------------------

    /** The exact body `server/api/places.py`'s `PlaceSerializer.validate_label` produces for a
     * 31-character label, in DRF's field-error envelope. Copied from the server's own sentence
     * rather than paraphrased - if that sentence changes, this test should be updated to the new
     * one, and the point being asserted (the phone says what the SERVER said) is unaffected. */
    private val serverLabelRefusal =
        """{"label":["Nothing was saved. That place name is 31 characters long and a place name can be """ +
            """at most 30. A name that long is usually a whole sentence that was heard as one - try """ +
            """something short, like 'home' or 'the gym'."]}"""

    @Test
    fun `a server refusal is relayed in the server's own words, not paraphrased`() = runBlocking {
        // The label cap now lives in the engine (ticket 14). A rule the phone does not hold can
        // only be explained by the server, so the sentence has to survive the trip intact.
        val backend = FakePlacesBackend(upsertRefusal = EngineFailure.Refused(400, serverLabelRefusal))
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, "gym").message

        assertTrue(
            "the engine's own explanation must reach the user: was <$result>",
            result.contains("31 characters long") && result.contains("at most 30"),
        )
        assertFalse(
            "and it must arrive unwrapped, not wearing DRF's JSON envelope",
            result.contains("{\"label\"") || result.contains("[\""),
        )
        assertTrue(
            "Room must never be written when the server refused",
            CarDatabase.getDatabase(context).placeDao().getAll().isEmpty(),
        )
    }

    @Test
    fun `a 5xx is a fault, not a refusal, and keeps the generic sentence`() = runBlocking {
        // EngineHttp.classify files 5xx under Refused too (the request DID reach the engine), so
        // relaying every Refused verbatim would read a server fault out as though the user had
        // asked for something disallowed.
        val backend = FakePlacesBackend(
            upsertRefusal = EngineFailure.Refused(500, "The engine failed on its side (HTTP 500)."),
        )
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, "gym").message

        assertTrue(
            "a fault must still say in words that nothing saved: was <$result>",
            result.contains("didn't save") || result.contains("went wrong"),
        )
        assertFalse("and must not quote the HTTP status at the user", result.contains("HTTP 500"))
    }

    @Test
    fun `the blank-label guard survives and never reaches the backend`() = runBlocking {
        // Ticket 14 keeps this one deliberately: an empty path segment addresses the COLLECTION
        // route, not a row, so `PUT /api/places//` is not a write this label could ever make. It
        // is load-bearing for URL construction, not a duplicated business rule.
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        // "by the way" and "location" are not blank as spoken - normalizeLabel strips both to
        // nothing, which is the same refusal by a different road and is worth holding here too.
        for (nothing in listOf("", "   ", "by the way", "location")) {
            val result = PlaceController.tagPlace(context, nothing).message
            assertTrue(
                "a label with no name in it must be refused before any write: <$nothing> gave <$result>",
                result.contains("didn't catch"),
            )
        }
        assertEquals("nothing may reach the server", 0, backend.upsertCalls)
        assertTrue(CarDatabase.getDatabase(context).placeDao().getAll().isEmpty())
    }

    /** 31 characters - one past `PlaceSerializer.LABEL_MAX_LENGTH`, which is the length
     * [serverLabelRefusal] above is the engine's real answer to. */
    private val tooLongLabel = "coffee shop on north westheimer"

    @Test
    fun `on Supabase the local label cap still applies and nothing reaches the server`() = runBlocking {
        // The trap from django-engine ticket 14, held as a test: `public.places` has
        // `check (length(trim(label)) > 0)` and NO length bound, and this transport writes the
        // replica off a server ACK that would happily arrive. Deleting the local cap here does not
        // move the rule to a server - it deletes the rule.
        assertEquals(
            "this test is only meaningful while places still defaults to Supabase",
            Transport.SUPABASE,
            EngineTransport(context).transportFor(EngineBackends.ASPECT_PLACES),
        )
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, tooLongLabel).message

        assertEquals("nothing in normalizeLabel shortens this one", 31, tooLongLabel.length)
        assertTrue("the local guard is what answers here: was <$result>", result.contains("didn't catch"))
        assertEquals("and nothing may be attempted against the server", 0, backend.upsertAttempts)
        assertTrue(CarDatabase.getDatabase(context).placeDao().getAll().isEmpty())
    }

    @Test
    fun `on Django the label cap defers to the server, whose sentence reaches the user`() = runBlocking {
        // The A25 defect of 2026-09-07: the local cap returned "I didn't catch what to call this
        // spot" and the request was never made, so PlaceController.engineRefusal - a function whose
        // own doc names this exact case as what it relays - could not fire. The engine's wording is
        // the deliverable: it names the length, the limit, and what a name that long usually is.
        EngineTransport(context).setTransport(EngineBackends.ASPECT_PLACES, Transport.DJANGO)
        val backend = FakePlacesBackend(upsertRefusal = EngineFailure.Refused(400, serverLabelRefusal))
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, tooLongLabel).message

        assertEquals("the request must actually be made", 1, backend.upsertAttempts)
        assertTrue(
            "the engine's own explanation must reach the user: was <$result>",
            result.contains("31 characters long") && result.contains("at most 30"),
        )
        assertFalse("the local guard's sentence must not be what answers", result.contains("didn't catch"))
        assertTrue(
            "a refused write still writes nothing",
            CarDatabase.getDatabase(context).placeDao().getAll().isEmpty(),
        )
    }

    @Test
    fun `on Django with no engine resolved, the local cap is still the only guard there is`() = runBlocking {
        // Transport flipped but NO backend - a device set to Django with no address or token. The
        // unconfigured branch writes straight into Room with no server in the path, so lifting the
        // cap on the transport alone would store a misheard sentence as a place name. This is the
        // half of serverOwnsTheLabelCap that is easy to drop and impossible to notice.
        EngineTransport(context).setTransport(EngineBackends.ASPECT_PLACES, Transport.DJANGO)
        PlaceController.backendOverride = null

        val result = PlaceController.tagPlace(context, tooLongLabel).message

        assertTrue("the local guard must still answer: was <$result>", result.contains("didn't catch"))
        assertTrue(
            "and nothing may be stored locally either",
            CarDatabase.getDatabase(context).placeDao().getAll().isEmpty(),
        )
    }
}
