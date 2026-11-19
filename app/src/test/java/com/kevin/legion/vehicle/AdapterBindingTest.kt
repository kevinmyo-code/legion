@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AdapterBinding]: the words that route "this is the X" to the one binding action. Invented cars. */
class AdapterBindingTest {
    private fun car(id: String, year: Int, make: String, model: String, archived: Boolean = false) =
        Vehicle(obdMac = id, name = "", make = make, model = model, year = year, personaPrompt = "", archived = archived)

    private val fleet = listOf(
        car("c1", 2004, "Acme", "Zephyr"),
        car("c2", 2012, "Bolt", "Comet"),
        car("c3", 2001, "Bolt", "Nova", archived = true),
    )

    @Test
    fun `this car is a year make model on file names that one car`() {
        assertEquals("c1", AdapterBinding.carOnFileNamed(fleet, 2004, "Acme", "Zephyr")?.obdMac)
        assertEquals("c1", AdapterBinding.carOnFileNamed(fleet, 0, "", "zephyr")?.obdMac)
        assertNull("wrong year", AdapterBinding.carOnFileNamed(fleet, 1999, "Acme", "Zephyr"))
        assertNull("not on file", AdapterBinding.carOnFileNamed(fleet, 2010, "Mazda", "Miata"))
        assertNull("archived", AdapterBinding.carOnFileNamed(fleet, 2001, "Bolt", "Nova"))
        val twins = fleet + car("c4", 2004, "Acme", "Zephyr")
        assertNull("two match: ask, never guess", AdapterBinding.carOnFileNamed(twins, 2004, "Acme", "Zephyr"))
    }

    @Test
    fun `the refusal names the binding action and forbids clearing codes`() {
        val r = AdapterBinding.unknownCarRefusal("Zephyr")
        assertTrue(r.contains("car adapter_in vehicle=Zephyr"))
        assertTrue(r.contains("Nothing was read or changed"))
        assertTrue(r.contains("Never clear codes"))
        assertTrue(!r.contains("the a car"))
    }

