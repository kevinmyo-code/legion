package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.purchases.PurchaseDraft
import com.kevin.legion.purchases.PurchaseOutcome
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
 * [DjangoPurchasesBackend] against `server/openapi.yaml`'s purchase shapes: the honest outcome
 * states (ok, unreachable, refused, plus unconfirmed for an unreadable 2xx) and the exact requests
 * that go out. Also the checklists `untick` request, which now carries `?today=`.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoPurchasesBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoPurchasesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private val entry = """
        {"id": "p1", "item": "Head & Shoulders shampoo", "bought_on": 20351, "bought_on_date": "2025-11-20",
         "logged_at": "2025-11-20T10:00:00Z", "logged_by": "Mia", "logged_by_me": false,
         "store": "Target", "price_cents": 899, "price_note": "entered by hand", "quantity_note": null,
         "visibility": "private", "source": "MANUAL", "tick": null, "deleted_at": null, "sync_id": "s1"}
    """.trimIndent()

    private val backfilled = """
        {"id": "p2", "item": "shampoo", "bought_on": 20300, "bought_on_date": "2025-09-30",
         "logged_at": "2025-10-04T10:00:00Z", "logged_by": null, "logged_by_me": false,
         "store": null, "price_cents": null, "price_note": null, "quantity_note": null,
         "visibility": "shared", "source": "GROCERIES_BACKFILL", "tick": null, "deleted_at": null, "sync_id": null}
    """.trimIndent()

    @Test
    fun `last-bought decodes every match, keeps null logged_by as null and sends q`() = runBlocking {
        val body = """{"query": "shampoo", "message": "m", "matches": [
            {"entry": $entry, "exact": false, "times_logged": 2},
            {"entry": $backfilled, "exact": true, "times_logged": 1}]}"""
        val engine = EngineTestSupport.RecordingEngine { json(body) }

        val outcome = backend(engine).lastBought("shampoo")

        val matches = (outcome as PurchaseOutcome.Ok).value
        assertEquals(2, matches.size)
        assertEquals("Head & Shoulders shampoo", matches[0].entry.item)
        assertEquals("Mia", matches[0].entry.loggedBy)
        assertEquals(899L, matches[0].entry.priceCents)
        assertTrue(matches[0].entry.isPrivate)
        assertEquals(false, matches[0].exact)
        assertNull("not recorded stays null, never a guessed name", matches[1].entry.loggedBy)
        val url = engine.requests.single().url
        assertEquals("/api/purchases/last-bought", url.encodedPath)
        assertEquals("shampoo", url.parameters["q"])
    }

    @Test
    fun `list decodes results and the engine's own empty message`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"results": [], "truncated": false, "message": "Nothing logged yet."}""")
        }

        val listing = (backend(engine).list(null, 10) as PurchaseOutcome.Ok).value

        assertTrue(listing.entries.isEmpty())
        assertEquals("Nothing logged yet.", listing.message)
        assertEquals("10", engine.requests.single().url.parameters["limit"])
    }

    @Test
    fun `create posts the entry and a 201 is ok`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(entry, HttpStatusCode.Created) }

        val outcome = backend(engine).create(
            PurchaseDraft(
                "shampoo", 20351, store = "Target", priceCents = 899, note = null, isPrivate = true, syncId = "s1",
            ),
        )

        assertTrue(outcome is PurchaseOutcome.Ok)
        val request = engine.requests.single()
        assertEquals("/api/purchases/", request.url.encodedPath)
        val sent = (request.body as io.ktor.http.content.TextContent).text
        assertTrue(sent, sent.contains("\"visibility\":\"private\""))
        assertTrue(sent, sent.contains("\"price_cents\":899"))
        assertTrue(sent, sent.contains("\"sync_id\":\"s1\""))
        assertTrue("an absent note is not sent", !sent.contains("quantity_note"))
    }

    @Test
    fun `an unreachable engine is Unreachable, not an empty answer`() = runBlocking {
        val backend = DjangoPurchasesBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )

        assertTrue(backend.lastBought("shampoo") is PurchaseOutcome.Unreachable)
        assertTrue(backend.list(null, 5) is PurchaseOutcome.Unreachable)
        assertTrue(backend.create(PurchaseDraft("x", 1, syncId = "s")) is PurchaseOutcome.Unreachable)
    }

    @Test
    fun `a 400 is Refused in the engine's own sentence, unwrapped`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"item": ["This field may not be blank."]}""", HttpStatusCode.BadRequest)
        }

        val outcome = backend(engine).create(PurchaseDraft("", 1, syncId = "s"))

        assertEquals(PurchaseOutcome.Refused("This field may not be blank."), outcome)
    }

    @Test
    fun `a rejected token is Refused with the sign-in sentence`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"detail": "bad token"}""", HttpStatusCode.Unauthorized)
        }

        val outcome = backend(engine).lastBought("shampoo")

        assertTrue(outcome is PurchaseOutcome.Refused)
        assertTrue((outcome as PurchaseOutcome.Refused).sentence.contains("Sign in again"))
    }

    @Test
    fun `a 2xx that does not decode is Unconfirmed, never ok`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"id": 5}""", HttpStatusCode.Created) }

        val outcome = backend(engine).create(PurchaseDraft("shampoo", 1, syncId = "s"))

        assertTrue(outcome is PurchaseOutcome.Unconfirmed)
    }

    @Test
    fun `checklist untick with today puts today on the query, and the old form does not`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }
        val checklists = DjangoChecklistsBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

        checklists.untick("c1", "i1", 20_703, 20_704).getOrThrow()
        checklists.untick("c1", "i1", 20_703).getOrThrow()

        assertEquals("20704", engine.requests[0].url.parameters["today"])
        assertEquals("/api/checklists/c1/items/i1/tick/20703", engine.requests[0].url.encodedPath)
        assertNull(engine.requests[1].url.parameters["today"])
    }
}
