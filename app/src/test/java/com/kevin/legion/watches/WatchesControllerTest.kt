package com.kevin.legion.watches

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0083: the controller both doors call. Money is integer cents through BigDecimal. */
class WatchesControllerTest {

    @Test
    fun `dollars become integer cents without a float`() {
        assertEquals(25_000L, WatchesController.cents("250"))
        assertEquals(1_99L, WatchesController.cents("1.99"))
        assertEquals(120_000L, WatchesController.cents("\$1,200"))
        assertEquals(10L, WatchesController.cents("0.1"))
        assertNull(WatchesController.cents("lots"))
        assertNull(WatchesController.cents("-5"))
        assertNull(WatchesController.cents(""))
    }

    @Test
    fun `the one line of text fills the kind's own param`() {
        val charge = WatchesController.params(KIND_CHARGE_OVER, "500", "Example Hardware")!!
        assertEquals(JsonPrimitive(50_000L), charge["amount_cents"])
        assertEquals(JsonPrimitive("Example Hardware"), charge["merchant"])
        val balance = WatchesController.params(KIND_BALANCE_UNDER, "100", "ending in 1234")!!
        assertEquals(JsonPrimitive("1234"), balance["account"])
        val mail = WatchesController.params(KIND_MAIL_REPLY, null, "Acme, Example Corp;  Initech ")!!
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Acme"), JsonPrimitive("Example Corp"), JsonPrimitive("Initech"))),
            mail["companies"],
        )
        assertTrue(WatchesController.params(KIND_CANVAS_DUE, null, "ignored")!!.isEmpty())
    }

    @Test
    fun `a money watch with no amount is refused before anything is sent`() = runBlocking {
        val backend = FakeWatchesBackend()
        val out = WatchesController(backend).set(KIND_DEPOSIT_OVER, null, "Example Payroll", null, SOURCE_VOICE)
        assertTrue(out is WatchOutcome.Refused)
        assertTrue((out as WatchOutcome.Refused).sentence.startsWith("No watch was set"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `offline says nothing was set, never that it was`() = runBlocking {
        val backend = FakeWatchesBackend().apply { offline = WatchOutcome.Unreachable("No engine address saved.") }
        val out = WatchesController(backend).set(KIND_GRADE_POSTED, null, null, null, SOURCE_SCREEN)
        val words = WatchWording.failed(out, "set")
        assertTrue(words, words.startsWith("Nothing was set: the engine could not be reached"))
    }
}