    @Test
    fun `he is asked once a connection, and a new connection asks again`() {
        AdapterBinding.resetForTest()
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.note("c1|pending:obd:m"))
        assertEquals(AdapterBinding.ASKED, AdapterBinding.note("c1|pending:obd:m"))
        // Another car minutes later is another connection: asked, never "he was already asked".
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.note("c2|pending:obd:m@2"))
        // The spoken ask at connect counts as the one question.
        AdapterBinding.markAsked("c3|pending:obd:m@3")
        assertEquals(AdapterBinding.ASKED, AdapterBinding.note("c3|pending:obd:m@3"))
        AdapterBinding.resetForTest()
    }

    @Test
    fun `clearing codes needs a car he named or a VIN proved`() {
        val held = DongleCar.Held("m", null, DongleCar.HeldReason.NO_VIN)
        val recognised = DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED)
        assertTrue(AdapterBinding.clearRefusal(held, connected = true)!!.startsWith("Nothing was cleared"))
        assertTrue(AdapterBinding.clearRefusal(recognised, connected = true)!!.startsWith("Nothing was cleared"))
        assertNull(AdapterBinding.clearRefusal(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ANSWER), connected = true))
        assertNull(AdapterBinding.clearRefusal(DongleCar.OnCar("m", "c1", "VIN", DongleCar.Basis.VIN), connected = true))
        // Connected while the car is still being decided (the VIN read): refused, never the hand pick.
        assertTrue(AdapterBinding.clearRefusal(null, connected = true)!!.startsWith("Nothing was cleared"))
        // Not connected at all: the caller's own "not connected" answer stands.
        assertNull(AdapterBinding.clearRefusal(null, connected = false))
    }

    @Test
    fun `the car screen line shows while the car is unknown or only recognised, never once it is known`() {
        val line = AdapterBinding.screenLine(DongleCar.Held("m", null, DongleCar.HeldReason.NO_VIN))!!
        assertTrue(line.contains("the adapter is in the") && line.contains("no codes are cleared"))
        assertEquals(line, AdapterBinding.screenLine(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED)))
        assertNull(AdapterBinding.screenLine(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ANSWER)))
        assertNull(AdapterBinding.screenLine(DongleCar.OnCar("m", "c1", "VIN", DongleCar.Basis.VIN)))
        assertNull(AdapterBinding.screenLine(null))
    }

    @Test
    fun `the recognition note says his yes is adapter_in for that car`() {
        AdapterBinding.resetForTest()
        val note = AdapterBinding.noteFor(
            DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED), mapOf("c1" to "Zephyr"), "c9|c1",
        )!!
        assertTrue(note, note.contains("His yes is car adapter_in with the Zephyr"))
        assertFalse(note, note.contains("switch_vehicle"))
        AdapterBinding.resetForTest()
    }

    @Test
    fun `a recognised car is said once per connection, an unknown one is asked`() {
        AdapterBinding.resetForTest()
        val recognised = DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED)
        val first = AdapterBinding.noteFor(recognised, mapOf("c1" to "Zephyr"), "c1|c1")!!
        assertTrue(first.contains("Looks like the Zephyr"))
        assertNull(AdapterBinding.noteFor(recognised, mapOf("c1" to "Zephyr"), "c1|c1"))
        // The same car on a later connection is said again.
        assertTrue(AdapterBinding.noteFor(recognised, mapOf("c1" to "Zephyr"), "c2|c1")!!.contains("Looks like"))
        assertNull(AdapterBinding.noteFor(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ANSWER), emptyMap(), "c2|c1"))
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.noteFor(DongleCar.Held("m", null, DongleCar.HeldReason.NO_VIN), emptyMap(), "c3|p"))
        AdapterBinding.resetForTest()
    }

    /**
     * Seam d (2026-11-19 integration): his one word right after the which-car question code SPOKE on
     * this connection is his answer, when it names exactly one car on file by membership and the
     * model's adapter_in names that car too. Otherwise the lone-word guard still holds it.
     */
    @Test
    fun `a one-word answer to the spoken which-car ask counts only on this connection, in time, naming one car`() {
        AdapterBinding.resetForTest()
        val labels = listOf("2010 Acme Zephyr", "2010 Acme Comet")
        val t0 = 1_000_000L
        assertFalse("never spoken", AdapterBinding.answersSpokenAsk("c1|p", "Comet", "Comet", labels, t0))
        // A tool result's ask is not a spoken one (it has its own field, needs_answer).
        AdapterBinding.note("c1|p")
        assertFalse("asked in a result only", AdapterBinding.answersSpokenAsk("c1|p", "Comet", "Comet", labels, t0))
        AdapterBinding.markAsked("c1|p", t0)
        assertTrue(AdapterBinding.answersSpokenAsk("c1|p", "Comet", "the Comet", labels, t0 + 5_000))
        assertTrue("case folded", AdapterBinding.answersSpokenAsk("c1|p", "comet", "2010 Acme Comet", labels, t0 + 5_000))
        assertFalse("another connection", AdapterBinding.answersSpokenAsk("c2|p", "Comet", "Comet", labels, t0 + 5_000))
        assertFalse("too late", AdapterBinding.answersSpokenAsk("c1|p", "Comet", "Comet", labels, t0 + AdapterBinding.SPOKEN_ASK_ANSWER_MS + 1))
        assertFalse("names both cars", AdapterBinding.answersSpokenAsk("c1|p", "Acme", "Acme", labels, t0 + 5_000))
        assertFalse("names no car", AdapterBinding.answersSpokenAsk("c1|p", "Five", "Comet", labels, t0 + 5_000))
        assertFalse("the model picked another car", AdapterBinding.answersSpokenAsk("c1|p", "Comet", "Zephyr", labels, t0 + 5_000))
        assertFalse("no connection", AdapterBinding.answersSpokenAsk(null, "Comet", "Comet", labels, t0 + 5_000))
        AdapterBinding.resetForTest()
    }
}
