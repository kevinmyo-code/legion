package com.kevin.legion.backend.engine

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoEventsBackend] against the engine's REAL response shape.
 *
 * **The fixture is not hand-written.** `app/src/test/resources/engine_fixtures/events_page.json`
 * holds three rows copied verbatim out of a live `GET /api/events` against the running engine at
 * 192.168.1.117:8000 on 2026-09-06 (only the longest `notes` string was truncated, and only for
 * legibility - every key, every null, every timestamp format is as the server sent it). That is the
 * point: a decoder tested against a fixture someone typed from the serializer's field list proves
 * the author read the same file twice, not that the wire shape parses.
 *
 * The three rows are chosen to cover what a hand-written one would most likely get wrong: a
 * recurring event with a `repeat_end_date` DATE (not a timestamp), an all-day row whose
 * `starts_at` is UTC midnight and whose `structured_meta` is a real JSON OBJECT, and a tombstone
 * with a non-null `deleted_at`.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoEventsBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoEventsBackend =
        DjangoEventsBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `fetchChangedSince decodes the engine's real response shape`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("events_page.json")) }

        val rows = backend(engine).fetchChangedSince(0L).getOrThrow()

        assertEquals(3, rows.size)

        val recurring = rows.first { it.serverId == "7d6accbd-214e-44f5-a862-352047e69589" }
        assertEquals(EventKind.EVENT, recurring.kind)
        assertEquals("WEEKLY", recurring.repeatKind)
        assertEquals("TUESDAY,THURSDAY", recurring.repeatDaysOfWeek)
        // repeat_end_date is a DATE ("2026-12-03"), not a timestamp - parsed as UTC midnight.
        assertEquals(
            Instant.parse("2026-12-03T00:00:00Z").toEpochMilli(),
            recurring.repeatEndDateMs,
        )
        // Microsecond precision plus a `Z` suffix is what DRF actually emits; a parser that only
        // handled `+00:00` or whole seconds would throw here rather than quietly mis-parse.
        assertEquals(Instant.parse("2026-09-05T13:26:43.036779Z").toEpochMilli(), recurring.updatedAtMs)
        assertFalse(recurring.deleted)
        assertNull(recurring.originGuid)

        val allDay = rows.first { it.serverId == "65b840a4-beb7-4ac7-96c3-9097d0ad82b4" }
        assertTrue(allDay.allDay)
        // The all-day UTC-midnight convention survives the round trip untouched - nothing
        // re-derives a LOCAL midnight, which is what would shift this row by a day.
        assertEquals(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli(), allDay.startsAtMs)
        // structured_meta arrives as a jsonb OBJECT and is carried as a compact JSON string, not
        // as an escaped string scalar.
        val meta = allDay.structuredMeta
        assertNotNull(meta)
        assertTrue(meta!!.startsWith("{"))
        assertTrue(meta.contains("\"kind\":\"planning_marker\""))
        assertEquals("a303fd5c-968c-441a-ba6b-99f392b42062", allDay.originGuid)

        val tombstone = rows.first { it.serverId == "7dcbfa00-1203-4582-b141-467479478d30" }
        // deleted_at non-null becomes `deleted = true` - the one thing EventsSync.pull's tombstone
        // branch depends on, and the reason fetchChangedSince must NOT filter tombstones out.
        assertTrue(tombstone.deleted)
        assertNull(tombstone.startsAtMs)
    }

    @Test
    fun `fetchChangedSince sends the watermark as an encoded query parameter`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [], "next": null}""") }

        backend(engine).fetchChangedSince(1_756_000_000_000L).getOrThrow()

        assertEquals(1, engine.requests.size)
        val url = engine.requests.single().url
        assertEquals("/api/events", url.encodedPath)
        assertEquals(Instant.ofEpochMilli(1_756_000_000_000L).toString(), url.parameters["since"])
    }

    @Test
    fun `fetchActive filters tombstones out, unlike fetchChangedSince`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("events_page.json")) }

        val rows = backend(engine).fetchActive().getOrThrow()

        assertEquals(2, rows.size)
        assertTrue(rows.none { it.deleted })
    }

    @Test
    fun `an unreachable engine is a failure that says nothing was sent`() = runBlocking {
        val backend = DjangoEventsBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )

        val result = backend.fetchChangedSince(0L)

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unreachable)
        assertTrue(failure.sentence.contains("nothing was sent"))
    }

    @Test
    fun `a 401 is Unauthorized, never a refusal a caller might queue behind`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"detail": "Invalid token."}""", HttpStatusCode.Unauthorized)
        }

        val result = backend(engine).fetchChangedSince(0L)

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unauthorized)
        assertTrue(failure.sentence.contains("Invalid token."))
    }

    @Test
    fun `uploadMigratedEvent reports true only on a 201, false on the idempotent 200`() = runBlocking {
        // A minimal row - only the STATUS distinguishes the two outcomes here, and the body is
        // decoded purely so a 2xx this client cannot understand fails as Malformed rather than
        // being reported as a successful upload of something nobody checked.
        val row = """{"id": "e1", "title": "a note", "source": "legion",
            "created_at": "2026-09-06T00:00:00Z", "updated_at": "2026-09-06T00:00:00Z"}"""
        val created = EngineTestSupport.RecordingEngine { json(row, HttpStatusCode.Created) }
        val existing = EngineTestSupport.RecordingEngine { json(row, HttpStatusCode.OK) }
        val event = MigratedEvent(originGuid = "guid-1", fields = EventFields(title = "a note", startsAtMs = null))

        assertTrue(backend(created).uploadMigratedEvent(event).getOrThrow())
        // 200 = EventListCreateView.post matched origin_guid and handed back the row that was
        // already there. Reporting that as "created" would be an outcome nobody observed.
        assertFalse(backend(existing).uploadMigratedEvent(event).getOrThrow())
    }

    @Test
    fun `uploadMigratedEvent refuses BEFORE sending when the caller attached skip dates`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }
        val event = MigratedEvent(
            originGuid = "guid-2",
            fields = EventFields(title = "a class", startsAtMs = 0L),
            skipDatesEpochMs = listOf(0L),
        )

        val result = backend(engine).uploadMigratedEvent(event)

        assertTrue(result.isFailure)
        // Nothing went out at all - a partial upload (the event row landed, its skips did not) is
        // impossible rather than merely unlikely.
        assertTrue(engine.requests.isEmpty())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("nothing was sent"))
    }

    @Test
    fun `fetchSkips reports unknown, never an empty list`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = backend(engine).fetchSkips("any-id")

        // An empty list is the same value as "this event has no skipped occurrences". The engine
        // has no such endpoint at all, so saying so is the only honest answer.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unknown, not empty"))
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `softDelete reports false for a 404 and true for the engine's idempotent 204`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine {
            json("""{"detail": "No event with id x."}""", HttpStatusCode.NotFound)
        }
        val removed = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }

        assertFalse(backend(gone).softDelete("x").getOrThrow())
        assertTrue(backend(removed).softDelete("x").getOrThrow())
    }
}
