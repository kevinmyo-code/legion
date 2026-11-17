package com.kevin.legion.service.cascade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0090 gap 5 (2026-11-17): the turn after a read-through opener starts marked, one turn only. */
class ReadThroughCarryTest {

    @Test
    fun `his turn after a read-through opener is carried, and only that one`() {
        val c = ReadThroughCarry()
        assertFalse("the opener is marked by its own flag", c.startTurn(fromUser = false, openerMarked = true))
        assertTrue("his reply to it", c.startTurn(fromUser = true, openerMarked = false))
        assertFalse("the turn after that is clean", c.startTurn(fromUser = true, openerMarked = false))
    }

    @Test
    fun `a plain opener carries nothing`() {
        val c = ReadThroughCarry()
        c.startTurn(fromUser = false, openerMarked = false)
        assertFalse(c.startTurn(fromUser = true, openerMarked = false))
    }

    @Test
    fun `his own words never start a carry`() {
        val c = ReadThroughCarry()
        c.startTurn(fromUser = true, openerMarked = true)
        assertFalse(c.startTurn(fromUser = true, openerMarked = false))
    }
}
