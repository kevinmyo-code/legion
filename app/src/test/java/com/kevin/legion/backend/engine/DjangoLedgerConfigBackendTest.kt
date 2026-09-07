package com.kevin.legion.backend.engine

import com.kevin.legion.backend.BudgetTargetFields
import com.kevin.legion.backend.CategoryFields
import com.kevin.legion.backend.CategoryRuleFields
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoLedgerConfigBackend] - the WRITABLE half of the ledger aspect.
 *
 * The contrast with [DjangoLedgerAndPantryGateTest] is the point of both files existing: these
 * three tables (`categories`, `category_rules`, `budget_targets`) carry the full four synced routes
 * and are what the phone writes today, while the gated tables in that file carry `get` and nothing
 * else. One aspect key, two very different halves, and the server draws the line rather than the
 * client choosing where to.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoLedgerConfigBackendTest {

    private val context = RuntimeEnvironment.getApplication()
    private val guid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoLedgerConfigBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the changed-categories feed sends a watermark and includes tombstones`() = runBlocking {
        val page = """
            {"results": [
              {"id":"aaaaaaaa-0000-4000-8000-000000000001","name":"groceries","is_food_category":true,
               "provenance":"USER","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z",
               "deleted_at":null,"origin_guid":"$guid"},
              {"id":"aaaaaaaa-0000-4000-8000-000000000002","name":"retired","is_food_category":false,
               "provenance":"USER","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-02T00:00:00Z",
               "deleted_at":"2026-09-02T00:00:00Z","origin_guid":"bbbbbbbb-0000-4000-8000-000000000003"}
            ], "next": null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(page) }

        val rows = backend(engine).fetchChangedCategoriesSince(1_788_357_600_000L).getOrThrow()

        assertEquals(2, rows.size)
        assertFalse(rows.first { it.name == "groceries" }.deleted)
        // A tombstone is exactly what the merge's delete branch is waiting for, so it must arrive
        // rather than be filtered - LedgerConfigBackend's own interface doc says so in words.
        assertTrue(rows.first { it.name == "retired" }.deleted)

        val url = engine.requests.single().url
        assertEquals("/api/ledger/categories/", url.encodedPath)
        assertEquals("2026-09-02T14:00:00Z", url.parameters["since"])
        assertEquals("never active-only", null, url.parameters["active"])
    }

    @Test
    fun `a category upsert PUTs to its origin_guid and leaves the guid out of the body`() = runBlocking {
        val row = """
            {"id":"aaaaaaaa-0000-4000-8000-000000000001","name":"groceries","is_food_category":true,
             "provenance":"USER","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z",
             "deleted_at":null,"origin_guid":"$guid"}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(row) }

        val saved = backend(engine).upsertCategory(guid, CategoryFields("groceries", true)).getOrThrow()

        assertEquals("groceries", saved.name)
        assertEquals(guid, saved.originGuid)

        val request = engine.requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/ledger/categories/$guid/", request.url.encodedPath)
        val body = (request.body as TextContent).text
        assertTrue("the URL is the authority on identity: $body", !body.contains("origin_guid"))
        assertTrue(body.contains("\"is_food_category\":true"))
    }

    @Test
    fun `a category rule sends created_at_client, never created_at`() = runBlocking {
        val row = """
            {"id":"cccccccc-0000-4000-8000-000000000001","category":"groceries","substring":"HEB",
             "created_at_client":"2026-07-04T12:00:00Z","provenance":"USER",
             "created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z",
             "deleted_at":null,"origin_guid":"$guid"}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(row) }

        val saved = backend(engine)
            .upsertCategoryRule(guid, CategoryRuleFields("groceries", "HEB", 1_783_166_400_000L))
            .getOrThrow()

        // The rule was made on a phone on 2026-07-04; the row reached Postgres on 09-01. Reading
        // `created_at` here would silently re-date every rule to its migration instant.
        assertEquals(1_783_166_400_000L, saved.createdAtMs)

        val body = (request(engine))
        assertTrue("the client clock is sent: $body", body.contains("\"created_at_client\""))
        assertTrue("and the server's own is not: $body", !body.contains("\"created_at\":"))
    }

    @Test
    fun `a budget target sends a bare DATE month and Long cents`() = runBlocking {
        val row = """
            {"id":"dddddddd-0000-4000-8000-000000000001","category":"groceries","currency":"USD",
             "amount_cents":45000,"effective_from_month":"2026-09-01","provenance":"USER",
             "created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z",
             "deleted_at":null,"origin_guid":"$guid"}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(row) }

        val saved = backend(engine).upsertBudgetTarget(
            guid,
            BudgetTargetFields("groceries", "USD", 45_000L, 1_788_220_800_000L),
        ).getOrThrow()

        // Section 4 rule 3: money is Long cents on both sides of the wire, never a Double.
        assertEquals(45_000L, saved.amountCents)
        // 2026-09-01T00:00:00Z - a bare DATE column read at UTC midnight, same as everywhere else.
        assertEquals(1_788_220_800_000L, saved.effectiveFromMonthEpochMs)

        val body = request(engine)
        assertTrue("a DATE column, not a timestamp: $body", body.contains("\"effective_from_month\":\"2026-09-01\""))
        assertTrue(body.contains("\"amount_cents\":45000"))
    }

    @Test
    fun `a delete is a soft delete, and a 404 means there was no such row rather than a failure`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine { json("""{"detail":"No such row."}""", HttpStatusCode.NotFound) }

        assertFalse(backend(gone).softDeleteCategory(guid).getOrThrow())
        assertEquals(HttpMethod.Delete, gone.requests.single().method)

        val ok = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }
        assertTrue(backend(ok).softDeleteBudgetTarget(guid).getOrThrow())
    }

    private fun request(engine: EngineTestSupport.RecordingEngine): String =
        (engine.requests.single().body as TextContent).text
}
