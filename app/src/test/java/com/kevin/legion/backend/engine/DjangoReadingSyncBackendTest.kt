package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.books.BookmarkWrite
import com.kevin.legion.books.DjangoReadingSyncBackend
import com.kevin.legion.books.PositionWrite
import com.kevin.legion.books.SyncReply
import com.kevin.legion.books.SyncVariant
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoReadingSyncBackend] against ticket 08's contract in
 * `.scratch/books/issues/08-build-reading-sync-engine.md`. The engine is not built or deployed when
 * this is written, so these shapes are the ticket's, not a recording of a live reply.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoReadingSyncBackendTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sha = "c".repeat(64)

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoReadingSyncBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun positionJson(at: String = "2026-10-23T10:00:00Z", updated: String = "2026-10-23T10:00:05Z") =
        """{"book_sha256": "$sha", "variant": "original", "locator": {"href": "c1.xhtml"},
            "total_progression": 0.43, "chapter_label": "Ch 3", "recorded_at": "$at",
            "device": "web", "updated_at": "$updated"}"""

    @Test fun `positions are read from a results envelope and the cursor is the newest updated_at`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            val older = positionJson(updated = "2026-10-23T10:00:05Z")
            val newer = positionJson(updated = "2026-10-23T11:00:00Z")
            json("""{"results": [$older, $newer], "next": null}""")
        }
        val reply = backend(engine).positions(since = "2026-10-22T00:00:00+00:00") as SyncReply.Ok
        assertEquals(2, reply.value.rows.size)
        assertEquals("2026-10-23T11:00:00Z", reply.value.cursor)
        val first = reply.value.rows.first()
        assertEquals(SyncVariant.ORIGINAL, first.variant)
        assertEquals(0.43, first.totalProgression, 0.0)
        assertEquals("""{"href":"c1.xhtml"}""", first.locator)
        assertEquals("web", first.device)
        val request = engine.requests.single()
        assertEquals("/api/books/positions", request.url.encodedPath)
        // Ktor encodes the '+' of an isoformat() offset; a raw one would decode to a space on the server.
        assertEquals("2026-10-22T00:00:00+00:00", request.url.parameters["since"])
        assertTrue(request.url.encodedQuery.contains("%2B"))
    }

    @Test fun `a bare array is read too and rows with an unknown variant are skipped`() = runBlocking {
        val bad = positionJson().replace("original", "mystery")
        val engine = EngineTestSupport.RecordingEngine { json("[${positionJson()}, $bad]") }
        val reply = backend(engine).positions(null) as SyncReply.Ok
        assertEquals(1, reply.value.rows.size)
        assertNull(engine.requests.single().url.parameters["since"])
    }

    @Test fun `a next cursor is followed and a repeated one stops the loop`() = runBlocking {
        var page = 0
        val engine = EngineTestSupport.RecordingEngine {
            page++
            json("""{"results": [${positionJson()}], "next": "2026-10-23T10:00:05Z"}""")
        }
        val reply = backend(engine).positions(null) as SyncReply.Ok
        // Page 1 has no since, asks again with its next; page 2 repeats the same next, so it stops there.
        assertEquals(2, page)
        assertEquals(2, reply.value.rows.size)
    }

    @Test fun `bookmarks keep tombstones and read the client's uuid from id`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"results": [{"id": "u-1", "book_sha256": "$sha", "variant": "resizable", "locator": {"page": 4},
                    "total_progression": 0.2, "label": "x", "note": null, "created_at": "2026-10-23T09:00:00Z",
                    "deleted_at": "2026-10-23T09:30:00Z", "updated_at": "2026-10-23T09:30:00Z"}]}""",
            )
        }
        val row = (backend(engine).bookmarks(null) as SyncReply.Ok).value.rows.single()
        assertEquals("u-1", row.uuid)
        assertEquals(SyncVariant.RESIZABLE, row.variant)
        assertTrue(row.deletedAtMs != null)
    }

    @Test fun `putting a position goes to the hash and variant path with the contract's body`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(positionJson()) }
        val write = PositionWrite("""{"href":"c1.xhtml"}""", 0.43, "Ch 3", 1_761_213_600_000, "phone")
        val reply = backend(engine).putPosition(sha, SyncVariant.RESIZABLE, write) as SyncReply.Ok
        assertEquals(false, reply.value.keptNewer)
        val request = engine.requests.single()
        assertEquals("/api/books/positions/$sha/resizable", request.url.encodedPath)
        assertEquals("PUT", request.method.value)
        val body = (request.body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("\"total_progression\":0.43"))
        assertTrue(body.contains("\"device\":\"phone\""))
    }

    @Test fun `a kept newer answer carries the stored row`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(positionJson().trimEnd('}') + """, "kept": "newer"}""")
        }
        val write = PositionWrite(null, 0.1, null, 5, "phone")
        val reply = backend(engine).putPosition(sha, SyncVariant.ORIGINAL, write) as SyncReply.Ok
        assertTrue(reply.value.keptNewer)
        assertEquals(0.43, reply.value.stored!!.totalProgression, 0.0)
    }

    @Test fun `a 200 for a bookmark create is success and the body is the uuid path`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }
        val reply = backend(engine).putBookmark("u-9", BookmarkWrite(sha, SyncVariant.ORIGINAL, null, 0.1, "x", 5))
        assertTrue(reply is SyncReply.Ok)
        assertEquals("/api/books/bookmarks/u-9", engine.requests.single().url.encodedPath)
    }

    @Test fun `deleting a bookmark the engine never had counts as deleted`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { respond("", HttpStatusCode.NotFound) }
        assertTrue(backend(engine).deleteBookmark("u-9") is SyncReply.Ok)
        assertEquals("DELETE", engine.requests.single().method.value)
    }

    @Test fun `a 400 is a refusal in the engine's words and a 500 waits`() = runBlocking {
        val refused = backend(
            EngineTestSupport.RecordingEngine { json("""{"detail": "Bad hash."}""", HttpStatusCode.BadRequest) },
        ).putPosition(sha, SyncVariant.ORIGINAL, PositionWrite(null, 0.1, null, 5, "phone"))
        assertEquals("Bad hash.", (refused as SyncReply.Rejected).sentence)

        val failed = backend(EngineTestSupport.RecordingEngine { respond("boom", HttpStatusCode.InternalServerError) })
            .putPosition(sha, SyncVariant.ORIGINAL, PositionWrite(null, 0.1, null, 5, "phone"))
        assertTrue(failed is SyncReply.Unreachable)
    }

    @Test fun `a revoked token is unavailable and an offline engine is unreachable`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { respond("", HttpStatusCode.Unauthorized) }
        val denied = backend(engine).positions(null)
        assertTrue(denied is SyncReply.Unavailable)
        val offline = DjangoReadingSyncBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        ).positions(null)
        assertTrue(offline is SyncReply.Unreachable)
    }

    @Test fun `an answer that is not JSON is a refusal, never an empty success`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { respond("<html>", HttpStatusCode.OK) }
        assertTrue(backend(engine).positions(null) is SyncReply.Rejected)
    }
}
