package com.kevin.legion.backend.engine

import com.kevin.legion.backend.CommitOutcome
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.backend.MigratedLedgerTransaction
import com.kevin.legion.backend.MigratedReceipt
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoLedgerBackend] and [DjangoPantryBackend] - the two aspects whose tables sit behind
 * CLAUDE.md section 4's gate.
 *
 * **The property this file exists to pin is an ABSENCE**, which is why several cases assert on
 * `engine.requests` rather than on a return value: `statements`, `ledger_transactions`, `receipts`,
 * `receipt_line_items` and `ingested_files` are `GatedReadViewSet` tables whose detail routes carry
 * `get` and nothing else (`server/api/synced.py`'s `synced_paths`: "a gated table's detail route
 * carries `get` and nothing else, so PUT and DELETE reach `http_method_not_allowed`"). A client
 * that offered a write path against one would be caught by the server - but only after sending a
 * request that asserts something it has no business asserting, so these backends refuse first and
 * send nothing at all.
 *
 * Shapes come from `server/openapi.yaml` (the `LedgerTransaction`, `Receipt`, `ReceiptLineItem`,
 * `ReceiptCommitted` and `IngestOutcome` schemas), which is the authority this ticket was told to
 * read rather than the live engine, since a deploy can lag `dev`.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoLedgerAndPantryGateTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun http(engine: EngineTestSupport.RecordingEngine) =
        EngineHttp(EngineTestSupport.signedInConfig(context), engine.client())

    // --- ledger --------------------------------------------------------------------------------

    private val oneTransactionPage = """
        {"results": [{
          "id": "6b1f0a2c-0000-4000-8000-000000000001",
          "statement_id": "9c2e0b3d-0000-4000-8000-000000000002",
          "account_last4": "4821",
          "account_nickname": "BofA Checking",
          "currency": "USD",
          "txn_date": "2026-08-04",
          "description": "HEB #472",
          "amount_cents": -8347,
          "balance_cents": 512900,
          "line_ref": "2026-08-04|HEB #472|-8347",
          "category": "groceries",
          "category_pending": false,
          "pending_logged_at": null,
          "reversal_of": null,
          "provenance": "DETERMINISTIC",
          "created_at": "2026-08-30T04:12:07.114521Z",
          "origin_guid": null
        }], "next": null}
    """.trimIndent()

    @Test
    fun `transactions are read with a since-less feed and never with active=1`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(oneTransactionPage) }

        val rows = DjangoLedgerBackend(http(engine)).fetchActiveTransactions().getOrThrow()

        val row = rows.single()
        assertEquals(-8347L, row.amountCents)
        assertEquals("DETERMINISTIC", row.provenance)
        assertNull("a row the gate committed carries no origin_guid", row.originGuid)
        // 2026-08-04T00:00:00Z - a bare DATE column, read at UTC midnight so it cannot shift a day
        // between the two transports.
        assertEquals(1785801600000L, row.txnDateEpochMs)

        val url = engine.requests.single().url
        assertEquals("/api/ledger/transactions/", url.encodedPath)
        // `has_tombstones = False` on a GatedReadViewSet, so `?active=1` is a parameter this view
        // does not advertise; "everything" is a since-less request, never a narrowed one.
        assertNull(url.parameters["active"])
        assertNull(url.parameters["since"])
    }

    @Test
    fun `the changed-transactions feed sends the watermark on the created_at cursor`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(oneTransactionPage) }

        DjangoLedgerBackend(http(engine)).fetchChangedTransactionsSince(1_786_000_000_000L).getOrThrow()

        val url = engine.requests.single().url
        // GatedReadViewSet.cursor_field is "created_at", which is the same column
        // SupabaseLedgerBackend filters with a `gte` - so both transports answer one question.
        // Z-suffixed by Instant.toString(), which is what api/sync.parse_since reads without help.
        assertEquals("2026-08-06T07:06:40Z", url.parameters["since"])
        assertNull(url.parameters["active"])
    }

    @Test
    fun `a migrated transaction upload sends NOTHING and says the gate owns that table`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = DjangoLedgerBackend(http(engine)).uploadMigratedTransaction(
            MigratedLedgerTransaction(
                originGuid = "0f8f0000-0000-4000-8000-00000000000a",
                statementId = null,
                accountLast4 = "4821",
                accountNickname = "BofA Checking",
                currency = "USD",
                txnDateEpochMs = 1_785_801_600_000L,
                description = "HEB #472",
                amountCents = -8347,
                balanceCents = null,
                lineRef = "ref",
                category = null,
                categoryPending = false,
                pendingLoggedAtMs = null,
                provenance = IngestMethod.UNRECONCILED,
            ),
        )

        assertTrue("nothing may report a migration that did not happen", result.isFailure)
        assertTrue(
            "no request at all - not even one the server would 405: ${engine.requests}",
            engine.requests.isEmpty(),
        )
        val sentence = (result.exceptionOrNull() as EngineHttpException).failure.sentence
        assertTrue("the gate's own endpoint is named: $sentence", sentence.contains("/api/ingest/statement"))
        assertTrue("and the absence is stated in words: $sentence", sentence.contains("Nothing was sent"))
    }

    // --- pantry --------------------------------------------------------------------------------

    private val oneReceiptPage = """
        {"results": [{
          "id": "aa1f0a2c-0000-4000-8000-000000000001",
          "ingested_file_id": null,
          "store": "HEB",
          "purchase_date": "2026-08-04",
          "currency": "USD",
          "total_cents": 8347,
          "subtotal_cents": 7712,
          "tax_cents": 635,
          "other_charges_cents": null,
          "photo_object_path": null,
          "provenance": "LLM_RECONCILED",
          "created_at": "2026-08-30T04:12:07.114521Z",
          "origin_guid": null,
          "unaccounted_cents": null
        }], "next": null}
    """.trimIndent()

    private val oneLinePage = """
        {"results": [{
          "id": "bb1f0a2c-0000-4000-8000-000000000009",
          "receipt_id": "aa1f0a2c-0000-4000-8000-000000000001",
          "name": "bananas",
          "quantity": "2.500",
          "unit_price_cents": 59,
          "total_price_cents": 148,
          "estimated_calories_kcal": "265.00",
          "estimated_protein_g": "3.20",
          "estimated_carbs_g": null,
          "estimated_fat_g": null,
          "reversal_of": null,
          "provenance": "LLM_RECONCILED",
          "created_at": "2026-08-30T04:12:07.114521Z",
          "origin_guid": null
        }], "next": null}
    """.trimIndent()

    @Test
    fun `receipts join their lines, and the decimal columns arrive as STRINGS`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.url.encodedPath.contains("line-items")) json(oneLinePage) else json(oneReceiptPage)
        }

        val receipts = DjangoPantryBackend(http(engine)).fetchActiveReceipts().getOrThrow()

        val receipt = receipts.single()
        assertEquals(8347L, receipt.totalCents)
        assertNull("a healthy receipt has no unexplained residual", receipt.unaccountedCents)
        val line = receipt.lines.single()
        // `quantity` and the four estimates are DecimalFields and render as JSON strings
        // (api/pantry.py: "a decimal rendered as a JSON number is a decimal handed to a float
        // parser"). Reading them as numbers would fail to decode outright, which is what this pins.
        assertEquals(2.5, line.quantity, 0.0001)
        assertEquals(265.0, line.estimatedCaloriesKcal!!, 0.0001)
        assertNull(line.estimatedCarbsG)
        // Money is not among them - bigint on the wire, Long here (section 4 rule 3).
        assertEquals(148L, line.totalPriceCents)

        val paths = engine.requests.map { it.url.encodedPath }
        assertEquals(listOf("/api/pantry/receipts/", "/api/pantry/line-items/"), paths)
        assertTrue(
            "neither gated feed may narrow with ?active=1",
            engine.requests.all { it.url.parameters["active"] == null },
        )
    }

    @Test
    fun `commitReceipt posts the payload unchanged to the gate endpoint`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"outcome":"COMMITTED","receipt_id":"aa1f0a2c-0000-4000-8000-000000000001",
                   "inserted":7,"anchors":{},"estimates":{}}""".trimIndent(),
                HttpStatusCode.Created,
            )
        }

        val outcome = DjangoPantryBackend(http(engine))
            .commitReceipt("""{"content_sha256":"abc","store":"HEB","total_cents":8347}""")
            .getOrThrow()

        assertTrue(outcome is CommitOutcome.Committed)
        assertEquals(7, (outcome as CommitOutcome.Committed).insertedLines)

        val request = engine.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/ingest/receipt", request.url.encodedPath)
        // The body IS the RPC payload, not `{"payload": ...}` - the envelope the Supabase transport
        // had to build exists only because the SQL function's one parameter was named `payload`.
        val body = (request.body as TextContent).text
        assertTrue("no envelope was added around it: $body", body.contains("\"content_sha256\":\"abc\""))
        assertTrue("and no wrapper key: $body", !body.contains("\"payload\""))
    }

    @Test
    fun `a quarantine is a success carrying the gate's verdict, never a transport failure`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"outcome":"QUARANTINED","inserted":0,"reason":"lines sum to 8200, total says 8347."}""")
        }

        val outcome = DjangoPantryBackend(http(engine)).commitReceipt("""{"content_sha256":"abc"}""").getOrThrow()

        // Section 4 doing its job is a VERDICT. Reporting it as a failure would invite a blind
        // retry of a document the gate has already judged - see CommitOutcome's own doc comment.
        assertTrue(outcome is CommitOutcome.Quarantined)
        assertEquals(
            "lines sum to 8200, total says 8347.",
            (outcome as CommitOutcome.Quarantined).reason,
        )
    }

    @Test
    fun `a migrated receipt upload sends NOTHING and says the gate owns that table`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = DjangoPantryBackend(http(engine)).uploadMigratedReceipt(
            MigratedReceipt(
                originGuid = "0f8f0000-0000-4000-8000-00000000000b",
                store = "HEB",
                purchaseDateEpochMs = 1_785_801_600_000L,
                currency = "USD",
                totalCents = 8347,
                subtotalCents = 7712,
                taxCents = 635,
                otherChargesCents = null,
                unaccountedCents = null,
                lines = emptyList(),
            ),
        )

        assertTrue(result.isFailure)
        assertTrue("no request at all: ${engine.requests}", engine.requests.isEmpty())
        val sentence = (result.exceptionOrNull() as EngineHttpException).failure.sentence
        assertTrue("the gate's own endpoint is named: $sentence", sentence.contains("/api/ingest/receipt"))
    }
}
