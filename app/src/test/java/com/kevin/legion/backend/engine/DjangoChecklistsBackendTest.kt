package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoChecklistsBackend] against `server/checklists/views.py`'s real shapes.
 *
 * The `/api/changes?aspects=checklists` body used here is the one the LIVE engine returned on
 * 2026-09-06 (`{"server_time": ..., "checklists": [], "checklist_items": [], "checklist_ticks": []}`
 * - those tables really are still empty, which is the state this ticket's backfill exists to
 * change), with populated rows added in the shape `ChecklistSerializer`/`ChecklistItemSerializer`/
 * `ChecklistTickSerializer` declare.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoChecklistsBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoChecklistsBackend =
        DjangoChecklistsBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `fetchChanges decodes all three tables and the server_time watermark`() = runBlocking {
        val body = """
            {
              "server_time": "2026-09-06T12:47:39.687402Z",
              "checklists": [{
                "id": "c1", "name": "bio", "schedule_kind": "DAILY", "schedule_every": 1,
                "schedule_days_of_week": null, "sort_order": 0, "archived": false,
                "created_at": "2026-09-05T10:00:00Z", "updated_at": "2026-09-06T10:00:00Z",
                "deleted_at": null, "sync_id": "sync-c1"
              }],
              "checklist_items": [{
                "id": "i1", "checklist": "c1", "text": "walk 10k steps", "sort_order": 0,
                "created_at": "2026-09-05T10:00:00Z", "updated_at": "2026-09-06T10:00:00Z",
                "deleted_at": null, "sync_id": "sync-i1", "measure_unit": "steps",
                "measure_target": 10000.0, "measure_direction": "AT_LEAST"
              }],
              "checklist_ticks": [{
                "id": "t1", "item": "i1", "day": 20703,
                "ticked_at": "2026-09-06T11:00:00Z", "updated_at": "2026-09-06T11:00:00Z",
                "deleted_at": null, "sync_id": null, "value": 8400.0, "source": "USER_REPORTED"
              }]
            }
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(body) }

        val changes = backend(engine).fetchChanges(null).getOrThrow()

        assertEquals("2026-09-06T12:47:39.687402Z", changes.serverTime)
        assertEquals("bio", changes.checklists.single().name)
        assertEquals("DAILY", changes.checklists.single().scheduleKind)
        assertEquals("c1", changes.items.single().checklistServerId)
        assertEquals(10000.0, changes.items.single().measureTarget!!, 0.001)
        assertEquals(20703, changes.ticks.single().day)
        assertEquals(8400.0, changes.ticks.single().value!!, 0.001)
        // A tick's sync_id is null because TickRequestSerializer has no such field at all - which
        // is exactly why ChecklistsSync matches ticks on (item, day) instead.
        assertEquals(null, changes.ticks.single().syncId)

        // The aspects filter is always sent; `since` is omitted entirely when there is no
        // watermark, which the engine reads as "fetch everything".
        val url = engine.requests.single().url
        assertEquals("/api/changes", url.encodedPath)
        assertEquals("checklists", url.parameters["aspects"])
        assertEquals(null, url.parameters["since"])
    }

    @Test
    fun `a 400 measured-tick refusal surfaces the engine's own sentence, verbatim`() = runBlocking {
        // The exact body `TickRequestSerializer.validate` produces, and the exact sentence
        // `tests/test_checklists_api.py` asserts on - which is itself copied verbatim from
        // ChecklistController.tick's TickOutcome.Refused. The phone and the engine refuse in the
        // same words; this test is what keeps that true through the transport.
        val sentence =
            "\"walk 10k steps\" is measured in steps - give a number to tick it, nothing was recorded."
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"non_field_errors": ["$sentence"]}""", HttpStatusCode.BadRequest)
        }

        val result = backend(engine).tick("c1", "i1", 20703, value = null, source = "USER_REPORTED")

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Refused)
        assertEquals(400, (failure as EngineFailure.Refused).status)
        // Verbatim - nothing prefixed, nothing paraphrased.
        assertTrue(failure.body.contains(sentence))
    }

    @Test
    fun `a patch re-states sync_id rather than sending a null that would wipe it`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "c1", "name": "bio", "sort_order": 0, "archived": false,
                    "created_at": "2026-09-05T10:00:00Z", "updated_at": "2026-09-06T10:00:00Z",
                    "sync_id": "sync-c1"}""",
            )
        }

        backend(engine).patchChecklist(
            serverId = "c1",
            syncId = "sync-c1",
            fields = com.kevin.legion.backend.ChecklistFields("bio", null, null, null, 0, false),
        ).getOrThrow()

        val sent = engine.requests.single().body
        val text = (sent as io.ktor.http.content.TextContent).text
        assertTrue(text.contains("\"sync_id\":\"sync-c1\""))
        // Every other writable column is on the wire too, nulls included - that is what makes a
        // clear-to-null (dropping a schedule) actually clear on DRF's partial=True patch.
        assertTrue(text.contains("\"schedule_kind\":null"))
    }

    @Test
    fun `untick reports false for a 404 and true for the engine's idempotent 204`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine {
            json("""{"detail": "No item x on checklist c1."}""", HttpStatusCode.NotFound)
        }
        val done = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }

        assertFalse(backend(gone).untick("c1", "x", 20703).getOrThrow())
        assertTrue(backend(done).untick("c1", "i1", 20703).getOrThrow())
    }
}
