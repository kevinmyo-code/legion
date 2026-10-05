package com.kevin.legion.purchases

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PurchasesController]'s state mapping and its pre-flight refusals, over a fake [PurchasesBackend].
 * The backend's own failure mapping (HTTP to ok / unreachable / refused / unconfirmed) is
 * `DjangoPurchasesBackendTest`; this file proves the controller passes each state through unchanged
 * and never sends what it must refuse first.
 */
class PurchasesControllerTest {

    private fun purchase(item: String = "shampoo", boughtOn: Int = 100) = Purchase(
        id = "p", item = item, boughtOn = boughtOn, loggedBy = "Kevin", loggedByMe = true, store = null,
        priceCents = null, priceNote = null, quantityNote = null, isPrivate = false, source = "MANUAL",
    )

    private class FakeBackend(
        val create: PurchaseOutcome<Purchase>,
        val last: PurchaseOutcome<List<PurchaseMatch>> = PurchaseOutcome.Ok(emptyList()),
    ) : PurchasesBackend {
        val drafts = mutableListOf<PurchaseDraft>()
        var reads = 0
        override suspend fun lastBought(query: String): PurchaseOutcome<List<PurchaseMatch>> {
            reads++
            return last
        }
        override suspend fun list(query: String?, limit: Int): PurchaseOutcome<PurchaseListing> {
            reads++
            return PurchaseOutcome.Ok(PurchaseListing(emptyList(), false, null))
        }
        override suspend fun create(draft: PurchaseDraft): PurchaseOutcome<Purchase> {
            drafts += draft
            return create
        }
        val edits = mutableListOf<PurchaseEdit>()
        val deletes = mutableListOf<String>()
        var change: PurchaseOutcome<Purchase> = create
        var removal: PurchaseOutcome<Unit> = PurchaseOutcome.Ok(Unit)
        override suspend fun update(edit: PurchaseEdit): PurchaseOutcome<Purchase> {
            edits += edit
            return change
        }
        override suspend fun delete(id: String): PurchaseOutcome<Unit> {
            deletes += id
            return removal
        }
    }

    private fun controller(backend: FakeBackend) =
        PurchasesController(backend, todayProvider = { 20_000 }, newSyncId = { "sync-1" })

    @Test
    fun `log defaults the date to today and sends a sync id, trimmed and blank-free`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))

        val outcome = controller(backend).log("  shampoo ", store = "  ", note = " 2 pack ")

        assertTrue(outcome is PurchaseOutcome.Ok)
        val draft = backend.drafts.single()
        assertEquals("shampoo", draft.item)
        assertEquals(20_000, draft.boughtOn)
        assertNull("a blank store is not sent", draft.store)
        assertEquals("2 pack", draft.note)
        assertEquals("sync-1", draft.syncId)
    }

    @Test
    fun `log passes unreachable, refused and unconfirmed through untouched`() = runBlocking {
        val states = listOf(
            PurchaseOutcome.Unreachable("no engine"),
            PurchaseOutcome.Refused("no"),
            PurchaseOutcome.Unconfirmed("garbled"),
        )
        for (state in states) {
            assertEquals(state, controller(FakeBackend(state)).log("shampoo"))
        }
    }

    @Test
    fun `a blank item or a negative price is refused before anything is sent`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))

        val blank = controller(backend).log("   ")
        val negative = controller(backend).log("shampoo", priceCents = -1)

        assertTrue(blank is PurchaseOutcome.Refused)
        assertTrue((blank as PurchaseOutcome.Refused).sentence.contains("Nothing was logged"))
        assertTrue(negative is PurchaseOutcome.Refused)
        assertTrue("the backend was never called", backend.drafts.isEmpty())
    }

    @Test
    fun `lastBought with no words is refused without a request`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))

        assertTrue(controller(backend).lastBought("  ") is PurchaseOutcome.Refused)
        assertEquals(0, backend.reads)
    }

    @Test
    fun `price text becomes whole cents and anything unreadable is invalid, never rounded`() {
        assertEquals(PurchasesController.ParsedPrice(499, true), PurchasesController.parsePrice("4.99"))
        assertEquals(PurchasesController.ParsedPrice(499, true), PurchasesController.parsePrice(" \$4.99 "))
        assertEquals(PurchasesController.ParsedPrice(500, true), PurchasesController.parsePrice("5"))
        assertEquals(PurchasesController.ParsedPrice(null, true), PurchasesController.parsePrice(""))
        assertFalse(PurchasesController.parsePrice("4.999").valid)
        assertFalse(PurchasesController.parsePrice("-3").valid)
        assertFalse(PurchasesController.parsePrice("abc").valid)
    }

    private fun theirs() = purchase().copy(loggedBy = "Mia", loggedByMe = false)

    @Test
    fun `edit sends every field, trimmed, with blanks as null so the engine clears them`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))

        val outcome = controller(backend).edit(
            purchase(), item = " test conditioner ", boughtOn = 5, store = "  ", priceCents = null,
            note = " 2 pack ", isPrivate = true,
        )

        assertTrue(outcome is PurchaseOutcome.Ok)
        val edit = backend.edits.single()
        assertEquals(PurchaseEdit("p", "test conditioner", 5, null, null, "2 pack", true), edit)
    }

    @Test
    fun `a backfilled entry with no logger may be changed, someone elses may not`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))
        val backfilled = purchase().copy(loggedBy = null, loggedByMe = false)

        assertTrue(controller(backend).delete(backfilled) is PurchaseOutcome.Ok)
        val refusedEdit = controller(backend).edit(theirs(), "x", 1)
        val refusedDelete = controller(backend).delete(theirs())

        assertTrue(refusedEdit is PurchaseOutcome.Refused)
        assertTrue(refusedDelete is PurchaseOutcome.Refused)
        assertEquals("only the backfilled delete reached the backend", listOf("p"), backend.deletes)
        assertTrue(backend.edits.isEmpty())
    }

    @Test
    fun `edit refuses a blank item or negative price before sending and delete passes states through`() = runBlocking {
        val backend = FakeBackend(PurchaseOutcome.Ok(purchase()))

        assertTrue(controller(backend).edit(purchase(), "  ", 1) is PurchaseOutcome.Refused)
        assertTrue(controller(backend).edit(purchase(), "x", 1, priceCents = -5) is PurchaseOutcome.Refused)
        assertTrue(backend.edits.isEmpty())

        for (state in listOf(PurchaseOutcome.Unreachable("no engine"), PurchaseOutcome.Refused("no"))) {
            backend.removal = state
            backend.change = state
            assertEquals(state, controller(backend).delete(purchase()))
            assertEquals(state, controller(backend).edit(purchase(), "x", 1))
        }
    }
}
