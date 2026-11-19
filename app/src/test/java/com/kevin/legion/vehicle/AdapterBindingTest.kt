@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AdapterBinding]: the words that route "this is the X" to the one binding action. Invented cars. */
class AdapterBindingTest {
    @Test
    fun `the refusal names the binding action and forbids clearing codes`() {
        val r = AdapterBinding.unknownCarRefusal("Zephyr")
        assertTrue(r.contains("car switch_vehicle vehicle=Zephyr"))
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
}
