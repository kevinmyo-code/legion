package com.kevin.legion.ui.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure helpers behind ADAPTER's NEARBY list. Both exist because the
 * Midnight version got them wrong in ways that only showed on real hardware: a
 * list re-sorted on every sighting jumped under the driver's finger as names
 * resolved, and a dual-mode dongle was collapsed onto one transport by the
 * scanner that happened to see it last.
 *
 * The dual-mode cases below are regressions against a real failure on Kevin's
 * phone (2026-08-09): the V020 answers classic inquiry AND advertises over BLE,
 * its bond times out every time, and the old "classic always wins" tie-breaker
 * therefore hid the one transport that works.
 */
class ObdDeviceScreenTest {

    private fun row(
        mac: String,
        name: String? = null,
        classic: Boolean = false,
        ble: Boolean = false,
        obdLike: Boolean = false,
    ) = ObdDeviceRow(
        mac = mac,
        name = name,
        seenClassic = classic,
        seenBle = ble,
        looksLikeObd = obdLike,
    )

    @Test
    fun `first sighting is kept`() {
        val out = foldDiscovered(emptyMap(), row("AA", "V020", ble = true))
        assertEquals(1, out.size)
        assertTrue(out.getValue("AA").seenBle)
        assertFalse(out.getValue("AA").seenClassic)
    }

    @Test
    fun `a later name replaces a null name`() {
        val first = foldDiscovered(emptyMap(), row("AA", null, ble = true))
        val second = foldDiscovered(first, row("AA", "V020", ble = true))
        assertEquals("V020", second.getValue("AA").name)
    }

    @Test
    fun `a resolved name is never lost to a later nameless sighting`() {
        val named = foldDiscovered(emptyMap(), row("AA", "V020", ble = true))
        val out = foldDiscovered(named, row("AA", null, ble = true))
        assertEquals("V020", out.getValue("AA").name)
    }

    @Test
    fun `a classic sighting does not erase the BLE one`() {
        val bleFirst = foldDiscovered(emptyMap(), row("AA", "V020", ble = true))
        val out = foldDiscovered(bleFirst, row("AA", "V020", classic = true))
        val merged = out.getValue("AA")
        assertTrue("the BLE path must survive - it is the one that works", merged.seenBle)
        assertTrue(merged.seenClassic)
        assertTrue(merged.isDualMode)
    }

    @Test
    fun `a BLE sighting does not erase the classic one`() {
        val classicFirst = foldDiscovered(emptyMap(), row("AA", "V020", classic = true))
        val out = foldDiscovered(classicFirst, row("AA", "V020", ble = true))
        val merged = out.getValue("AA")
        assertTrue(merged.seenClassic)
        assertTrue(merged.seenBle)
    }

    @Test
    fun `merge order does not change the result`() {
        val bleFirst = foldDiscovered(foldDiscovered(emptyMap(), row("AA", "V020", ble = true)), row("AA", "V020", classic = true))
        val classicFirst = foldDiscovered(foldDiscovered(emptyMap(), row("AA", "V020", classic = true)), row("AA", "V020", ble = true))
        assertEquals(bleFirst, classicFirst)
    }

    @Test
    fun `looksLikeObd latches on once any sighting resolves the name`() {
        val unnamed = foldDiscovered(emptyMap(), row("AA", null, ble = true, obdLike = false))
        val out = foldDiscovered(unnamed, row("AA", "V020", ble = true, obdLike = true))
        assertTrue(out.getValue("AA").looksLikeObd)
    }

    @Test
    fun `a sighting that teaches nothing new leaves the map identical`() {
        val first = foldDiscovered(emptyMap(), row("AA", "V020", ble = true, obdLike = true))
        val second = foldDiscovered(first, row("AA", "V020", ble = true, obdLike = true))
        assertSame("an idle scan must not churn recomposition", first, second)
    }

    @Test
    fun `already-paired devices are excluded from NEARBY`() {
        val rows = listOf(row("AA", "OBDII", classic = true), row("BB", "V020", ble = true))
        val out = sortNearby(rows, pairedMacs = setOf("AA"))
        assertEquals(listOf("BB"), out.map { it.mac })
    }

    @Test
    fun `ordering puts OBD-looking names first, then named, then MAC order`() {
        val rows = listOf(
            row("CC", null),
            row("BB", "Some Speaker"),
            row("AA", "OBDII", obdLike = true),
            row("DD", null),
        )
        val out = sortNearby(rows, pairedMacs = emptySet())
        assertEquals(listOf("AA", "BB", "CC", "DD"), out.map { it.mac })
    }

    @Test
    fun `ordering is stable across repeated sightings`() {
        val rows = listOf(row("DD", null), row("CC", null), row("BB", null))
        val first = sortNearby(rows, emptySet()).map { it.mac }
        val second = sortNearby(rows.reversed(), emptySet()).map { it.mac }
        assertEquals(first, second)
    }
}
