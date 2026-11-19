package com.kevin.legion.service.cascade

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A write that went through and was not heard is owed until its line plays (2026-11-19). What is
 * heard turn by turn is in [WriteSpeechTurnTest]. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class OwedWritesTest {

    private fun fact(item: String, ok: Boolean = true, result: String = """{"item":"$item","list":"Groceries"}""") =
        ScriptedLines.writeFact("manage_item", "add", JSONObject(result), ok)

    @Test
    fun `a write whose line played is not owed, one whose line did not play is`() {
        val milk = fact("milk")
        val eggs = fact("eggs")
        val owed = OwedWrites()
        owed.owe(listOf(milk, eggs), played = listOf(milk), cutOff = false)
        val due = owed.due()!!
        assertEquals("Done: added eggs to Groceries.", due.line)
        // Not paid until it plays: asked again, it is still due.
        assertEquals(due.line, owed.due()!!.line)
        owed.paid(due.facts)
        assertNull(owed.due())
        assertTrue(owed.isEmpty())
    }

    @Test
    fun `two identical writes are two debts, and owing the same fact twice counts it once`() {
        val a = fact("milk")
        val b = fact("milk")
        val owed = OwedWrites()
        owed.owe(listOf(a, b), emptyList(), cutOff = false)
        owed.owe(listOf(a, b), emptyList(), cutOff = false)
        assertEquals("Done: added milk to Groceries; added milk to Groceries.", owed.due()!!.line)
        owed.paid(listOf(a))
        assertEquals("Done: added milk to Groceries.", owed.due()!!.line)
    }

    @Test
    fun `a failed or refused write is never owed, an unconfirmed one only after a cut-off (review F4, F9)`() {
        val failed = fact("essay", ok = false, result = """{"title":"essay"}""")
        val same = fact("x", result = """{"changed":false,"item":"x"}""")
        val timedOut = ScriptedLines.writeFact("manage_item", "add", JSONObject("""{"item":"stamps"}"""), ok = false, confirmed = false)
        val owed = OwedWrites()
        owed.owe(listOf(failed, same, timedOut), emptyList(), cutOff = false)
        assertNull(owed.due())
        owed.owe(listOf(failed, same, timedOut), emptyList(), cutOff = true)
        assertEquals("I couldn't confirm that stamps went through.", owed.due()!!.line)
    }

    @Test
    fun `a fact once heard is never owed again, and a close writes the rest down once`() {
        val milk = fact("milk")
        val owed = OwedWrites()
        owed.owe(listOf(milk), emptyList(), cutOff = true)
        owed.paid(owed.due()!!.facts)
        owed.owe(listOf(milk), emptyList(), cutOff = true)
        assertNull(owed.due())
        val eggs = fact("eggs")
        owed.owe(listOf(eggs), emptyList(), cutOff = true)
        assertEquals("Done: added eggs to Groceries.", owed.writeDown())
        assertNull(owed.writeDown())
    }

    @Test
    fun `a debt paid while a new one arrives keeps the new one`() {
        val milk = fact("milk")
        val eggs = fact("eggs")
        val owed = OwedWrites()
        owed.owe(listOf(milk), emptyList(), cutOff = true)
        val due = owed.due()!!
        owed.owe(listOf(eggs), emptyList(), cutOff = true)
        owed.paid(due.facts)
        assertEquals("Done: added eggs to Groceries.", owed.due()!!.line)
    }
}
