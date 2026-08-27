package com.kevin.legion.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CalendarReadToolLogic.structuredBlock] - the parser that lets a calendar event carry machine
 * fields for Alfred without the prose beneath them reaching the model. Every branch here is the
 * plain-JUnit half of this file's existing "pure logic, thin dispatch" split.
 */
class StructuredBlockTest {

    @Test
    fun `plain description carries no block`() {
        assertNull(CalendarReadToolLogic.structuredBlock("Dentist, bring the referral letter"))
    }

    @Test
    fun `empty description carries no block`() {
        assertNull(CalendarReadToolLogic.structuredBlock(""))
    }

    @Test
    fun `sentinel must be the first line`() {
        val desc = "Some preamble\nLEGION::v1\ncourse: COSC4320\n---"
        assertNull(CalendarReadToolLogic.structuredBlock(desc))
    }

    @Test
    fun `parses keys up to the terminator and stops`() {
        val desc = """
            LEGION::v1
            course: COSC4320
            source: canvas_verified
            ---
            Prose that must never reach the model.
            trap: this line is after the terminator
        """.trimIndent()
        val out = CalendarReadToolLogic.structuredBlock(desc)!!
        assertEquals(mapOf("course" to "COSC4320", "source" to "canvas_verified"), out)
    }

    @Test
    fun `value may contain colons`() {
        val desc = "LEGION::v1\nconflict: syllabus says 2026-09-06; canvas says 2026-08-30\n---"
        assertEquals(
            "syllabus says 2026-09-06; canvas says 2026-08-30",
            CalendarReadToolLogic.structuredBlock(desc)!!["conflict"],
        )
    }

    @Test
    fun `duplicate key keeps the first occurrence`() {
        val desc = "LEGION::v1\nsource: canvas_verified\nsource: inferred\n---"
        assertEquals("canvas_verified", CalendarReadToolLogic.structuredBlock(desc)!!["source"])
    }

    @Test
    fun `blank values and malformed lines are skipped`() {
        val desc = "LEGION::v1\ncourse: COSC4320\nempty:\nno-colon-here\n---"
        assertEquals(mapOf("course" to "COSC4320"), CalendarReadToolLogic.structuredBlock(desc))
    }

    @Test
    fun `block with no usable pairs is null not an empty map`() {
        assertNull(CalendarReadToolLogic.structuredBlock("LEGION::v1\n---"))
    }

    @Test
    fun `missing terminator still parses what it has`() {
        val desc = "LEGION::v1\ncourse: MATH3391\nstatus: pending"
        assertEquals(
            mapOf("course" to "MATH3391", "status" to "pending"),
            CalendarReadToolLogic.structuredBlock(desc),
        )
    }

    // -- CalendarReadToolLogic.proseAfter: the human half of a description, the counterpart to
    // structuredBlock's machine half above. --

    @Test
    fun `plain description with no block is entirely prose`() {
        assertEquals("Dentist, bring the referral letter", CalendarReadToolLogic.proseAfter("Dentist, bring the referral letter"))
    }

    @Test
    fun `empty description has no prose`() {
        assertNull(CalendarReadToolLogic.proseAfter(""))
    }

    @Test
    fun `prose is everything after the terminator`() {
        val desc = "LEGION::v1\ncourse: COSC4320\nsource: canvas_verified\n---\nBring a calculator."
        assertEquals("Bring a calculator.", CalendarReadToolLogic.proseAfter(desc))
    }

    @Test
    fun `a block with no trailing prose has none`() {
        assertNull(CalendarReadToolLogic.proseAfter("LEGION::v1\ncourse: COSC4320\n---"))
    }

    @Test
    fun `a block whose terminator never closed has no prose`() {
        assertNull(CalendarReadToolLogic.proseAfter("LEGION::v1\ncourse: MATH3391\nstatus: pending"))
    }
}
