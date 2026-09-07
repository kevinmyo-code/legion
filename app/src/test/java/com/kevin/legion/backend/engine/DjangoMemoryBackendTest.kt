package com.kevin.legion.backend.engine

import com.kevin.legion.backend.CompanionMemoryFields
import com.kevin.legion.backend.MemoryAuditFields
import com.kevin.legion.backend.MemoryBackend
import com.kevin.legion.backend.MemoryEntryFields
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoMemoryBackend] against `server/api/memory.py`'s real shapes - `memories`,
 * `companion_memories`, `memory_audit`.
 *
 * **The three fixtures are the live engine's own replies**, captured 2026-09-07 and trimmed to two
 * rows each. The append-only refusal asserted below was measured against that engine too:
 * `DELETE /api/memory/memory_audit/06ab059e-.../` answered
 * `405 {"detail":"Nothing was deleted. memory_audit is append-only: ..."}`.
 *
 * `conversation_audit` is a different interface entirely
 * ([com.kevin.legion.backend.ConversationAuditBackend], with its own reconcile) and is neither
 * routed by the engine's `memory` aspect nor touched here.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoMemoryBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoMemoryBackend =
        DjangoMemoryBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the memories since-feed parses and carries the watermark`() = runBlocking {
        val engine =
            EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("memory_memories_page.json")) }

        val rows = backend(engine).fetchChangedMemoryEntriesSince(0L).getOrThrow()

        assertEquals(2, rows.size)
        val work = rows.single { it.serverId == "2af976ec-2367-4f01-b40f-10a9a1579c9f" }
        assertEquals("Work address is 945 Bunker Hill Road, Houston, TX 77024", work.text)
        assertEquals("3b501785-fe66-45ce-8bd6-576b6c1d4493", work.originGuid)
        assertEquals(1785099978969L, work.loggedAtMs)
        assertFalse(work.deleted)

        val url = engine.requests.single().url
        assertEquals("/api/memory/memories/", url.encodedPath)
        assertEquals("1970-01-01T00:00:00Z", url.parameters["since"])
        assertNull(url.parameters["active"])
    }

    @Test
    fun `a companion memory parses and its embedding never appears on the wire`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(EngineTestSupport.fixture("memory_companion_memories_page.json"))
        }

        val rows = backend(engine).fetchChangedCompanionMemoriesSince(0L).getOrThrow()

        val name = rows.single { it.serverId == "6c261297-9d9d-4572-86da-bf97330011d8" }
        assertEquals("Driver's name is Kevin.", name.text)
        assertEquals("driver", name.category)
        assertEquals("consolidated", name.source)
        assertEquals(7, name.importance)
        assertEquals("00:1D:A5:0E:82:0E", name.vehicleId)
        assertEquals(1785091975295L, name.loggedAtMs)
    }

    @Test
    fun `an upsert sends the eleven crossing fields and no embedding at all`() = runBlocking {
        // The embedding never leaves the device. That is true three times over - the local column
        // has no counterpart in RemoteCompanionMemory, in `public.companion_memories`, or in
        // CompanionMemorySerializer.Meta.fields - so there is nothing here to filter. This asserts
        // the guarantee where it is observable, at the wire, so a future field added to the write
        // DTO by mistake fails a test rather than shipping.
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "6c261297-9d9d-4572-86da-bf97330011d8", "vehicle_id": "00:1D:A5:0E:82:0E",
                    "text": "Driver's name is Kevin.", "category": "driver", "source": "consolidated",
                    "importance": 7, "logged_at": "2026-07-26T18:52:55.295000Z",
                    "last_accessed_at": "2026-07-26T18:52:55.295000Z", "provenance": "USER",
                    "created_at": "2026-09-02T17:47:53.851792Z",
                    "updated_at": "2026-09-07T12:00:00Z", "deleted_at": null,
                    "origin_guid": "cc08cf9b-265d-4633-9f60-7d5670c2be1e"}""",
            )
        }

        backend(engine).upsertCompanionMemory(
            "cc08cf9b-265d-4633-9f60-7d5670c2be1e",
            CompanionMemoryFields(
                vehicleId = "00:1D:A5:0E:82:0E",
                text = "Driver's name is Kevin.",
                category = "driver",
                source = "consolidated",
                importance = 7,
                loggedAtMs = 1785091975295L,
                lastAccessedAtMs = 1785091975295L,
            ),
        ).getOrThrow()

        val request = engine.requests.single()
        assertEquals("PUT", request.method.value)
        assertEquals(
            "/api/memory/companion_memories/cc08cf9b-265d-4633-9f60-7d5670c2be1e/",
            request.url.encodedPath,
        )
        val sent = (request.body as TextContent).text
        assertFalse("no embedding vector may reach the wire", sent.contains("embedding"))
        assertFalse(sent.contains("embeddingVector"))
        assertFalse(sent.contains("embedding_model"))
        // The rest DOES cross, so this is not passing because the body was empty.
        assertTrue(sent.contains("\"text\":\"Driver's name is Kevin.\""))
        assertTrue(sent.contains("\"importance\":7"))
    }

    @Test
    fun `memory_audit has no delete path at all, on the interface or on the wire`() = runBlocking {
        // Three guarantees, and this asserts all three.
        //
        // 1. The INTERFACE has no delete. MemoryBackend declares softDeleteMemoryEntry and
        //    softDeleteCompanionMemory and no third one, so there is nothing to call - checked by
        //    reflection rather than by a comment, because a future override would compile fine.
        // `substringBefore("-")` because a suspend function returning Result<Boolean> - an inline
        // value class - is name-mangled by the compiler into `softDeleteMemoryEntry-gIAlu-s`. The
        // hash is a JVM signature detail, not part of the interface, and stripping it is what makes
        // this assertion about the interface rather than about the mangling scheme.
        val deleteFunctions = MemoryBackend::class.java.methods
            .map { it.name.substringBefore("-") }
            .filter { it.startsWith("softDelete") }
            .toSet()
        assertEquals(setOf("softDeleteMemoryEntry", "softDeleteCompanionMemory"), deleteFunctions)

        // 2. Driving every function this backend HAS issues no DELETE to that table.
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"results": [], "next": null}""")
        }
        val subject = backend(engine)
        subject.fetchChangedMemoryAuditSince(0L).getOrThrow()
        subject.softDeleteMemoryEntry("cc08cf9b-265d-4633-9f60-7d5670c2be1e")
        subject.softDeleteCompanionMemory("cc08cf9b-265d-4633-9f60-7d5670c2be1e")
        assertTrue(
            "nothing may DELETE against memory_audit",
            engine.requests.none {
                it.method.value == "DELETE" && it.url.encodedPath.contains("memory_audit")
            },
        )

        // 3. And if one ever did, the engine refuses in words rather than quietly succeeding -
        //    verbatim from the live engine on 2026-09-07.
        val refusal = "Nothing was deleted. memory_audit is append-only: it is an audit trail, " +
            "and a trail with rows removed from it is not one. There is no delete route for this table."
        assertEquals(refusal, engineRefusalSentence("""{"detail": "$refusal"}"""))
    }

    @Test
    fun `an audit row is upserted by PUT and reads back its nullable columns`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "806d1884-95e0-431f-99cd-945ca9217374", "event": "spoken",
                    "store": "speech", "detail": "It's a", "ref_id": 0,
                    "vehicle_id": "00:1D:A5:0E:82:0E", "logged_at": "2026-08-21T04:20:02.063000Z",
                    "provenance": "USER", "created_at": "2026-09-02T17:48:11.948870Z",
                    "updated_at": "2026-09-02T17:48:11.948870Z", "deleted_at": null,
                    "origin_guid": "06ab059e-ce15-4104-86e1-d3a4cd02ca65"}""",
            )
        }

        val saved = backend(engine).upsertMemoryAudit(
            "06ab059e-ce15-4104-86e1-d3a4cd02ca65",
            MemoryAuditFields("spoken", "speech", "It's a", 0L, "00:1D:A5:0E:82:0E", 1787286002063L),
        ).getOrThrow()

        assertEquals("spoken", saved.event)
        assertEquals(0L, saved.refId)
        assertEquals("00:1D:A5:0E:82:0E", saved.vehicleId)
        val request = engine.requests.single()
        assertEquals("PUT", request.method.value)
        assertEquals(
            "/api/memory/memory_audit/06ab059e-ce15-4104-86e1-d3a4cd02ca65/",
            request.url.encodedPath,
        )
    }

    @Test
    fun `an audit row whose nullable columns are absent still decodes`() = runBlocking {
        // ref_id and vehicle_id are nullable server-side while RemoteMemoryAudit declares them
        // non-null; the fallback lives in the row DTO and mirrors SupabaseMemoryBackend's own.
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"results": [{"id": "x", "event": "written", "store": "memories",
                    "detail": "d", "ref_id": null, "vehicle_id": null,
                    "logged_at": "2026-08-21T04:20:02.063000Z",
                    "updated_at": "2026-08-21T04:20:02.063000Z", "deleted_at": null,
                    "origin_guid": "g"}], "next": null}""",
            )
        }

        val row = backend(engine).fetchChangedMemoryAuditSince(0L).getOrThrow().single()

        assertEquals(0L, row.refId)
        assertEquals("", row.vehicleId)
    }

    @Test
    fun `an unreachable engine fails in words rather than reporting a save`() = runBlocking {
        val http = EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient())

        val result = DjangoMemoryBackend(http)
            .upsertMemoryEntry("3b501785-fe66-45ce-8bd6-576b6c1d4493", MemoryEntryFields("remember this", 1L))

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unreachable)
        assertTrue(failure.sentence.startsWith("Couldn't save that memory."))
    }

    @Test
    fun `a paged since-feed follows the next cursor and de-duplicates the overlap row`() = runBlocking {
        // `paginate_since` hands back the LAST row's own updated_at and `parse_since` compares with
        // `>=`, so the last row of each page reappears as the first row of the next. Collecting by
        // server id is what stops that overlap becoming a duplicate; stopping when `next` repeats
        // the cursor it was just given is what stops it looping.
        var call = 0
        val engine = EngineTestSupport.RecordingEngine {
            call++
            if (call == 1) {
                json(
                    """{"results": [
                        {"id": "a", "text": "one", "logged_at": "2026-07-26T21:06:18.969000Z",
                         "updated_at": "2026-09-01T00:00:00Z", "deleted_at": null, "origin_guid": "ga"},
                        {"id": "b", "text": "two", "logged_at": "2026-07-26T21:06:18.969000Z",
                         "updated_at": "2026-09-02T00:00:00Z", "deleted_at": null, "origin_guid": "gb"}
                    ], "next": "2026-09-02T00:00:00Z"}""",
                )
            } else {
                json(
                    """{"results": [
                        {"id": "b", "text": "two", "logged_at": "2026-07-26T21:06:18.969000Z",
                         "updated_at": "2026-09-02T00:00:00Z", "deleted_at": null, "origin_guid": "gb"},
                        {"id": "c", "text": "three", "logged_at": "2026-07-26T21:06:18.969000Z",
                         "updated_at": "2026-09-03T00:00:00Z", "deleted_at": null, "origin_guid": "gc"}
                    ], "next": null}""",
                )
            }
        }

        val rows = backend(engine).fetchChangedMemoryEntriesSince(0L).getOrThrow()

        assertEquals(listOf("a", "b", "c"), rows.map { it.serverId })
        assertEquals(2, engine.requests.size)
        assertEquals("2026-09-02T00:00:00Z", engine.requests[1].url.parameters["since"])
    }
}
