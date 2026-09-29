package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
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
 * [DjangoLedgerBackend.fetchTransactionSet] - the page loop's completeness verdict, which is what
 * lets the ledger mirror delete by absence (backend-etl ticket 14). The server half is
 * `server/tests/test_ledger_categories_and_paging.py`.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoLedgerBackendSetTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoLedgerBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun row(id: String) = """
        {"id": "$id", "statement_id": "stmt", "account_last4": "4146", "account_nickname": "BofA card",
         "currency": "USD", "txn_date": "2026-09-10", "description": "ROW $id", "amount_cents": -100,
         "balance_cents": null, "line_ref": "r-$id", "category": null, "category_pending": true,
         "pending_logged_at": null, "reversal_of": null, "provenance": "DETERMINISTIC",
         "verification_note": null, "created_at": "2026-09-28T12:00:00.000000Z", "origin_guid": null}
    """.trimIndent()

    private val stamp = "2026-09-28T12:00:00Z"

    @Test
    fun `follows next and next_after past a page that shares one timestamp, and reports complete`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            when (request.url.parameters["after"]) {
                null -> json("""{"results": [${row("a")}, ${row("b")}], "next": "$stamp", "next_after": "b"}""")
                "b" -> json("""{"results": [${row("c")}], "next": null, "next_after": null}""")
                else -> error("unexpected after=${request.url.parameters["after"]}")
            }
        }

        val set = backend(engine).fetchTransactionSet().getOrThrow()

        assertTrue(set.complete)
        assertEquals(listOf("a", "b", "c"), set.rows.map { it.serverId })
        val second = engine.requests[1].url
        assertEquals(stamp, second.parameters["since"])
        assertEquals("b", second.parameters["after"])
        assertNull("the first read asks for everything", engine.requests[0].url.parameters["since"])
    }

    @Test
    fun `an engine without the tiebreak stalls on one timestamp and the read says incomplete`() = runBlocking {
        // What the engine did before `paginate_keyset`: `next` repeats and there is no `next_after`.
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"results": [${row("a")}, ${row("b")}], "next": "$stamp"}""")
        }

        val set = backend(engine).fetchTransactionSet().getOrThrow()

        assertFalse(set.complete)
        assertEquals(listOf("a", "b"), set.rows.map { it.serverId })
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `an unreachable engine is a failure, never an empty complete set`() = runBlocking {
        val backend = DjangoLedgerBackend(EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()))
        assertTrue(backend.fetchTransactionSet().isFailure)
    }
}
