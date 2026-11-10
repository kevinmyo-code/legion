package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.memory.MemoryOutcome
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-review ticket 08's wire shapes, as `server/member_memory/review_views.py` renders them, and
 * the recall flag on a memory row. Made-up memories only.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoMemoryReviewBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun http(engine: EngineTestSupport.RecordingEngine) =
        EngineHttp(EngineTestSupport.signedInConfig(context), engine.client())

    private val change = """{"id": 12, "action": "merge", "status": "pending", "by_code": false,
        "cluster": "4.1", "before": [{"id": "a", "text": "Sam's pharmacy is Maple Pharmacy", "kind": "fact",
        "durable": true}, {"id": "b", "text": null, "kind": null, "durable": null}],
        "after": {"text": "Sam's pharmacy is Maple Pharmacy"}, "after_words": "Sam's pharmacy is Maple Pharmacy",
        "reason": "Same pharmacy.", "action_words": "These say the same thing; keep one.", "confidence": "high",
        "gate": "passed", "evidence_date": "2026-11-07", "created_at": "2026-11-09T10:00:00Z", "result": null}"""

    @Test
    fun `pending decodes what the To check card shows`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [$change]}""") }
        val got = (DjangoMemoryReviewBackend(http(engine)).pending() as MemoryOutcome.Ok).value.single()
        assertEquals(12L, got.id)
        assertEquals("4.1", got.cluster)
        assertEquals(listOf("Sam's pharmacy is Maple Pharmacy", null), got.before.map { it.text })
        assertEquals("2026-11-07", got.evidenceDate)
        val req = engine.requests.single()
        assertEquals("/api/member-memories/review/changes", req.url.encodedPath)
        assertEquals("pending", req.url.parameters["status"])
    }

    @Test
    fun `accept posts to the change and a 409 is refused in the engine's words`() = runBlocking {
        val ok = EngineTestSupport.RecordingEngine { json("""{"id": 12, "status": "accepted", "words": "Done."}""") }
        assertEquals(MemoryOutcome.Ok("Done."), DjangoMemoryReviewBackend(http(ok)).accept(12))
        assertEquals("/api/member-memories/review/changes/12/accept", ok.requests.single().url.encodedPath)
        val stale = EngineTestSupport.RecordingEngine {
            respond(
                """{"detail": "Nothing was changed. Those memories changed since the review."}""",
                HttpStatusCode.Conflict,
                headersOf("Content-Type", "application/json"),
            )
        }
        val refused = DjangoMemoryReviewBackend(http(stale)).accept(12) as MemoryOutcome.Refused
        assertEquals(409, refused.status)
        assertTrue(refused.sentence.contains("changed since the review"))
    }

    @Test
    fun `accept all and restore use their routes`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"accepted": 2, "stale": 0, "words": "Accepted 2."}""")
        }
        val backend = DjangoMemoryReviewBackend(http(engine))
        assertEquals(MemoryOutcome.Ok("Accepted 2."), backend.acceptCluster("4.1"))
        backend.revert(12)
        assertEquals(
            listOf("/api/member-memories/review/clusters/4.1/accept", "/api/member-memories/review/changes/12/revert"),
            engine.requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun `retired keeps only rows the review retired, and a row carries its recall flag`() = runBlocking {
        val base = """"kind": "fact", "importance": 5, "decay_class": "durable", "source": "phone", "shared": false,
            "about_you": true, "created_at": "2026-11-01T10:00:00Z""""
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"results": [{"id": "x", "text": "Old", $base, "retired_reason": "expired", "retired_change": 44},
                {"id": "y", "text": "Live", $base, "retired_reason": null,
                 "pending_review": {"change_id": 9, "action": "merge", "after": "one row"}}]}""",
            )
        }
        val retired = (DjangoMemoryReviewBackend(http(engine)).retired(500) as MemoryOutcome.Ok).value
        assertEquals(listOf("x"), retired.map { it.id })
        assertEquals(44L, retired.single().retiredChange)
        assertEquals("true", engine.requests.single().url.parameters["include_superseded"])
        val listed = (DjangoMemberMemoryBackend(http(engine)).list(500) as MemoryOutcome.Ok).value
        assertEquals("one row", listed.single { it.id == "y" }.pendingReview!!.after)
    }
}
