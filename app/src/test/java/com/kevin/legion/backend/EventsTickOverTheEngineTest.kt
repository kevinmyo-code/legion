package com.kevin.legion.backend

import com.kevin.legion.backend.engine.DjangoEventsBackend
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineTestSupport
import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.testutil.RoomTestReset
import io.ktor.http.HttpMethod
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
 * The tick, end to end over the DJANGO transport: `NotesController.tickAppointment` ->
 * `EventsAppointmentWriter.setDone` -> outbox -> `EventsOutboxDrain` -> a real
 * `PATCH /api/events/<id>` on the wire.
 *
 * **What this file adds over [EventsAppointmentWriterTest], which already covers the same path
 * against a fake [EventsBackend].** A fake proves the writer CALLS `upsert`; it cannot prove the
 * request that leaves the phone is a PATCH to the right path carrying `done: true`, and the defect
 * this fixes was precisely a case of nothing reaching the wire. [DjangoEventsBackend] is built on a
 * [io.ktor.client.engine.mock.MockEngine] here, so no socket opens and the assertion is on the
 * bytes the transport actually produced.
 *
 * **Why the backend is constructed rather than resolved through
 * [com.kevin.legion.backend.engine.EngineBackends].** That resolution is already pinned down by
 * `EngineBackendsTest` ("events resolves to the Django backend only once the transport is
 * flipped"), and reproducing it here would need a real Android Keystore to decrypt the engine
 * token - `EngineConfig`'s production `decrypt` is `KeyVault::decrypt`, which no JVM test has.
 * What is asserted here instead is the half `EngineBackendsTest` cannot reach: given the Django
 * backend, this is the request.
 */
@RunWith(RobolectricTestRunner::class)
class EventsTickOverTheEngineTest {

    private val context = RuntimeEnvironment.getApplication()

    /** The engine's reply to a PATCH: the row as stored, which is what the phone's own merge reads
     * back. Only the fields [DjangoEventsBackend] needs are present; `ignoreUnknownKeys` and the
     * DTO's own defaults cover the rest. */
    private fun storedRow(done: Boolean) = """
        {"id": "9c5f0f7e-0000-4000-8000-000000000001",
         "title": "MATH 3391 Module 3: Discussion - first post due",
         "source": "canvas", "kind": "task",
         "done": $done, "done_at": ${if (done) "\"2026-09-06T18:00:00Z\"" else "null"},
         "created_at": "2026-09-01T00:00:00Z", "updated_at": "2026-09-06T18:00:00Z"}
    """.trimIndent()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun tearDown() {
        EventsAppointmentWriter.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun seedSyncedTask(): Event {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val row = Event(
            id = 0,
            serverId = "9c5f0f7e-0000-4000-8000-000000000001",
            guid = java.util.UUID.randomUUID().toString(),
            title = "MATH 3391 Module 3: Discussion - first post due",
            startsAt = 1_000L,
            endsAt = 2_000L,
            allDay = false,
            source = "canvas",
            kind = EventKind.TASK,
            updatedAtMs = now,
            createdAt = now,
        )
        return row.copy(id = db.eventDao().insert(row))
    }

    private fun bodyOf(request: io.ktor.client.request.HttpRequestData): String =
        (request.body as io.ktor.http.content.TextContent).text

    @Test
    fun `ticking a task PATCHes the engine's own events endpoint with done true`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(storedRow(done = true)) }
        val backend = DjangoEventsBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))
        EventsAppointmentWriter.backendOverride = backend
        val task = seedSyncedTask()

        EventsAppointmentWriter.setDone(context, task, done = true)

        assertEquals(1, engine.requests.size)
        val sent = engine.requests.single()
        assertEquals(HttpMethod.Patch, sent.method)
        assertEquals(
            "${EngineTestSupport.BASE_URL}/api/events/9c5f0f7e-0000-4000-8000-000000000001",
            sent.url.toString(),
        )
        val body = bodyOf(sent)
        assertTrue(body, body.contains("\"done\":true"))
        // done_at rides with it - a PATCH that only flipped `done` would leave the two disagreeing
        // server-side, and DRF's partial=True would not clear it on the untick either.
        assertTrue(body, Regex("\"done_at\":\"[^\"]+\"").containsMatchIn(body))
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `an unreachable engine queues the tick, and the drain sends the same PATCH later`() = runBlocking {
        val offline = DjangoEventsBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )
        EventsAppointmentWriter.backendOverride = offline
        val task = seedSyncedTask()

        EventsAppointmentWriter.setDone(context, task, done = true)

        // The local tick stands and the mutation is queued - never dropped, and never reported as
        // sent (CLAUDE.md section 7).
        assertTrue(CarDatabase.getDatabase(context).eventDao().getById(task.id)!!.done)
        assertEquals(1, CarDatabase.getDatabase(context).outboxDao().getAll().size)

        val engine = EngineTestSupport.RecordingEngine { json(storedRow(done = true)) }
        val backOnline = DjangoEventsBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()),
        )
        val report = EventsOutboxDrain.drain(context, backOnline)

        assertEquals(1, report.succeeded)
        assertEquals(HttpMethod.Patch, engine.requests.single().method)
        assertTrue(bodyOf(engine.requests.single()).contains("\"done\":true"))
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }
}
