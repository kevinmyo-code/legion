package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.testutil.RoomTestReset
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
 * The tombstone-propagation fix, traced 2026-09-02: [SupabaseEventsBackend.fetchActive] filters
 * `deleted_at IS NULL` **server-side**, so a row soft-deleted on another device was never in what
 * a pull saw at all - [EventsSync.pull]'s own tombstone/[merged] branch was correct but
 * unreachable end to end. [EventsSyncTest]'s own `FakeEventsBackend` could not catch this: it is a
 * hand-written in-memory map that never applied that filter in the first place, so it always
 * "worked" regardless of whether the real backend did.
 *
 * **This suite exercises the REAL [SupabaseEventsBackend], not a hand-written fake** - a
 * [MockEngine] stands in for the network transport only, so [SupabaseEventsBackend.fetchChangedSince]'s
 * own decode path (the same `translating`/`EventRowDto`/`toRemoteEvent` code every production call
 * goes through) is what is actually under test, matching the ticket brief's own instruction: "not
 * only a fake that skips the filter."
 */
@RunWith(RobolectricTestRunner::class)
class EventsRealtimeFetchTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun tombstonedRowJson(serverId: String, originGuid: String, updatedAt: String) = """
        [
          {
            "id": "$serverId",
            "title": "Deleted on another device",
            "created_at": "2026-01-01T00:00:00Z",
            "starts_at": null,
            "source": "legion",
            "kind": "event",
            "updated_at": "$updatedAt",
            "deleted_at": "$updatedAt",
            "origin_guid": "$originGuid"
          }
        ]
    """.trimIndent()

    private fun clientBacked(engine: MockEngine) = createSupabaseClient(
        supabaseUrl = "https://test.supabase.co",
        supabaseKey = "test-anon-key",
    ) {
        httpEngine = engine
        install(Postgrest)
    }

    @Test
    fun `fetchChangedSince decodes a tombstoned row, unlike fetchActive`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = tombstonedRowJson("srv-tomb-1", "guid-tomb-1", "2026-09-02T00:00:00Z"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val backend = SupabaseEventsBackend(clientBacked(engine))

        val result = backend.fetchChangedSince(0L).getOrThrow()

        assertEquals(1, result.size)
        assertTrue("a tombstoned row must decode with deleted = true", result.single().deleted)
        // The request itself must carry no deleted_at filter - the whole point of this method
        // existing as a SEPARATE function from fetchActive, not a parameter added to it.
        val requestUrl = engine.requestHistory.single().url.toString()
        assertFalse("fetchChangedSince must never filter out tombstones", requestUrl.contains("deleted_at"))
    }

    @Test
    fun `a server tombstone soft-deletes the matching local row through the real backend`() = runBlocking {
        val guid = "guid-tomb-2"
        val db = CarDatabase.getDatabase(context)
        val localId = db.eventDao().insert(
            Event(
                serverId = "srv-tomb-2",
                title = "About to be deleted elsewhere",
                startsAt = null,
                source = "legion",
                updatedAtMs = 1_000L,
                kind = EventKind.EVENT,
                guid = guid,
                deleted = false,
            ),
        )

        val engine = MockEngine { _ ->
            respond(
                content = tombstonedRowJson("srv-tomb-2", guid, "2026-09-02T00:00:00Z"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val backend = SupabaseEventsBackend(clientBacked(engine))

        val report = EventsSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = db.eventDao().getById(localId)
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
    }
}
