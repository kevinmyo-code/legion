package com.kevin.legion.backend.engine

import com.kevin.legion.backend.HomeTable
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Engine-brain ticket 03: the homes routes as `server/homes/views.py` serves them. Invented rows. */
@RunWith(RobolectricTestRunner::class)
class DjangoHomesBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoHomesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the backfill posts its rows to the table's batch route and reads the counts`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"received": 2, "created": 1, "updated": 0, "unchanged": 1,
                   "failed": [{"origin_guid": "x", "detail": "no"}], "engine_count": 7, "says": "..."}""",
            )
        }
        val rows = listOf("p-1", "p-2").map { guid -> buildJsonObject { put("origin_guid", guid) } }

        val result = backend(engine).batch(HomeTable.PENDING, rows).getOrThrow()

        val request = engine.requests.single()
        assertEquals("POST", request.method.value)
        assertTrue(request.url.encodedPath.endsWith("/api/ledger/pending/batch/"))
        assertTrue((request.body as TextContent).text.contains("\"rows\""))
        assertEquals(1, result.created)
        assertEquals(1, result.unchanged)
        assertEquals(1, result.failed)
        assertEquals(7, result.engineCount)
        assertEquals(listOf("x:"), result.refused)
    }

    @Test
    fun `a clear of a row the engine never held is done, an unreachable engine is not`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine {
            json("""{"detail": "Nothing was changed."}""", HttpStatusCode.NotFound)
        }
        assertTrue(backend(gone).delete(HomeTable.PENDING, "p-1").isSuccess)
        assertTrue(gone.requests.single().url.encodedPath.endsWith("/api/ledger/pending/p-1/"))

        val broken = EngineTestSupport.RecordingEngine {
            json("""{"detail": "boom"}""", HttpStatusCode.InternalServerError)
        }
        assertTrue(backend(broken).delete(HomeTable.PENDING, "p-1").isFailure)
    }
}
