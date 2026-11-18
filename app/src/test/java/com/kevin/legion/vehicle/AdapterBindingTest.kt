@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AdapterBinding]: the words that route "this is the X" to the one binding action. Invented cars. */
class AdapterBindingTest {
    private fun car(id: String, year: Int, make: String, model: String, archived: Boolean = false) =
        Vehicle(obdMac = id, name = "", make = make, model = model, year = year, personaPrompt = "", archived = archived)

    private val fleet = listOf(
        car("c1", 2004, "Ford", "Ranger"),
        car("c2", 2012, "Honda", "Fit"),
        car("c3", 2001, "Honda", "Civic", archived = true),
    )

    @Test
    fun `this car is a year make model on file names that one car`() {
        assertEquals("c1", AdapterBinding.carOnFileNamed(fleet, 2004, "Ford", "Ranger")?.obdMac)
        assertEquals("c1", AdapterBinding.carOnFileNamed(fleet, 0, "", "ranger")?.obdMac)
        assertNull("wrong year", AdapterBinding.carOnFileNamed(fleet, 1999, "Ford", "Ranger"))
        assertNull("not on file", AdapterBinding.carOnFileNamed(fleet, 2010, "Mazda", "Miata"))
        assertNull("archived", AdapterBinding.carOnFileNamed(fleet, 2001, "Honda", "Civic"))
        val twins = fleet + car("c4", 2004, "Ford", "Ranger")
        assertNull("two match: ask, never guess", AdapterBinding.carOnFileNamed(twins, 2004, "Ford", "Ranger"))
    }

    @Test
    fun `the refusal names the binding action and forbids clearing codes`() {
        val r = AdapterBinding.unknownCarRefusal("Ranger")
        assertTrue(r.contains("car switch_vehicle vehicle=Ranger"))
        assertTrue(r.contains("Nothing was read or changed"))
        assertTrue(r.contains("Never clear codes"))
        assertTrue(!r.contains("the a car"))
    }

    @Test
    fun `he is asked once a conversation, then the result only says he was`() {
        AdapterBinding.resetForTest()
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.note(now = 10_000))
        assertEquals(AdapterBinding.ASKED, AdapterBinding.note(now = 20_000))
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.note(now = 10_000 + AdapterBinding.ASK_WINDOW_MS))
        AdapterBinding.resetForTest()
    }

    @Test
    fun `clearing codes needs a car he named or a VIN proved`() {
        val held = DongleCar.Held("m", null, DongleCar.HeldReason.NO_VIN)
        val recognised = DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED)
        assertTrue(AdapterBinding.clearRefusal(held)!!.startsWith("Nothing was cleared"))
        assertTrue(AdapterBinding.clearRefusal(recognised)!!.startsWith("Nothing was cleared"))
        assertNull(AdapterBinding.clearRefusal(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ANSWER)))
        assertNull(AdapterBinding.clearRefusal(DongleCar.OnCar("m", "c1", "VIN", DongleCar.Basis.VIN)))
        assertNull(AdapterBinding.clearRefusal(null))
    }

    @Test
    fun `a recognised car is said once per connection, an unknown one is asked`() {
        AdapterBinding.resetForTest()
        val recognised = DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ASSUMED)
        val first = AdapterBinding.noteFor(recognised, mapOf("c1" to "Ranger"))!!
        assertTrue(first.contains("Looks like the Ranger"))
        assertNull(AdapterBinding.noteFor(recognised, mapOf("c1" to "Ranger")))
        assertNull(AdapterBinding.noteFor(DongleCar.OnCar("m", "c1", null, DongleCar.Basis.ANSWER), emptyMap()))
        assertEquals(AdapterBinding.ASK_ONCE, AdapterBinding.noteFor(DongleCar.Held("m", null, DongleCar.HeldReason.NO_VIN), emptyMap()))
        AdapterBinding.resetForTest()
    }
}
