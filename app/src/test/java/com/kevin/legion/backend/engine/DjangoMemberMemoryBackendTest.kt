package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.memory.MemoryEdit
import com.kevin.legion.memory.MemoryOutcome
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 09: the wire shapes of `/api/member-memories/` as
 * `server/member_memory/views.py` and `service.as_dict` render them, and the outcome each answer
 * becomes. Made-up memories only.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoMemberMemoryBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoMemberMemoryBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private val row = """{"id": "7b1c0c4e-0000-4000-8000-000000000001", "text": "Jeep needs an LS swap eventually",
        "kind": "fact", "importance": 6, "decay_class": "durable", "source": "consolidation", "shared": false,
        "about_you": true, "supersedes": null, "superseded_at": null, "last_recalled_at": null, "recall_count": 0,
        "from_conversation": "11111111-0000-4000-8000-000000000002", "created_at": "2026-11-05T14:00:00Z",
        "updated_at": "2026-11-05T14:00:00Z"}"""

    @Test
    fun `the list decodes every field the screen shows`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [$row]}""") }
        val outcome = backend(engine).list(500)
        val m = (outcome as MemoryOutcome.Ok).value.single()
        assertEquals("Jeep needs an LS swap eventually", m.text)
        assertEquals("consolidation", m.source)
        assertEquals("11111111-0000-4000-8000-000000000002", m.fromConversation)
        assertTrue(m.aboutYou)
        assertEquals("/api/member-memories/", engine.requests.single().url.encodedPath)
        assertEquals("500", engine.requests.single().url.parameters["limit"])
    }

    @Test
    fun `recall posts the query to recall with no trailing slash`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [${row.dropLast(1)}, "score": 2.1}]}""") }
        val outcome = backend(engine).recall("jeep", 6)
        assertTrue(outcome is MemoryOutcome.Ok)
        val req = engine.requests.single()
        assertEquals("/api/member-memories/recall", req.url.encodedPath)
        assertEquals("""{"query":"jeep","limit":6}""", (req.body as TextContent).text)
    }

    @Test
    fun `create sends text, shared and source`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row, HttpStatusCode.Created) }
        backend(engine).create("x", shared = true, source = "phone")
        val sent = (engine.requests.single().body as TextContent).text
        assertEquals("""{"text":"x","shared":true,"source":"phone"}""", sent)
    }

    @Test
    fun `update sends only what changed`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row) }
        backend(engine).update("abc", MemoryEdit(shared = true))
        val req = engine.requests.single()
        assertEquals("PATCH", req.method.value)
        assertEquals("/api/member-memories/abc", req.url.encodedPath)
        assertEquals("""{"shared":true}""", (req.body as TextContent).text)
    }

    @Test
    fun `delete is ok only on a 204`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine { respond("", HttpStatusCode.NoContent) }
        assertEquals(MemoryOutcome.Ok(Unit), backend(gone).delete("abc"))
        assertEquals("DELETE", gone.requests.single().method.value)

        val odd = EngineTestSupport.RecordingEngine { json("{}") }
        assertTrue(backend(odd).delete("abc") is MemoryOutcome.Unconfirmed)
    }

    @Test
    fun `a 404 and a 400 keep their status and the engine's own words`() = runBlocking {
        val missing = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Nothing was changed. There is no memory with that id that you can see."}""",
                HttpStatusCode.NotFound,
            )
        }
        val refused = backend(missing).delete("abc") as MemoryOutcome.Refused
        assertEquals(404, refused.status)
        assertTrue(refused.sentence.startsWith("Nothing was changed."))

        val vault = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Nothing was saved. They belong in the ID vault on the phone."}""",
                HttpStatusCode.BadRequest,
            )
        }
        val sensitive = backend(vault).create("passport E1234567", false, "phone") as MemoryOutcome.Refused
        assertEquals(400, sensitive.status)
        assertTrue(sensitive.sentence.contains("ID vault"))
    }

    @Test
    fun `a 403 refusal is the engine's sentence, not sign in again`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Nothing was read or written. This is a recruiter's demo visit."}""",
                HttpStatusCode.Forbidden,
            )
        }
        val refused = backend(engine).list(500) as MemoryOutcome.Refused
        assertEquals(403, refused.status)
        assertEquals("Nothing was read or written. This is a recruiter's demo visit.", refused.sentence)
    }

    @Test
    fun `offline is unreachable`() = runBlocking {
        val outcome = DjangoMemberMemoryBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        ).list(500)
        assertTrue(outcome is MemoryOutcome.Unreachable)
    }
}
